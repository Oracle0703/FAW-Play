// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay.hud

import android.os.DeadObjectException
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock

/**
 * Fixed 3212 setters, used only by the firmware-verified Data session on its single worker.
 * The service VDEX SHA256 is 04359538b6abd8a7d6310f3f38eaf944a4ddbeb5153c454bc2e29767ae0efd1d.
 * Wire types/transactions come from its Stub/Proxy; command vs feedback enums from OEM HVAC.
 * This class does not bind services, queue requests, wait for feedback, retry writes or claim success.
 */
internal class HongqiVehicleControlRpc(private val root: IBinder) {
    private val modules = mutableMapOf<Int, IBinder>() // Data worker only

    init { check(root.interfaceDescriptor == HongqiVehicleReadOnlyRpc.ROOT) { "root-descriptor" } }

    fun execute(
        command: HongqiVehicleControlCommand,
        snapshot: HongqiVehicleSnapshot,
        allowed: () -> Boolean = { true },
    ): HongqiVehicleControlResult {
        var dispatchedAt: Long? = null
        var rawReturn: Int? = null
        fun result(status: HongqiVehicleControlStatus, reason: String? = null) =
            HongqiVehicleControlResult(command, status, rawReturn, reason, dispatchedAt)
        try {
            active(allowed)
            if (!command.valid) return result(HongqiVehicleControlStatus.REJECTED, "invalid-argument")
            precondition(command, snapshot)?.let { return result(it.first, it.second) }
            // Match QISICarEOLManager.carType(): only DD4/DD5 are reviewed type1.
            // Read it for this request; never log, return or persist the vehicle identifier.
            if (!reviewedModel(allowed)) return result(HongqiVehicleControlStatus.UNSUPPORTED, "model-not-verified")
            val (moduleId, transaction) = address(command)
            val module = module(moduleId, allowed)
            val request = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                request.writeInterfaceToken(DESCRIPTORS.getValue(moduleId))
                write(command, request)
                // Module/model lookup is synchronous and may have stalled. Recheck the
                // snapshot at the actual write boundary, not just before connecting.
                precondition(command, snapshot)?.let { return result(it.first, it.second) }
                active(allowed)
                dispatchedAt = SystemClock.elapsedRealtime()
                if (!module.transact(transaction, request, reply, 0)) {
                    return result(HongqiVehicleControlStatus.REJECTED, "setter-rejected")
                }
                reply.readException()
                if (reply.dataAvail() != 4) throw ControlFailure("reply-shape")
                rawReturn = reply.readInt()
                active(allowed)
            } catch (error: DeadObjectException) {
                modules.remove(moduleId)
                throw error
            } finally { request.recycle(); reply.recycle() }
            // AbsCanSignalAdapterManager.sendMessage converts the CAN dispatch boolean
            // into 1/0. Neither is an ECU acknowledgement or physical-state confirmation.
            return when (rawReturn) {
                1 -> result(HongqiVehicleControlStatus.ACCEPTED_UNCONFIRMED)
                -2 -> result(HongqiVehicleControlStatus.UNSUPPORTED, "oem-unsupported")
                0 -> result(HongqiVehicleControlStatus.REJECTED, "oem-rejected")
                -65535 -> result(HongqiVehicleControlStatus.REJECTED, "oem-invalid-argument")
                else -> if ((rawReturn ?: 0) < 0) result(HongqiVehicleControlStatus.REJECTED, "oem-rejected")
                    else result(HongqiVehicleControlStatus.UNAVAILABLE, "unknown-acknowledgement")
            }
        } catch (_: Inactive) {
            return result(HongqiVehicleControlStatus.CANCELLED,
                if (dispatchedAt == null) "cancelled-before-dispatch" else "cancelled-after-dispatch")
        } catch (error: ControlFailure) {
            return result(HongqiVehicleControlStatus.UNAVAILABLE, error.reason)
        } catch (error: Exception) {
            return result(HongqiVehicleControlStatus.UNAVAILABLE, error.javaClass.simpleName)
        }
    }

    private fun precondition(
        command: HongqiVehicleControlCommand,
        snapshot: HongqiVehicleSnapshot,
    ): Pair<HongqiVehicleControlStatus, String>? {
        val current = snapshot.fresh(SystemClock.elapsedRealtime())
        fun value(field: HongqiVehicleField): Double? {
            val reading = current.readings[field] ?: return null
            if (reading.failure != null) return null
            return reading.raw?.takeIf { field.reading(it, reading.sampledAtElapsedMillis).raw != null }
        }
        if (current.status != HongqiVehicleConnectionState.READY || value(command.feedbackField) == null) {
            return HongqiVehicleControlStatus.UNAVAILABLE to "feedback-unavailable"
        }
        val capability = when (command) {
            is HongqiVehicleControlCommand.SeatHeat -> HongqiVehicleField.FRONT_HEAT_SUPPORTED
            is HongqiVehicleControlCommand.SeatVent -> HongqiVehicleField.FRONT_VENT_SUPPORTED
            else -> return null
        }
        return when (value(capability)) {
            null -> HongqiVehicleControlStatus.UNAVAILABLE to "capability-unavailable"
            1.0 -> null
            else -> HongqiVehicleControlStatus.UNSUPPORTED to "capability-absent"
        }
    }

    private fun reviewedModel(allowed: () -> Boolean): Boolean {
        val module = module(31, allowed)
        val request = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            request.writeInterfaceToken(DESCRIPTORS.getValue(31))
            active(allowed)
            if (!module.transact(4, request, reply, 0)) throw ControlFailure("model-query-rejected")
            reply.readException()
            val identifier = reply.readString()
            if (reply.dataAvail() != 0) throw ControlFailure("model-reply-shape")
            active(allowed)
            identifier != null && identifier.length in 10..128 &&
                (identifier.startsWith("7901010DD4") || identifier.startsWith("7901010DD5"))
        } catch (error: DeadObjectException) {
            modules.remove(31)
            throw error
        } finally { request.recycle(); reply.recycle() }
    }

    private fun module(id: Int, allowed: () -> Boolean): IBinder {
        active(allowed)
        modules[id]?.let { return it }
        val descriptor = DESCRIPTORS.getValue(id)
        val request = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            request.writeInterfaceToken(HongqiVehicleReadOnlyRpc.ROOT)
            request.writeInt(id)
            if (!root.transact(1, request, reply, 0)) throw ControlFailure("module-query-rejected")
            reply.readException()
            val module = reply.readStrongBinder() ?: throw ControlFailure("module-absent")
            if (reply.dataAvail() != 0) throw ControlFailure("module-reply-shape")
            if (module.interfaceDescriptor != descriptor) throw ControlFailure("module-descriptor")
            active(allowed)
            modules[id] = module
            module
        } finally { request.recycle(); reply.recycle() }
    }

    private fun active(allowed: () -> Boolean) { if (!allowed()) throw Inactive() }
    private class Inactive : Exception()
    private class ControlFailure(val reason: String) : Exception()

    private fun address(command: HongqiVehicleControlCommand): Pair<Int, Int> = when (command) {
        is HongqiVehicleControlCommand.Temperature -> 33 to 12
        is HongqiVehicleControlCommand.Fan -> 33 to 14
        is HongqiVehicleControlCommand.Circulation -> 33 to 16
        is HongqiVehicleControlCommand.SeatHeat -> 49 to 8
        is HongqiVehicleControlCommand.SeatVent -> 49 to 4
        is HongqiVehicleControlCommand.Switch -> 33 to when (command.setting) {
            HongqiVehicleClimateSwitch.POWER -> 2
            HongqiVehicleClimateSwitch.AUTO -> 4
            HongqiVehicleClimateSwitch.SYNC -> 6
            HongqiVehicleClimateSwitch.COMPRESSOR -> 8
            HongqiVehicleClimateSwitch.FRONT_DEFROST -> 18
        }
    }

    private fun write(command: HongqiVehicleControlCommand, request: Parcel) {
        when (command) {
            is HongqiVehicleControlCommand.Temperature -> {
                request.writeInt(command.side.wireIndex)
                request.writeFloat(command.celsius)
            }
            is HongqiVehicleControlCommand.Fan -> request.writeInt(command.level)
            is HongqiVehicleControlCommand.Switch -> request.writeInt(if (command.enabled) 2 else 1)
            is HongqiVehicleControlCommand.Circulation ->
                request.writeInt(if (command.mode == HongqiVehicleCirculation.FRESH_AIR) 1 else 2)
            is HongqiVehicleControlCommand.SeatHeat -> {
                request.writeInt(command.side.wireIndex)
                request.writeInt(command.level.rawFeedback + 1)
            }
            is HongqiVehicleControlCommand.SeatVent -> {
                request.writeInt(command.side.wireIndex)
                request.writeInt(command.level.rawFeedback + 1)
            }
        }
    }

    companion object {
        private val DESCRIPTORS = mapOf(
            31 to "ltd.qisi.adapterproxy.ICarEOLManager",
            33 to "ltd.qisi.adapterproxy.ICarATCManager",
            49 to "ltd.qisi.adapterproxy.ICarSCCUManager",
        )
    }
}

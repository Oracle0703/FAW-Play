// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay.vehicle

import android.os.Binder
import android.os.IBinder
import android.os.Parcel

/** 3222 Qisi Stub constants and primitive wire types, recovered from the OEM CompactDex.
 *
 * See docs/evidence/hs5-comfort-3222.json. Raw state/seat codes are not translated into
 * Apple enums or guessed on/off meanings. Reading and subscribing never writes vehicle state.
 */
internal object Hs5ComfortProtocol {
    const val ATC_MODULE = 33
    const val ATC_DESCRIPTOR = "ltd.qisi.adapterproxy.ICarATCManager"
    const val ATC_LISTENER = "ltd.qisi.adapterproxy.listener.ICarATCStateListener"
    const val SCCU_MODULE = 49
    const val SCCU_DESCRIPTOR = "ltd.qisi.adapterproxy.ICarSCCUManager"
    const val SCCU_LISTENER = "ltd.qisi.adapterproxy.listener.IVehicleCarSCCUChangedListener"
    const val EOL_MODULE = 31
    const val EOL_DESCRIPTOR = "ltd.qisi.adapterproxy.ICarEOLManager"

    fun read(rpc: Hs5VehicleRpc): Map<Hs5VehicleField, Double?> = buildMap {
        fun integer(field: Hs5VehicleField, module: Int, method: Int, vararg args: Int) {
            put(field, runCatching { normalize(field, rpc.int(module, method, *args).toDouble()) }.getOrNull())
        }
        fun floating(field: Hs5VehicleField, method: Int, vararg args: Int) {
            put(field, runCatching { normalize(field, rpc.float(ATC_MODULE, method, *args).toDouble()) }.getOrNull())
        }
        integer(Hs5VehicleField.HVAC_POWER, ATC_MODULE, 1)
        integer(Hs5VehicleField.HVAC_AUTO, ATC_MODULE, 3, -1)
        integer(Hs5VehicleField.HVAC_SYNC, ATC_MODULE, 5, -1)
        integer(Hs5VehicleField.HVAC_COMPRESSOR, ATC_MODULE, 7, -1)
        integer(Hs5VehicleField.HVAC_BLOW_MODE, ATC_MODULE, 9, 0)
        floating(Hs5VehicleField.HVAC_LEFT_TEMPERATURE, 11, 0)
        floating(Hs5VehicleField.HVAC_RIGHT_TEMPERATURE, 11, 1)
        integer(Hs5VehicleField.HVAC_FAN, ATC_MODULE, 13)
        integer(Hs5VehicleField.HVAC_RECIRCULATION, ATC_MODULE, 15)
        integer(Hs5VehicleField.HVAC_FRONT_DEFROST, ATC_MODULE, 17)
        floating(Hs5VehicleField.AMBIENT_TEMPERATURE, 93)
        // The similarly named ATC seat methods are not the path used by the OEM HVAC app.
        integer(Hs5VehicleField.DRIVER_SEAT_HEAT, SCCU_MODULE, 7, 0)
        integer(Hs5VehicleField.PASSENGER_SEAT_HEAT, SCCU_MODULE, 7, 1)
        integer(Hs5VehicleField.DRIVER_SEAT_VENT, SCCU_MODULE, 3, 0)
        integer(Hs5VehicleField.PASSENGER_SEAT_VENT, SCCU_MODULE, 3, 1)
    }

    fun capability(rpc: Hs5VehicleRpc): Hs5VehicleCapabilities {
        // Match only the branches in QISICarEOLManager.carType(); never persist/log the PDSN.
        val model = runCatching {
            rpc.string(EOL_MODULE, 4)?.let {
                it.startsWith("7901010DD4") || it.startsWith("7901010DD5")
            } == true
        }.getOrDefault(false)
        val heat = runCatching { rpc.int(EOL_MODULE, 49) == 1 }.getOrDefault(false)
        val vent = runCatching { rpc.int(EOL_MODULE, 50) == 1 }.getOrDefault(false)
        return Hs5VehicleCapabilities(model, model && heat, model && vent)
    }

    /** Requires an explicit command; OEM acknowledgement is not a physical-state confirmation. */
    fun execute(rpc: Hs5VehicleRpc, command: Hs5VehicleCommand): Int = when (command) {
        is Hs5VehicleCommand.ClimateSetting -> {
            val method = when (command.setting) {
                Hs5ClimateSetting.POWER -> 2
                Hs5ClimateSetting.AUTO -> 4
                Hs5ClimateSetting.SYNC -> 6
                Hs5ClimateSetting.COMPRESSOR -> 8
                Hs5ClimateSetting.RECIRCULATION -> 16
                Hs5ClimateSetting.FRONT_DEFROST -> 18
            }
            // QISICarATCManager.isRightValue accepts 1..2, not Boolean 0/1.
            val range = if (command.setting == Hs5ClimateSetting.RECIRCULATION) 0..2 else 1..2
            require(command.encodedValue in range)
            rpc.int(ATC_MODULE, method, command.encodedValue)
        }
        is Hs5VehicleCommand.ClimateBlowMode -> {
            require(command.encodedMode in setOf(1, 3, 5, 6, 7))
            rpc.int(ATC_MODULE, 10, 0, command.encodedMode)
        }
        is Hs5VehicleCommand.ClimateTemperature -> {
            require(command.side in 0..1 && command.celsius.isFinite() && command.celsius in 15f..35f &&
                command.celsius * 2f == kotlin.math.floor(command.celsius * 2f))
            rpc.callIntFloat(ATC_MODULE, 12, command.side, command.celsius)
        }
        is Hs5VehicleCommand.ClimateFan -> {
            require(command.level in 0..7)
            rpc.int(ATC_MODULE, 14, command.level)
        }
        is Hs5VehicleCommand.SeatLevel -> {
            // 1..4 is the OEM accepted command encoding, not a claim of four heating levels.
            // Its off/level mapping is not verified; independent *State methods return -2.
            require(command.side in 0..1 && command.encodedLevel in 1..4)
            rpc.int(SCCU_MODULE, if (command.heating) 8 else 4, command.side, command.encodedLevel)
        }
    }

    fun listeners(onValue: (Hs5VehicleField, Double?) -> Unit): List<Hs5VehicleSubscription> = listOf(
        Hs5VehicleSubscription(ATC_MODULE, ATC_DESCRIPTOR, 85, 86, ClimateListener(onValue)),
        Hs5VehicleSubscription(SCCU_MODULE, SCCU_DESCRIPTOR, 29, 30, SeatListener(onValue)),
    )

    private fun normalize(field: Hs5VehicleField, value: Double): Double? {
        if (!value.isFinite()) return null
        return when (field) {
            Hs5VehicleField.HVAC_LEFT_TEMPERATURE, Hs5VehicleField.HVAC_RIGHT_TEMPERATURE ->
                value.takeIf { it in 15.0..35.0 && it * 2 == kotlin.math.floor(it * 2) }
            // CarACConvertUtil: valid raw 0..253 becomes raw / 2 - 40; error is -65533.
            Hs5VehicleField.AMBIENT_TEMPERATURE -> value.takeIf { it in -40.0..86.5 }
            Hs5VehicleField.HVAC_FAN -> value.takeIf { it in 0.0..7.0 }
            Hs5VehicleField.HVAC_BLOW_MODE -> value.takeIf { it in 0.0..7.0 }
            Hs5VehicleField.HVAC_POWER, Hs5VehicleField.HVAC_AUTO, Hs5VehicleField.HVAC_SYNC,
            Hs5VehicleField.HVAC_COMPRESSOR, Hs5VehicleField.HVAC_RECIRCULATION,
            Hs5VehicleField.HVAC_FRONT_DEFROST -> value.takeIf { it in 0.0..2.0 }
            Hs5VehicleField.DRIVER_SEAT_HEAT, Hs5VehicleField.PASSENGER_SEAT_HEAT,
            Hs5VehicleField.DRIVER_SEAT_VENT, Hs5VehicleField.PASSENGER_SEAT_VENT ->
                value.takeIf { it in 0.0..4.0 }
            else -> null
        }
    }

    private abstract class Listener(private val descriptor: String) : Binder() {
        init { attachInterface(null, descriptor) }

        final override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == IBinder.INTERFACE_TRANSACTION) {
                reply?.writeString(descriptor)
                return true
            }
            val count = payloadBytes(code) ?: return super.onTransact(code, data, reply, flags)
            data.enforceInterface(descriptor)
            require(data.dataAvail() == count) { "Invalid vehicle callback payload" }
            receive(code, data)
            // OEM Proxy passes reply=null and flags=FLAG_ONEWAY (fifth transact argument=1).
            // Also acknowledge synchronous callers for compatibility.
            if (flags and IBinder.FLAG_ONEWAY == 0) reply?.writeNoException()
            return true
        }

        abstract fun payloadBytes(code: Int): Int?
        abstract fun receive(code: Int, data: Parcel)
    }

    private class ClimateListener(private val onValue: (Hs5VehicleField, Double?) -> Unit) : Listener(ATC_LISTENER) {
        override fun payloadBytes(code: Int): Int? = when (code) {
            1, 2, 3, 4, 7, 8, 9 -> 4
            5, 6 -> 8
            else -> null
        }

        override fun receive(code: Int, data: Parcel) {
            val field: Hs5VehicleField
            val value: Double
            when (code) {
                5 -> { // onACBlowModeChange(int index, int mode)
                    val side = data.readInt()
                    require(side in 0..1) { "Invalid climate area" }
                    value = data.readInt().toDouble()
                    // Snapshot exposes the front-left mode, matching getACBlowMode(0).
                    if (side != 0) return
                    field = Hs5VehicleField.HVAC_BLOW_MODE
                }
                6 -> { // onACTemperatureChange(int index, float temperature)
                    val side = data.readInt()
                    require(side in 0..1) { "Invalid climate area" }
                    field = if (side == 0) Hs5VehicleField.HVAC_LEFT_TEMPERATURE else Hs5VehicleField.HVAC_RIGHT_TEMPERATURE
                    value = data.readFloat().toDouble()
                }
                else -> {
                    field = when (code) {
                        1 -> Hs5VehicleField.HVAC_POWER
                        2 -> Hs5VehicleField.HVAC_AUTO
                        3 -> Hs5VehicleField.HVAC_SYNC
                        4 -> Hs5VehicleField.HVAC_COMPRESSOR
                        7 -> Hs5VehicleField.HVAC_FAN
                        8 -> Hs5VehicleField.HVAC_RECIRCULATION
                        9 -> Hs5VehicleField.HVAC_FRONT_DEFROST
                        else -> return
                    }
                    value = data.readInt().toDouble()
                }
            }
            onValue(field, normalize(field, value))
        }
    }

    private class SeatListener(private val onValue: (Hs5VehicleField, Double?) -> Unit) : Listener(SCCU_LISTENER) {
        override fun payloadBytes(code: Int): Int? = if (code == 2 || code == 4) 8 else null

        override fun receive(code: Int, data: Parcel) {
            // onFrontSeatVentLevel / onFrontSeatHeatLevel(int index, int encodedLevel)
            val side = data.readInt()
            require(side in 0..1) { "Invalid seat area" }
            val value = data.readInt().toDouble()
            val field = when {
                code == 4 && side == 0 -> Hs5VehicleField.DRIVER_SEAT_HEAT
                code == 4 -> Hs5VehicleField.PASSENGER_SEAT_HEAT
                side == 0 -> Hs5VehicleField.DRIVER_SEAT_VENT
                else -> Hs5VehicleField.PASSENGER_SEAT_VENT
            }
            onValue(field, normalize(field, value))
        }
    }
}

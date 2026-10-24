// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay.hud

import android.os.IBinder
import android.os.DeadObjectException
import android.os.Parcel
import android.os.RemoteException
import android.os.SystemClock

/**
 * Fixed primitive getter whitelist, with root/module descriptors checked before dispatch.
 * HongqiVehicleField preserves OEM return values; physical units and write acknowledgements
 * must not be inferred from a successful Parcel read.
 */
internal class HongqiVehicleReadOnlyRpc(private val root: IBinder) {
    private val modules = mutableMapOf<Int, IBinder>()
    private val moduleFailures = mutableMapOf<Int, String>()

    init { check(root.interfaceDescriptor == ROOT) { "root-descriptor" } }

    fun connectModules(allowed: () -> Boolean = { true }) {
        for ((id, descriptor) in DESCRIPTORS) {
            if (!allowed()) return
            if (id in modules) continue
            try {
                val request = Parcel.obtain()
                val reply = Parcel.obtain()
                try {
                    request.writeInterfaceToken(ROOT)
                    request.writeInt(id)
                    if (!root.transact(1, request, reply, 0)) throw RemoteException("module-rejected")
                    if (!allowed()) return
                    reply.readException()
                    val module = reply.readStrongBinder() ?: throw RemoteException("module-absent")
                    check(module.interfaceDescriptor == descriptor) { "module-descriptor" }
                    if (!allowed()) return
                    modules[id] = module
                    moduleFailures.remove(id)
                } finally { request.recycle(); reply.recycle() }
            } catch (error: Exception) {
                moduleFailures[id] = error.javaClass.simpleName
            }
        }
    }

    val connectedModuleCount: Int get() = modules.size

    fun read(field: HongqiVehicleField): HongqiVehicleReading {
        val module = modules[field.moduleId] ?: return HongqiVehicleReading(
            null, SystemClock.elapsedRealtime(), "module-${moduleFailures[field.moduleId] ?: "unavailable"}",
        )
        val request = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            request.writeInterfaceToken(DESCRIPTORS.getValue(field.moduleId))
            field.side?.let(request::writeInt)
            if (!module.transact(field.transaction, request, reply, 0)) throw RemoteException("getter-rejected")
            reply.readException()
            if (reply.dataAvail() != 4) throw RemoteException("reply-shape")
            val value = if (field.floating) reply.readFloat().toDouble() else reply.readInt().toDouble()
            field.reading(value, SystemClock.elapsedRealtime())
        } catch (error: Exception) {
            if (error is DeadObjectException) {
                modules.remove(field.moduleId)
                moduleFailures[field.moduleId] = error.javaClass.simpleName
            }
            HongqiVehicleReading(null, SystemClock.elapsedRealtime(), error.javaClass.simpleName)
        } finally { request.recycle(); reply.recycle() }
    }

    companion object {
        const val ROOT = "ltd.qisi.adapterproxy.IVehicleRemoteClienter"
        private val DESCRIPTORS = mapOf(
            15 to "ltd.qisi.adapterproxy.ICarVehicleStateManager",
            33 to "ltd.qisi.adapterproxy.ICarATCManager",
            49 to "ltd.qisi.adapterproxy.ICarSCCUManager",
            31 to "ltd.qisi.adapterproxy.ICarEOLManager",
        )

        // The later system-package audit has byte-identical Qisi code, but a different package
        // label is not permission to broaden this exact runtime firmware gate.
        fun matchesFirmware(build: String, versionCode: Long, versionName: String?): Boolean =
            build == "3212" && versionCode == 3167L && versionName == "20240905-114725"
    }
}

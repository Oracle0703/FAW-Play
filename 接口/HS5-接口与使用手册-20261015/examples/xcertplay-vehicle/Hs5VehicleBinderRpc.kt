// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay.vehicle

import android.os.DeadObjectException
import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException

/** Android 9 Qisi wire contract, recovered from 3222 Stub/Proxy code (not AIDL declaration order). */
internal class Hs5VehicleBinderRpc(private val client: IBinder) : Hs5VehicleRpc {
    private val modules = mutableMapOf<Int, IBinder>()
    private val problems = linkedSetOf<String>()
    var permissionDenied = false
        private set
    var transportDead = false
        private set
    val failure: String? get() = problems.takeIf { it.isNotEmpty() }?.joinToString(",")

    init { require(client.interfaceDescriptor == CLIENT) { "Unexpected vehicle client interface" } }

    fun beginRead() { problems.clear(); permissionDenied = false }

    private fun module(id: Int): IBinder {
        modules[id]?.let { if (it.isBinderAlive) return it else modules.remove(id) }
        val descriptor = DESCRIPTORS[id] ?: throw IllegalArgumentException("Unknown vehicle module")
        val request = Parcel.obtain()
        val response = Parcel.obtain()
        try {
            request.writeInterfaceToken(CLIENT)
            request.writeInt(id)
            if (!client.transact(1, request, response, 0)) throw RemoteException("Vehicle query rejected")
            response.readException()
            val result = response.readStrongBinder() ?: throw RemoteException("Vehicle module unavailable")
            if (result.interfaceDescriptor != descriptor) throw SecurityException("Unexpected vehicle module interface")
            modules[id] = result
            return result
        } finally { request.recycle(); response.recycle() }
    }

    private fun <T> request(id: Int, method: Int, write: (Parcel) -> Unit, read: (Parcel) -> T): T {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            val target = module(id)
            data.writeInterfaceToken(DESCRIPTORS.getValue(id))
            write(data)
            if (!target.transact(method, data, reply, 0)) throw RemoteException("Vehicle method unavailable")
            reply.readException()
            return read(reply)
        } catch (error: Exception) {
            if (error is SecurityException) permissionDenied = true
            if (error is DeadObjectException || !client.isBinderAlive) transportDead = true
            problems.add("$id:${error.javaClass.simpleName}")
            throw error
        } finally { data.recycle(); reply.recycle() }
    }

    private fun requirePrimitive(reply: Parcel) {
        check(reply.dataAvail() >= 4) { "Truncated vehicle reply" }
    }

    override fun int(module: Int, method: Int, vararg args: Int): Int =
        request(module, method, { data -> args.forEach(data::writeInt) }) { requirePrimitive(it); it.readInt() }

    override fun float(module: Int, method: Int, vararg args: Int): Float =
        request(module, method, { data -> args.forEach(data::writeInt) }) { requirePrimitive(it); it.readFloat() }

    override fun string(module: Int, method: Int): String? = request(module, method, {}) {
        requirePrimitive(it)
        it.readString()?.also { text -> check(text.length <= 256) { "Invalid vehicle identifier length" } }
    }

    override fun callIntFloat(module: Int, method: Int, area: Int, value: Float): Int =
        request(module, method, { it.writeInt(area); it.writeFloat(value) }) { requirePrimitive(it); it.readInt() }

    override fun register(module: Int, method: Int, listener: IBinder) {
        request(module, method, { it.writeStrongBinder(listener) }) { Unit }
    }

    override fun unregister(module: Int, method: Int, listener: IBinder) {
        request(module, method, { it.writeStrongBinder(listener) }) { Unit }
    }

    companion object {
        const val CLIENT = "ltd.qisi.adapterproxy.IVehicleRemoteClienter"
        private val DESCRIPTORS = mapOf(
            15 to "ltd.qisi.adapterproxy.ICarVehicleStateManager",
            31 to "ltd.qisi.adapterproxy.ICarEOLManager",
            33 to "ltd.qisi.adapterproxy.ICarATCManager",
            49 to "ltd.qisi.adapterproxy.ICarSCCUManager",
        )
    }
}

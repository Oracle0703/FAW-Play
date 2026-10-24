// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay.vehicle

import android.os.Binder
import android.os.IBinder
import android.os.Parcel

/** Read-only 3222 Qisi vehicle-state wire contract; see docs/evidence/hs5-driving-3222.json.
 *
 * These values are not Apple vehicle-state enums. In particular, the physical speed/fuel/tire
 * units have not been confirmed, and the OEM getters already scale mileage, fuel consumption
 * and tire pressure. Do not apply CAN scaling again or forward RAW fields as physical values.
 */
internal object Hs5DrivingProtocol {
    const val MODULE = 15
    const val DESCRIPTOR = "ltd.qisi.adapterproxy.ICarVehicleStateManager"
    const val LISTENER_DESCRIPTOR = "ltd.qisi.adapterproxy.listener.IVehicleCarStateChangedListener"

    private val doors = listOf(Hs5VehicleField.DOOR_FL, Hs5VehicleField.DOOR_FR, Hs5VehicleField.DOOR_RL, Hs5VehicleField.DOOR_RR)
    private val tires = listOf(
        Hs5VehicleField.TIRE_PRESSURE_RAW_FL, Hs5VehicleField.TIRE_PRESSURE_RAW_FR,
        Hs5VehicleField.TIRE_PRESSURE_RAW_RL, Hs5VehicleField.TIRE_PRESSURE_RAW_RR,
    )

    fun read(rpc: Hs5VehicleRpc): Map<Hs5VehicleField, Double?> = buildMap {
        fun integer(field: Hs5VehicleField, method: Int, vararg args: Int) {
            // One unavailable property does not prevent reading the other OEM properties.
            put(field, runCatching { normalize(field, rpc.int(MODULE, method, *args).toDouble()) }.getOrNull())
        }
        integer(Hs5VehicleField.SPEED_RAW, 7)
        integer(Hs5VehicleField.GEAR_RAW, 6)
        integer(Hs5VehicleField.FUEL_RAW, 31)
        integer(Hs5VehicleField.LOW_FUEL, 58)
        integer(Hs5VehicleField.RANGE_KM, 2)
        integer(Hs5VehicleField.ODOMETER_KM, 24)
        put(Hs5VehicleField.AVERAGE_FUEL_CONSUMPTION_RAW, runCatching {
            normalize(Hs5VehicleField.AVERAGE_FUEL_CONSUMPTION_RAW, rpc.float(MODULE, 59).toDouble())
        }.getOrNull())
        doors.forEachIndexed { index, field -> integer(field, 5, index) }
        integer(Hs5VehicleField.TRUNK, 28)
        integer(Hs5VehicleField.POSITION_LIGHT, 19)
        tires.forEachIndexed { index, field -> integer(field, 26, index) }
    }

    fun listeners(onValue: (Hs5VehicleField, Double?) -> Unit): List<Hs5VehicleSubscription> = listOf(
        Hs5VehicleSubscription(MODULE, DESCRIPTOR, 42, 43, StateListener(onValue)),
    )

    private fun normalize(field: Hs5VehicleField, value: Double): Double? {
        // OEM -1/-2 and 0xffff0001..3, including already-scaled negative errors, stay unknown.
        if (!value.isFinite() || value < 0.0) return null
        return when (field) {
            Hs5VehicleField.SPEED_RAW -> value.takeIf { it < 32766.0 }
            Hs5VehicleField.GEAR_RAW -> value.takeIf { it in 0.0..8.0 || it == 14.0 }
            Hs5VehicleField.FUEL_RAW -> value.takeIf { it < 254.0 }
            Hs5VehicleField.RANGE_KM -> value.takeIf { it != 65533.0 && it != 65535.0 }
            Hs5VehicleField.LOW_FUEL, Hs5VehicleField.DOOR_FL, Hs5VehicleField.DOOR_FR,
            Hs5VehicleField.DOOR_RL, Hs5VehicleField.DOOR_RR, Hs5VehicleField.TRUNK,
            Hs5VehicleField.POSITION_LIGHT -> value.takeIf { it == 0.0 || it == 1.0 }
            else -> value
        }
    }

    private class StateListener(private val onValue: (Hs5VehicleField, Double?) -> Unit) : Binder() {
        init { attachInterface(null, LISTENER_DESCRIPTOR) }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == IBinder.INTERFACE_TRANSACTION) {
                reply?.writeString(LISTENER_DESCRIPTOR)
                return true
            }
            // Only callbacks whose 3222 CAN -> SDK -> Binder chain is confirmed are consumed.
            // Odometer, consumption, tire pressure and trunk continue through polling.
            val bytes = when (code) {
                8 -> 8 // onCarDoorPosState(int position, int state)
                10, 11, 13, 29, 30, 31 -> 4
                else -> return super.onTransact(code, data, reply, flags)
            }
            data.enforceInterface(LISTENER_DESCRIPTOR)
            require(data.dataAvail() == bytes) { "Invalid vehicle-state callback payload" }
            val field: Hs5VehicleField
            if (code == 8) {
                val index = data.readInt()
                require(index in doors.indices) { "Invalid door area" }
                field = doors[index]
            } else {
                field = when (code) {
                    10 -> Hs5VehicleField.SPEED_RAW
                    11 -> Hs5VehicleField.GEAR_RAW
                    13 -> Hs5VehicleField.POSITION_LIGHT
                    29 -> Hs5VehicleField.LOW_FUEL
                    30 -> Hs5VehicleField.FUEL_RAW
                    31 -> Hs5VehicleField.RANGE_KM
                    else -> return false
                }
            }
            onValue(field, normalize(field, data.readInt().toDouble()))
            // OEM Proxy passes reply=null and flags=FLAG_ONEWAY (fifth transact argument=1).
            // Also acknowledge synchronous callers for compatibility.
            if (flags and IBinder.FLAG_ONEWAY == 0) reply?.writeNoException()
            return true
        }
    }
}

// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay.airplay.rcs.caf

import com.shilapi.xcertplay.hud.HongqiVehicleClimateSwitch
import com.shilapi.xcertplay.hud.HongqiVehicleCirculation
import com.shilapi.xcertplay.hud.HongqiVehicleControlCommand
import com.shilapi.xcertplay.hud.HongqiVehicleField
import com.shilapi.xcertplay.hud.HongqiVehicleSeatLevel
import com.shilapi.xcertplay.hud.HongqiVehicleSide
import com.shilapi.xcertplay.hud.HongqiVehicleSnapshot

/**
 * HS5 model, separate from Apple's type identifiers and the Qisi Binder transaction numbers.
 * Every reviewed field is available to a local renderer. Only characteristics whose CAF types
 * are present in the recovered catalog are published to the phone; instance IIDs belong to us.
 */
object Hs5CafVehicleModel {
    const val PLUGIN_ID = 1L
    const val PLUGIN_NAME = "cn.autunn.hs5"
    const val DRIVER_TEMPERATURE_IID = 102L
    const val PASSENGER_TEMPERATURE_IID = 112L
    const val FAN_IID = 122L

    data class Property(
        val field: HongqiVehicleField,
        val raw: Double?,
        val writable: Boolean,
        val available: Boolean,
    )

    /** Immutable current values. Unsupported, stale and failed getters remain null. */
    fun properties(snapshot: HongqiVehicleSnapshot, now: Long): Map<HongqiVehicleField, Property> {
        val current = snapshot.fresh(now)
        return HongqiVehicleField.entries.associateWith { field ->
            val reading = current.readings[field]
            val value = reading?.raw?.takeIf { reading.failure == null && it.isFinite() }
            Property(field, value, writable(field), value != null)
        }
    }

    private val writableFields = setOf(
        HongqiVehicleField.DRIVER_TEMPERATURE, HongqiVehicleField.PASSENGER_TEMPERATURE,
        HongqiVehicleField.FAN, HongqiVehicleField.CLIMATE_POWER, HongqiVehicleField.CLIMATE_AUTO,
        HongqiVehicleField.CLIMATE_SYNC, HongqiVehicleField.CLIMATE_COMPRESSOR,
        HongqiVehicleField.CLIMATE_FRONT_DEFROST, HongqiVehicleField.CLIMATE_CIRCULATION,
        HongqiVehicleField.DRIVER_HEAT, HongqiVehicleField.PASSENGER_HEAT,
        HongqiVehicleField.DRIVER_VENT, HongqiVehicleField.PASSENGER_VENT,
    )

    fun writable(field: HongqiVehicleField): Boolean = field in writableFields

    /** Local UI/control binding for all reviewed setters, with no arbitrary Binder access. */
    fun command(field: HongqiVehicleField, value: Any?): HongqiVehicleControlCommand? {
        val side = if (field in setOf(HongqiVehicleField.PASSENGER_TEMPERATURE,
                HongqiVehicleField.PASSENGER_HEAT, HongqiVehicleField.PASSENGER_VENT))
            HongqiVehicleSide.PASSENGER else HongqiVehicleSide.DRIVER
        val command = when (field) {
            HongqiVehicleField.DRIVER_TEMPERATURE, HongqiVehicleField.PASSENGER_TEMPERATURE ->
                number(value)?.takeIf { it * 2 == kotlin.math.floor(it * 2) }
                    ?.let { HongqiVehicleControlCommand.Temperature(side, it.toFloat()) }
            HongqiVehicleField.FAN -> integer(value)?.let { HongqiVehicleControlCommand.Fan(it) }
            HongqiVehicleField.CLIMATE_POWER -> switch(HongqiVehicleClimateSwitch.POWER, value)
            HongqiVehicleField.CLIMATE_AUTO -> switch(HongqiVehicleClimateSwitch.AUTO, value)
            HongqiVehicleField.CLIMATE_SYNC -> switch(HongqiVehicleClimateSwitch.SYNC, value)
            HongqiVehicleField.CLIMATE_COMPRESSOR -> switch(HongqiVehicleClimateSwitch.COMPRESSOR, value)
            HongqiVehicleField.CLIMATE_FRONT_DEFROST -> switch(HongqiVehicleClimateSwitch.FRONT_DEFROST, value)
            HongqiVehicleField.CLIMATE_CIRCULATION -> integer(value)?.let {
                when (it) {
                    1 -> HongqiVehicleControlCommand.Circulation(HongqiVehicleCirculation.FRESH_AIR)
                    2 -> HongqiVehicleControlCommand.Circulation(HongqiVehicleCirculation.RECIRCULATE)
                    else -> null
                }
            }
            HongqiVehicleField.DRIVER_HEAT, HongqiVehicleField.PASSENGER_HEAT ->
                integer(value)?.let { HongqiVehicleSeatLevel.entries.getOrNull(it) }
                    ?.let { HongqiVehicleControlCommand.SeatHeat(side, it) }
            HongqiVehicleField.DRIVER_VENT, HongqiVehicleField.PASSENGER_VENT ->
                integer(value)?.let { HongqiVehicleSeatLevel.entries.getOrNull(it) }
                    ?.let { HongqiVehicleControlCommand.SeatVent(side, it) }
            else -> null
        }
        return command?.takeIf { it.valid }
    }

    val characteristicFields: Map<Long, HongqiVehicleField> = linkedMapOf(
        DRIVER_TEMPERATURE_IID to HongqiVehicleField.DRIVER_TEMPERATURE,
        PASSENGER_TEMPERATURE_IID to HongqiVehicleField.PASSENGER_TEMPERATURE,
        FAN_IID to HongqiVehicleField.FAN,
    )

    fun wireValue(iid: Long, properties: Map<HongqiVehicleField, Property>): Any? {
        val field = characteristicFields[iid] ?: return null
        val raw = properties[field]?.raw ?: return null
        return if (field == HongqiVehicleField.FAN) raw.toLong() else raw
    }

    fun writeCommand(iid: Long, value: Any?): HongqiVehicleControlCommand? =
        characteristicFields[iid]?.let { field ->
            if (field == HongqiVehicleField.FAN && CafNumbers.toLongOrNull(value) == null) null
            else command(field, value)
        }

    /** The type IDs come from CafTypeCatalog, not from HS5 CAN/module numbers. */
    fun configTree(): Map<String, Any?> = linkedMapOf(
        "accessories" to listOf(linkedMapOf(
            "type" to 0x01000001L, "iid" to 100L, "version" to "1.0",
            "services" to listOf(
                temperature(101L, DRIVER_TEMPERATURE_IID),
                temperature(111L, PASSENGER_TEMPERATURE_IID),
                linkedMapOf("type" to 0x11000007L, "iid" to 121L,
                    "characteristics" to listOf(linkedMapOf(
                        "type" to 0x31000012L, "iid" to FAN_IID,
                        "format" to CafCharacteristicFormat.UINT8.wireValue,
                        "writable" to true, "mutable" to true, "supportsInvalid" to true,
                        "minimumValue" to 0L, "maximumValue" to 7L, "stepValue" to 1L,
                    ))),
            ),
        )),
    )

    private fun temperature(serviceIid: Long, characteristicIid: Long): Map<String, Any?> =
        linkedMapOf("type" to 0x11000002L, "iid" to serviceIid,
            "characteristics" to listOf(linkedMapOf(
                "type" to 0x31000017L, "iid" to characteristicIid,
                "format" to CafCharacteristicFormat.FLOAT.wireValue,
                "writable" to true, "mutable" to true, "supportsInvalid" to true,
                "minimumValue" to HongqiVehicleControlCommand.MIN_CELSIUS.toDouble(),
                "maximumValue" to HongqiVehicleControlCommand.MAX_CELSIUS.toDouble(),
                "stepValue" to 0.5,
            )))

    private fun number(value: Any?): Double? = (value as? Number)?.toDouble()?.takeIf { it.isFinite() }
    private fun integer(value: Any?): Int? = number(value)?.takeIf {
        it >= Int.MIN_VALUE && it <= Int.MAX_VALUE && it == kotlin.math.floor(it)
    }?.toInt()
    private fun switch(setting: HongqiVehicleClimateSwitch, value: Any?) =
        (value as? Boolean)?.let { HongqiVehicleControlCommand.Switch(setting, it) }
}

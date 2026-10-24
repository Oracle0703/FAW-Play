package com.shilapi.xcertplay.hud

import android.content.Context
import android.os.Build

/** The small read-only BYD data surface DiPlay can forward to CarPlay. */
enum class AutunnVehicleField(
    internal val symbol: String,
    internal val symbolAliases: List<String>,
    internal val deviceName: String,
    internal val defaultDevice: Int,
    internal val defaultFid: Int,
    internal val transaction: Int,
) {
    SPEED("Speed.SPEED_AUTO_SPEED", emptyList(), "SPEED", 1013, -1807745016, 7),
    GEAR("Gearbox.GEARBOX_AUTO_MODE_TYPE", emptyList(), "GEARBOX", 1011, 555745336, 5),
    SOC("Statistic.STATISTIC_ELEC_PERCENTAGE", emptyList(), "STATISTIC", 1014, 1246777400, 7),
    RANGE("Statistic.STATISTIC_ELEC_DRIVING_RANGE", emptyList(), "STATISTIC", 1014, 1246765118, 5),
    REMAINING_KWH(
        "Power.POWER_BATTERY_REMAIN_ELECTRICITY",
        emptyList(),
        "POWER",
        1005,
        882901008,
        7,
    ),
    BMS_STATE(
        "Charging.CHARGING_BATTERRY_DEVICE_STATE",
        listOf("Charging.CHARGING_BATTERY_DEVICE_STATE"),
        "CHARGING",
        1009,
        876609560,
        5,
    ),
}

enum class AutunnFieldSource { FIRMWARE, KNOWN_13, DEFAULT }

data class AutunnReadAddress(
    val device: Int,
    val fid: Int,
    val transaction: Int,
    val source: AutunnFieldSource,
) {
    fun command(): String = "service call autoservice $transaction i32 $device i32 $fid"
}

data class AutunnFieldProbeResult(
    val field: AutunnVehicleField,
    val supported: Boolean,
    val address: AutunnReadAddress?,
    /** Decoded numeric value at probe time; gear and BMS state retain their integer code. */
    val value: Double? = null,
    val failure: String? = null,
)

/** A completed, read-only probe of the current head unit. */
data class AutunnVehicleCapabilities(
    val fields: Map<AutunnVehicleField, AutunnFieldProbeResult>,
    val catalogAvailable: Boolean,
    val firmwareKey: String,
    val detectedAtMillis: Long = System.currentTimeMillis(),
) {
    fun result(field: AutunnVehicleField): AutunnFieldProbeResult =
        fields[field] ?: AutunnFieldProbeResult(field, false, null, failure = "not probed")

    fun supports(field: AutunnVehicleField): Boolean = result(field).supported

    val motionSupported: Boolean get() = supports(AutunnVehicleField.SPEED) && supports(AutunnVehicleField.GEAR)
    val gearSupported: Boolean get() = supports(AutunnVehicleField.GEAR)
    val batterySupported: Boolean get() = supports(AutunnVehicleField.SOC) && supports(AutunnVehicleField.RANGE)
    val batteryEnergySupported: Boolean get() = supports(AutunnVehicleField.REMAINING_KWH)
    val chargingSupported: Boolean get() = supports(AutunnVehicleField.BMS_STATE)
}

data class AutunnVehicleAutomaticReplaceResult(
    val saved: Boolean,
    val lostFields: Set<AutunnVehicleField> = emptySet(),
    val snapshotChanged: Boolean = false,
)

/**
 * Persists the last successfully completed probe. Its firmware id is diagnostic metadata rather
 * than an invalidation key: temporary platform differences, process restarts and app updates must
 * not discard working addresses. A later complete probe atomically replaces this snapshot.
 */
object AutunnVehicleFieldStore {
    private const val PREFS = "diplay_byd_vehicle_fields"
    private const val KEY_SCHEMA = "schema"
    private const val KEY_FIRMWARE = "firmware"
    private const val KEY_CATALOG = "catalog"
    private const val KEY_DETECTED_AT = "detected_at"
    private const val SCHEMA = 1

    @Volatile private var memory: AutunnVehicleCapabilities? = null

    fun firmwareKey(): String = "${Build.FINGERPRINT}|${Build.DISPLAY}"

    @Synchronized
    fun load(context: Context): AutunnVehicleCapabilities? {
        memory?.let { return it }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getInt(KEY_SCHEMA, 0) != SCHEMA) return null
        val savedFirmware = prefs.getString(KEY_FIRMWARE, null) ?: return null
        val fields = AutunnVehicleField.entries.associateWith { field ->
            val name = field.name.lowercase()
            val supported = prefs.getBoolean("${name}_supported", false)
            val address = if (supported && prefs.contains("${name}_fid")) {
                AutunnReadAddress(
                    device = prefs.getInt("${name}_device", field.defaultDevice),
                    fid = prefs.getInt("${name}_fid", field.defaultFid),
                    transaction = field.transaction,
                    source = prefs.getString("${name}_source", null)
                        ?.let { runCatching { AutunnFieldSource.valueOf(it) }.getOrNull() }
                        ?: AutunnFieldSource.DEFAULT,
                )
            } else null
            AutunnFieldProbeResult(field, supported, address)
        }
        return AutunnVehicleCapabilities(
            fields = fields,
            catalogAvailable = prefs.getBoolean(KEY_CATALOG, false),
            firmwareKey = savedFirmware,
            detectedAtMillis = prefs.getLong(KEY_DETECTED_AT, 0L),
        ).also {
            memory = it
        }
    }

    @Synchronized
    fun save(context: Context, capabilities: AutunnVehicleCapabilities) {
        val key = firmwareKey()
        require(capabilities.firmwareKey == key) { "probe belongs to another firmware" }
        val edit = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear()
            .putInt(KEY_SCHEMA, SCHEMA)
            .putString(KEY_FIRMWARE, key)
            .putBoolean(KEY_CATALOG, capabilities.catalogAvailable)
            .putLong(KEY_DETECTED_AT, capabilities.detectedAtMillis)
        for (field in AutunnVehicleField.entries) {
            val name = field.name.lowercase()
            val result = capabilities.result(field)
            edit.putBoolean("${name}_supported", result.supported)
            result.address?.let { address ->
                edit.putInt("${name}_device", address.device)
                    .putInt("${name}_fid", address.fid)
                    .putString("${name}_source", address.source.name)
            }
        }
        check(edit.commit()) { "Could not persist BYD vehicle field probe" }
        memory = capabilities
        // A candidate held for losing fields or a changed snapshot is not runtime data.
        AutunnVehicleCapabilityProbe.publishBatteryReading(context, capabilities)
    }

    /**
     * No probe, automatic or user-requested, silently removes a previously confirmed field: a
     * candidate is committed only when it confirms every field the last-known-good snapshot
     * supported, and dropping fields takes the user's [replaceAnyway]. [expected] also prevents a
     * late result from overwriting a snapshot saved since it was read.
     */
    @Synchronized
    fun replaceAutomatically(
        context: Context,
        expected: AutunnVehicleCapabilities?,
        candidate: AutunnVehicleCapabilities,
    ): AutunnVehicleAutomaticReplaceResult {
        val current = load(context)
        if (current != expected) {
            return AutunnVehicleAutomaticReplaceResult(saved = false, snapshotChanged = true)
        }
        val lost = current?.let { saved ->
            AutunnVehicleField.entries.filterTo(linkedSetOf()) { saved.supports(it) && !candidate.supports(it) }
        }.orEmpty()
        if (lost.isNotEmpty()) {
            return AutunnVehicleAutomaticReplaceResult(saved = false, lostFields = lost)
        }
        save(context, candidate)
        return AutunnVehicleAutomaticReplaceResult(saved = true)
    }

    /** The user's explicit replacement may drop fields, but not overwrite a snapshot saved since [expected]. */
    @Synchronized
    fun replaceAnyway(
        context: Context,
        expected: AutunnVehicleCapabilities?,
        candidate: AutunnVehicleCapabilities,
    ): Boolean {
        if (load(context) != expected) return false
        save(context, candidate)
        return true
    }

    /** Cached firmware address when available, otherwise the release's DiLink 5 constant. */
    fun address(context: Context, field: AutunnVehicleField): AutunnReadAddress =
        load(context)?.result(field)?.address ?: defaultAddress(field)

    fun defaultAddress(field: AutunnVehicleField) = AutunnReadAddress(
        field.defaultDevice,
        field.defaultFid,
        field.transaction,
        AutunnFieldSource.DEFAULT,
    )

    /** Test-only cache reset; production keeps the last successful probe until it is replaced. */
    fun clearMemoryForTests() {
        memory = null
    }
}

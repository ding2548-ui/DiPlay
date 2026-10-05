package com.shilapi.xcertplay.hud

import android.content.Context
import android.os.Build

/** The small read-only BYD data surface DiPlay can forward to CarPlay. */
enum class BydVehicleField(
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

enum class BydFieldSource { FIRMWARE, KNOWN_13, DEFAULT }

data class BydReadAddress(
    val device: Int,
    val fid: Int,
    val transaction: Int,
    val source: BydFieldSource,
) {
    fun command(): String = "service call autoservice $transaction i32 $device i32 $fid"
}

data class BydFieldProbeResult(
    val field: BydVehicleField,
    val supported: Boolean,
    val address: BydReadAddress?,
    /** Decoded numeric value at probe time; gear and BMS state retain their integer code. */
    val value: Double? = null,
    val failure: String? = null,
)

/** A completed, read-only probe of the current head unit. */
data class BydVehicleCapabilities(
    val fields: Map<BydVehicleField, BydFieldProbeResult>,
    val catalogAvailable: Boolean,
    val firmwareKey: String,
    val detectedAtMillis: Long = System.currentTimeMillis(),
) {
    fun result(field: BydVehicleField): BydFieldProbeResult =
        fields[field] ?: BydFieldProbeResult(field, false, null, failure = "not probed")

    fun supports(field: BydVehicleField): Boolean = result(field).supported

    val motionSupported: Boolean get() = supports(BydVehicleField.SPEED) && supports(BydVehicleField.GEAR)
    val gearSupported: Boolean get() = supports(BydVehicleField.GEAR)
    val batterySupported: Boolean get() = supports(BydVehicleField.SOC) && supports(BydVehicleField.RANGE)
    val batteryEnergySupported: Boolean get() = supports(BydVehicleField.REMAINING_KWH)
    val chargingSupported: Boolean get() = supports(BydVehicleField.BMS_STATE)
}

data class BydVehicleAutomaticReplaceResult(
    val saved: Boolean,
    val lostFields: Set<BydVehicleField> = emptySet(),
    val snapshotChanged: Boolean = false,
)

/**
 * Persists the last successfully completed probe. Its firmware id is diagnostic metadata rather
 * than an invalidation key: temporary platform differences, process restarts and app updates must
 * not discard working addresses. A later complete probe atomically replaces this snapshot.
 */
object BydVehicleFieldStore {
    private const val PREFS = "diplay_byd_vehicle_fields"
    private const val KEY_SCHEMA = "schema"
    private const val KEY_FIRMWARE = "firmware"
    private const val KEY_CATALOG = "catalog"
    private const val KEY_DETECTED_AT = "detected_at"
    private const val SCHEMA = 1

    @Volatile private var memory: BydVehicleCapabilities? = null

    fun firmwareKey(): String = "${Build.FINGERPRINT}|${Build.DISPLAY}"

    @Synchronized
    fun load(context: Context): BydVehicleCapabilities? {
        memory?.let { return it }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getInt(KEY_SCHEMA, 0) != SCHEMA) return null
        val savedFirmware = prefs.getString(KEY_FIRMWARE, null) ?: return null
        val fields = BydVehicleField.entries.associateWith { field ->
            val name = field.name.lowercase()
            val supported = prefs.getBoolean("${name}_supported", false)
            val address = if (supported && prefs.contains("${name}_fid")) {
                BydReadAddress(
                    device = prefs.getInt("${name}_device", field.defaultDevice),
                    fid = prefs.getInt("${name}_fid", field.defaultFid),
                    transaction = field.transaction,
                    source = prefs.getString("${name}_source", null)
                        ?.let { runCatching { BydFieldSource.valueOf(it) }.getOrNull() }
                        ?: BydFieldSource.DEFAULT,
                )
            } else null
            BydFieldProbeResult(field, supported, address)
        }
        return BydVehicleCapabilities(
            fields = fields,
            catalogAvailable = prefs.getBoolean(KEY_CATALOG, false),
            firmwareKey = savedFirmware,
            detectedAtMillis = prefs.getLong(KEY_DETECTED_AT, 0L),
        ).also {
            memory = it
        }
    }

    @Synchronized
    fun save(context: Context, capabilities: BydVehicleCapabilities) {
        val key = firmwareKey()
        require(capabilities.firmwareKey == key) { "probe belongs to another firmware" }
        val edit = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear()
            .putInt(KEY_SCHEMA, SCHEMA)
            .putString(KEY_FIRMWARE, key)
            .putBoolean(KEY_CATALOG, capabilities.catalogAvailable)
            .putLong(KEY_DETECTED_AT, capabilities.detectedAtMillis)
        for (field in BydVehicleField.entries) {
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
        BydVehicleCapabilityProbe.publishBatteryReading(context, capabilities)
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
        expected: BydVehicleCapabilities?,
        candidate: BydVehicleCapabilities,
    ): BydVehicleAutomaticReplaceResult {
        val current = load(context)
        if (current != expected) {
            return BydVehicleAutomaticReplaceResult(saved = false, snapshotChanged = true)
        }
        val lost = current?.let { saved ->
            BydVehicleField.entries.filterTo(linkedSetOf()) { saved.supports(it) && !candidate.supports(it) }
        }.orEmpty()
        if (lost.isNotEmpty()) {
            return BydVehicleAutomaticReplaceResult(saved = false, lostFields = lost)
        }
        save(context, candidate)
        return BydVehicleAutomaticReplaceResult(saved = true)
    }

    /** The user's explicit replacement may drop fields, but not overwrite a snapshot saved since [expected]. */
    @Synchronized
    fun replaceAnyway(
        context: Context,
        expected: BydVehicleCapabilities?,
        candidate: BydVehicleCapabilities,
    ): Boolean {
        if (load(context) != expected) return false
        save(context, candidate)
        return true
    }

    /** Cached firmware address when available, otherwise the release's DiLink 5 constant. */
    fun address(context: Context, field: BydVehicleField): BydReadAddress =
        load(context)?.result(field)?.address ?: defaultAddress(field)

    fun defaultAddress(field: BydVehicleField) = BydReadAddress(
        field.defaultDevice,
        field.defaultFid,
        field.transaction,
        BydFieldSource.DEFAULT,
    )

    /** Test-only cache reset; production keeps the last successful probe until it is replaced. */
    fun clearMemoryForTests() {
        memory = null
    }
}

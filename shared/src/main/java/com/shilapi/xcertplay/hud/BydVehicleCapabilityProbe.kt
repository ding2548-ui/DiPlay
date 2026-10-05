package com.shilapi.xcertplay.hud

import android.content.Context
import android.os.Build
import com.shilapi.xcertplay.adb.AdbKeys
import com.shilapi.xcertplay.adb.LocalAdb

data class BydVehicleProbeOutcome(
    val access: BydAdbAccess.State,
    val capabilities: BydVehicleCapabilities? = null,
    val error: String? = null,
)

/** User-initiated, read-only detection for controller-13 / DiLink 3 head units. */
object BydVehicleCapabilityProbe {
    // One probe at a time: a cancelled automatic re-probe still reading must not push a new one into timeouts.
    @Synchronized
    fun probe(context: Context, persist: Boolean = true): BydVehicleProbeOutcome {
        val app = context.applicationContext
        LocalAdb(AdbKeys.load(app)).use { adb ->
            val access = BydAdbAccess.state(adb.connect(mayAsk = false))
            if (access != BydAdbAccess.State.READY) return BydVehicleProbeOutcome(access)

            val catalogOutput = adb.shell(catalogCommand(app), 15_000)
            val batch = probeFields(catalogOutput, Build.VERSION.SDK_INT, adb::shell)
            return finishProbe(app, access, batch, persist)
        }
    }

    internal fun finishProbe(
        context: Context,
        access: BydAdbAccess.State,
        batch: ProbeBatch,
        persist: Boolean,
    ): BydVehicleProbeOutcome {
        if (!batch.complete) {
            return BydVehicleProbeOutcome(
                access,
                error = "autoservice probe incomplete: repliedFields=${batch.repliedFields}/" +
                    "${BydVehicleField.entries.size} failedReads=${batch.failedReads}",
            )
        }
        val capabilities = batch.capabilities
        if (persist) BydVehicleFieldStore.save(context, capabilities)
        return BydVehicleProbeOutcome(access, capabilities)
    }

    private fun catalogCommand(context: Context): String {
        val apk = shellQuote(context.packageCodePath)
        return "CLASSPATH=$apk app_process /system/bin ${Byd13CatalogProbeMain::class.java.name}"
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"

    internal data class ProbeBatch(
        val capabilities: BydVehicleCapabilities,
        val transportReplies: Int,
        val repliedFields: Int,
        val failedReads: Int,
    ) {
        val complete: Boolean get() =
            failedReads == 0 && repliedFields == BydVehicleField.entries.size
    }

    internal fun probeFields(
        catalogOutput: String?,
        sdkInt: Int,
        shell: (String) -> String?,
        firmwareKey: String = BydVehicleFieldStore.firmwareKey(),
    ): ProbeBatch {
        val catalog = catalogOutput?.let(BydFirmwareCatalog::parse)
        var transportReplies = 0
        var repliedFields = 0
        var failedReads = if (catalogOutput == null) 1 else 0
        val results = LinkedHashMap<BydVehicleField, BydFieldProbeResult>()
        for (field in BydVehicleField.entries) {
            // A failed read already makes the probe incomplete: stop rather than wait out more timeouts.
            if (failedReads > 0) break
            var accepted: BydFieldProbeResult? = null
            var lastFailure = "no candidate"
            var receivedReply = false
            for (candidate in candidates(field, catalog, sdkInt)) {
                val output = shell(candidate.command())
                if (output != null) {
                    transportReplies++
                    receivedReply = true
                } else {
                    failedReads++
                }
                val decoded = decode(field, output)
                if (decoded != null) {
                    accepted = BydFieldProbeResult(field, true, candidate, decoded)
                    break
                }
                lastFailure = if (output == null) "ADB read failed" else "unsupported reply"
                if (output == null) break
            }
            if (receivedReply) repliedFields++
            results[field] = accepted ?: BydFieldProbeResult(field, false, null, failure = lastFailure)
        }
        return ProbeBatch(
            capabilities = BydVehicleCapabilities(
                fields = results,
                catalogAvailable = catalog != null,
                firmwareKey = firmwareKey,
            ),
            transportReplies = transportReplies,
            repliedFields = repliedFields,
            failedReads = failedReads,
        )
    }

    private fun candidates(field: BydVehicleField, catalog: BydFirmwareCatalog?, sdkInt: Int): List<BydReadAddress> {
        val values = ArrayList<BydReadAddress>()
        catalog?.fid(field)?.let { fid ->
            values += BydReadAddress(catalog.device(field), fid, field.transaction, BydFieldSource.FIRMWARE)
        }
        if (sdkInt <= Build.VERSION_CODES.Q) values += known13(field)
        values += BydVehicleFieldStore.defaultAddress(field)
        return values.distinctBy { Triple(it.transaction, it.device, it.fid) }
    }

    /** Field reports observed on controller-13 / DiLink 3; every value is still live-probed. */
    private fun known13(field: BydVehicleField): List<BydReadAddress> = when (field) {
        BydVehicleField.SPEED -> listOf(BydReadAddress(1013, -1176502256, 7, BydFieldSource.KNOWN_13))
        BydVehicleField.GEAR -> listOf(BydReadAddress(1011, 555745336, 5, BydFieldSource.KNOWN_13))
        BydVehicleField.SOC -> listOf(BydReadAddress(1014, 1033543720, 7, BydFieldSource.KNOWN_13))
        BydVehicleField.BMS_STATE -> listOf(
            BydReadAddress(1009, 1231032336, 5, BydFieldSource.KNOWN_13),
            BydReadAddress(1009, 876611608, 5, BydFieldSource.KNOWN_13),
        )
        else -> emptyList()
    }

    private fun decode(field: BydVehicleField, output: String?): Double? {
        val word = BydParcel.value(output) ?: return null
        return when (field) {
            BydVehicleField.SPEED -> java.lang.Float.intBitsToFloat(word).toDouble()
                .takeIf { it.isFinite() && it in 0.0..300.0 }
            BydVehicleField.GEAR -> word.toDouble().takeIf { word in 1..6 }
            BydVehicleField.SOC -> java.lang.Float.intBitsToFloat(word).toDouble()
                .takeIf { it.isFinite() && it in 0.0..100.0 }
            BydVehicleField.RANGE -> word.toDouble().takeIf { word in 0..3000 }
            BydVehicleField.REMAINING_KWH -> java.lang.Float.intBitsToFloat(word).toDouble()
                .takeIf { it.isFinite() && it in 0.0..300.0 }
            BydVehicleField.BMS_STATE -> word.toDouble().takeIf { word in 0..255 }
        }
    }

    internal fun publishBatteryReading(context: Context, capabilities: BydVehicleCapabilities) {
        if (!BydOutputSettings.legacyVehicleProbe(context)) return
        if (!capabilities.batterySupported) return
        val percent = capabilities.result(BydVehicleField.SOC).value ?: return
        val range = capabilities.result(BydVehicleField.RANGE).value?.toInt() ?: return
        val measured = capabilities.result(BydVehicleField.REMAINING_KWH).value
        val charging = capabilities.result(BydVehicleField.BMS_STATE).value?.toInt() == 1
        BydBatteryStatus.publishProbeReading(context, BydBatteryReading(percent, range, measured, charging))
    }
}

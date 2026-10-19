package com.shilapi.xcertplay.setup

import android.content.Context
import android.os.Build
import com.shilapi.xcertplay.hud.BydOutputSettings
import com.shilapi.xcertplay.hud.BydVehicleDataMode

/**
 * The car the driver told DiPlay about. The setup guide uses it to explain which features apply and
 * to route the OEM outputs to the right protocol family.
 *
 * Historically this was BYD-only (DiLink 3/4/5); the PSA/Leapmotor ports added a manual
 * [LEAPMOTOR] entry so a driver of an S01/T03 can pick their car outright. [LEAPMOTOR] is never
 * produced by [detect] — the Leapmotor file probe answers that — it only comes from a manual pick,
 * and once saved it also drives that probe's override so every downstream transport flips together.
 */
enum class DiLinkGeneration(val key: String) {
    DILINK_3("dilink3"),
    DILINK_4("dilink4"),
    DILINK_5("dilink5"),
    /** 零跑 S01 / T03 (and siblings): use the car's broadcast protocols, no BYD ADB. */
    LEAPMOTOR("leapmotor"),
    UNKNOWN("unknown");

    /** True for the one non-BYD family, so callers can branch without naming the constant. */
    val isLeapmotor: Boolean get() = this == LEAPMOTOR

    /** How the guess was made, so the guide can say how sure it is. */
    enum class Source { SYSTEM_VERSION, LIKELY, NONE }

    data class Detection(val generation: DiLinkGeneration, val source: Source, val evidence: String?)

    companion object {
        private val VERSION = Regex("""DiLink\s*([345])(?:\.\d+)?""", RegexOption.IGNORE_CASE)
        private const val CONTROLLER_VERSION_PROPERTY = "apps.setting.product.outswver"
        private const val PREFS = "diplay"
        private const val KEY = "setup_dilink_generation"

        fun fromKey(key: String?): DiLinkGeneration? = entries.firstOrNull { it.key == key }

        /**
         * BYD names the build after the head unit: DiLink 3.0 reports product `DiLink3.0` and a
         * `BYD-AUTO/DiLink3.0/...` fingerprint. DiLink 5.1 reports a generic `IVI` product on Android 13.
         *
         * [LEAPMOTOR] is never returned here: the Leapmotor probe owns that answer and the guide
         * only offers the manual pick. Detection stays a pure function of the build strings.
         */
        fun detect(product: String?, fingerprint: String?, display: String?, sdkInt: Int,
                   controllerVersion: String? = null): Detection {
            for (value in listOf(product, fingerprint, display)) {
                val match = value?.let(VERSION::find) ?: continue
                val generation = when (match.groupValues[1]) {
                    "3" -> DILINK_3
                    "4" -> DILINK_4
                    else -> DILINK_5
                }
                return Detection(generation, Source.SYSTEM_VERSION, value)
            }
            controllerVersion?.trim()?.takeIf { it.isNotEmpty() }?.let { version ->
                val generation = when (version.substringBefore('.').toIntOrNull()) {
                    13, 15, 18 -> DILINK_3
                    16, 17, 21 -> DILINK_4
                    23, 34, 57, 59 -> DILINK_5
                    else -> null
                }
                if (generation != null) return Detection(generation, Source.SYSTEM_VERSION, version)
            }
            if (fingerprint?.startsWith("BYD-AUTO/IVI/") == true && sdkInt >= 33) {
                return Detection(DILINK_5, Source.LIKELY, fingerprint)
            }
            return Detection(UNKNOWN, Source.NONE, fingerprint?.takeIf { it.isNotBlank() })
        }

        fun detect(): Detection = detect(Build.PRODUCT, Build.FINGERPRINT, Build.DISPLAY,
            Build.VERSION.SDK_INT, readControllerVersion())

        private fun readControllerVersion(): String? = runCatching {
            val cls = Class.forName("android.os.SystemProperties")
            val method = cls.getMethod("get", String::class.java, String::class.java)
            method.invoke(null, CONTROLLER_VERSION_PROPERTY, "")?.toString()
        }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }

        private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        fun confirmed(context: Context): DiLinkGeneration? = fromKey(prefs(context).getString(KEY, null))

        /** The driver's confirmed choice, or the detected generation when they have not picked one. */
        fun current(context: Context): DiLinkGeneration = confirmed(context) ?: detect().generation

        /**
         * Save the driver's pick and push it into the shared probe so every module agrees.
         *
         * - [LEAPMOTOR] marks the unit as Leapmotor (all Leapmotor protocols on, no detection needed)
         *   and moves the advanced vehicle-data panel onto the CAN bus, because a Leapmotor head unit
         *   has no adbd and neither DiLink mode can read anything there.
         * - A BYD generation explicitly marks it as *not* Leapmotor, which also overrides a stale
         *   probe file on a re-flashed unit, and drops a stale Leapmotor vehicle-data mode. An
         *   explicit default/legacy pick is left alone.
         * - [UNKNOWN] ("Not sure") clears the override and defers to the runtime probe, so leaving it
         *   on Not sure never breaks a real Leapmotor car.
         */
        fun save(context: Context, generation: DiLinkGeneration) {
            prefs(context).edit().putString(KEY, generation.key).apply()
            when (generation) {
                DiLinkGeneration.UNKNOWN -> BydOutputSettings.clearLeapmotorUserChoice(context)
                else -> {
                    BydOutputSettings.setLeapmotorUserChoice(context, generation.isLeapmotor)
                    when {
                        generation.isLeapmotor -> BydOutputSettings.setVehicleDataMode(
                            context, BydVehicleDataMode.LEAPMOTOR)
                        BydOutputSettings.vehicleDataMode(context) == BydVehicleDataMode.LEAPMOTOR ->
                            BydOutputSettings.setVehicleDataMode(context, BydVehicleDataMode.DEFAULT)
                        else -> Unit
                    }
                }
            }
        }
    }
}

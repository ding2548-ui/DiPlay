package com.shilapi.xcertplay

import android.content.Context

/**
 * Tells a Leapmotor head unit apart from any other car at runtime, so one APK can drive both lamp
 * transports without a build-time flag and without ever starting the BYD ADB worker on a car that
 * does not answer it.
 *
 * Evidence (reverse-engineered from the head unit's own `VehicleSetting.apk`,
 * `setting/system/SystemFragment.smali` — `getCarType()`): the OEM app identifies the car by reading
 * the file `/data/leapmotor/CarType` (`util/CommonTools.smali:789` — `ReadBytesFromFile`) and taking
 * the byte at offset 7 as the model code (1..5 = the S01 variants). The path and its `leapmotor`
 * owner are what we key on; the model code is a bonus we only log.
 *
 * The OEM's own reader does `array-length` on the result without a null guard, so it would crash on
 * a missing file. We therefore never read first: [isLeapmotor] only checks that the file exists and
 * is a regular file, which is exactly the guard `ReadBytesFromFile` applies internally, and a
 * missing file simply means "not a Leapmotor".
 *
 * `/data/leapmotor/` is world-readable on these units, so no permission is needed. The secondary
 * AIDL route (`LpDeviceCtrlService` → `Leapmotor.DeviceCtrlAidlService` in
 * `com.leapmotor.systemupdate` → `IDeviceCtrlInterface.getCarType()`, transaction 0x15) is
 * deliberately not used: it is asynchronous, needs a `<queries>` package-visibility entry, and
 * answers the same question.
 *
 * ## Manual override (setup wizard)
 * The setup guide also lets the driver pick the head unit by hand ("零跑 S01/T03" or a BYD DiLink
 * generation). That explicit choice must beat the file probe both ways:
 *  - pick Leapmotor → treat the unit as a Leapmotor even if the probe file is somehow missing;
 *  - pick any BYD generation → do **not** run the Leapmotor broadcasts even if the probe file is
 *    present (an OEM-swapped or re-flashed unit is exactly the case the manual pick exists for).
 * [setUserChoice] / [clearUserChoice] persist that choice; [isLeapmotor] consults it before probing.
 */
internal object LmCarProbe {
    /** File the OEM app reads to identify the car. */
    const val CAR_TYPE_FILE = "/data/leapmotor/CarType"

    /** Directory that only a Leapmotor head unit carries. */
    const val CAR_TYPE_DIR = "/data/leapmotor"

    /** Model codes at byte 7 of [CAR_TYPE_FILE]; logged for diagnostics only. */
    private val MODELS = mapOf(
        1 to "S01 380 standard", 2 to "S01 380 PRO",
        3 to "S01 460 standard", 4 to "S01 460 PRO",
        5 to "S01 460 standard (smart option)",
    )

    private const val PREFS = "diplay"

    /**
     * Tri-state manual choice. Absent = obey the probe; `true`/`false` = the driver's explicit pick
     * wins. Stored as an int so "absent" is distinguishable from an explicit `false`.
     */
    private const val KEY_USER_CHOICE = "head_unit_leapmotor"

    private const val CHOICE_UNSET = 0
    private const val CHOICE_BYD = 1
    private const val CHOICE_LEAPMOTOR = 2

    @Volatile private var cached: Boolean? = null

    @Volatile private var userChoice: Boolean? = null

    /**
     * True when this head unit is a Leapmotor car. The driver's manual choice wins; otherwise the
     * file probe decides. Cached: neither the file nor the choice changes during a session.
     */
    fun isLeapmotor(): Boolean = userChoice ?: (cached ?: probe().also { cached = it })

    /**
     * Load the saved manual choice once, before the first [isLeapmotor] call. Safe to call more than
     * once; only the first call reads prefs.
     */
    fun loadUserChoice(context: Context) {
        if (userChoice != null) return
        userChoice = readChoice(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE))
    }

    /**
     * Persist the driver's explicit pick. `true` = "this is a Leapmotor", `false` = "this is a BYD
     * DiLink unit". Writes the loaded prefs and drops the memoised answers so the next
     * [isLeapmotor] uses the new value.
     */
    fun setUserChoice(context: Context, leapmotor: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_USER_CHOICE, if (leapmotor) CHOICE_LEAPMOTOR else CHOICE_BYD).apply()
        userChoice = leapmotor
        cached = null
    }

    /** Forget the manual choice and go back to the file probe. Test/support seam. */
    fun clearUserChoice(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_USER_CHOICE).apply()
        userChoice = null
        cached = null
    }

    private fun readChoice(prefs: android.content.SharedPreferences): Boolean? = when (prefs.getInt(KEY_USER_CHOICE, CHOICE_UNSET)) {
        CHOICE_LEAPMOTOR -> true
        CHOICE_BYD -> false
        else -> null
    }

    private fun probe(): Boolean {
        val found = runCatching { java.io.File(CAR_TYPE_FILE).isFile }.getOrDefault(false)
        val model = if (found) runCatching { modelCode() }.getOrNull() else null
        AmbientDiagnostics.log("leapmotor probe file=$found model=${model ?: 0} path=$CAR_TYPE_FILE")
        return found
    }

    /** Model code at offset 7, or null when the file is shorter than the OEM expects. */
    private fun modelCode(): Int? {
        val bytes = java.io.File(CAR_TYPE_FILE).readBytes()
        return if (bytes.size >= 16) bytes[7].toInt() and 0xff else null
    }

    /** Human-readable model, for the diagnostic line only. */
    fun modelName(): String? = runCatching { MODELS[modelCode()] }.getOrNull()

    /** Test seam: forget the cached answer and any manual choice held in memory. */
    internal fun resetForTests() {
        cached = null
        userChoice = null
    }
}

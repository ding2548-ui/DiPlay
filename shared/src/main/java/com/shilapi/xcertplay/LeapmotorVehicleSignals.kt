package com.shilapi.xcertplay

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.orchestration.CarPlayController

/**
 * The Leapmotor head unit's vehicle signals, read from the CAN server's broadcast API.
 *
 * Protocol (reverse-engineered from `com.leapmotor.vehiclesetting`, see
 * `docs/零跑车机数据来源分析.md` §2): sending the broadcast `leapmotor.apptocan.update` with extra
 * `upgradeAsk="upgrade"` makes `com.leapmotor.carcanserver` answer with `leapmotor.cantoapp.update`,
 * whose extras are CAN signal codes in decimal — one extra per signal, an Int or a Float, with
 * `-1` / `-1.0f` meaning "not present in this frame" (never a real zero).
 *
 * Known codes (from `UpdateTools$CanDataReceiver`, keep this table in sync with that source):
 *  - `1010105` gear: 1=R, 2=N, 3=D, anything else (including 0) = P.
 *  - `100001` ICU speed, `1100412` effective speed (Int), `1100409` ABS/ESC speed (Float).
 *  - `1060115` battery SOC, `1030115` power SOC, `1060306` battery SOH (Int), `1060122` battery
 *    voltage (Float).
 *  - `1030505` fast charge, `1030506` slow charge, `1110113` EPB (Int).
 *
 * The same broadcast feeds every consumer here, so one poll serves the gear monitor, the iPhone's
 * vehicle status (battery) and dead reckoning (speed). No ADB is involved: the Leapmotor head unit
 * has no adbd.
 */
object LeapmotorVehicleSignals {
    private const val TAG = "DiPlay-Vehicle"
    private const val REQUEST_ACTION = "leapmotor.apptocan.update"
    private const val RESPONSE_ACTION = "leapmotor.cantoapp.update"
    private const val REQUEST_EXTRA = "upgradeAsk"
    private const val REQUEST_VALUE = "upgrade"
    private const val CARCAN_PACKAGE = "com.leapmotor.carcanserver"
    private const val POLL_MILLIS = 1_000L

    // CAN signal codes (decimal), see the class comment.
    private const val EXTRA_GEAR = "1010105"
    private const val EXTRA_SPEED_ICU = "100001"
    private const val EXTRA_SPEED_EFFECTIVE = "1100412"
    private const val EXTRA_SPEED_ABS = "1100409"
    private const val EXTRA_SOC_BATTERY = "1060115"
    private const val EXTRA_SOC_POWER = "1030115"
    private const val EXTRA_SOH = "1060306"
    private const val EXTRA_VOLTAGE = "1060122"
    private const val EXTRA_FAST_CHARGE = "1030505"
    private const val EXTRA_SLOW_CHARGE = "1030506"
    private const val EXTRA_EPB = "1110113"

    const val GEAR_R = 1
    const val GEAR_N = 2
    const val GEAR_D = 3

    /**
     * One decoded frame. Null fields are signals the car did not report this time (the protocol's
     * -1 / -1.0f sentinel); they must never be read as a real zero.
     */
    data class Snapshot(
        val gearRaw: Int? = null,
        /** km/h; the effective speed is preferred, then ICU, then ABS. */
        val speedKmh: Double? = null,
        /** 0..100 as reported by the car. */
        val socPercent: Int? = null,
        val sohPercent: Int? = null,
        val voltageVolts: Double? = null,
        val fastCharging: Boolean? = null,
        val slowCharging: Boolean? = null,
        val epb: Int? = null,
        val elapsedMillis: Long = 0L,
    ) {
        val gear: VehicleGearName?
            get() = when (gearRaw) {
                null -> null
                GEAR_R -> VehicleGearName.REVERSE
                GEAR_N -> VehicleGearName.NEUTRAL
                GEAR_D -> VehicleGearName.DRIVE
                else -> VehicleGearName.PARK
            }

        val charging: Boolean?
            get() = when {
                fastCharging == null && slowCharging == null -> null
                else -> fastCharging == true || slowCharging == true
            }
    }

    /** P/R/N/D as the car reports it; P is "not R, N or D" because the car has no explicit P code. */
    enum class VehicleGearName(val letter: Char) { PARK('P'), REVERSE('R'), NEUTRAL('N'), DRIVE('D') }

    private var receiver: BroadcastReceiver? = null
    private var pollThread: Thread? = null
    private var appContext: Context? = null

    @Volatile private var controller: CarPlayController? = null

    /** Both the logcat line and the app's diagnostic report receive these. */
    @Volatile var onDiagnostic: ((String) -> Unit)? = null

    /** The most recent frame, or null before any arrived. */
    @Volatile private var latest: Snapshot? = null

    @Volatile private var everReceived = false

    /** Consumers that want every accepted frame (battery, speed sampling). */
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(Snapshot) -> Unit>()

    private var pausedByReverse = false

    /** Whether the CAN server answer has ever arrived; drives the settings copy and gating. */
    fun available(): Boolean = everReceived

    fun snapshot(): Snapshot? = latest

    fun addListener(listener: (Snapshot) -> Unit) {
        listeners.add(listener)
        latest?.let(listener)
    }

    fun removeListener(listener: (Snapshot) -> Unit) {
        listeners.remove(listener)
    }

    @Synchronized
    fun attach(context: Context, next: CarPlayController?) {
        // Leapmotor-only channel: only the car's `carcanserver` answers `apptocan.update`. On any
        // other head unit the 1 Hz poll would run forever unanswered, so gate here — `LmCarProbe`
        // lives in this module, whereas the Activity that calls us does not see it (`internal`).
        if (!LmCarProbe.isLeapmotor()) return
        val application = context.applicationContext
        appContext = application
        controller = next
        if (receiver != null) return
        val created = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                handle(intent)
            }
        }
        val filter = IntentFilter(RESPONSE_ACTION)
        // The CAN server broadcasts this action, so the receiver is exported. The flag overload of
        // registerReceiver only exists from Android 13; on this API 25 head unit passing the flag
        // would be a NoSuchMethodError of its own, so the two-argument call stays for older
        // platforms and lint is silenced for the whole block rather than for the branch.
        @SuppressLint("UnspecifiedRegisterReceiverFlag")
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                application.registerReceiver(created, filter, Context.RECEIVER_EXPORTED)
            } else {
                application.registerReceiver(created, filter)
            }
        }
            .onSuccess {
                receiver = created
                report("vehicle signals listening on $RESPONSE_ACTION")
            }
            .onFailure { report("vehicle signals receiver not registered: ${it.message}") }
        if (pollThread == null) {
            pollThread = Thread(::runPoll, "diplay-vehicle-poll").apply {
                isDaemon = true
                start()
            }
        }
    }

    @Synchronized
    fun detach(expected: CarPlayController?) {
        if (expected != null && controller !== expected) return
        controller = null
        receiver?.let { active ->
            runCatching { appContext?.unregisterReceiver(active) }
            receiver = null
            report("vehicle signals detached")
        }
        pollThread?.interrupt()
        pollThread = null
        latest = null
        everReceived = false
        pausedByReverse = false
    }

    private fun runPoll() {
        while (!Thread.currentThread().isInterrupted) {
            val context = appContext
            if (controller != null && context != null) {
                runCatching {
                    context.sendBroadcast(
                        Intent(REQUEST_ACTION)
                            .setPackage(CARCAN_PACKAGE)
                            .putExtra(REQUEST_EXTRA, REQUEST_VALUE),
                    )
                }
            }
            try {
                Thread.sleep(POLL_MILLIS)
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    private fun handle(intent: Intent?) {
        if (intent?.action != RESPONSE_ACTION) return
        val frame = decode(intent) ?: return
        val previous = latest
        latest = frame
        everReceived = true
        if (previous?.gearRaw != frame.gearRaw) {
            report(
                "gear raw=${previous?.gearRaw ?: -1} -> ${frame.gearRaw ?: -1} " +
                    "(${frame.gear?.letter ?: '?'})",
            )
            updateReverse(controller, reverse = frame.gearRaw == GEAR_R)
        }
        for (listener in listeners) {
            runCatching { listener(frame) }
        }
    }

    private fun decode(intent: Intent): Snapshot? {
        @Suppress("DEPRECATION")
        val gear = intOrNull(intent, EXTRA_GEAR)
        val speed = firstPositive(
            floatOrNull(intent, EXTRA_SPEED_ABS)?.toDouble(),
            intOrNull(intent, EXTRA_SPEED_EFFECTIVE)?.toDouble(),
            intOrNull(intent, EXTRA_SPEED_ICU)?.toDouble(),
        )
        val soc = intOrNull(intent, EXTRA_SOC_BATTERY) ?: intOrNull(intent, EXTRA_SOC_POWER)
        val soh = intOrNull(intent, EXTRA_SOH)
        val voltage = floatOrNull(intent, EXTRA_VOLTAGE)?.toDouble()
        val fast = boolOrNull(intent, EXTRA_FAST_CHARGE)
        val slow = boolOrNull(intent, EXTRA_SLOW_CHARGE)
        val epb = intOrNull(intent, EXTRA_EPB)
        // A frame with none of the known signals means this head unit does not speak the protocol.
        if (gear == null && speed == null && soc == null && soh == null && voltage == null &&
            fast == null && slow == null && epb == null
        ) {
            return null
        }
        return Snapshot(
            gearRaw = gear,
            speedKmh = speed,
            socPercent = soc,
            sohPercent = soh,
            voltageVolts = voltage,
            fastCharging = fast,
            slowCharging = slow,
            epb = epb,
            elapsedMillis = SystemClock.elapsedRealtime(),
        )
    }

    /** The protocol's -1 means "absent"; a missing key reads as -1 and is reported as null. */
    @Suppress("DEPRECATION")
    private fun intOrNull(intent: Intent, key: String): Int? =
        runCatching { intent.getIntExtra(key, -1) }.getOrDefault(-1).takeIf { it != -1 }

    @Suppress("DEPRECATION")
    private fun floatOrNull(intent: Intent, key: String): Float? =
        runCatching { intent.getFloatExtra(key, -1.0f) }.getOrDefault(-1.0f).takeIf { it != -1.0f }

    /** Charge flags arrive as 0/1; -1 means the car did not report the signal. */
    private fun boolOrNull(intent: Intent, key: String): Boolean? =
        intOrNull(intent, key)?.let { it != 0 }

    /** Prefer the first reading that is present and positive; 0 km/h is treated as "no sample". */
    private fun firstPositive(vararg values: Double?): Double? =
        values.firstOrNull { it != null && it > 0.0 }

    /** 倒车 → 暂停 CarPlay；退出倒车 → 恢复（仅当是本次倒车暂停的）。 */
    private fun updateReverse(active: CarPlayController?, reverse: Boolean) {
        if (reverse) {
            if (pausedByReverse) return
            val sent = active?.sendMediaButton(CarPlayMediaButton.PAUSE) ?: false
            // Only remember our own successful pause, so we never "resume" a pause the user chose.
            pausedByReverse = sent
            report("reverse: pause CarPlay sent=$sent")
        } else {
            if (!pausedByReverse) return
            pausedByReverse = false
            val sent = active?.sendMediaButton(CarPlayMediaButton.PLAY) ?: false
            report("reverse ended: resume CarPlay sent=$sent")
        }
    }

    private fun report(message: String) {
        Log.i(TAG, message)
        runCatching { onDiagnostic?.invoke(message) }
    }
}

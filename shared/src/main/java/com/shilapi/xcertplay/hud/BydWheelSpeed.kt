package com.shilapi.xcertplay.hud

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.shilapi.xcertplay.transport.VehicleGear
import com.shilapi.xcertplay.transport.VehicleSpeedReading
import com.shilapi.xcertplay.transport.VehicleSpeedSample
import com.shilapi.xcertplay.transport.VehicleSpeedSource
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Speed and gear through the adb shell (autoservice binder), where apps would need a BYD signature.
 * The ids are BYD SDK 1.0.5's speed (km/h) and gearbox devices; on DiLink 5.0 they read 0.0 and 1
 * while parked.
 */
internal object BydWheelSpeed {
    const val SPEED = "service call autoservice 7 i32 1013 i32 -1807745016" // float km/h
    const val GEAR = "service call autoservice 5 i32 1011 i32 555745336" // 1 P, 2 R, 3 N, 4 D

    fun speedCommand(context: Context): String = if (BydOutputSettings.legacyVehicleProbe(context)) {
        BydVehicleFieldStore.load(context)?.result(BydVehicleField.SPEED)?.address?.command() ?: SPEED
    } else SPEED

    fun gearCommand(context: Context): String = if (BydOutputSettings.legacyVehicleProbe(context)) {
        BydVehicleFieldStore.load(context)?.result(BydVehicleField.GEAR)?.address?.command() ?: GEAR
    } else GEAR

    fun metersPerSecond(output: String?): Double? {
        val kmh = BydParcel.value(output)?.let { java.lang.Float.intBitsToFloat(it).toDouble() } ?: return null
        return if (kmh in 0.0..300.0) kmh / 3.6 else null
    }

    fun gear(output: String?): VehicleGear? = when (BydParcel.value(output)) {
        1 -> VehicleGear.PARK
        2 -> VehicleGear.REVERSE
        3 -> VehicleGear.NEUTRAL
        4, 5, 6 -> VehicleGear.DRIVE // M and S are forward selector positions on older SDKs.
        else -> null
    }
}

/**
 * Optional, needs ADB over network: wheel speed for the iPhone's dead reckoning in tunnels. Reads the
 * speed four times a second and the gear once a second, only while the iPhone asks for location.
 */
internal object BydWheelSpeedSource : VehicleSpeedSource {
    private const val TAG = "DiPlay-BYD-Speed"
    private const val READ_MILLIS = 250L
    private const val GEAR_EVERY_READS = 4
    private const val MAX_SAMPLES = 40

    private val shell = BydAdbShell(TAG)
    private var context: Context? = null
    private var executor: ScheduledExecutorService? = null
    private val samples = ArrayList<VehicleSpeedSample>()
    private var gear: VehicleGear? = null
    private var reads = 0
    private var firstSampleLogged = false

    fun attach(appContext: Context) = apply { context = appContext.applicationContext }

    @Synchronized
    override fun start() {
        if (executor != null) return
        samples.clear()
        gear = null
        reads = 0
        firstSampleLogged = false
        executor = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "diplay-wheel-speed").apply { isDaemon = true }
        }.also { it.scheduleWithFixedDelay(::poll, 0, READ_MILLIS, TimeUnit.MILLISECONDS) }
        Log.i(TAG, "wheel speed started")
    }

    override fun stop() {
        val running = synchronized(this) { executor.also { executor = null } } ?: return
        // Close adb on the reader thread, after any read in flight, then let the thread end.
        running.execute { shell.close() }
        running.shutdown()
        Log.i(TAG, "wheel speed stopped")
    }

    @Synchronized
    override fun drain(): VehicleSpeedReading? {
        val current = gear ?: return null
        if (samples.isEmpty()) return null
        return VehicleSpeedReading(current, samples.toList()).also { samples.clear() }
    }

    private fun poll() {
        val app = context ?: return
        if (reads++ % GEAR_EVERY_READS == 0) {
            BydWheelSpeed.gear(shell.run(app, BydWheelSpeed.gearCommand(app)))?.let { next ->
                val changed = synchronized(this) { (gear != next).also { gear = next } }
                if (changed) Log.i(TAG, "gear $next")
            }
        }
        val before = SystemClock.elapsedRealtime()
        val speed = BydWheelSpeed.metersPerSecond(shell.run(app, BydWheelSpeed.speedCommand(app))) ?: return
        val sample = VehicleSpeedSample((before + SystemClock.elapsedRealtime()) / 2, speed)
        synchronized(this) {
            if (samples.size == MAX_SAMPLES) samples.removeAt(0)
            samples += sample
        }
        if (!firstSampleLogged) {
            firstSampleLogged = true
            Log.i(TAG, "first wheel speed ${"%.1f".format(speed * 3.6)} km/h")
        }
    }
}

package com.shilapi.xcertplay.hud

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.shilapi.xcertplay.LeapmotorVehicleSignals
import com.shilapi.xcertplay.transport.VehicleGear
import com.shilapi.xcertplay.transport.VehicleSpeedReading
import com.shilapi.xcertplay.transport.VehicleSpeedSample
import com.shilapi.xcertplay.transport.VehicleSpeedSource

/**
 * The Leapmotor car's speed and gear for the iPhone's dead reckoning in tunnels, decoded from the
 * CAN broadcast (`LeapmotorVehicleSignals`) instead of ADB: a Leapmotor head unit has no adbd.
 *
 * The broadcast already carries the speed (`1100409` / `1100412` / `100001`, km/h) and the gear
 * (`1010105`), so [poll] only copies the newest frame — the 1 Hz bus cadence bounds the sample
 * rate. Dead reckoning tolerates that: the iPhone extrapolates between samples.
 *
 * The car reports no P gear; the gear letter is R/N/D and "not R, N or D" stands in for P, matching
 * the gear monitor's reading.
 */
internal object LeapmotorSpeedSource : VehicleSpeedSource {
    private const val TAG = "DiPlay-LM-Speed"
    private const val MAX_SAMPLES = 40

    private var context: Context? = null
    private var started = false
    private val samples = ArrayList<VehicleSpeedSample>()
    private var gear: VehicleGear? = null
    private var firstSampleLogged = false

    private val listener: (LeapmotorVehicleSignals.Snapshot) -> Unit = { frame ->
        onFrame(frame)
    }

    fun attach(appContext: Context) = apply { context = appContext.applicationContext }

    @Synchronized
    override fun start() {
        if (started) return
        started = true
        samples.clear()
        gear = null
        firstSampleLogged = false
        LeapmotorVehicleSignals.addListener(listener)
        Log.i(TAG, "speed source started")
    }

    @Synchronized
    override fun stop() {
        if (!started) return
        started = false
        LeapmotorVehicleSignals.removeListener(listener)
        samples.clear()
        gear = null
        Log.i(TAG, "speed source stopped")
    }

    @Synchronized
    override fun drain(): VehicleSpeedReading? {
        val current = gear ?: return null
        if (samples.isEmpty()) return null
        return VehicleSpeedReading(current, samples.toList()).also { samples.clear() }
    }

    private fun onFrame(frame: LeapmotorVehicleSignals.Snapshot) {
        synchronized(this) {
            frame.gear?.let { gear = it.toVehicleGear() }
            val speedKmh = frame.speedKmh ?: return
            val metersPerSecond = speedKmh / 3.6
            if (samples.size == MAX_SAMPLES) samples.removeAt(0)
            samples += VehicleSpeedSample(
                elapsedMillis = SystemClock.elapsedRealtime(),
                metersPerSecond = metersPerSecond,
            )
            if (!firstSampleLogged) {
                firstSampleLogged = true
                Log.i(TAG, "first CAN speed ${"%.1f".format(speedKmh)} km/h")
            }
        }
    }

    private fun LeapmotorVehicleSignals.VehicleGearName.toVehicleGear(): VehicleGear = when (this) {
        LeapmotorVehicleSignals.VehicleGearName.PARK -> VehicleGear.PARK
        LeapmotorVehicleSignals.VehicleGearName.REVERSE -> VehicleGear.REVERSE
        LeapmotorVehicleSignals.VehicleGearName.NEUTRAL -> VehicleGear.NEUTRAL
        LeapmotorVehicleSignals.VehicleGearName.DRIVE -> VehicleGear.DRIVE
    }
}

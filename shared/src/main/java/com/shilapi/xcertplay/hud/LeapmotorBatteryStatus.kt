package com.shilapi.xcertplay.hud

import android.content.Context
import com.shilapi.xcertplay.LeapmotorVehicleSignals
import com.shilapi.xcertplay.transport.VehicleStatusProvider
import com.shilapi.xcertplay.transport.VehicleStatusSnapshot
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * The Leapmotor car's charge for the iPhone's vehicle status, decoded from the CAN broadcast
 * (`LeapmotorVehicleSignals`) instead of ADB: a Leapmotor head unit has no adbd.
 *
 * What the car reports: battery SOC (`1060115`, falling back to power SOC `1030115`), battery
 * voltage (`1060122`) and the fast/slow charge flags (`1030505` / `1030506`). It does **not**
 * report remaining range or energy on this broadcast, so the range the iPhone sees is estimated
 * from SOC times [fullRangeKm]. The estimate is honest enough for Apple Maps' low-charge warning
 * and charger suggestions, which is all this plane drives.
 *
 * The car must also have the enable switch on; [LeapmotorVehicleSignals.addListener] delivers the
 * cached frame at once, so a snapshot is ready as soon as a frame has ever arrived.
 */
internal object LeapmotorBatteryStatus : VehicleStatusProvider {
    private const val TAG = "DiPlay-LM-Battery"

    /**
     * The full-charge range used to turn SOC into a range estimate. S01 has 380 km and 460 km
     * variants (see LmCarProbe's model table); 400 km is a middle default. A wrong-but-plausible
     * figure only shifts the iPhone's range display, never a control decision.
     */
    private const val DEFAULT_FULL_RANGE_KM = 400

    @Volatile private var context: Context? = null
    @Volatile private var started = false
    @Volatile private var latest: LeapmotorVehicleSignals.Snapshot? = null

    private val listener: (LeapmotorVehicleSignals.Snapshot) -> Unit = { frame ->
        latest = frame
    }

    @Synchronized
    fun start(appContext: Context) {
        context = appContext.applicationContext
        if (started) return
        started = true
        LeapmotorVehicleSignals.addListener(listener)
    }

    @Synchronized
    fun stop() {
        if (!started) return
        started = false
        LeapmotorVehicleSignals.removeListener(listener)
        latest = null
        context = null
    }

    override fun snapshot(): VehicleStatusSnapshot? {
        val frame = latest ?: return null
        val percent = frame.socPercent?.takeIf { it in 0..100 } ?: return null
        val lowPercent = context?.let { BydOutputSettings.lowChargePercent(it) }
            ?: BydOutputSettings.DEFAULT_LOW_CHARGE_PERCENT
        return snapshotFrom(frame, percent, lowPercent, DEFAULT_FULL_RANGE_KM)
    }

    /**
     * Pure mapping, so it can be unit-tested without Android. [percent] is clamped by the caller's
     * `takeIf`; the range is SOC times [fullRangeKm]; the charging flag is the car's own.
     */
    internal fun snapshotFrom(
        frame: LeapmotorVehicleSignals.Snapshot,
        percent: Int,
        lowPercent: Int,
        fullRangeKm: Int,
    ): VehicleStatusSnapshot {
        val fraction = percent / 100.0
        val rangeKm = (fullRangeKm * fraction).roundToInt()
        val fullWh = (fullRangeKm * 0.15 * 1000).roundToLong() // ~150 Wh/km, only for the ratio
        val currentWh = (fullWh * fraction).roundToLong()
        return VehicleStatusSnapshot(
            rangeKm = rangeKm,
            rangeWarning = percent <= lowPercent,
            batteryPercent = percent.toDouble(),
            currentChargeWh = currentWh,
            maxChargeWh = fullWh,
            maxRangeKm = fullRangeKm,
            charging = frame.charging == true,
        )
    }
}

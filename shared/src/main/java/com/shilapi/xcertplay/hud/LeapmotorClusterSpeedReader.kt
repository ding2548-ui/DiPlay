package com.shilapi.xcertplay.hud

import android.content.Context
import android.os.SystemClock
import com.shilapi.xcertplay.LeapmotorVehicleSignals
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

/**
 * The cluster speedometer's speed on a Leapmotor head unit, read from the CAN broadcast
 * ([LeapmotorVehicleSignals]) instead of the BYD ADB shell: a Leapmotor unit has no adbd, so
 * [BydClusterSpeedReader] can never return a reading there.
 *
 * Same [observe] contract as the BYD reader, so the view can pick either one by car. The car's bus
 * runs at about 1 Hz, so this reader does not poll the car: it caches the newest frame's speed and
 * reports it on a timer, letting the reading go stale (null) when no frame has arrived for a while,
 * exactly as the ADB reader does when a shell read fails.
 *
 * The CAN server is started by the CarPlay session itself ([LeapmotorVehicleSignals.attach]), so a
 * listener added here receives frames for as long as the cluster overlay is up.
 */
object LeapmotorClusterSpeedReader {
    /** How long a reading stays usable after the frame that carried it. */
    private const val FRESH_MILLIS = 2_000L

    /** The car's bus cadence; the overlay is repainted faster than this so it can expire on time. */
    private const val POLL_MILLIS = 250L

    fun observe(context: Context, changed: (Int?) -> Unit): () -> Unit {
        val newest = AtomicReference<Pair<Int, Long>?>(null)
        val listener: (LeapmotorVehicleSignals.Snapshot) -> Unit = { frame ->
            val kmh = frame.speedKmh?.takeIf { it.isFinite() && it >= 0 && it <= 300 }?.roundToInt()
            newest.set(kmh?.let { it to SystemClock.elapsedRealtime() })
        }
        LeapmotorVehicleSignals.addListener(listener)

        val executor = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "diplay-lm-cluster-speed").apply { isDaemon = true }
        }
        val poll = executor.scheduleWithFixedDelay({
            val sample = newest.get()
            val fresh = sample?.takeIf { SystemClock.elapsedRealtime() - it.second <= FRESH_MILLIS }?.first
            changed(fresh)
        }, 0, POLL_MILLIS, TimeUnit.MILLISECONDS)

        return {
            poll.cancel(false)
            LeapmotorVehicleSignals.removeListener(listener)
            executor.shutdown()
        }
    }
}

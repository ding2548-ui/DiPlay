package com.shilapi.xcertplay.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.util.Log
import com.shilapi.xcertplay.transport.CarPlayLocationFix
import com.shilapi.xcertplay.transport.Iap2LocationProvider
import com.shilapi.xcertplay.transport.NmeaLocationEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.Locale

/**
 * Foreground Android location source for iAP2 LocationInformation.
 *
 * ## One source, not "whichever is newest"
 *
 * The head unit's own **GPS** is the source the iPhone is meant to follow, so it wins whenever it has
 * a usable fix. Fused location is the second choice, and cell/Wi-Fi location is only a last resort
 * for a unit with no GPS antenna at all.
 *
 * The previous rule was "freshest fix wins across GPS, fused and network". On a car that is exactly
 * wrong: `NETWORK_PROVIDER` emits a coarse fix (hundreds to thousands of metres) at least as often as
 * the GPS emits an accurate one, so it kept winning, and the position the iPhone received jumped
 * between the real road position and a cell-tower estimate kilometres away. That reads as DiPlay
 * fighting the iPhone's own GPS, when in fact it was DiPlay fighting itself.
 *
 * Each tier also has an accuracy ceiling, so a GPS fix that only sees two satellites is not forwarded
 * as if it were good.
 *
 * ## What this cannot do
 *
 * iOS still owns the final decision. When the car reports nothing at all (no fix, or a fix older than
 * [preferredLocationAgeMillis]) the phone falls back to its own GPS — that is system behaviour and no
 * app can switch it off. What this class guarantees is that whenever the car *does* report, it reports
 * one consistent source instead of two disagreeing ones.
 */
class AndroidCarPlayLocationProvider(
    context: Context,
    private val preferredLocationAgeMillis: Long = DEFAULT_PREFERRED_LOCATION_AGE_MILLIS,
) : Iap2LocationProvider {
    private val locationManager =
        context.applicationContext.getSystemService(LocationManager::class.java)
    private val stateLock = Any()

    /** One location source, its preference order and the worst accuracy still worth forwarding. */
    private data class Tier(val provider: String, val rank: Int, val maxAccuracyMeters: Float)

    private val tiers: List<Tier> = buildList {
        if (LocationManager.GPS_PROVIDER in locationManager.allProviders) {
            add(Tier(LocationManager.GPS_PROVIDER, 0, GPS_MAX_ACCURACY_METERS))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            LocationManager.FUSED_PROVIDER in locationManager.allProviders) {
            add(Tier(LocationManager.FUSED_PROVIDER, 1, FUSED_MAX_ACCURACY_METERS))
        }
        if (LocationManager.NETWORK_PROVIDER in locationManager.allProviders) {
            add(Tier(LocationManager.NETWORK_PROVIDER, 2, NETWORK_MAX_ACCURACY_METERS))
        }
    }.sortedBy { it.rank }
    private val preferredProviders = tiers.map { it.provider }

    private val latestFixes = ConcurrentHashMap<String, Location>()
    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val provider = location.provider ?: return
            if (provider in preferredProviders) {
                latestFixes[provider] = location
            }
        }

        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit
        override fun onStatusChanged(provider: String, status: Int, extras: Bundle?) = Unit
    }

    private var started = false

    /** Newest fix already handed to the iPhone, so one fix is never re-encoded tick after tick. */
    private var lastSentFixMillis = 0L
    private var lastReportedProvider: String? = null
    private var lastReportMillis = 0L

    @SuppressLint("MissingPermission")
    override fun start(): Boolean = synchronized(stateLock) {
        if (started) {
            true
        } else {
            seedLastKnownLocations()
            var subscribedProviders = 0
            for (provider in preferredProviders) {
                try {
                    locationManager.requestLocationUpdates(
                        provider,
                        UPDATE_INTERVAL_MILLIS,
                        MIN_DISTANCE_METERS,
                        listener,
                        Looper.getMainLooper(),
                    )
                    subscribedProviders++
                } catch (error: Exception) {
                    Log.w(TAG, "Could not subscribe to Android location provider $provider", error)
                }
            }
            started = subscribedProviders > 0
            if (!started) {
                Log.w(TAG, "No Android location provider could be started")
            }
            report("location sources ${preferredProviders.joinToString()}")
            started
        }
    }

    @SuppressLint("MissingPermission")
    private fun seedLastKnownLocations() {
        for (provider in preferredProviders) {
            val location = try {
                locationManager.getLastKnownLocation(provider)
            } catch (error: Exception) {
                Log.w(TAG, "Could not read last known location from $provider", error)
                null
            } ?: continue
            latestFixes[provider] = location
            Log.i(
                TAG,
                "Seeded $provider location ageMs=${(System.currentTimeMillis() - location.time).coerceAtLeast(0)}",
            )
        }
    }

    override fun stop() {
        val shouldUnregister = synchronized(stateLock) {
            val wasStarted = started
            started = false
            wasStarted
        }
        if (!shouldUnregister) return
        try {
            locationManager.removeUpdates(listener)
        } catch (_: Exception) {
            // Already unregistered.
        }
    }

    override fun latestNmea(): String? {
        val fix = latestFix() ?: return null
        // The reporter ticks once a second while the car may deliver at 0.2–1 Hz. Re-encoding the
        // same fix would tell the iPhone the same timestamp over and over, which reads as a GPS that
        // has stopped updating, so nothing is sent until a newer fix exists.
        if (fix.time <= lastSentFixMillis) return null
        lastSentFixMillis = fix.time
        reportFix(fix)
        return try {
            NmeaLocationEncoder.encode(
                CarPlayLocationFix(
                    latitudeDegrees = fix.latitude,
                    longitudeDegrees = fix.longitude,
                    altitudeMeters = fix.altitude.takeIf { fix.hasAltitude() }?.toDouble(),
                    bearingDegrees = fix.bearing.takeIf { fix.hasBearing() }?.toDouble(),
                    speedMetersPerSecond = fix.speed.takeIf { fix.hasSpeed() }?.toDouble(),
                    accuracyMeters = fix.accuracy.takeIf { fix.hasAccuracy() }?.toDouble(),
                    timestampMillis = fix.time,
                ),
            )
        } catch (error: Throwable) {
            Log.w(TAG, "Could not encode Android location as NMEA", error)
            null
        }
    }

    /**
     * The fix to forward: the best tier that has a fresh, accurate-enough fix, in preference order.
     * Only when no tier qualifies does the most accurate raw fix stand in, so a unit without a GPS
     * antenna still reports a position instead of going silent.
     */
    private fun latestFix(): Location? {
        val now = System.currentTimeMillis()
        fun fresh(tier: Tier): Location? = latestFixes[tier.provider]
            ?.takeIf { now - it.time <= preferredLocationAgeMillis }
            ?.takeIf { !it.hasAccuracy() || it.accuracy <= tier.maxAccuracyMeters }
        for (tier in tiers) fresh(tier)?.let { return it }
        return tiers.asSequence()
            .mapNotNull { latestFixes[it.provider] }
            .filter { now - it.time <= preferredLocationAgeMillis }
            .minByOrNull { if (it.hasAccuracy()) it.accuracy else Float.MAX_VALUE }
    }

    private fun reportFix(fix: Location) {
        val now = System.currentTimeMillis()
        val provider = fix.provider ?: "?"
        if (provider == lastReportedProvider && now - lastReportMillis < REPORT_INTERVAL_MILLIS) return
        lastReportedProvider = provider
        lastReportMillis = now
        val accuracy = if (fix.hasAccuracy()) format("%.0f", fix.accuracy) else "?"
        report(
            "location fix provider=$provider accuracy=${accuracy}m " +
                "ageMs=${(now - fix.time).coerceAtLeast(0)}",
        )
    }

    private fun report(message: String) {
        Log.i(TAG, message)
        runCatching { onDiagnostic?.invoke(message) }
    }

    private fun format(pattern: String, vararg arguments: Any): String =
        String.format(Locale.US, pattern, *arguments)

    companion object {
        private const val TAG = "CarPlayLocation"

        /**
         * Bound by the CarPlay host, so the lines land in the exported diagnostic report (which is
         * assembled from the host's log, not from a logcat dump).
         */
        @Volatile var onDiagnostic: ((String) -> Unit)? = null

        private const val UPDATE_INTERVAL_MILLIS = 1_000L
        private const val MIN_DISTANCE_METERS = 0f
        private const val DEFAULT_PREFERRED_LOCATION_AGE_MILLIS = 30_000L

        /** A car GPS fix worse than this is not worth forwarding; 120 m is well off any road. */
        private const val GPS_MAX_ACCURACY_METERS = 120f
        private const val FUSED_MAX_ACCURACY_METERS = 120f

        /**
         * Cell/Wi-Fi fixes are only a fallback for a unit with no GPS antenna, and only while they
         * are at least city-block accurate. Anything coarser is a tower guess, not a position.
         */
        private const val NETWORK_MAX_ACCURACY_METERS = 3_000f
        private const val REPORT_INTERVAL_MILLIS = 30_000L
    }
}

package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.iap2.message.Iap2ControlMessages
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** One Android location sample converted into the fields carried by the NMEA pair. */
data class CarPlayLocationFix(
    val latitudeDegrees: Double,
    val longitudeDegrees: Double,
    val altitudeMeters: Double? = null,
    val bearingDegrees: Double? = null,
    val speedMetersPerSecond: Double? = null,
    val accuracyMeters: Double? = null,
    val timestampMillis: Long? = null,
) {
    init {
        require(latitudeDegrees.isFinite() && latitudeDegrees in -90.0..90.0) {
            "latitudeDegrees must be between -90 and 90"
        }
        require(longitudeDegrees.isFinite() && longitudeDegrees in -180.0..180.0) {
            "longitudeDegrees must be between -180 and 180"
        }
    }
}

/** Supplies location data only while the phone has subscribed to iAP2 LocationInformation. */
interface Iap2LocationProvider : AutoCloseable {
    /** The 0xFFFA parameter ids, i.e. the sentence types the iPhone asked for; called before [start]. */
    fun onRequested(components: Set<Int>) = Unit

    /** Starts location updates and returns whether at least one source was subscribed. */
    fun start(): Boolean

    fun stop()

    fun latestNmea(): String?

    override fun close() {
        stop()
    }
}

/** LIVI-compatible $GPGGA + $GPRMC encoding used by iAP2 0xFFFB. */
object NmeaLocationEncoder {
    fun encode(fix: CarPlayLocationFix): String {
        val timestamp = fix.timestampMillis
            ?.takeIf { it > 0 }
            ?.let(::ofEpochMilli)
            ?: ofEpochMilli(System.currentTimeMillis())
        val time = format("%02d%02d%02d.00", timestamp.hour, timestamp.minute, timestamp.second)
        val date = format(
            "%02d%02d%02d",
            timestamp.day,
            timestamp.month,
            timestamp.year % 100,
        )

        val latitude = degreesToNmea(fix.latitudeDegrees, latitude = true)
        val longitude = degreesToNmea(fix.longitudeDegrees, latitude = false)
        val hdop = fix.accuracyMeters
            ?.takeIf { it.isFinite() && it > 0 }
            ?.let { min(50.0, max(0.5, it / 5.0)) }
            ?: 1.0
        val altitude = fix.altitudeMeters
            ?.takeIf { it.isFinite() }
            ?.let { format("%.1f", it) }
            ?: "0.0"

        val ggaBody = "GPGGA,$time,${latitude.value},${latitude.hemisphere}," +
            "${longitude.value},${longitude.hemisphere},1,08,${format("%.1f", hdop)}," +
            "$altitude,M,0.0,M,,"
        val speedKnots = fix.speedMetersPerSecond
            ?.takeIf { it.isFinite() && it >= 0 }
            ?.let { format("%.2f", it * KNOTS_PER_METER_PER_SECOND) }
            ?: "0.00"
        val course = fix.bearingDegrees
            ?.takeIf { it.isFinite() }
            ?.let { format("%.2f", it) }
            ?: "0.00"
        val rmcBody = "GPRMC,$time,A,${latitude.value},${latitude.hemisphere}," +
            "${longitude.value},${longitude.hemisphere},$speedKnots,$course,$date,,"

        return "$${ggaBody}*${checksum(ggaBody)}\r\n$${rmcBody}*${checksum(rmcBody)}\r\n"
    }

    private fun degreesToNmea(value: Double, latitude: Boolean): NmeaCoordinate {
        val absolute = abs(value)
        var degrees = absolute.toInt()
        var minutes = (absolute - degrees) * MINUTES_PER_DEGREE
        if (minutes >= MINUTES_PER_DEGREE - MINUTE_ROUNDING_TOLERANCE) {
            degrees += 1
            minutes = 0.0
        }
        val degreeText = if (latitude) format("%02d", degrees) else format("%03d", degrees)
        val hemisphere = if (latitude) {
            if (value >= 0) "N" else "S"
        } else {
            if (value >= 0) "E" else "W"
        }
        return NmeaCoordinate("$degreeText${format("%07.4f", minutes)}", hemisphere)
    }

    private fun checksum(body: String): String {
        var value = 0
        for (character in body) value = value xor character.code
        return format("%02X", value)
    }

    private fun ofEpochMilli(millis: Long): Timestamp = Timestamp(millis)

    private fun format(format: String, vararg arguments: Any): String =
        String.format(Locale.US, format, *arguments)

    private data class NmeaCoordinate(val value: String, val hemisphere: String)

    private class Timestamp(millis: Long) {
        private val fields = java.time.Instant.ofEpochMilli(millis)
            .atZone(java.time.ZoneOffset.UTC)

        val hour: Int = fields.hour
        val minute: Int = fields.minute
        val second: Int = fields.second
        val day: Int = fields.dayOfMonth
        val month: Int = fields.monthValue
        val year: Int = fields.year
    }

    private const val KNOTS_PER_METER_PER_SECOND = 1.94384449
    private const val MINUTES_PER_DEGREE = 60.0
    private const val MINUTE_ROUNDING_TOLERANCE = 0.00005
}

/**
 * The iPhone's StartLocationInformation in one wireless session. The iPhone asks on the Bluetooth
 * iAP2 link and closes that link about 2 s later. In testing it did not ask again on the Wi-Fi
 * link, even while driving, so the Wi-Fi link carries the request on.
 */
class Iap2LocationRequest {
    /** The 0xFFFA parameter ids while a request is running, otherwise null. */
    @Volatile var components: Set<Int>? = null
}

/**
 * Accessory side of iAP2 LocationInformation on one link: starts on 0xFFFA, sends the latest fix
 * on every [tick] (about once a second), stops on 0xFFFC. With [continueRequest], a request that
 * [request] recorded on the Bluetooth link starts this link too.
 */
class Iap2LocationReporter(
    private val provider: Iap2LocationProvider?,
    private val onProgress: (String) -> Unit,
    private val request: Iap2LocationRequest? = null,
    private val continueRequest: Boolean = false,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private var active = false
    private var sentLogged = false
    private var continued = false
    private var lastAttemptNanos = 0L

    /** Handles 0xFFFA/0xFFFC; returns false for any other message. */
    fun handle(frame: Iap2Frame, send: (Iap2Frame) -> Unit): Boolean = when (frame.messageId) {
        Iap2LocationMessages.START_LOCATION_INFORMATION -> {
            val components = Iap2LocationMessages.requestedComponents(frame)
            onProgress("iap2 rx=0xfffa start-location-information components=$components")
            request?.components = components
            provider?.onRequested(components)
            start(send)
            true
        }
        Iap2LocationMessages.STOP_LOCATION_INFORMATION -> {
            onProgress("iap2 rx=0xfffc stop-location-information")
            request?.components = null
            active = false
            sentLogged = false
            provider?.stop()
            true
        }
        else -> false
    }

    /**
     * Sends the latest fix once a second while active; on the Wi-Fi link first takes over a Bluetooth
     * request once. The loop calls this after every incoming message too, so it must not send each time:
     * that sent a dozen fixes in 0.1 s at session start.
     */
    fun tick(send: (Iap2Frame) -> Unit) {
        // The Bluetooth reporter receives STOP_LOCATION_INFORMATION and clears the shared request.
        // Wi-Fi may already have taken over by then, so its active loop must observe that stop too.
        if (continueRequest && continued && request?.components == null) {
            active = false
            sentLogged = false
            return
        }
        if (continueRequest && !active && !continued) {
            val components = request?.components
            if (components != null) {
                continued = true
                onProgress("iap2 location request continues from the Bluetooth link components=$components")
                provider?.onRequested(components)
                start(send)
                return
            }
        }
        if (active && sinceAttemptMillis() >= POLL_INTERVAL_MILLIS) sendLatest(send)
    }

    /** Wakes the loop when the next fix is due, or every second while a Bluetooth request may still arrive. */
    fun pollTimeout(remainingMillis: Long): Long = when {
        active -> min(remainingMillis, (POLL_INTERVAL_MILLIS - sinceAttemptMillis()).coerceAtLeast(1))
        continueRequest && !continued && provider != null -> min(remainingMillis, POLL_INTERVAL_MILLIS)
        else -> remainingMillis
    }

    private fun sinceAttemptMillis() = (nanoTime() - lastAttemptNanos) / 1_000_000

    private fun start(send: (Iap2Frame) -> Unit) {
        active = startProvider()
        sentLogged = false
        if (active) sendLatest(send)
    }

    private fun startProvider(): Boolean {
        if (provider == null) return false
        return try {
            provider.start().also { started -> if (!started) onProgress("iap2 location provider did not start") }
        } catch (error: Exception) {
            onProgress("iap2 location provider start failed: ${error.message}")
            false
        }
    }

    private fun sendLatest(send: (Iap2Frame) -> Unit) {
        lastAttemptNanos = nanoTime()
        val sentence = provider?.latestNmea() ?: return
        send(Iap2LocationMessages.locationInformation(sentence))
        if (!sentLogged) {
            sentLogged = true
            onProgress("iap2 tx=0xfffb location-information")
        }
    }

    private companion object {
        const val POLL_INTERVAL_MILLIS = 1_000L
    }
}

object Iap2LocationMessages {
    const val START_LOCATION_INFORMATION = 0xfffa
    const val LOCATION_INFORMATION = 0xfffb
    const val STOP_LOCATION_INFORMATION = 0xfffc

    /** 0xFFFA selector for `$PASCD`; the matching IdentificationInformation flag is 20. */
    const val VEHICLE_SPEED_DATA = 4

    /** The parameter ids of a 0xFFFA request (the sentence types asked for), or none if unreadable. */
    fun requestedComponents(frame: Iap2Frame): Set<Int> =
        runCatching { frame.body().asList().map { it.id }.toSortedSet() }.getOrDefault(emptySet())

    fun locationInformation(nmeaSentence: String): Iap2Frame {
        require(nmeaSentence.isNotEmpty()) { "NMEA sentence must not be empty" }
        return Iap2ControlMessages.locationInformation(nmeaSentence)
    }
}

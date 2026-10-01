package com.shilapi.xcertplay.airplay

import java.math.BigInteger
import java.util.Base64

/**
 * iOS 27 "video in car": while the car is parked, the iPhone hands the head unit a media URL and
 * drives playback; the head unit plays it in its own player. Observed with an iPhone on iOS 27 and
 * checked against Apple's CarPlay Simulator (Additional Tools for Xcode 27) and its AirPlay web app.
 *
 * - /info carries [info] (videoPlaybackInfo); SETUP then enables the [FEATURE] the iPhone proposes.
 * - The iPhone opens a [SETTINGS_CHANNEL_UUID] data stream (see VideoSettingsChannel) and, to play,
 *   a remote control session without a socket ([REMOTE_CONTROL_UUIDS], controlType 1). Its messages
 *   arrive as POST /command with X-Apple-StreamID and {params: {data: bplist}} and are answered the
 *   same way.
 * - requestUI [UI_URL] asks the car to show its player.
 *
 * Media whose key needs FairPlay (Apple TV+) does not play: Apple's receiver passes such key URLs to
 * the iPhone (unhandledURL) and answers its streamingKey with a FairPlay key request, which needs a
 * licensed FairPlay receiver. Plain http(s) media such as Safari's plays.
 */
object VideoInCar {
    const val FEATURE = "videoPlayback"
    const val UI_URL = "videoplayback:"
    const val SETTINGS_CHANNEL_UUID = "BB493F61-A6B8-4769-8D74-80C23A9F71C4"
    val REMOTE_CONTROL_UUIDS = setOf(
        "A6B27562-B43A-4F2D-B75F-82391E250194", // video setup
        "E3DC3EA6-E6C3-4B30-847C-B7ACFEBEA654", // overlay UI
    )

    /** CoreMedia errors Apple's receiver reports when an item cannot play. */
    const val ERROR_NETWORK = -17221
    const val ERROR_DECODER = -12911
    const val ERROR_INCOMPATIBLE_ASSET = -12927

    /** Whether video may play now; the host sets it from the car's gear (Leapmotor: N). */
    @Volatile var allowed = false

    /** Bits the AirPlay web app's manifest adds to the legacy feature bits (featureList.additionalAirPlayFeatures). */
    private val ADDITIONAL_FEATURE_BITS = listOf(0, 64)

    /** The legacy AirPlay feature bits plus [ADDITIONAL_FEATURE_BITS], as base64 of the little-endian bit set. */
    fun featuresEx(legacyFeatures: Long): String {
        var bits = BigInteger.valueOf(legacyFeatures)
        ADDITIONAL_FEATURE_BITS.forEach { bits = bits.setBit(it) }
        val littleEndian = bits.toByteArray().reversedArray().dropLastWhile { it == 0.toByte() }.toByteArray()
        return Base64.getEncoder().encodeToString(littleEndian)
    }

    /**
     * /info videoPlaybackInfo. The capabilities follow the web app's playerCapabilities, with what this
     * player cannot do turned off.
     */
    fun info(legacyFeatures: Long, allowed: Boolean): Map<String, Any?> = linkedMapOf(
        "videoPlaybackAllowed" to allowed,
        "featuresEx" to featuresEx(legacyFeatures),
        "playbackCapabilities" to linkedMapOf(
            "supportsOfflineHLS" to false,
            "supportsAirPlayVideoWithSharePlay" to false,
            "supportsInterstitials" to false,
            "supportsIntegratedTimeline" to false,
            "supportsAVMetrics" to false,
            "supportsUIForAudioOnlyContent" to true,
            "supportsFPSSecureStop" to false,
        ),
    )

    /** A queued media item the car can play. */
    data class Item(val uuid: Any?, val url: String, val startMillis: Int)

    /** The item of an insertPlayQueueItem message, or null when its media cannot play here. */
    fun parseItem(message: Map<String, Any?>): Item? {
        val item = message["item"] as? Map<*, *> ?: return null
        val url = item["Content-Location"] as? String ?: return null
        val scheme = url.substringBefore(':').lowercase()
        if (scheme != "http" && scheme != "https") return null
        val startSeconds = (item["Start-Position-Seconds"] as? Number)?.toDouble()
            ?: (item["Start-Position"] as? Map<*, *>)?.let(::seconds)
        val startMillis = startSeconds?.let { (it * 1000).toLong().coerceIn(0, Int.MAX_VALUE.toLong()).toInt() } ?: 0
        return Item(item["uuid"], url, startMillis)
    }

    /** The target of a seek message in milliseconds. */
    fun seekMillis(message: Map<String, Any?>): Int? =
        (message["time"] as? Map<*, *>)?.let(::seconds)?.let { (it * 1000).toLong().coerceIn(0, Int.MAX_VALUE.toLong()).toInt() }

    /** The car player's state for [playbackInfoResponse]. */
    data class PlayerState(
        val prepared: Boolean,
        val playing: Boolean,
        val positionSeconds: Double,
        val durationSeconds: Double,
        val bufferedSeconds: Double,
    )

    /**
     * The answer to a playbackInfo request, as Apple's web app builds it: the fields sit in "info", times
     * are CMTime dictionaries and ranges are {start, duration}.
     */
    fun playbackInfoResponse(messageId: Any?, itemUuid: Any?, state: PlayerState?): Map<String, Any?> {
        val ready = state?.prepared == true
        val duration = state?.durationSeconds ?: 0.0
        val info = linkedMapOf<String, Any?>(
            "item" to linkedMapOf("uuid" to itemUuid),
            "rate" to if (state?.playing == true) 1.0 else 0.0,
            "readyToPlay" to ready,
            "playbackLikelyToKeepUp" to ready,
        )
        if (state != null && ready) {
            info["position"] = cmTime(state.positionSeconds)
            info["duration"] = cmTime(duration)
            info["seekableTimeRanges"] = listOf(range(0.0, duration))
            info["loadedTimeRanges"] = listOf(range(0.0, state.bufferedSeconds))
        }
        info["droppedVideoFrames"] = 0L
        info["totalVideoFrames"] = 0L
        info["decodedFrameCount"] = 0L
        info["playbackState"] = when {
            state == null || !ready -> "loading"
            state.playing -> "playing"
            else -> "paused"
        }
        info["interstitialInfo"] = linkedMapOf<String, Any?>()
        return linkedMapOf("info" to info, "kind" to "response", "type" to "playbackInfo", "messageID" to messageId).withoutNulls()
    }

    fun seekResponse(messageId: Any?): Map<String, Any?> =
        linkedMapOf("type" to "seek", "kind" to "response", "messageID" to messageId).withoutNulls()

    /**
     * The answer to a property request: {key, value}. What this player does not report has no value,
     * as the web app answers null for properties it does not support (plists have no null).
     */
    fun propertyResponse(messageId: Any?, key: Any?, state: PlayerState?): Map<String, Any?> {
        val ready = state?.prepared == true
        val value: Any? = when (key) {
            "hasEnabledAudio" -> true
            "muted" -> false
            "seekableTimeRanges" -> if (ready) listOf(range(0.0, state!!.durationSeconds)) else null
            "loadedTimeRanges" -> if (ready) listOf(range(0.0, state!!.bufferedSeconds)) else null
            else -> null
        }
        return linkedMapOf("key" to key, "value" to value, "kind" to "response", "type" to "property", "messageID" to messageId)
            .withoutNulls()
    }

    /**
     * Tells the iPhone the item cannot play here, as Apple's receiver does before it ends the item:
     * {type: error, error: {domain, code}, uuid}. The iPhone then stops showing it as playing on CarPlay.
     */
    fun errorNotification(itemUuid: Any?, code: Int): Map<String, Any?> =
        linkedMapOf("type" to "error", "error" to linkedMapOf("domain" to "", "code" to code.toLong()), "uuid" to itemUuid)
            .withoutNulls()

    /** Tells the iPhone at once that the car's player started or paused, e.g. from the steering wheel. */
    fun playbackStateNotification(playing: Boolean, itemUuid: Any?): Map<String, Any?> =
        if (playing) {
            linkedMapOf("type" to "playbackState", "name" to "playing", "item" to linkedMapOf("uuid" to itemUuid)).withoutNulls()
        } else {
            linkedMapOf("type" to "playbackState", "name" to "paused")
        }

    /** CMTime as the web app sends it: milliseconds, flags 1 (valid). */
    fun cmTime(seconds: Double): Map<String, Any?> =
        linkedMapOf("value" to Math.round(seconds * 1000), "timescale" to 1000L, "flags" to 1L, "epoch" to 0L)

    private fun range(start: Double, duration: Double) = linkedMapOf("start" to cmTime(start), "duration" to cmTime(duration))

    /** Plists have no null; a missing key stands for it, as the iPhone reads it. */
    private fun Map<String, Any?>.withoutNulls(): Map<String, Any?> {
        fun strip(value: Any?): Any? = when (value) {
            is Map<*, *> -> value.entries.filter { it.value != null }.associateTo(linkedMapOf()) { it.key.toString() to strip(it.value) }
            is List<*> -> value.filterNotNull().map(::strip)
            else -> value
        }
        @Suppress("UNCHECKED_CAST")
        return strip(this) as Map<String, Any?>
    }

    private fun seconds(time: Map<*, *>): Double? {
        val value = (time["value"] as? Number)?.toDouble() ?: return null
        val timescale = (time["timescale"] as? Number)?.toDouble()?.takeIf { it > 0 } ?: return null
        return value / timescale
    }
}

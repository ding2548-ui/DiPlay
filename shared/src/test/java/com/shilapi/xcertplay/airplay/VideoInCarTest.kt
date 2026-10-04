package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoInCarTest {
    private val config = AirPlayConfig(
        deviceName = "test",
        deviceId = "02:00:00:00:00:02",
        btMac = "02:00:00:00:00:02",
        sourceVersion = "366.0",
        main = AirPlayDisplayConfig(widthPixels = 1280, heightPixels = 720),
    )

    @Test
    fun featuresExAddsTheWebAppBitsToTheLegacyFeatures() {
        // What the iPhone accepted from DiPlay's legacy features 0x615653aee2 plus bits 0 and 64.
        assertEquals("465TVmEAAAAB", VideoInCar.featuresEx(0x615653aee2L))
    }

    @Test
    fun videoPlaybackInfoIsDeclaredOnlyWhenEnabled() {
        assertFalse(AirPlayInfoPlist.build(config).containsKey("videoPlaybackInfo"))

        val info = AirPlayInfoPlist.build(config.copy(videoInCar = true))["videoPlaybackInfo"] as Map<*, *>
        assertEquals(false, info["videoPlaybackAllowed"])
        assertEquals("465TVmEAAAAB", info["featuresEx"])
        assertEquals(false, (info["playbackCapabilities"] as Map<*, *>)["supportsFPSSecureStop"])
    }

    @Test
    fun setupEnablesVideoOnlyWhenConfiguredAndProposed() {
        val proposed = listOf("iAPChannel", "videoPlayback")
        assertFalse("videoPlayback" in setupEnabledFeatures(config, proposed))
        assertFalse("videoPlayback" in setupEnabledFeatures(config.copy(videoInCar = true), listOf("iAPChannel")))
        assertTrue("videoPlayback" in setupEnabledFeatures(config.copy(videoInCar = true), proposed))
    }

    @Test
    fun videoAvailabilityWaitsForFeatureAndEventChannel() {
        val sent = mutableListOf<Boolean>()
        val availability = VideoPlaybackAvailability { sent += it; true }

        assertEquals(VideoPlaybackDelivery.QUEUED, availability.setDesired(true))
        assertEquals(VideoPlaybackDelivery.QUEUED, availability.setFeatureEnabled(true))
        assertTrue(sent.isEmpty())
        assertEquals(VideoPlaybackDelivery.SENT, availability.setEventReady(true))
        assertEquals(listOf(true), sent)
        assertEquals(VideoPlaybackDelivery.UNCHANGED, availability.setDesired(true))
    }

    @Test
    fun videoAvailabilityCoalescesChangesBeforeAirPlayIsReady() {
        val sent = mutableListOf<Boolean>()
        val availability = VideoPlaybackAvailability { sent += it; true }

        availability.setDesired(true)
        availability.setDesired(false)
        availability.setEventReady(true)
        assertTrue(sent.isEmpty())
        assertEquals(VideoPlaybackDelivery.SENT, availability.setFeatureEnabled(true))
        assertEquals(listOf(false), sent)
    }

    @Test
    fun videoAvailabilityIsResentAfterAnEventChannelReconnect() {
        val sent = mutableListOf<Boolean>()
        val availability = VideoPlaybackAvailability { sent += it; true }

        availability.setDesired(true)
        availability.setFeatureEnabled(true)
        availability.setEventReady(true)
        availability.setEventReady(false)
        assertEquals(VideoPlaybackDelivery.SENT, availability.setEventReady(true))
        assertEquals(listOf(true, true), sent)
    }

    @Test
    fun aFailedAvailabilityWriteRemainsPendingForRetry() {
        val attempts = mutableListOf<Boolean>()
        var writable = false
        val availability = VideoPlaybackAvailability { allowed -> attempts += allowed; writable }

        availability.setDesired(true)
        availability.setFeatureEnabled(true)
        assertEquals(VideoPlaybackDelivery.QUEUED, availability.setEventReady(true))
        writable = true
        assertEquals(VideoPlaybackDelivery.SENT, availability.setEventReady(true))
        assertEquals(listOf(true, true), attempts)
    }

    @Test
    fun teardownOfAVideoDataStreamKeepsTheIapTunnel() {
        fun body(vararg streams: Map<String, Any?>) = mapOf("streams" to streams.toList())

        assertEquals(emptyList<Int>(), teardownStreamTypes(body(mapOf("type" to 130L, "streamID" to 3L))))
        assertEquals(listOf(130), teardownStreamTypes(body(mapOf("type" to 130L, "streamID" to 1L))))
        assertEquals(listOf(130, 110), teardownStreamTypes(body(mapOf("type" to 130L), mapOf("type" to 110L))))
        assertNull(teardownStreamTypes(emptyMap<String, Any?>()))
        assertNull(teardownStreamTypes(null))
    }

    @Test
    fun parsesSafariAndAppleTvItems() {
        val safari = VideoInCar.parseItem(
            mapOf(
                "type" to "insertPlayQueueItem",
                "item" to mapOf(
                    "uuid" to "94FF",
                    "Content-Location" to "https://example.com/v.mp4",
                    "Start-Position" to mapOf("value" to 48_962_676_084L, "timescale" to 1_000_000_000L, "flags" to 1L),
                ),
            ),
        )
        assertEquals(VideoInCar.Item("94FF", "https://example.com/v.mp4", 48_962), safari)

        val seconds = VideoInCar.parseItem(
            mapOf("item" to mapOf("Content-Location" to "http://example.com/a.m3u8", "Start-Position-Seconds" to 12.5)),
        )
        assertEquals(12_500, seconds?.startMillis)

        assertNull(VideoInCar.parseItem(mapOf("item" to mapOf("Content-Location" to "file:///sdcard/v.mp4"))))
        assertNull(VideoInCar.parseItem(mapOf("item" to mapOf("uuid" to "x"))))
    }

    @Test
    fun appSchemesOnlyWhenTheIphoneLoadsThem() {
        val app = mapOf("item" to mapOf("uuid" to "A1", "Content-Location" to "mainm3u8://example/index.m3u8"))
        assertNull(VideoInCar.parseItem(app))
        assertEquals(VideoInCar.Item("A1", "mainm3u8://example/index.m3u8", 0), VideoInCar.parseItem(app, iphoneLoadsAppSchemes = true))

        for (url in listOf("file:///sdcard/v.mp4", "content://media/1", "asset:///v.mp4",
            "rawresource:///123", "android.resource://app/raw/video", "skd://key", "not a url",
            "/sdcard/v.mp4", "https", "FILE:///sdcard/v.mp4")) {
            assertNull(url, VideoInCar.parseItem(mapOf("item" to mapOf("Content-Location" to url)), iphoneLoadsAppSchemes = true))
        }
    }

    @Test
    fun seekTargetIsACmTime() {
        assertEquals(90_000, VideoInCar.seekMillis(mapOf("time" to mapOf("value" to 90L, "timescale" to 1L))))
        assertNull(VideoInCar.seekMillis(mapOf("time" to mapOf("value" to 90L, "timescale" to 0L))))
    }

    @Test
    fun playbackInfoAnswersInTheWebAppFormat() {
        val state = VideoInCar.PlayerState(prepared = true, playing = true, positionSeconds = 61.5, durationSeconds = 600.0, bufferedSeconds = 120.0)
        val response = VideoInCar.playbackInfoResponse(7L, "94FF", state)
        val info = response["info"] as Map<*, *>

        assertEquals("response", response["kind"])
        assertEquals("playbackInfo", response["type"])
        assertEquals(7L, response["messageID"])
        assertEquals(mapOf("uuid" to "94FF"), info["item"])
        assertEquals(1.0, info["rate"])
        assertEquals("playing", info["playbackState"])
        assertEquals(mapOf("value" to 61_500L, "timescale" to 1000L, "flags" to 1L, "epoch" to 0L), info["position"])
        val seekable = (info["seekableTimeRanges"] as List<*>).single() as Map<*, *>
        assertEquals(600_000L, (seekable["duration"] as Map<*, *>)["value"])

        val loading = VideoInCar.playbackInfoResponse(8L, "94FF", null)["info"] as Map<*, *>
        assertEquals("loading", loading["playbackState"])
        assertFalse(loading.containsKey("position"))
    }

    @Test
    fun propertiesThePlayerCannotReportAreNull() {
        val state = VideoInCar.PlayerState(prepared = true, playing = false, positionSeconds = 0.0, durationSeconds = 60.0, bufferedSeconds = 30.0)

        val audio = VideoInCar.propertyResponse(3L, "hasEnabledAudio", state)
        assertEquals(mapOf("key" to "hasEnabledAudio", "value" to true, "kind" to "response", "type" to "property", "messageID" to 3L), audio)
        assertEquals(1, (VideoInCar.propertyResponse(4L, "loadedTimeRanges", state)["value"] as List<*>).size)
        val log = VideoInCar.propertyResponse(5L, "playbackAccessLog", state)
        assertEquals(mapOf("key" to "playbackAccessLog", "kind" to "response", "type" to "property", "messageID" to 5L), log)
    }

    @Test
    fun everyReplyEncodesAsABinaryPlist() {
        // A null in a reply crashed DiPlay in the car: plists have no null.
        val loading = VideoInCar.PlayerState(prepared = false, playing = false, positionSeconds = 0.0, durationSeconds = 0.0, bufferedSeconds = 0.0)
        listOf(
            VideoInCar.propertyResponse(null, "playbackAccessLog", null),
            VideoInCar.propertyResponse(1L, "seekableTimeRanges", loading),
            VideoInCar.playbackInfoResponse(null, null, null),
            VideoInCar.playbackInfoResponse(2L, null, loading),
            VideoInCar.seekResponse(null),
            VideoInCar.playbackStateNotification(true, null),
            VideoInCar.errorNotification(null, VideoInCar.ERROR_INCOMPATIBLE_ASSET),
        ).forEach { reply ->
            val decoded = BplistCodec.decode(BplistCodec.encode(reply)) as Map<*, *>
            assertEquals(reply["type"], decoded["type"])
        }
    }

    @Test
    fun anItemThatCannotPlayIsReportedAsAppleReceiversDo() {
        assertEquals(
            mapOf("type" to "error", "error" to mapOf("domain" to "", "code" to -12927L), "uuid" to "C6C3"),
            VideoInCar.errorNotification("C6C3", VideoInCar.ERROR_INCOMPATIBLE_ASSET),
        )
    }

    @Test
    fun wheelPauseIsAPlaybackStateNotification() {
        assertEquals(mapOf("type" to "playbackState", "name" to "paused"), VideoInCar.playbackStateNotification(false, "94FF"))
        assertEquals(
            mapOf("type" to "playbackState", "name" to "playing", "item" to mapOf("uuid" to "94FF")),
            VideoInCar.playbackStateNotification(true, "94FF"),
        )
    }
}

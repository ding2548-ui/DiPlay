package com.shilapi.xcertplay

import com.shilapi.xcertplay.airplay.CarPlayButton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Leapmotor car-control payload vectors come from the car-fangkong-inject skill (captured on a
 * real head unit): the `receiver` extra is a byte[] whose first two bytes are `(b0 * 0x64) + b1`
 * followed by UTF-8 JSON with `data.type = -1`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24], manifest = Config.NONE)
class LeapmotorMediaKeyProtocolTest {

    private fun framed(json: String): ByteArray {
        val body = json.toByteArray(Charsets.UTF_8)
        val length = body.size
        val head = byteArrayOf((length / 0x64).toByte(), (length % 0x64).toByte())
        return head + body
    }

    @Test
    fun nextOneFromRealCarPayload() {
        val payload = framed("""{"data":{"action":"nextOne","type":-1},"type":"music"}""")
        assertEquals("nextOne", LeapmotorMediaProtocol.actionFromPayload(payload))
        assertEquals(CarPlayButton.NEXT, CarPlayButton.forLeapmotorAction("nextOne"))
    }

    @Test
    fun lengthPrefixBeyondOneByteIsDecoded() {
        // 0x64 bytes of padding make the length span both prefix bytes.
        val padding = "x".repeat(120)
        val json = """{"data":{"action":"preOne","type":-1},"type":"music","pad":"$padding"}"""
        val payload = framed(json)
        assertEquals("preOne", LeapmotorMediaProtocol.actionFromPayload(payload))
        assertEquals(CarPlayButton.PREVIOUS, CarPlayButton.forLeapmotorAction("preOne"))
    }

    @Test
    fun playPauseAndExplicitPlayPauseMapToTheToggle() {
        assertEquals(CarPlayButton.PLAY_PAUSE, CarPlayButton.forLeapmotorAction("playpause"))
        assertEquals(CarPlayButton.PLAY_PAUSE, CarPlayButton.forLeapmotorAction("PLAYPAUSE"))
        assertEquals(CarPlayButton.PLAY, CarPlayButton.forLeapmotorAction("play"))
        assertEquals(CarPlayButton.PAUSE, CarPlayButton.forLeapmotorAction("pause"))
    }

    @Test
    fun nonMusicTypeIsRejected() {
        val payload = framed("""{"data":{"action":"nextOne","type":-1},"type":"phone"}""")
        assertNull(LeapmotorMediaProtocol.actionFromPayload(payload))
    }

    @Test
    fun onlyWheelKeysAreForwardedAndCarCommandsAreDropped() {
        // The car emits its own `pause` when another app takes audio focus, and echoes what it
        // received; forwarding those paused CarPlay itself (v2.0-79 report).
        for (wheel in listOf("nextOne", "preOne", "playpause", "PLAYPAUSE")) {
            assertTrue("wheel key $wheel must be forwarded", CarPlayButton.isWheelAction(wheel))
        }
        for (internal in listOf("pause", "play", "stop", "info", "unknownKey", "")) {
            assertFalse("car command '$internal' must be dropped", CarPlayButton.isWheelAction(internal))
        }
    }

    @Test
    fun unknownActionNeverFiresARandomKey() {
        assertNull(CarPlayButton.forLeapmotorAction("someFutureKey"))
        assertNull(CarPlayButton.forLeapmotorAction(null))
        assertNull(CarPlayButton.forLeapmotorAction(""))
    }

    @Test
    fun malformedPayloadsAreIgnored() {
        assertNull(LeapmotorMediaProtocol.actionFromPayload(null))
        assertNull(LeapmotorMediaProtocol.actionFromPayload(byteArrayOf(0, 0)))
        assertNull(LeapmotorMediaProtocol.actionFromPayload(byteArrayOf(0, 40, 0x7b)))
        assertNull(LeapmotorMediaProtocol.actionFromJson(""))
        assertNull(LeapmotorMediaProtocol.actionFromJson("not json"))
        // Declared length beyond the buffer must still parse what is present.
        val payload = framed("""{"data":{"action":"nextOne","type":-1},"type":"music"}""")
        val truncated = payload.copyOf(payload.size - 3)
        assertNull(LeapmotorMediaProtocol.actionFromPayload(truncated))
    }

    @Test
    fun actionWithoutTypeStillMaps() {
        assertEquals(
            "nextOne",
            LeapmotorMediaProtocol.actionFromJson("""{"data":{"action":"nextOne"}}"""),
        )
    }
}

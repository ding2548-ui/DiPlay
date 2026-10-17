package com.shilapi.xcertplay.media

import android.Manifest
import android.media.AudioDeviceInfo
import android.media.AudioManager
import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioFormat
import com.shilapi.xcertplay.airplay.AudioStreamId
import com.shilapi.xcertplay.airplay.MicrophoneConfig
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.AudioDeviceInfoBuilder
import org.robolectric.shadows.ShadowAudioRecord

/**
 * A Podofo SM4250 head unit (#782) routed a CarPlay call to its earpiece, so the call was silent. A call routed
 * there moves to the loudspeaker without the driver turning on **Calls on the loudspeaker**.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class CallEarpieceFallbackTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val telephony = AudioStreamId(100, "telephony")
    private val diagnostics = CopyOnWriteArrayList<String>()
    private lateinit var manager: AudioManager
    private lateinit var sink: AndroidMediaSink
    private val earpiece = AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE).build()
    private val speaker = AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER).build()

    @Before fun setUp() {
        shadowOf(context).grantPermissions(Manifest.permission.RECORD_AUDIO, Manifest.permission.MODIFY_AUDIO_SETTINGS)
        manager = context.getSystemService(AudioManager::class.java)
        ShadowAudioRecord.setSourceProvider { _ ->
            object : ShadowAudioRecord.AudioRecordSource {
                override fun readInByteArray(buffer: ByteArray, offset: Int, size: Int, blocking: Boolean): Int {
                    Thread.sleep(5)
                    return 0
                }
            }
        }
        sink = AndroidMediaSink(context = context, onAudioDiagnostic = diagnostics::add, callSpeakerphone = { false })
    }

    @After fun tearDown() {
        sink.close()
        ShadowAudioRecord.clearSource()
    }

    @Test fun aCallOnTheEarpieceMovesToTheLoudspeakerOnceAndTheEndOfTheCallTakesItBack() {
        startCall()
        val renderer = callRenderer()
        route(renderer, earpiece)
        @Suppress("DEPRECATION")
        assertTrue(manager.isSpeakerphoneOn)
        route(renderer, earpiece)
        assertEquals(listOf("Audio: call routed to the earpiece; loudspeaker=true stream=$telephony"),
            diagnostics.filter { it.startsWith("Audio: call routed") })

        sink.onMicrophoneStopped(telephony)
        @Suppress("DEPRECATION")
        assertFalse(manager.isSpeakerphoneOn)
    }

    @Test fun aCallOnTheLoudspeakerIsLeftAlone() {
        startCall()
        route(callRenderer(), speaker)
        @Suppress("DEPRECATION")
        assertFalse(manager.isSpeakerphoneOn)
        assertTrue(diagnostics.none { it.startsWith("Audio: call routed") })
    }

    @Test fun nothingMovesOutsideACall() {
        // The call track exists, but the call's microphone never started communication mode.
        route(callRenderer(), earpiece)
        @Suppress("DEPRECATION")
        assertFalse(manager.isSpeakerphoneOn)
        assertTrue(diagnostics.none { it.startsWith("Audio: call routed") })
    }

    private fun startCall() = sink.onMicrophoneStarted(telephony, MicrophoneConfig(
        audioType = "telephony",
        sampleRate = 16_000,
        channels = 1,
        payloadType = 100,
        frameMillis = 20,
        host = InetAddress.getLoopbackAddress(),
        port = 9,
        key = ByteArray(32),
    ))

    /** The sink's own renderer for the call's audio, as a call's downlink creates it. */
    private fun callRenderer(): Any = AndroidMediaSink::class.java
        .getDeclaredMethod("audioRenderer", AudioStreamId::class.java, AudioFormat::class.java)
        .apply { isAccessible = true }
        .invoke(sink, telephony, AudioFormat(AudioCodecKind.LPCM, 16_000, 1, 100, "telephony"))!!

    private fun route(renderer: Any, device: AudioDeviceInfo) {
        renderer.javaClass.getDeclaredMethod("checkCallRoute", AudioDeviceInfo::class.java)
            .apply { isAccessible = true }
            .invoke(renderer, device)
    }
}

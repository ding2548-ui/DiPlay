package com.shilapi.xcertplay.media

import android.Manifest
import android.media.AudioDeviceInfo
import android.media.AudioManager
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

/** A phone or tablet used as the head unit played CarPlay calls on its earpiece (#701). */
@RunWith(RobolectricTestRunner::class)
class CallSpeakerphoneTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val telephony = AudioStreamId(100, "telephony")
    private val diagnostics = CopyOnWriteArrayList<String>()
    private lateinit var manager: AudioManager
    private var sink: AndroidMediaSink? = null

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
    }

    @After fun tearDown() {
        sink?.close()
        ShadowAudioRecord.clearSource()
    }

    @Test @Config(sdk = [29], manifest = Config.NONE)
    fun theOptionPutsTheCallOnTheLoudspeakerAndTheEndOfTheCallTakesItBack() {
        start(speakerphone = true)
        @Suppress("DEPRECATION")
        assertTrue(manager.isSpeakerphoneOn)
        assertTrue(diagnostics.joinToString("\n"), diagnostics.any { it.startsWith("Audio: mode entered communication") && it.endsWith("loudspeaker=true") })

        sink!!.onMicrophoneStopped(telephony)
        @Suppress("DEPRECATION")
        assertFalse(manager.isSpeakerphoneOn)
    }

    @Test @Config(sdk = [33], manifest = Config.NONE)
    fun android12AndNewerChooseTheBuiltInSpeakerAsTheCommunicationDevice() {
        val speaker = AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER).build()
        val earpiece = AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE).build()
        shadowOf(manager).setAvailableCommunicationDevices(listOf(earpiece, speaker))

        start(speakerphone = true)
        assertEquals(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, manager.communicationDevice?.type)

        sink!!.onMicrophoneStopped(telephony)
        assertTrue(manager.communicationDevice?.type != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
    }

    @Test @Config(sdk = [29], manifest = Config.NONE)
    fun offByDefaultTheCallRouteIsLeftAlone() {
        start(speakerphone = false)
        @Suppress("DEPRECATION")
        assertFalse(manager.isSpeakerphoneOn)
        assertTrue(diagnostics.none { it.contains("loudspeaker=") })
    }

    private fun start(speakerphone: Boolean) {
        sink = AndroidMediaSink(context = context, onAudioDiagnostic = diagnostics::add, callSpeakerphone = { speakerphone })
        sink!!.onMicrophoneStarted(telephony, MicrophoneConfig(
            audioType = "telephony",
            sampleRate = 16_000,
            channels = 1,
            payloadType = 100,
            frameMillis = 20,
            host = InetAddress.getLoopbackAddress(),
            port = 9,
            key = ByteArray(32),
        ))
    }
}

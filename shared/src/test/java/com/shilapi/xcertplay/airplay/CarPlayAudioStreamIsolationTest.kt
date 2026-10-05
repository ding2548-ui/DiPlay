package com.shilapi.xcertplay.airplay

import java.io.Closeable
import java.net.Socket
import java.security.SecureRandom
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class CarPlayAudioStreamIsolationTest {
    @Test fun guidanceSetupAndMediaReplacementKeepTheOtherAudioStreamAlive() {
        val session = session()
        val engine = CarPlayMediaEngine(object : MediaSink {})
        try {
            assertNotNull(engine.onAudio(session, 100, setup("media")))
            val streams = streams(engine)
            val mediaKey = CarPlayMediaEngine.StreamKey(session, 100, "media")
            val firstMedia = streams[mediaKey]
            assertNotNull(engine.onAudio(session, 100, setup("default")))
            val guidanceKey = CarPlayMediaEngine.StreamKey(session, 100, "default")
            val guidance = streams[guidanceKey]
            assertSame(firstMedia, streams[mediaKey])
            assertEquals(2, streams.size)
            assertNotNull(engine.onAudio(session, 100, setup("MEDIA")))
            assertNotSame(firstMedia, streams[mediaKey])
            assertSame(guidance, streams[guidanceKey])
            assertEquals(2, streams.size)
        } finally {
            engine.onSessionClosed(session)
            session.close()
        }
    }

    @Test fun typeTeardownClosesEveryVariantButKeepsOtherTypes() {
        val stopped = mutableListOf<AudioStreamId>()
        val engine = CarPlayMediaEngine(object : MediaSink {
            override fun onAudioStopped(id: AudioStreamId) { stopped.add(id) }
        })
        val session = session()
        try {
            assertNotNull(engine.onAudio(session, 100, setup("media")))
            assertNotNull(engine.onAudio(session, 100, setup("default")))
            assertNotNull(engine.onAudio(session, 102, setup("media")))
            stopped.clear()
            engine.onTeardown(session, 100)
            assertEquals(setOf(AudioStreamId(100, "media"), AudioStreamId(100, "default")), stopped.toSet())
            assertEquals(setOf(CarPlayMediaEngine.StreamKey(session, 102, "media")), streams(engine).keys)
        } finally {
            engine.onSessionClosed(session)
            session.close()
        }
    }

    private fun setup(audioType: String): Map<String, Any?> = mapOf(
        "audioType" to audioType,
        "audioFormat" to 0x8000L,
        "streamConnectionID" to 42L,
    )

    @Suppress("UNCHECKED_CAST")
    private fun streams(engine: CarPlayMediaEngine): MutableMap<CarPlayMediaEngine.StreamKey, Closeable> =
        CarPlayMediaEngine::class.java.getDeclaredField("streams").apply { isAccessible = true }
            .get(engine) as MutableMap<CarPlayMediaEngine.StreamKey, Closeable>

    private fun session(): AirPlaySession {
        val session = AirPlaySession(
            socket = Socket(),
            config = AirPlayConfig(
                deviceName = "test", deviceId = "02:00:00:00:00:02", btMac = "02:00:00:00:00:01",
                sourceVersion = "1.0", main = AirPlayDisplayConfig(widthPixels = 800, heightPixels = 480),
            ),
            identity = AirPlayIdentity.generate(), pairings = PairingStore(), mfi = null,
            listener = object : AirPlaySessionListener {}, media = object : AirPlayMediaHandler {},
        )
        val secret = ByteArray(32).also(SecureRandom()::nextBytes)
        session.pairVerify.javaClass.getDeclaredField("sharedSecret").apply { isAccessible = true }
            .set(session.pairVerify, secret)
        return session
    }
}

package com.shilapi.xcertplay.airplay

import java.net.Socket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.io.DataOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BufferedAudioSessionTest {
    @Test
    fun disabledOutputDeclinesType103BeforeCallingTheMediaHandler() {
        var setups = 0
        val session = session(disabled = true, media = object : AirPlayMediaHandler {
            override fun onBufferedAudio(session: AirPlaySession, stream: Map<String, Any?>): Map<String, Any?>? {
                setups++
                return mapOf("type" to 103, "dataPort" to 1234)
            }
        })
        try {
            val response = handle(session, "SETUP", BplistCodec.encode(mapOf("streams" to listOf(mapOf("type" to 103)))))
            val decoded = BplistCodec.decode(response.body) as Map<*, *>
            assertEquals(emptyList<Any>(), decoded["streams"])
            assertEquals(0, setups)
        } finally { session.close() }
    }

    @Test
    fun malformedControlIsRejectedBeforeItCanDefaultToStartingPlayback() {
        var controls = 0
        val session = session(media = object : AirPlayMediaHandler {
            override fun onBufferedAudioControl(session: AirPlaySession, method: String, body: Map<String, Any?>): Map<String, Any?>? {
                controls++
                return null
            }
        })
        try {
            assertEquals(400, handle(session, "SETRATE", byteArrayOf(1, 2, 3)).status)
            assertEquals(0, controls)
            assertEquals(200, handle(session, "GETANCHOR", ByteArray(0)).status)
            assertEquals(1, controls)
        } finally { session.close() }
    }

    @Test
    fun rejectedReplacementPreservesTheExistingBufferedStream() {
        val engine = CarPlayMediaEngine(object : MediaSink {})
        val session = session(media = engine)
        try {
            val first = engine.onBufferedAudio(session, validSetup())!!
            assertNull(engine.onBufferedAudio(session, validSetup() + ("shk" to ByteArray(8))))
            assertNull(engine.onBufferedAudio(session, validSetup() + ("spf" to 960)))
            Socket("127.0.0.1", first["dataPort"] as Int).use { }
            val feedback = engine.onFeedback(session)!!["streams"] as List<*>
            assertEquals(1, feedback.size)
            assertEquals(103, (feedback.single() as Map<*, *>)["type"])
        } finally { session.close() }
    }

    @Test
    fun missingOrInvalidRateCannotStartAValidStream() {
        val engine = CarPlayMediaEngine(object : MediaSink {})
        val session = session(media = engine)
        try {
            assertTrue(engine.onBufferedAudio(session, validSetup()) != null)
            assertNull(engine.onBufferedAudioControl(session, "SETRATE", emptyMap()))
            assertNull(engine.onBufferedAudioControl(session, "SETRATEANCHORTIME", mapOf("rate" to 2)))
            assertNull(engine.onBufferedAudioControl(session, "GETANCHOR", emptyMap()))
            assertEquals(1, engine.onBufferedAudioControl(session, "SETRATE", mapOf("rate" to 1, "rtpTime" to 1024L))!!["rate"])
            assertNull(engine.onBufferedAudioControl(session, "SETRATEANCHORTIME", mapOf("rate" to 0.5)))
            assertEquals(1, engine.onBufferedAudioControl(session, "GETANCHOR", emptyMap())!!["rate"])
        } finally { session.close() }
    }

    @Test
    fun replacingTheSessionClosesOldPreloadAndLateOldCleanupCannotStopTheNewOutput() {
        val events = CopyOnWriteArrayList<String>()
        val sink = object : MediaSink {
            override fun onAudioStarted(id: AudioStreamId, format: AudioFormat, firstSample: Int) { events += "start:$firstSample" }
            override fun onAudioRtp(id: AudioStreamId, format: AudioFormat, rtp: ByteArray, sample: Int) { events += "rtp:$sample" }
            override fun onAudioStopped(id: AudioStreamId) { events += "stop" }
        }
        val engine = CarPlayMediaEngine(sink)
        val old = session(media = engine)
        val replacement = session(media = engine)
        try {
            val oldSetup = engine.onBufferedAudio(old, validSetup())!!
            Socket("127.0.0.1", oldSetup["dataPort"] as Int).use { oldSocket ->
                sendFrame(oldSocket, 0, 1024)
                engine.onBufferedAudioControl(old, "SETRATE", mapOf("rate" to 1, "rtpTime" to 1024))
                waitFor { events.contains("rtp:1024") }
                val newSetup = engine.onBufferedAudio(replacement, validSetup())!!
                oldSocket.soTimeout = 1000
                assertEquals("The retired TCP preload must be cancelled", -1, oldSocket.getInputStream().read())
                Socket("127.0.0.1", newSetup["dataPort"] as Int).use { newSocket ->
                    sendFrame(newSocket, 1, 100000)
                    engine.onBufferedAudioControl(replacement, "SETRATE", mapOf("rate" to 1, "rtpTime" to 100000))
                    waitFor { events.contains("rtp:100000") }
                    val started = events.indexOf("start:100000")
                    assertNull(engine.onBufferedAudioControl(old, "SETRATEANCHORTIME", mapOf("rate" to 0)))
                    engine.onTeardown(old, 103)
                    old.close()
                    sendFrame(newSocket, 2, 101024)
                    waitFor { events.contains("rtp:101024") }
                    assertTrue("Late old cleanup stopped the replacement renderer", events.drop(started).none { it == "stop" })
                    assertEquals(1, engine.onBufferedAudioControl(replacement, "GETANCHOR", emptyMap())!!["rate"])
                }
            }
        } finally { old.close(); replacement.close() }
    }

    @Test
    fun aRetiredSessionCannotReclaimTheBufferedRendererWithALateSetup() {
        val engine = CarPlayMediaEngine(object : MediaSink {})
        val old = session(media = engine)
        val replacement = session(media = engine)
        try {
            assertTrue(engine.onBufferedAudio(old, validSetup()) != null)
            assertTrue(engine.onBufferedAudio(replacement, validSetup()) != null)
            assertNull(engine.onBufferedAudio(old, validSetup()))
            old.close()
            assertNull(engine.onBufferedAudio(old, validSetup()))
            assertTrue(engine.onFeedback(replacement) != null)
        } finally { old.close(); replacement.close() }
    }

    @Test
    fun replacementWaitsForDeliveryWithoutHoldingTheEngineLockAcrossAReentrantCallback() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val reentered = AtomicBoolean(false)
        val holdTimedOut = AtomicBoolean(false)
        lateinit var engine: CarPlayMediaEngine
        lateinit var old: AirPlaySession
        engine = CarPlayMediaEngine(object : MediaSink {
            override fun onAudioRtp(id: AudioStreamId, format: AudioFormat, rtp: ByteArray, sample: Int) {
                entered.countDown()
                if (!release.await(30, TimeUnit.SECONDS)) {
                    holdTimedOut.set(true)
                    return
                }
                reentered.set(engine.onBufferedAudioControl(old, "GETANCHOR", emptyMap()) == null)
            }
        })
        old = session(media = engine)
        val registrations = bufferedRegistrations(engine)
        val replacement = session(media = engine)
        val third = session(media = engine)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val setup = engine.onBufferedAudio(old, validSetup())!!
            Socket("127.0.0.1", setup["dataPort"] as Int).use { socket ->
                sendFrame(socket, 0, 1024)
                engine.onBufferedAudioControl(old, "SETRATE", mapOf("rate" to 1, "rtpTime" to 1024))
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                // feedback/anchor acquire the stream lock held by this callback. Observe only
                // the concurrent registry so the test does not release delivery by timing out.
                assertTrue(registrations.containsKey(old))
                val pending = executor.submit<Map<String, Any?>?> { engine.onBufferedAudio(replacement, validSetup()) }
                waitFor(10) { !registrations.containsKey(old) }
                assertTrue("Held sink callback timed out before controlled release", !holdTimedOut.get())
                assertTrue("Replacement must finish old delivery before publishing its port", !pending.isDone)
                assertNull(engine.onBufferedAudio(third, validSetup()))
                release.countDown()
                assertTrue(pending.get(10, TimeUnit.SECONDS) != null)
                assertTrue("Held sink callback timed out before controlled release", !holdTimedOut.get())
                assertTrue("Engine lock blocked an old sink callback during retirement", reentered.get())
            }
        } finally {
            release.countDown()
            old.close(); replacement.close(); third.close(); executor.shutdownNow()
        }
    }

    @Test
    fun closingThePendingReplacementCannotReleaseTheBarrierBeforeOldOutputCleanup() {
        val stopped = CountDownLatch(1)
        val release = CountDownLatch(1)
        val delivered = CountDownLatch(1)
        val holdTimedOut = AtomicBoolean(false)
        val engine = CarPlayMediaEngine(object : MediaSink {
            override fun onAudioRtp(id: AudioStreamId, format: AudioFormat, rtp: ByteArray, sample: Int) { delivered.countDown() }
            override fun onAudioStopped(id: AudioStreamId) {
                stopped.countDown()
                holdTimedOut.set(!release.await(30, TimeUnit.SECONDS))
            }
        })
        val old = session(media = engine)
        val replacement = session(media = engine)
        val third = session(media = engine)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val setup = engine.onBufferedAudio(old, validSetup())!!
            Socket("127.0.0.1", setup["dataPort"] as Int).use { socket ->
                sendFrame(socket, 0, 1024)
                engine.onBufferedAudioControl(old, "SETRATE", mapOf("rate" to 1, "rtpTime" to 1024))
                assertTrue(delivered.await(10, TimeUnit.SECONDS))
                val pending = executor.submit<Map<String, Any?>?> { engine.onBufferedAudio(replacement, validSetup()) }
                assertTrue(stopped.await(10, TimeUnit.SECONDS))
                replacement.close()
                assertTrue("Held output cleanup timed out before controlled release", !holdTimedOut.get())
                assertNull(engine.onBufferedAudio(third, validSetup()))
                release.countDown()
                assertNull(pending.get(10, TimeUnit.SECONDS))
                assertTrue("Held output cleanup timed out before controlled release", !holdTimedOut.get())
                assertNull(engine.onBufferedAudio(replacement, validSetup()))
                assertTrue(engine.onBufferedAudio(third, validSetup()) != null)
            }
        } finally {
            release.countDown()
            old.close(); replacement.close(); third.close(); executor.shutdownNow()
        }
    }

    @Test
    fun reentrantSetupDuringOldStopIsDeclinedUntilThatCallbackCompletes() {
        val delivered = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val declined = AtomicBoolean(false)
        lateinit var engine: CarPlayMediaEngine
        lateinit var replacement: AirPlaySession
        engine = CarPlayMediaEngine(object : MediaSink {
            override fun onAudioRtp(id: AudioStreamId, format: AudioFormat, rtp: ByteArray, sample: Int) { delivered.countDown() }
            override fun onAudioStopped(id: AudioStreamId) {
                declined.set(engine.onBufferedAudio(replacement, validSetup()) == null)
                stopped.countDown()
            }
        })
        val old = session(media = engine)
        replacement = session(media = engine)
        try {
            val setup = engine.onBufferedAudio(old, validSetup())!!
            Socket("127.0.0.1", setup["dataPort"] as Int).use { socket ->
                sendFrame(socket, 0, 1024)
                engine.onBufferedAudioControl(old, "SETRATE", mapOf("rate" to 1, "rtpTime" to 1024))
                assertTrue(delivered.await(3, TimeUnit.SECONDS))
                // A malformed TCP frame closes the stream from its receiver, outside the engine.
                DataOutputStream(socket.getOutputStream()).apply { writeShort(2); flush() }
                assertTrue(stopped.await(3, TimeUnit.SECONDS))
                assertTrue(declined.get())
                waitFor { engine.onBufferedAudio(replacement, validSetup()) != null }
            }
        } finally { old.close(); replacement.close() }
    }

    private fun sendFrame(socket: Socket, sequence: Int, timestamp: Int) {
        val header = java.nio.ByteBuffer.allocate(12).apply {
            put(0x80.toByte()); put(0x60.toByte()); putShort(sequence.toShort()); putInt(timestamp); putInt(0)
        }.array()
        val tail = java.nio.ByteBuffer.allocate(8).putLong(sequence.toLong()).array()
        val nonce = ByteArray(12).also { tail.copyInto(it, 4) }
        val encrypted = AirPlayCrypto.chachaSeal(ByteArray(32), nonce, byteArrayOf(0x21), header.copyOfRange(4, 12))
        val body = header + encrypted + tail
        DataOutputStream(socket.getOutputStream()).apply { writeShort(body.size + 2); write(body); flush() }
    }

    private fun bufferedRegistrations(engine: CarPlayMediaEngine): java.util.concurrent.ConcurrentHashMap<*, *> =
        CarPlayMediaEngine::class.java.getDeclaredField("bufferedStreams").apply { isAccessible = true }
            .get(engine) as java.util.concurrent.ConcurrentHashMap<*, *>

    private fun waitFor(seconds: Long = 3, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue("condition not met in time", condition())
    }

    private fun validSetup(): Map<String, Any?> = mapOf(
        "type" to 103, "shk" to ByteArray(32), "audioFormat" to 0x800000,
        "ct" to 4, "spf" to 1024, "streamConnectionID" to 1,
    )

    private fun handle(session: AirPlaySession, method: String, body: ByteArray): RtspMessage.Response =
        AirPlaySession::class.java.getDeclaredMethod("handle", RtspMessage.Request::class.java).apply { isAccessible = true }
            .invoke(session, RtspMessage.Request(method, "rtsp://test", "RTSP/1.0", emptyMap(), body)) as RtspMessage.Response

    private fun session(disabled: Boolean = false, media: AirPlayMediaHandler): AirPlaySession = AirPlaySession(
        socket = object : Socket() {
            override fun getRemoteSocketAddress(): SocketAddress = InetSocketAddress(InetAddress.getLoopbackAddress(), 1234)
        },
        config = AirPlayConfig(
            deviceName = "test", deviceId = "02:00:00:00:00:02", btMac = "02:00:00:00:00:01", sourceVersion = "1.0",
            main = AirPlayDisplayConfig(widthPixels = 800, heightPixels = 480),
            mainBufferedAudio = true, disableAudioOutput = disabled,
        ),
        identity = AirPlayIdentity.generate(), pairings = PairingStore(), mfi = null,
        listener = object : AirPlaySessionListener {}, media = media,
    )
}

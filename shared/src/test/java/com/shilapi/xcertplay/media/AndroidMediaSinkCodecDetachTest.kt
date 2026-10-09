package com.shilapi.xcertplay.media

import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCrypto
import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import android.view.Surface
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaCodec
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Surface detach against a decoder that really configures, queues and drains a MediaCodec: Robolectric's
 * ShadowMediaCodec runs the registered fake codec's process() on the decoder worker inside
 * queueInputBuffer, so a codec that blocks there stands in for a native call that hangs.
 *
 * Robolectric does not render: setOutputSurface is a no-op, no image ever reaches the parking
 * ImageReader, and releaseOutputBuffer only recycles the buffer. What a codec renders to is therefore
 * read from the worker's own record (outputSurface) and the configure-time surface the shadow reports.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class AndroidMediaSinkCodecDetachTest {
    private val sps = byteArrayOf(0x67, 0x42, 0xC0.toByte(), 0x1E)
    private val pps = byteArrayOf(0x68, 0xCE.toByte())
    // AVCDecoderConfigurationRecord with one SPS and one PPS.
    private val avcC = byteArrayOf(1, 0x42, 0xC0.toByte(), 0x1E, 0xFF.toByte(), 0xE1.toByte(), 0, sps.size.toByte()) +
        sps + byteArrayOf(1, 0, pps.size.toByte()) + pps
    private val idr = byteArrayOf(0, 0, 0, 1, 0x65, 0x88.toByte(), 0x84.toByte(), 0x21)

    private lateinit var codec: GateCodec
    private val sinks = ArrayList<AndroidMediaSink>()
    private val uncaught = ConcurrentLinkedQueue<Throwable>()
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    /** The fake codec: records configure surfaces and can hold the worker inside process(). */
    private class GateCodec : ShadowMediaCodec.CodecConfig.Codec {
        val configuredSurfaces = CopyOnWriteArrayList<Surface?>()
        @Volatile private var gate: CountDownLatch? = null
        @Volatile var entered = CountDownLatch(1)
            private set

        /** The next process() call blocks, ignoring interrupts like a hung native call, until [release]. */
        fun blockNext() {
            entered = CountDownLatch(1)
            gate = CountDownLatch(1)
        }

        fun release() {
            gate?.countDown()
        }

        override fun process(input: ByteBuffer, output: ByteBuffer) {
            val held = gate ?: return
            entered.countDown()
            var interrupted = false
            while (true) {
                try {
                    held.await()
                    break
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
            gate = null
            if (interrupted) Thread.currentThread().interrupt()
        }

        override fun onConfigured(format: MediaFormat?, surface: Surface?, crypto: MediaCrypto?, flags: Int) {
            configuredSurfaces += surface
        }
    }

    @Before fun setUp() {
        codec = GateCodec()
        ShadowMediaCodec.addDecoder(MediaFormat.MIMETYPE_VIDEO_AVC, ShadowMediaCodec.CodecConfig(64 * 1024, 64 * 1024, codec))
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, error -> uncaught += error }
    }

    @After fun tearDown() {
        codec.release()
        sinks.forEach { sink ->
            sink.close()
            sink.awaitVideoReleased(5_000)
        }
        ShadowMediaCodec.clearCodecs()
        Thread.setDefaultUncaughtExceptionHandler(previousHandler)
    }

    @Test fun smoothVideoParksTheMainDecoderOnAConsumerDrainedOffTheMainThread() {
        val a = newSurface()
        val sink = configuredSink(a)
        val codecBefore = mediaCodecOf(mainDecoder(sink))

        val detach = sink.beginSurfaceDetach(a, parkMain = true)
        assertTrue(detach.await())

        assertEquals(DetachOutcome.PARKED, detach.requests.single().outcome)
        val decoder = mainDecoder(sink)
        val parking = parkingOf(decoder)
        assertNotNull("the parked decoder owns an offscreen consumer", parking)
        parking!!
        assertNotSame(Looper.getMainLooper(), parking.listenerLooper)
        assertSame(parking.listenerLooper, readerListenerLooper(parking))
        assertSame("the codec now renders to the consumer", parking.surface, decoder.field<Surface?>("outputSurface"))
        assertSame("parking keeps the codec and its state", codecBefore, mediaCodecOf(decoder))
        assertEquals(listOf<Surface?>(a), codec.configuredSurfaces.toList())
    }

    @Test fun theParkingConsumerDrainsWhileTheMainThreadIsBlocked() {
        val a = newSurface()
        val sink = configuredSink(a)
        assertTrue(sink.beginSurfaceDetach(a, parkMain = true).await())
        val parking = checkNotNull(parkingOf(mainDecoder(sink)))

        // The test thread is Robolectric's main thread: while it blocks here, as a SurfaceHolder destroy
        // callback does during a handoff, work for the consumer must still run.
        val consumerRan = CountDownLatch(1)
        val readerRan = CountDownLatch(1)
        Handler(parking.listenerLooper).post { consumerRan.countDown() }
        Handler(readerListenerLooper(parking)).post { readerRan.countDown() }
        assertTrue(consumerRan.await(5, TimeUnit.SECONDS))
        assertTrue(readerRan.await(5, TimeUnit.SECONDS))
    }

    @Test fun aNewHostsSurfaceQueuedBeforeTheDetachLeavesNothingToDetach() {
        val a = newSurface()
        val b = newSurface()
        val sink = configuredSink(a)
        holdWorkerInCodec(sink)

        // The new host's surface arrives before the old one's destroy callback, while the worker is busy.
        sink.setSurface(110, b)
        val detach = sink.beginSurfaceDetach(a, parkMain = true)
        val waiter = AwaitOnThread(detach)
        assertFalse("the codec still renders to A until the worker applies B", waiter.returned.await(300, TimeUnit.MILLISECONDS))

        codec.release()
        assertTrue(waiter.returned.await(5, TimeUnit.SECONDS))
        assertEquals(true, waiter.result.get())
        assertEquals(DetachOutcome.NOT_RENDERING, detach.requests.single().outcome)
        val decoder = mainDecoder(sink)
        assertSame(b, decoder.field<Surface?>("outputSurface"))
        assertNull("nothing was parked", parkingOf(decoder))
    }

    @Test fun aDecoderParkedFirstMovesToTheNewHostsSurfaceWithItsCodec() {
        val a = newSurface()
        val b = newSurface()
        val sink = configuredSink(a)
        val decoder = mainDecoder(sink)
        val codecBefore = mediaCodecOf(decoder)

        val first = sink.beginSurfaceDetach(a, parkMain = true)
        assertTrue(first.await())
        assertEquals(DetachOutcome.PARKED, first.requests.single().outcome)
        val parking = checkNotNull(parkingOf(decoder))

        sink.setSurface(110, b)
        assertTrue(barrier(sink))
        assertSame(b, decoder.field<Surface?>("outputSurface"))
        assertSame("moving back keeps the codec", codecBefore, mediaCodecOf(decoder))
        assertEquals(listOf<Surface?>(a), codec.configuredSurfaces.toList())

        // A detaches again (late) but the codec renders to B now.
        val stale = sink.beginSurfaceDetach(a, parkMain = true)
        assertTrue(stale.await())
        assertEquals(DetachOutcome.NOT_RENDERING, stale.requests.single().outcome)

        val second = sink.beginSurfaceDetach(b, parkMain = true)
        assertTrue(second.await())
        assertEquals(DetachOutcome.PARKED, second.requests.single().outcome)
        assertSame("the decoder reuses its consumer", parking, parkingOf(decoder))
        assertSame(codecBefore, mediaCodecOf(decoder))
    }

    @Test fun aDetachWaitsForAWorkerBusyInsideTheCodecEvenWhenInterrupted() {
        val a = newSurface()
        val sink = configuredSink(a)
        sink.detachTimeoutNanos = 30_000_000_000L // only the codec's progress decides here
        holdWorkerInCodec(sink)

        val detach = sink.beginSurfaceDetach(a, parkMain = true)
        val waiter = AwaitOnThread(detach)
        assertFalse(waiter.returned.await(300, TimeUnit.MILLISECONDS))
        waiter.thread.interrupt()
        assertFalse("an interrupt does not end the wait", waiter.returned.await(600, TimeUnit.MILLISECONDS))
        assertNull(detach.requests.single().outcome)

        val released = System.nanoTime()
        codec.release()
        assertTrue(waiter.returned.await(5, TimeUnit.SECONDS))
        assertTrue(System.nanoTime() - released < 2_000_000_000L)
        assertEquals(true, waiter.result.get())
        assertEquals("the interrupt is restored", true, waiter.interruptedAfter.get())
        assertEquals(DetachOutcome.PARKED, detach.requests.single().outcome)
    }

    @Test fun anUnconfirmedDetachRetiresTheDecoderAndTheNextFrameStartsAFreshOneOnNoSurface() {
        val a = newSurface()
        val c = newSurface()
        val sink = configuredSink(a)
        val diagnostics = ConcurrentLinkedQueue<String>()
        sink.setVideoDiagnosticHandler(110) { diagnostics += it }
        sink.detachTimeoutNanos = 200_000_000L
        sink.detachGraceNanos = 200_000_000L
        holdWorkerInCodec(sink)
        val stuck = mainDecoder(sink)

        val started = System.nanoTime()
        val detach = sink.beginSurfaceDetach(a, parkMain = true)
        assertFalse("the worker neither confirmed nor exited", detach.await())
        assertTrue(System.nanoTime() - started < 5_000_000_000L)
        assertNull(detach.requests.single().outcome)
        assertFalse("the unconfirmed decoder was retired", decoders(sink).containsValue(stuck))
        assertTrue(diagnostics.any { "detach not confirmed" in it })

        // The next frame starts a fresh decoder with the stream's configuration, not on the leaving surface.
        sink.onVideoFrame(110, idr)
        val fresh = mainDecoder(sink)
        assertNotSame(stuck, fresh)
        codec.release()
        assertTrue("the retired worker exits once the codec returns", sink.awaitVideoReleased(5_000))
        assertFalse(stuck.field<Thread>("thread").isAlive)
        assertTrue(barrier(sink))
        assertNull(fresh.field<Surface?>("outputSurface"))
        assertNotNull("it picked up the stream's configuration", fresh.field<Any?>("lastConfig"))
        assertEquals("no codec was started on A again", listOf<Surface?>(a), codec.configuredSurfaces.toList())

        sink.setSurface(110, c)
        sink.onVideoFrame(110, idr)
        assertTrue(barrier(sink))
        assertEquals(listOf<Surface?>(a, c), codec.configuredSurfaces.toList())
        assertTrue(uncaught.toString(), uncaught.isEmpty())
    }

    @Test fun aDetachWaitsForADecoderClosedAtStreamEndUntilItsWorkerExits() {
        val a = newSurface()
        val sink = configuredSink(a)
        sink.detachTimeoutNanos = 30_000_000_000L
        holdWorkerInCodec(sink)
        val closing = mainDecoder(sink)

        sink.onScreenStreamActive(110, false) // closes the busy decoder
        assertTrue(decoders(sink).isEmpty())
        val detach = sink.beginSurfaceDetach(a, parkMain = true)
        assertTrue(detach.requests.isEmpty())
        val waiter = AwaitOnThread(detach)
        assertFalse("its codec may still render to A", waiter.returned.await(300, TimeUnit.MILLISECONDS))

        codec.release()
        assertTrue(waiter.returned.await(5, TimeUnit.SECONDS))
        assertEquals(true, waiter.result.get())
        assertFalse(closing.field<Thread>("thread").isAlive)
    }

    @Test fun afterCloseTheSinkReportsReleaseOnlyOnceItsWorkersExit() {
        val a = newSurface()
        val sink = configuredSink(a)
        holdWorkerInCodec(sink)
        val decoder = mainDecoder(sink)

        sink.close()
        assertFalse("the worker is still inside the codec", sink.awaitVideoReleased(200))
        codec.release()
        assertTrue(sink.awaitVideoReleased(5_000))
        assertFalse(decoder.field<Thread>("thread").isAlive)
        assertTrue("nothing left to wait for", sink.beginSurfaceDetach(a, parkMain = true).await())
    }

    @Test fun detachCannotMissAWorkerWhoseSurfaceWasCapturedBeforePublication() {
        val a = newSurface()
        val sink = AndroidMediaSink(videoPacingDelayMillis = 90).also { sinks += it }
        sink.setSurface(110, a)
        val registry = GatedRegistry().also { it.putGate = RegistryGate() }
        sink.replaceField("videoDecoders", registry)
        val creator = thread { sink.onVideoConfig(110, avcC) }
        val gate = checkNotNull(registry.putGate)
        var detach: BeginOnThread? = null
        try {
            assertTrue(gate.entered.await(5, TimeUnit.SECONDS))
            assertTrue("an unpublished worker must not configure the old surface", codec.configuredSurfaces.isEmpty())
            detach = BeginOnThread(sink, a)
            awaitBlockedOnOwnership(detach.thread)
            assertEquals(1L, detach.returned.count)
            gate.release.countDown()
            creator.join(5_000)
            assertFalse(creator.isAlive)
            assertTrue(detach.returned.await(5, TimeUnit.SECONDS))
            assertEquals(true, detach.result.get())
            assertNotNull("the registered worker participated in detach", detach.detach.get()?.requests?.singleOrNull())
            assertNotSame(a, mainDecoder(sink).field<Surface?>("outputSurface"))
        } finally {
            gate.release.countDown()
            creator.join(5_000)
            detach?.thread?.join(5_000)
        }
    }

    @Test fun detachCannotMissADecoderTransferringToTheClosingRegistry() {
        val a = newSurface()
        val sink = configuredSink(a)
        holdWorkerInCodec(sink)
        val registry = GatedRegistry(decoders(sink)).also { it.removeGate = RegistryGate() }
        sink.replaceField("videoDecoders", registry)
        val retire = thread { sink.onScreenStreamActive(110, false) }
        val gate = checkNotNull(registry.removeGate)
        var detach: BeginOnThread? = null
        try {
            assertTrue(gate.entered.await(5, TimeUnit.SECONDS))
            assertTrue("active entry has already been removed", registry.isEmpty())
            detach = BeginOnThread(sink, a)
            awaitBlockedOnOwnership(detach.thread)
            assertEquals(1L, detach.returned.count)
            gate.release.countDown()
            retire.join(5_000)
            assertFalse(retire.isAlive)
            assertTrue(detach.begun.await(5, TimeUnit.SECONDS))
            assertEquals("the codec is still held after the transfer completes", 1L, detach.returned.count)
            codec.release()
            assertTrue(detach.returned.await(5, TimeUnit.SECONDS))
            assertEquals(true, detach.result.get())
        } finally {
            gate.release.countDown()
            codec.release()
            retire.join(5_000)
            detach?.thread?.join(5_000)
        }
    }

    @Test fun releaseObserversSurviveRegistrationRacingWorkerExitAndRunOutsideOwnershipLock() {
        val a = newSurface()
        val sink = configuredSink(a)
        holdWorkerInCodec(sink)
        sink.close()
        // A late accepted receive callback must not revive a closed sink after release was observed.
        sink.onVideoCodec(110, com.shilapi.xcertplay.airplay.VideoCodec.H264)
        sink.onVideoConfig(110, avcC)
        sink.onVideoFrame(110, idr)
        sink.onVideoFrame(110, idr, 1L, System.nanoTime())
        assertTrue(decoders(sink).isEmpty())

        val calls = AtomicInteger()
        val released = CountDownLatch(1)
        val delivered = CountDownLatch(9)
        val peerFinished = AtomicReference<Boolean>()
        sink.whenVideoReleased { throw IllegalStateException("observer failure must be isolated") }
        sink.whenVideoReleased {
            // The fake native call restores close's interrupt. Ignore it only for this deliberate
            // blocking lock probe; release observers do not promise an un-interrupted calling thread.
            val interrupted = Thread.interrupted()
            try {
                val peer = CountDownLatch(1)
                thread { sink.clearSurface(110, a); peer.countDown() }
                peerFinished.set(peer.await(5, TimeUnit.SECONDS))
                calls.incrementAndGet()
                delivered.countDown()
                released.countDown()
            } finally {
                if (interrupted) Thread.currentThread().interrupt()
            }
        }
        assertEquals(1L, released.count)
        val start = CountDownLatch(1)
        val registrations = (0 until 8).map {
            thread { start.await(); sink.whenVideoReleased { calls.incrementAndGet(); delivered.countDown() } }
        }
        start.countDown()
        codec.release()
        registrations.forEach { it.join(5_000); assertFalse(it.isAlive) }
        assertTrue(released.await(5, TimeUnit.SECONDS))
        assertTrue(delivered.await(5, TimeUnit.SECONDS))
        assertTrue(sink.awaitVideoReleased(5_000))
        assertEquals(true, peerFinished.get())
        sink.whenVideoReleased { calls.incrementAndGet() } // registration after release is immediate
        assertEquals("each successful observer runs exactly once", 10, calls.get())
        assertTrue(decoders(sink).isEmpty())
        assertEquals(listOf<Surface?>(a), codec.configuredSurfaces.toList())
    }

    @Test fun aStaleRetirementCannotRepublishAWorkerThatAlreadyReleasedItsCodec() {
        val a = newSurface()
        val sink = configuredSink(a)
        holdWorkerInCodec(sink)
        val decoder = mainDecoder(sink)
        sink.close()
        val notifying = CountDownLatch(1)
        val finishNotification = CountDownLatch(1)
        sink.whenVideoReleased {
            val interrupted = Thread.interrupted()
            try {
                notifying.countDown()
                finishNotification.await(5, TimeUnit.SECONDS)
            } finally {
                if (interrupted) Thread.currentThread().interrupt()
            }
        }
        try {
            codec.release()
            assertTrue(notifying.await(5, TimeUnit.SECONDS))
            assertTrue("cleanup finished but onExit is still notifying", decoder.field<Thread>("thread").isAlive)
            assertTrue(sink.field<Set<Any>>("closingVideoDecoders").isEmpty())
            // settleDetach can time out, pause, and only reach retire after onExit completed cleanup.
            sink.javaClass.getDeclaredMethod("retire", Int::class.javaPrimitiveType, decoder.javaClass)
                .apply { isAccessible = true }.invoke(sink, 110, decoder)
            val alreadyReleased = CountDownLatch(1)
            sink.whenVideoReleased { alreadyReleased.countDown() }
            assertEquals("already-released registration must complete immediately", 0L, alreadyReleased.count)
            assertTrue("the stale worker was not republished", sink.field<Set<Any>>("closingVideoDecoders").isEmpty())
        } finally {
            finishNotification.countDown()
            decoder.field<Thread>("thread").join(5_000)
        }
    }

    // --- helpers ---

    private fun newSurface() = Surface(SurfaceTexture(0))

    /** A smooth-video sink, as the host makes it: no default surface, the main stream configured on [surface]. */
    private fun configuredSink(surface: Surface): AndroidMediaSink {
        val sink = AndroidMediaSink(surface = null, videoPacingDelayMillis = 90)
        sinks += sink
        sink.setSurface(110, surface)
        sink.onVideoConfig(110, avcC)
        sink.onVideoFrame(110, idr, 1L, System.nanoTime())
        assertTrue(barrier(sink))
        assertEquals(listOf<Surface?>(surface), codec.configuredSurfaces.toList())
        assertNotNull(mediaCodecOf(mainDecoder(sink)))
        return sink
    }

    /** Holds the main decoder's worker inside MediaCodec.queueInputBuffer until [GateCodec.release]. */
    private fun holdWorkerInCodec(sink: AndroidMediaSink) {
        codec.blockNext()
        sink.onVideoFrame(110, idr, 16_666_668L, System.nanoTime())
        assertTrue("worker entered the codec", codec.entered.await(5, TimeUnit.SECONDS))
    }

    /**
     * Returns once every decoder has handled the jobs queued before it: a detach of a surface nobody uses
     * is completed by each worker in queue order.
     */
    private fun barrier(sink: AndroidMediaSink): Boolean = sink.beginSurfaceDetach(newSurface(), parkMain = false).await()

    private class AwaitOnThread(detach: AndroidMediaSink.SurfaceDetach) {
        val returned = CountDownLatch(1)
        val result = AtomicReference<Boolean?>()
        val interruptedAfter = AtomicReference<Boolean?>()
        val thread = thread(name = "surface-destroyed") {
            result.set(detach.await())
            interruptedAfter.set(Thread.currentThread().isInterrupted)
            returned.countDown()
        }
    }

    private class BeginOnThread(sink: AndroidMediaSink, surface: Surface) {
        val begun = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val detach = AtomicReference<AndroidMediaSink.SurfaceDetach>()
        val result = AtomicReference<Boolean>()
        val thread = thread(name = "surface-destroyed-during-registration") {
            val pending = sink.beginSurfaceDetach(surface, parkMain = true)
            detach.set(pending)
            begun.countDown()
            result.set(pending.await())
            returned.countDown()
        }
    }

    /** Registry hooks pause the actual mutation, not a duplicate implementation of the ownership lock. */
    private class RegistryGate {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        fun pause() {
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS)) { "registry gate timed out" }
        }
    }

    private class GatedRegistry(entries: Map<Int, Any> = emptyMap()) : ConcurrentHashMap<Int, Any>(entries) {
        var putGate: RegistryGate? = null
        var removeGate: RegistryGate? = null
        override fun put(key: Int, value: Any): Any? {
            putGate?.pause()
            return super.put(key, value)
        }
        override fun remove(key: Int, value: Any): Boolean {
            val removed = super.remove(key, value)
            if (removed) removeGate?.pause()
            return removed
        }
    }

    private fun awaitBlockedOnOwnership(worker: Thread) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (worker.isAlive && worker.state != Thread.State.BLOCKED && System.nanoTime() < deadline) {
            java.util.concurrent.locks.LockSupport.parkNanos(1_000_000)
        }
        assertEquals("detach waits for the in-progress registry mutation", Thread.State.BLOCKED, worker.state)
    }

    private fun Any.replaceField(name: String, value: Any) {
        javaClass.getDeclaredField(name).apply { isAccessible = true }.set(this, value)
    }

    private fun decoders(sink: AndroidMediaSink): Map<Int, Any> = sink.field("videoDecoders")

    private fun mainDecoder(sink: AndroidMediaSink): Any = checkNotNull(decoders(sink)[110]) { "no main decoder" }

    private fun mediaCodecOf(decoder: Any): MediaCodec? = decoder.field("decoder")

    private fun parkingOf(decoder: Any): ParkingOutput? = decoder.field("parking")

    /** The looper the ImageReader dispatches image-available callbacks on. */
    private fun readerListenerLooper(parking: ParkingOutput): Looper {
        val reader = parking.field<android.media.ImageReader>("reader")
        return reader.field<Handler>("mListenerHandler").looper
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> Any.field(name: String): T {
        var type: Class<*>? = javaClass
        while (type != null) {
            try {
                return type.getDeclaredField(name).apply { isAccessible = true }.get(this) as T
            } catch (_: NoSuchFieldException) {
                type = type.superclass
            }
        }
        error("no field $name on $javaClass")
    }
}

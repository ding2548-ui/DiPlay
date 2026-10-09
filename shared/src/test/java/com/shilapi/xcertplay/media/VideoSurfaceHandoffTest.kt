package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class VideoSurfaceHandoffTest {
    private val second = 1_000_000_000L

    @Test fun waitsUntilTheWorkerConfirms() {
        val request = SurfaceDetachRequest(Any(), park = true)
        thread { Thread.sleep(100); request.complete(DetachOutcome.PARKED) }
        assertTrue(request.await(2 * second, workerAlive = { true }))
        assertEquals(DetachOutcome.PARKED, request.outcome)
    }

    @Test fun anInterruptDoesNotEndTheWaitBeforeConfirmation() {
        val request = SurfaceDetachRequest(Any(), park = false)
        val started = CountDownLatch(1)
        var confirmed = false
        var interruptedAfter = false
        val waiter = thread {
            started.countDown()
            confirmed = request.await(5 * second, workerAlive = { true })
            interruptedAfter = Thread.currentThread().isInterrupted
        }
        started.await()
        Thread.sleep(50)
        waiter.interrupt()
        Thread.sleep(150)
        assertTrue("returned before the worker confirmed", waiter.isAlive)
        request.complete(DetachOutcome.RELEASED)
        waiter.join(2_000)
        assertTrue(confirmed)
        assertTrue("the interrupt is restored for the caller", interruptedAfter)
    }

    @Test fun aWorkerThatHasExitedCountsAsLettingGo() {
        // A worker releases its codec in its finally block before the thread ends.
        val alive = AtomicBoolean(true)
        val request = SurfaceDetachRequest(Any(), park = true)
        thread { Thread.sleep(100); alive.set(false) }
        assertTrue(request.await(5 * second, workerAlive = alive::get))
        assertNull(request.outcome)
    }

    @Test fun reportsWhenNothingConfirmsInTime() {
        val request = SurfaceDetachRequest(Any(), park = true)
        val started = System.nanoTime()
        assertFalse(request.await(second / 5, workerAlive = { true }))
        assertTrue(System.nanoTime() - started >= second / 5)
    }

    @Test fun aLaterResultDoesNotOverwriteTheFirst() {
        val request = SurfaceDetachRequest(Any(), park = true)
        request.complete(DetachOutcome.PARKED)
        request.complete(DetachOutcome.RELEASED) // a shutdown drain after the worker already answered
        assertEquals(DetachOutcome.PARKED, request.outcome)
    }

    @Test fun theDecisionFollowsTheSurfaceTheCodecActuallyRendersTo() {
        val leaving = Any()
        val other = Any()
        assertEquals(DetachOutcome.PARKED, detachAction(leaving, leaving, park = true, hasDecoder = true))
        assertEquals(DetachOutcome.RELEASED, detachAction(leaving, leaving, park = false, hasDecoder = true))
        // Nothing to park without a codec; the surface is simply dropped.
        assertEquals(DetachOutcome.RELEASED, detachAction(leaving, leaving, park = true, hasDecoder = false))
        // A new host's surface was applied first: the old one is no longer used, whatever the sink's map says.
        assertEquals(DetachOutcome.NOT_RENDERING, detachAction(other, leaving, park = true, hasDecoder = true))
        assertEquals(DetachOutcome.NOT_RENDERING, detachAction(null, leaving, park = true, hasDecoder = false))
    }

    @Test fun theQueueKeepsSurfaceWorkInOrderAndHandsItToAShutdown() {
        val queue = VideoDecodeQueue(maxFrames = 1)
        val newHost = VideoJob.SurfaceChanged(null)
        val detach = VideoJob.DetachSurface(SurfaceDetachRequest(Any(), park = true))
        queue.offer(newHost)
        queue.offer(VideoJob.Frame(byteArrayOf(1)))
        queue.offer(detach)
        queue.offer(VideoJob.Frame(byteArrayOf(2))) // overflow discards frames, never surface work
        val pending = queue.drain()
        assertEquals(listOf(newHost, detach), pending.filter { it !is VideoJob.Frame && it !is VideoJob.Resync })
        assertTrue(queue.drain().isEmpty())
        assertNull(queue.poll(0))
    }

    @Test fun completingOnTheDrainWakesAWaiter() {
        val request = SurfaceDetachRequest(Any(), park = true)
        val queue = VideoDecodeQueue()
        queue.offer(VideoJob.DetachSurface(request))
        val woke = CountDownLatch(1)
        thread { if (request.await(5 * second, workerAlive = { true })) woke.countDown() }
        // What a worker does in its finally block after releasing the codec.
        queue.drain().forEach { (it as? VideoJob.DetachSurface)?.request?.complete(DetachOutcome.RELEASED) }
        assertTrue(woke.await(2, TimeUnit.SECONDS))
        assertEquals(DetachOutcome.RELEASED, request.outcome)
    }
}

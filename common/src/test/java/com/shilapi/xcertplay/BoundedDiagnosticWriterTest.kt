package com.shilapi.xcertplay

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class BoundedDiagnosticWriterTest {
    @Test(timeout = 5_000) fun aBlockedWriterCannotBlockEnqueueAndOnlyTheLatest64PendingEntriesSurvive() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val enqueued = CountDownLatch(1)
        val written = Collections.synchronizedList(mutableListOf<Int>())
        val writer = BoundedDiagnosticWriter<Int> { value ->
            if (value == -1) {
                entered.countDown()
                release.await()
            }
            written.add(value)
        }
        try {
            assertTrue(writer.enqueue(-1))
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            Thread {
                repeat(100) { writer.enqueue(it) }
                enqueued.countDown()
            }.start()
            assertTrue("enqueue must finish while disk remains blocked", enqueued.await(2, TimeUnit.SECONDS))
            assertEquals(64, writer.pendingCount)
            assertEquals(36L, writer.droppedCount)
            assertFalse(writer.awaitIdle(10))
            release.countDown()
            assertTrue(writer.awaitIdle(2_000))
            assertEquals(listOf(-1) + (36 until 100).toList(), written.toList())
        } finally {
            release.countDown()
            writer.close()
        }
    }

    @Test(timeout = 5_000) fun aCallbackFailureDoesNotStopDrainingAndCloseNeverWaitsForDisk() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val written = mutableListOf<Int>()
        val writer = BoundedDiagnosticWriter<Int> { value ->
            if (value == 0) {
                entered.countDown()
                release.await()
                throw IllegalStateException("private failure detail")
            }
            written.add(value)
        }
        try {
            writer.enqueue(0)
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            writer.enqueue(1)
            writer.close()
            assertFalse(writer.enqueue(2))
            release.countDown()
            assertTrue(writer.awaitIdle(2_000))
            assertEquals(listOf(1), written)
        } finally {
            release.countDown()
            writer.close()
        }
    }
}

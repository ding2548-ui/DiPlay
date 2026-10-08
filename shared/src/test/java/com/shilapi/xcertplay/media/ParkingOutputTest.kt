package com.shilapi.xcertplay.media

import android.media.Image
import android.media.ImageReader
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowImageReader
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Tests the production callback's ownership boundary, not the shadow's native cleanup semantics. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 30], manifest = Config.NONE, shadows = [ParkingOutputTest.GatedImageReader::class])
class ParkingOutputTest {
    @Test fun readerCloseWaitsForTheConsumerToFinishClosingItsAcquiredImage() {
        val race = ImageCloseRace().also { GatedImageReader.race = it }
        val parking = ParkingOutput(16, 16)
        var closer: Thread? = null
        try {
            // ShadowImageReader's fake Surface posts the real registered callback when a buffer arrives.
            // MediaCodec does not actually render to ImageReader under Robolectric.
            val canvas = parking.surface.lockCanvas(null)
            parking.surface.unlockCanvasAndPost(canvas)
            assertTrue("production consumer acquired and started closing an image", race.imageCloseEntered.await(5, TimeUnit.SECONDS))
            val closed = CountDownLatch(1)
            val failures = ConcurrentLinkedQueue<Throwable>()
            closer = thread(name = "parking-reader-close") {
                try { parking.close() } catch (error: Throwable) { failures += error }
                finally { closed.countDown() }
            }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (closer.isAlive && closer.state != Thread.State.BLOCKED && System.nanoTime() < deadline) {
                java.util.concurrent.locks.LockSupport.parkNanos(1_000_000)
            }
            assertEquals("shutdown waits for the complete acquire/close callback", Thread.State.BLOCKED, closer.state)
            assertEquals("reader.close must not race the acquired Image.close", 1L, race.readerCloseEntered.count)
            race.finishImageClose.countDown()
            assertTrue(closed.await(5, TimeUnit.SECONDS))
            assertTrue(failures.toString(), failures.isEmpty())
            assertEquals(listOf("image released", "reader closed"), race.events.toList())
            // A queued callback captured before reader.close must skip acquisition after shutdown.
            val reader = parking.field<ImageReader>("reader")
            val listener = race.listener
            checkNotNull(listener).onImageAvailable(reader)
            assertEquals(listOf("image released", "reader closed"), race.events.toList())
        } finally {
            race.finishImageClose.countDown()
            closer?.join(5_000)
            parking.close()
        }
    }

    private fun <T> Any.field(name: String): T {
        @Suppress("UNCHECKED_CAST")
        return javaClass.getDeclaredField(name).apply { isAccessible = true }.get(this) as T
    }

    class ImageCloseRace {
        val imageCloseEntered = CountDownLatch(1)
        val finishImageClose = CountDownLatch(1)
        val readerCloseEntered = CountDownLatch(1)
        val events = ConcurrentLinkedQueue<String>()
        var listener: ImageReader.OnImageAvailableListener? = null
    }

    @Implements(ImageReader::class)
    class GatedImageReader : ShadowImageReader() {
        @org.robolectric.annotation.RealObject
        lateinit var actualReader: ImageReader

        @Implementation
        override fun nativeReleaseImage(image: Image) {
            val current = checkNotNull(race)
            current.listener = ImageReader::class.java.getDeclaredField("mListener")
                .apply { isAccessible = true }.get(actualReader) as ImageReader.OnImageAvailableListener
            current.imageCloseEntered.countDown()
            check(current.finishImageClose.await(10, TimeUnit.SECONDS)) { "image close gate timed out" }
            super.nativeReleaseImage(image)
            current.events += "image released"
        }

        @Implementation
        override fun close() {
            val current = checkNotNull(race)
            current.readerCloseEntered.countDown()
            super.close()
            current.events += "reader closed"
        }

        companion object {
            var race: ImageCloseRace? = null
        }
    }
}

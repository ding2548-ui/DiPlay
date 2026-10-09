package com.shilapi.xcertplay.media

import android.graphics.SurfaceTexture
import android.view.Surface
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class AndroidMediaSinkDetachTest {
    private val predicted = byteArrayOf(0, 0, 0, 1, 0x41, 1)

    @Test fun aDetachIsConfirmedWhileFramesAreQueued() {
        val surface = Surface(SurfaceTexture(0))
        val sink = AndroidMediaSink(surface = surface, videoPacingDelayMillis = 90)
        repeat(50) { sink.onVideoFrame(110, predicted, it * 16_666_667L + 1, System.nanoTime()) }
        val started = System.nanoTime()
        assertTrue(sink.beginSurfaceDetach(surface, parkMain = true).await())
        assertTrue(System.nanoTime() - started < 2_000_000_000L)
        sink.close()
    }

    @Test fun aDetachAfterTheDecoderShutDownReturnsAtOnce() {
        val surface = Surface(SurfaceTexture(0))
        val sink = AndroidMediaSink(surface = surface)
        sink.onVideoFrame(110, predicted)
        sink.onScreenStreamActive(110, false) // closes that stream's decoder worker
        assertTrue(sink.beginSurfaceDetach(surface, parkMain = true).await())
        sink.close()
        assertTrue(sink.beginSurfaceDetach(surface, parkMain = false).await())
    }

    @Test fun pacedFramesFromAReplacedAndANewStreamThreadDoNotRace() {
        // Callbacks of a replaced stream can overlap its successor's; pacing state lives on the worker.
        val sink = AndroidMediaSink(videoPacingDelayMillis = 90)
        val failures = ConcurrentLinkedQueue<String>()
        sink.setVideoDiagnosticHandler(110) { if ("failed" in it) failures += it }
        val errors = ConcurrentLinkedQueue<Throwable>()
        val senders = (0 until 2).map { stream ->
            thread {
                try {
                    repeat(20_000) { i ->
                        sink.onVideoFrame(110, predicted, (2L * i + stream) * 16_666_667L + 1, System.nanoTime())
                    }
                } catch (error: Throwable) {
                    errors += error
                }
            }
        }
        senders.forEach { it.join() }
        sink.close()
        assertTrue(errors.toString(), errors.isEmpty())
        assertTrue(failures.toString(), failures.isEmpty())
    }
}

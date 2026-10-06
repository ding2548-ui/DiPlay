package com.shilapi.xcertplay.transport

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbRequest
import android.os.Build
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Android 7 support for the USB request API.
 *
 * `UsbRequest.queue(ByteBuffer)` and `UsbDeviceConnection.requestWait(long)` are API 26. Android 7
 * only exposes `queue(ByteBuffer, int)` and the blocking `requestWait()`, so the timeout is
 * emulated on a dedicated worker. Cancelling the queued request still wakes the blocked platform
 * call, which is what the drain path relies on.
 *
 * One instance per USB connection: a wait that never returns only strands its own worker instead of
 * starving every later read.
 */
internal class UsbRequestCompat : Closeable {
    private val waitExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "xcertplay-usb-wait").apply { isDaemon = true }
    }

    fun queue(request: UsbRequest, buffer: ByteBuffer): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            request.queue(buffer)
        } else {
            @Suppress("DEPRECATION")
            request.queue(buffer, buffer.remaining())
        }

    /** Returns null when no request completed. Throws [TimeoutException] when [timeoutMillis] elapses. */
    fun requestWait(connection: UsbDeviceConnection, timeoutMillis: Long): UsbRequest? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return connection.requestWait(timeoutMillis)
        }
        // Reuse a wait that is still blocked after a timeout. Cancelling the queued request wakes
        // it; submitting a fresh one would only queue behind it and time out again.
        val future = inFlight
            ?: waitExecutor.submit(Callable { connection.requestWait() }).also { inFlight = it }
        return try {
            val completed = future.get(timeoutMillis, TimeUnit.MILLISECONDS)
            inFlight = null
            completed
        } catch (_: TimeoutException) {
            throw TimeoutException("USB request did not complete within ${timeoutMillis}ms")
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw TimeoutException("USB request wait was interrupted")
        } catch (failure: ExecutionException) {
            val cause = failure.cause
            if (cause is RuntimeException) throw cause
            if (cause is Error) throw cause
            throw IllegalStateException("USB request wait failed", cause)
        }
    }

    @Volatile private var inFlight: Future<UsbRequest?>? = null

    override fun close() {
        waitExecutor.shutdownNow()
    }
}

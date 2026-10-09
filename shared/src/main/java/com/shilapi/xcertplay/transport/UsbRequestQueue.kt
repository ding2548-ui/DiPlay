package com.shilapi.xcertplay.transport

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbRequest
import android.os.Build
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Queues and reaps reads on one [UsbDeviceConnection] on every supported Android version.
 *
 * API 26 added `UsbRequest.queue(ByteBuffer)` and `requestWait(timeout)`. Android 7.x has only
 * `queue(ByteBuffer, Int)` and a blocking `requestWait()`, so a helper thread reaps completions
 * there while a request is in flight, and [await] waits for them with a timeout.
 * The owner must be the only `requestWait` user on the connection, and must cancel its requests
 * before [close] so that the helper thread can finish.
 */
internal class UsbRequestQueue(private val connection: UsbDeviceConnection, threadName: String) : Closeable {
    private val reaper = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) null else {
        UsbCompletionReaper(threadName) { connection.requestWait() }
    }

    /** Queues [buffer] from its position to its limit; a completed read advances the position. */
    fun queue(request: UsbRequest, buffer: ByteBuffer): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) return request.queue(buffer)
        // The Android 7.x call fills a direct buffer from its start, whatever its position.
        require(buffer.position() == 0) { "Android 7 USB reads must start at buffer position 0" }
        @Suppress("DEPRECATION")
        val queued = request.queue(buffer, buffer.remaining())
        if (queued) reaper!!.queued()
        return queued
    }

    /**
     * Returns the next completed request, or null when Android reports a failed connection.
     * Throws [TimeoutException] when none completes within [timeoutMillis].
     */
    @Throws(TimeoutException::class)
    fun await(timeoutMillis: Long): UsbRequest? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            connection.requestWait(timeoutMillis)
        } else {
            reaper!!.await(timeoutMillis)
        }

    override fun close() {
        reaper?.close()
    }
}

/**
 * Turns a blocking "wait for any completion" call into completions that can be awaited with a
 * timeout. [reapBlocking] runs on one helper thread, and only while a queued request has not
 * completed yet: with nothing in flight it would block until the device detaches.
 * A null from [reapBlocking] means the connection failed; every later [await] then returns null.
 */
internal class UsbCompletionReaper<T : Any>(
    private val threadName: String,
    private val reapBlocking: () -> T?,
) : Closeable {
    private val lock = Object()
    private val completed = ArrayDeque<T>()
    private var inFlight = 0
    private var failed = false
    private var closed = false
    private var thread: Thread? = null

    /** Records one successfully queued request. Call it only after the request was queued. */
    fun queued() = synchronized(lock) {
        check(!closed) { "USB request reaper is closed" }
        inFlight += 1
        if (thread == null && !failed) {
            thread = Thread(::reapLoop, threadName).apply {
                isDaemon = true
                start()
            }
        }
        lock.notifyAll()
    }

    @Throws(TimeoutException::class)
    fun await(timeoutMillis: Long): T? = synchronized(lock) {
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (completed.isEmpty() && !failed) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) throw TimeoutException("No USB request completed within $timeoutMillis ms")
            TimeUnit.NANOSECONDS.timedWait(lock, remaining)
        }
        completed.removeFirstOrNull()
    }

    override fun close() = synchronized(lock) {
        closed = true
        lock.notifyAll()
    }

    private fun reapLoop() {
        while (true) {
            synchronized(lock) {
                while (inFlight == 0 && !closed) lock.wait()
                if (inFlight == 0) {
                    thread = null
                    return
                }
            }
            val request = try {
                reapBlocking()
            } catch (_: RuntimeException) {
                null
            }
            synchronized(lock) {
                if (request == null) {
                    failed = true
                    thread = null
                    lock.notifyAll()
                    return
                }
                inFlight -= 1
                completed.addLast(request)
                lock.notifyAll()
            }
        }
    }
}

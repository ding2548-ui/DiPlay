package com.shilapi.xcertplay.transport

import android.bluetooth.BluetoothSocket
import java.io.IOException
import java.util.ArrayDeque
import kotlin.math.min

/**
 * A bounded [BlockingDuplexByteStream] over an already-open RFCOMM socket.
 *
 * Android's RFCOMM input has no per-read timeout, so one daemon reader performs the blocking
 * reads. [close] closes the owned socket, which unblocks that reader.
 */
class BluetoothRfcommDuplexStream(
    private val socket: BluetoothSocket,
) : BlockingDuplexByteStream {
    private val lock = Object()
    private val sendLock = Object()
    private val input = socket.inputStream
    private val output = socket.outputStream
    private val pending = ArrayDeque<ByteArray>()
    private var pendingBytes = 0
    private var peerEnded = false
    private var closed = false
    private var socketCloseStarted = false
    private var failure: IOException? = null

    private val reader = Thread(::readLoop, "xcertplay-bluetooth-rfcomm-reader").apply {
        isDaemon = true
    }

    init {
        reader.start()
    }

    override fun send(data: ByteArray) {
        synchronized(sendLock) {
            synchronized(lock) {
                failure?.let { throw it }
                if (closed) throw IOException("Bluetooth RFCOMM stream is closed")
            }
            try {
                output.write(data)
                output.flush()
            } catch (io: IOException) {
                fail(io)
                throw io
            }
        }
    }

    override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? {
        require(maxBytes > 0) { "maxBytes must be positive" }
        require(timeoutMillis >= 0) { "timeoutMillis must not be negative" }

        val deadlineNanos = deadlineAfter(timeoutMillis)
        synchronized(lock) {
            while (true) {
                takePendingLocked(maxBytes)?.let { return it }
                failure?.let { throw it }
                if (peerEnded || closed) return EMPTY

                val remainingNanos = deadlineNanos - System.nanoTime()
                if (remainingNanos <= 0) return null
                try {
                    lock.wait(
                        remainingNanos / NANOS_PER_MILLISECOND,
                        (remainingNanos % NANOS_PER_MILLISECOND).toInt(),
                    )
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return null
                }
            }
        }
    }

    /** Closes the owned socket and waits briefly for the reader to leave its blocking read. */
    override fun close() {
        val firstClose = synchronized(lock) {
            if (closed) {
                false
            } else {
                closed = true
                lock.notifyAll()
                true
            }
        }
        if (!firstClose) return

        var closeFailure = closeSocketOnce()
        if (Thread.currentThread() !== reader) {
            try {
                reader.join(CLOSE_JOIN_MILLIS)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                closeFailure = combine(
                    closeFailure,
                    IOException("Interrupted while closing the Bluetooth RFCOMM reader", interrupted),
                )
            }
            if (reader.isAlive) {
                closeFailure = combine(
                    closeFailure,
                    IOException("Bluetooth RFCOMM reader did not stop after close"),
                )
            }
        }
        if (closeFailure != null) throw closeFailure
    }

    private fun readLoop() {
        var readFailure: IOException? = null
        try {
            while (true) {
                val readSize = synchronized(lock) {
                    while (!closed && failure == null && pendingBytes >= MAX_PENDING_BYTES) {
                        lock.wait()
                    }
                    if (closed || failure != null) return
                    min(READ_CHUNK_BYTES, MAX_PENDING_BYTES - pendingBytes)
                }

                val buffer = ByteArray(readSize)
                when (val count = input.read(buffer)) {
                    -1 -> {
                        synchronized(lock) {
                            peerEnded = true
                            lock.notifyAll()
                        }
                        return
                    }

                    0 -> Unit
                    else -> synchronized(lock) {
                        if (closed) return
                        pending.addLast(if (count == buffer.size) buffer else buffer.copyOf(count))
                        pendingBytes += count
                        lock.notifyAll()
                    }
                }
            }
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            if (!isClosed()) {
                readFailure = IOException("Bluetooth RFCOMM reader was interrupted", interrupted)
            }
        } catch (io: IOException) {
            if (!isClosed()) readFailure = io
        } catch (failure: Throwable) {
            readFailure = IOException("Bluetooth RFCOMM reader failed", failure)
            if (failure is Error) throw failure
        } finally {
            readFailure?.let(::fail)
            val closeFailure = closeSocketOnce()
            if (readFailure == null && closeFailure != null && !isClosed() && !endedCleanly()) {
                fail(closeFailure)
            }
        }
    }

    private fun takePendingLocked(maxBytes: Int): ByteArray? {
        val chunk = pending.pollFirst() ?: return null
        pendingBytes -= chunk.size
        if (chunk.size <= maxBytes) return chunk

        val head = chunk.copyOf(maxBytes)
        val tail = chunk.copyOfRange(maxBytes, chunk.size)
        pending.addFirst(tail)
        pendingBytes += tail.size
        return head
    }

    private fun fail(io: IOException) {
        synchronized(lock) {
            if (failure == null) failure = io
            lock.notifyAll()
        }
        closeSocketOnce()
    }

    private fun closeSocketOnce(): IOException? {
        synchronized(lock) {
            if (socketCloseStarted) return null
            socketCloseStarted = true
        }
        return try {
            socket.close()
            null
        } catch (failure: Throwable) {
            if (failure is Error) throw failure
            IOException("Could not close the Bluetooth RFCOMM socket", failure)
        }
    }

    private fun isClosed(): Boolean = synchronized(lock) { closed }

    private fun endedCleanly(): Boolean = synchronized(lock) { closed || peerEnded }

    private fun deadlineAfter(timeoutMillis: Long): Long {
        val now = System.nanoTime()
        val delta = timeoutMillis * NANOS_PER_MILLISECOND
        return if (Long.MAX_VALUE - now < delta) Long.MAX_VALUE else now + delta
    }

    private fun combine(first: IOException?, second: IOException): IOException {
        if (first == null) return second
        if (first !== second) first.addSuppressed(second)
        return first
    }

    private companion object {
        private const val READ_CHUNK_BYTES = 8_192
        private const val MAX_PENDING_BYTES = 65_536
        private const val CLOSE_JOIN_MILLIS = 1_000L
        private const val NANOS_PER_MILLISECOND = 1_000_000L
        private val EMPTY = ByteArray(0)
    }
}

// SPDX-License-Identifier: GPL-3.0-only
// Userspace wired networking: the NCM ethernet frames from [NcmUsbBridge] are pumped into
// the lwIP stack ([LwipNative]) instead of the kernel via VpnService, and the AirPlay
// sockets run inside lwIP. Ported from EasyPlay's LwipSessionNetwork (lifecycle, frame
// pumps and socket surface; the session link-local address is supplied by lwIP itself).
//
// EXPERIMENTAL (beta): this class is NOT wired into the live CarPlay path yet — see
// docs/EasyPlay-lwIP-移植评估.md. It requires a 32-bit process (the native library ships
// for armeabi-v7a only) and real-car testing of the frame direction logic.
package com.shilapi.xcertplay.network

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet6Address
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * @param ncm the wired USB bridge; its [NcmUsbBridge.recv]/[NcmUsbBridge.send] carry the
 *   raw NCM ethernet frames in both directions.
 */
class LwipSessionNetwork(
    private val ncm: com.shilapi.xcertplay.transport.NcmUsbBridge,
    private val onDiagnostic: (String) -> Unit = {},
    private val onError: (Throwable) -> Unit = {},
) : Closeable {
    private val running = AtomicBoolean(false)
    private var handle: Long = 0
    private var linkLocal: Inet6Address? = null
    private val pumpThreads = mutableListOf<Thread>()

    /** Starts the stack and both frame pumps. Throws when the native library is unusable. */
    fun start() {
        check(LwipNative.available) { "lwIP native library is unavailable on this ABI" }
        check(running.compareAndSet(false, true)) { "lwIP session already running" }
        try {
            handle = LwipNative.start(ncm.hostMac ?: ByteArray(6))
            val local = LwipNative.getLocalAddress(handle)
            val address = InetAddress.getByAddress(local) as? Inet6Address
            check(address != null && address.isLinkLocalAddress) {
                "lwIP must supply a session link-local IPv6 address"
            }
            linkLocal = address
            report("wired userspace NCM network started host=${address.hostAddress}")
            thread(name = "lwip-ncm-in") { receiveFrames() }
            thread(name = "lwip-ncm-out") { transmitFrames() }
        } catch (failure: Throwable) {
            running.set(false)
            runCatching { if (handle != 0L) LwipNative.stop(handle) }
            throw failure
        }
    }

    /** The session link-local address the AirPlay layer binds and advertises. */
    fun localAddress(): Inet6Address = linkLocal ?: error("lwIP session was not created")

    private fun receiveFrames() {
        var dropped = 0
        while (running.get()) {
            val frame = runCatching { ncm.recv(RECV_TIMEOUT_MILLIS) }.getOrElse {
                fail(it); return
            } ?: continue
            if (LwipNative.input(handle, frame)) {
                report("wired userspace frame direction=in bytes=${frame.size}")
            } else if (++dropped % DROP_REPORT_EVERY == 1) {
                report("wired userspace input queue full dropped=$dropped")
            }
        }
    }

    private fun transmitFrames() {
        while (running.get()) {
            val frame = LwipNative.pollOutput(handle, OUTPUT_CHUNK_BYTES) ?: continue
            if (frame.isEmpty()) continue
            report("wired userspace frame direction=out bytes=${frame.size}")
            runCatching { ncm.send(frame, SEND_TIMEOUT_MILLIS) }.getOrElse { fail(it); return }
        }
    }

    /** Raw TCP listener inside lwIP; the future AirPlay server endpoint. */
    inner class TcpListener internal constructor(private val fd: Int) : Closeable {
        fun accept(): TcpSocket {
            checkOpen()
            val client = LwipNative.accept(handle, fd)
            if (client < 0) throw SocketTimeoutException("lwIP accept timed out")
            return TcpSocket(client)
        }

        fun bind(port: Int, backlog: Int = 8) {
            checkOpen()
            LwipNative.bind(handle, fd, ANY_IPV6, port)
            LwipNative.listen(handle, fd, backlog)
        }

        fun localPort(): Int = LwipNative.getLocalPort(handle, fd)

        override fun close() {
            if (running.get()) LwipNative.close(handle, fd)
        }

        private fun checkOpen() {
            check(running.get()) { "USB IPv6 session is closed" }
        }
    }

    /** Raw TCP connection inside lwIP with stream-shaped IO. */
    inner class TcpSocket internal constructor(private val fd: Int) : Closeable {
        val input: InputStream = object : InputStream() {
            private val chunk = ByteArray(16 * 1024)
            override fun read(): Int {
                val count = read(chunk, 0, 1)
                return if (count <= 0) -1 else chunk[0].toInt() and 0xff
            }

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                check(running.get()) { "USB IPv6 session is closed" }
                val count = LwipNative.read(handle, fd, buffer, offset, length)
                return if (count <= 0) -1 else count
            }
        }

        val output: OutputStream = object : OutputStream() {
            override fun write(byte: Int) = write(byteArrayOf(byte.toByte()), 0, 1)

            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                check(running.get()) { "USB IPv6 session is closed" }
                var sent = 0
                while (sent < length) {
                    val count = LwipNative.write(handle, fd, buffer, offset + sent, length - sent)
                    if (count < 0) throw java.io.IOException("lwIP write failed")
                    sent += maxOf(count, 1)
                }
            }
        }

        fun connect(address: Inet6Address, port: Int, timeoutSeconds: Int = 10) {
            check(running.get()) { "USB IPv6 session is closed" }
            LwipNative.connect(handle, fd, address.address, port, timeoutSeconds)
        }

        fun setTcpNoDelay(enabled: Boolean) = LwipNative.setTcpNoDelay(handle, fd, enabled)

        override fun close() {
            if (running.get()) LwipNative.close(handle, fd)
        }
    }

    /** Raw UDP endpoint inside lwIP (mDNS etc.). */
    inner class UdpSocket internal constructor(private val fd: Int) : Closeable {
        fun bind(port: Int) {
            check(running.get()) { "USB IPv6 session is closed" }
            LwipNative.bind(handle, fd, ANY_IPV6, port)
            LwipNative.setReuseAddress(handle, fd, true)
        }

        fun sendto(buffer: ByteArray, address: Inet6Address, port: Int): Int =
            LwipNative.sendto(handle, fd, buffer, 0, buffer.size, address.address, port)

        /** Returns the payload length; sender address/port are written into [aux] as [port, ...]. */
        fun recvfrom(buffer: ByteArray, sender: ByteArray, aux: IntArray): Int =
            LwipNative.recvfrom(handle, fd, buffer, 0, buffer.size, sender, aux)

        fun joinGroup(address: ByteArray) = LwipNative.joinGroup(handle, address)

        fun leaveGroup(address: ByteArray) = LwipNative.leaveGroup(handle, address)

        fun setTimeout(seconds: Int) = LwipNative.setTimeout(handle, fd, seconds)

        override fun close() {
            if (running.get()) LwipNative.close(handle, fd)
        }
    }

    /** Opens a TCP listener socket inside lwIP. */
    fun tcpListener(): TcpListener {
        checkRunning()
        val fd = LwipNative.socket(handle, LwipNative.STREAM)
        check(fd >= 0) { "lwIP returned an invalid socket" }
        return TcpListener(fd)
    }

    /** Opens a TCP connection socket inside lwIP. */
    fun tcpSocket(): TcpSocket {
        checkRunning()
        val fd = LwipNative.socket(handle, LwipNative.STREAM)
        check(fd >= 0) { "lwIP returned an invalid socket" }
        return TcpSocket(fd)
    }

    /** Opens a UDP endpoint inside lwIP. */
    fun udpSocket(): UdpSocket {
        checkRunning()
        val fd = LwipNative.socket(handle, LwipNative.DGRAM)
        check(fd >= 0) { "lwIP returned an invalid socket" }
        return UdpSocket(fd)
    }

    override fun close() {
        if (!running.compareAndSet(true, false)) return
        LwipNative.stop(handle)
        handle = 0
        report("wired userspace NCM network stopped")
    }

    private fun checkRunning() {
        check(running.get()) { "USB IPv6 session is closed" }
    }

    private fun fail(failure: Throwable) {
        running.set(false)
        report("wired userspace failure exception=${failure.javaClass.simpleName}")
        onError(failure)
    }

    private fun report(message: String) {
        runCatching { onDiagnostic(message) }
    }

    private companion object {
        val ANY_IPV6 = ByteArray(16)
        const val RECV_TIMEOUT_MILLIS = 250L
        const val SEND_TIMEOUT_MILLIS = 1000
        const val OUTPUT_CHUNK_BYTES = 16 * 1024
        const val DROP_REPORT_EVERY = 64
    }
}

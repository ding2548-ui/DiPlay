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
/** The port the iPhone dials inside the lwIP stack (the AirPlay default). */
internal const val LWIP_LISTEN_PORT = 7000

class LwipSessionNetwork(
    private val ncm: com.shilapi.xcertplay.transport.NcmUsbBridge,
    private val onDiagnostic: (String) -> Unit = {},
    private val onError: (Throwable) -> Unit = {},
) : Closeable {
    private val running = AtomicBoolean(false)
    private var handle: Long = 0
    private var linkLocal: Inet6Address? = null
    private val pumpThreads = mutableListOf<Thread>()
    private val proxyListeners = mutableMapOf<Int, TcpListener>()
    private val udpProxies = mutableMapOf<Int, UdpSocket>()

    /** Starts the stack and both frame pumps. Throws when the native library is unusable. */
    /**
     * The lwIP netif MAC, exactly as EasyPlay derives it: use the bridge's host MAC when it
     * is not all zeros; otherwise a locally-administered random one (first byte gets the
     * locally-administered bit and loses the multicast bit). A zero MAC breaks IPv6 neighbor
     * discovery — the iPhone cannot resolve our link-local address, so it never opens its
     * TCP connection even though the stack and the proxy are alive (run-120 report).
     */
    private fun netifMac(): ByteArray {
        val bridged = ncm.hostMac
        if (bridged != null && bridged.any { it != 0.toByte() }) return bridged
        return ByteArray(6).also { random ->
            java.security.SecureRandom().nextBytes(random)
            random[0] = ((random[0].toInt() and 0xfe) or 0x02).toByte()
        }
    }

    fun start() {
        check(LwipNative.available) { "lwIP native library is unavailable on this ABI" }
        check(running.compareAndSet(false, true)) { "lwIP session already running" }
        try {
            handle = LwipNative.start(netifMac())
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
            // Per-frame logging lived here during the run-123..139 diagnosis; it grew the saved
            // reports by thousands of lines per session, so only anomalies remain.
            val accepted = LwipNative.input(handle, frame)
            if (!accepted && ++dropped % DROP_REPORT_EVERY == 1) {
                report("wired userspace input queue full dropped=$dropped ${describeFrame(frame)}")
            }
        }
    }

    private fun transmitFrames() {
        while (running.get()) {
            val frame = LwipNative.pollOutput(handle, OUTPUT_CHUNK_BYTES) ?: continue
            if (frame.isEmpty()) continue
            runCatching { ncm.send(frame, SEND_TIMEOUT_MILLIS) }.getOrElse { fail(it); return }
        }
    }

    /**
     * One-line protocol classification of a raw ethernet frame, so a dead lwIP session can
     * be diagnosed from the report alone: which neighbor-discovery packets arrived, whether
     * the stack answered, and whether the iPhone's TCP SYN ever reached the listener
     * (run-120/122 reports: frames flowed but nothing was ever classified).
     */
    private fun describeFrame(frame: ByteArray): String {
        if (frame.size < 14) return "short"
        val ethertype = ((frame[12].toInt() and 0xff) shl 8) or (frame[13].toInt() and 0xff)
        when (ethertype) {
            0x0806 -> return "arp"
            0x86dd -> Unit
            else -> return "ethertype=0x${ethertype.toString(16)}"
        }
        if (frame.size < 14 + 40) return "ipv6 short"
        val nextHeader = frame[14 + 6].toInt() and 0xff
        fun addressAt(offset: Int): String = runCatching {
            java.net.InetAddress.getByAddress(frame.copyOfRange(offset, offset + 16)).hostAddress ?: "?"
        }.getOrDefault("?")
        val source = addressAt(14 + 8)
        return when (nextHeader) {
            58 -> {
                if (frame.size < 14 + 40 + 1) return "ipv6 icmpv6 short"
                val type = frame[14 + 40].toInt() and 0xff
                val name = when (type) {
                    133 -> "icmpv6-rs"
                    134 -> "icmpv6-ra"
                    135 -> "icmpv6-ns"
                    136 -> "icmpv6-na"
                    128 -> "icmpv6-echo-request"
                    129 -> "icmpv6-echo-reply"
                    else -> "icmpv6-type=$type"
                }
                "$name src=$source"
            }
            6 -> {
                if (frame.size < 14 + 40 + 14) return "ipv6 tcp short"
                val destination = addressAt(14 + 24)
                val destinationPort = ((frame[14 + 40 + 2].toInt() and 0xff) shl 8) or (frame[14 + 40 + 3].toInt() and 0xff)
                val flags = frame[14 + 40 + 13].toInt() and 0xff
                val flagNames = buildList {
                    if (flags and 0x02 != 0) add("syn")
                    if (flags and 0x10 != 0) add("ack")
                    if (flags and 0x01 != 0) add("fin")
                    if (flags and 0x04 != 0) add("rst")
                    if (flags and 0x08 != 0) add("psh")
                }
                "ipv6 tcp ${flagNames.joinToString("-").ifEmpty { "flags=0x${flags.toString(16)}" }} dport=$destinationPort dst=$destination src=$source"
            }
            17 -> "ipv6 udp"
            else -> "ipv6 proto=$nextHeader"
        }
    }

    /** Raw TCP listener inside lwIP; the future AirPlay server endpoint. */
    inner class TcpListener internal constructor(internal val fd: Int) : Closeable {
        fun accept(): TcpSocket {
            checkOpen()
            val client = LwipNative.accept(handle, fd)
            if (client < 0) throw SocketTimeoutException("lwIP accept timed out")
            return TcpSocket(client).apply {
                // Without this the relayed streams stutter: lwIP's delayed ACK and the phone's
                // Nagle interact into ~1 s bursts (readMaxMs≈1000, touch2frame 80-200 ms —
                // the run-138 report). CarPlay traffic is latency-sensitive, not bulk-bound.
                setTcpNoDelay(true)
            }
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
    inner class TcpSocket internal constructor(internal val fd: Int) : Closeable {
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

    /**
     * Beta wired path: accepts iPhone TCP connections inside lwIP on [LWIP_LISTEN_PORT] and relays
     * every stream into the JVM-side AirPlay server over loopback. UDP is not relayed yet;
     *CarPlay's control/media streams over the wired NCM link are TCP.
     */
    /** The AirPlay control port: iPhone -> lwIP :7000 -> loopback relay. */
    fun startProxy(targetPort: Int) = startProxyPort(LWIP_LISTEN_PORT, targetPort)

    /**
     * Opens a relay for one more TCP port: iPhone -> lwIP :[listenPort] ->
     * loopback:[targetPort]. The AirPlay session announces extra ports as the session
     * progresses (eventPort, timing over TCP, stream data ports) — each announcement opens
     * its lwIP listener here.
     */
    @Synchronized
    fun startProxyPort(listenPort: Int, targetPort: Int = listenPort) {
        check(running.get()) { "USB IPv6 session is closed" }
        if (proxyListeners.containsKey(listenPort)) return
        val listener = tcpListener()
        LwipNative.setTimeout(handle, listener.fd, 1)
        listener.bind(listenPort)
        proxyListeners[listenPort] = listener
        thread(name = "lwip-proxy-$listenPort") {
            while (running.get()) {
                val client = try {
                    listener.accept()
                } catch (_: java.net.SocketTimeoutException) {
                    continue
                } catch (failure: Exception) {
                    if (running.get()) fail(failure)
                    return@thread
                }
                report("wired lwip proxy accepted port=$listenPort fd=${client.fd}")
                thread(name = "lwip-proxy-conn-$listenPort") { relay(client, targetPort) }
            }
        }
        report("wired lwip proxy listening port=$listenPort target=loopback:$targetPort")
    }

    private fun relay(client: TcpSocket, targetPort: Int) {
        runCatching {
            // The AirPlay listener is bound to InetAddress.getLoopbackAddress(), which resolves to
            // ::1 on this platform, so the relay has to dial the same family. A literal "127.0.0.1"
            // never reaches an IPv6-bound socket: the phone's connection would be accepted by the
            // lwIP listener and then dropped here with "Connection refused". The UDP relay below
            // already dials getLoopbackAddress().
            java.net.Socket(java.net.InetAddress.getLoopbackAddress(), targetPort)
                .apply { tcpNoDelay = true }.use { local ->
                client.use { remote ->
                    val upstream = thread {
                        runCatching { remote.input.copyTo(local.getOutputStream(), RELAY_CHUNK_BYTES) }
                        runCatching { local.shutdownOutput() }
                    }
                    runCatching { local.getInputStream().copyTo(remote.output, RELAY_CHUNK_BYTES) }
                    runCatching { remote.output.flush() }
                    upstream.join(2000)
                }
            }
        }.onFailure { if (running.get()) report("wired lwip relay ended: ${it.message}") }
    }

    /**
     * Relays one UDP port both ways: iPhone -> lwIP :[listenPort] -> loopback:[targetPort]
     * and back. Used for AirPlay timing (NTP) and keepalive — datagram protocols the TCP
     * proxy cannot carry. The phone's address is learned from the first datagram it sends
     * ("last peer"); JVM-side replies follow it, because the relayed JVM socket only ever
     * sees the loopback as its peer.
     */
    @Synchronized
    fun startUdpProxy(listenPort: Int, targetPort: Int = listenPort) {
        check(running.get()) { "USB IPv6 session is closed" }
        if (udpProxies.containsKey(listenPort)) return
        val socket = udpSocket()
        socket.bind(listenPort)
        socket.setTimeout(1)
        udpProxies[listenPort] = socket
        val local = java.net.DatagramSocket(null).apply {
            reuseAddress = true
            bind(java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0))
            soTimeout = RECV_TIMEOUT_MILLIS.toInt()
        }
        var lastPeer: Pair<ByteArray, Int>? = null
        thread(name = "lwip-udp-rx-$listenPort", isDaemon = true) {
            val buffer = ByteArray(65536)
            val sender = ByteArray(16)
            val aux = IntArray(2)
            while (running.get()) {
                val length = try {
                    socket.recvfrom(buffer, sender, aux)
                } catch (_: SocketTimeoutException) {
                    continue
                } catch (_: Exception) {
                    if (running.get()) fail(java.io.IOException("lwIP udp recvfrom failed"))
                    return@thread
                }
                if (length <= 0) continue
                lastPeer = sender.copyOf() to aux[0]
                try {
                    local.send(java.net.DatagramPacket(buffer, length, java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), targetPort)))
                } catch (_: Exception) {
                    if (running.get()) report("wired lwip udp relay rx drop port=$listenPort")
                }
            }
        }
        thread(name = "lwip-udp-tx-$listenPort", isDaemon = true) {
            val buffer = ByteArray(65536)
            while (running.get()) {
                val packet = java.net.DatagramPacket(buffer, buffer.size)
                try {
                    local.receive(packet)
                } catch (_: java.net.SocketTimeoutException) {
                    continue
                } catch (_: Exception) {
                    return@thread
                }
                val peer = lastPeer
                if (peer == null) {
                    report("wired lwip udp relay tx drop port=$listenPort (no phone peer yet)")
                    continue
                }
                try {
                    val payload = buffer.copyOf(packet.length)
                    @Suppress("UNCHECKED_CAST")
                    val address = InetAddress.getByAddress(peer.first) as Inet6Address
                    socket.sendto(payload, address, peer.second)
                } catch (_: Exception) {
                    if (running.get()) report("wired lwip udp relay tx drop port=$listenPort")
                }
            }
        }
        report("wired lwip udp proxy listening port=$listenPort target=loopback:$targetPort")
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
        return TcpSocket(fd).apply { setTcpNoDelay(true) }
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
        synchronized(proxyListeners) {
            proxyListeners.values.forEach { runCatching { it.close() } }
            proxyListeners.clear()
        }
        synchronized(udpProxies) {
            udpProxies.values.forEach { runCatching { it.close() } }
            udpProxies.clear()
        }
        LwipNative.stop(handle)
        handle = 0
        // Own the bridge like Ipv6NcmBridge does: the wired USB session dies with the stack.
        runCatching { ncm.close() }
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
        // EasyPlay parity (0xc8): pollOutput waits for up to maxBytes of pending output before
        // returning, so a large cap held tiny but urgent frames (pure ACKs, zero-window / window
        // updates) in lwIP for up to ~1 s — the phone stalled in exact ~1 s bursts (run-139
        // report: readMaxMs constant at ~1000). 200 bytes flushes every frame immediately.
        const val OUTPUT_CHUNK_BYTES = 200
        const val RELAY_CHUNK_BYTES = 16 * 1024
        const val DROP_REPORT_EVERY = 64
    }
}

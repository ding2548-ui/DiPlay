package com.shilapi.xcertplay.network

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Binder
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlayMediaHandler
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.PairingStore
import com.shilapi.xcertplay.mfi.MfiAuthenticator
import com.shilapi.xcertplay.transport.NcmUsbBridge
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hosts the AirPlay TCP listener for both NCM/VPN and local-only Wi-Fi transports.
 *
 * The wired path also owns the Android VPN tunnel and NCM IPv6 bridge. VPN consent is requested
 * with [prepare] before binding.
 */
class CarPlayVpnService : VpnService() {
    inner class LocalBinder : Binder() {
        val service: CarPlayVpnService get() = this@CarPlayVpnService
    }

    sealed class AttachResult {
        data object Started : AttachResult()
        data object AlreadyStarted : AttachResult()
        data class Failed(val message: String) : AttachResult()
    }

    private data class AirPlayAttachment(
        val address: InetAddress,
        val config: AirPlayConfig,
        val identity: AirPlayIdentity,
        val pairings: PairingStore,
        val mfi: MfiAuthenticator?,
        val listener: AirPlaySessionListener,
        val media: AirPlayMediaHandler,
    )

    private val binder = LocalBinder()
    private val active = AtomicBoolean(false)
    private val sessionsLock = Any()
    private val sessions = mutableSetOf<AirPlaySession>()
    @Volatile private var attachment: AirPlayAttachment? = null
    /** All bound listeners: the IPv6 wildcard plus an explicit IPv4 socket when needed. */
    private val serverSockets = mutableListOf<ServerSocket>()
    private var bridge: Ipv6NcmBridge? = null
    private var tun: ParcelFileDescriptor? = null
    private var attachGeneration = 0

    override fun onBind(intent: Intent?): IBinder = binder

    @Synchronized
    fun attach(
        ncm: NcmUsbBridge,
        linkLocal: String,
        hostMac: ByteArray,
        config: AirPlayConfig,
        identity: AirPlayIdentity,
        pairings: PairingStore,
        mfi: MfiAuthenticator?,
        listener: AirPlaySessionListener,
        media: AirPlayMediaHandler,
    ): AttachResult {
        if (active.get()) {
            Log.i(TAG, "replacing stale NCM/VPN attachment")
            releaseLocked()
        }
        active.set(true)
        val generation = ++attachGeneration
        return try {
            val address = InetAddress.getByName(linkLocal)
            if (address !is Inet6Address || !address.isLinkLocalAddress) {
                throw IllegalArgumentException("linkLocal must be a link-local IPv6 literal")
            }
            require(hostMac.size == 6) { "hostMac must be 6 bytes" }

            val tunFd = Builder()
                .addAddress(linkLocal, LINK_PREFIX)
                .addRoute(LINK_LOCAL_ROUTE, LINK_PREFIX)
                // The VPN only carries the link-local IPv6 route, but Android's leak-prevention
                // blocks every address family the VPN does not configure for all covered apps
                // (everyone but us). Without this line the head unit's own IPv4 internet died the
                // moment the wired VPN came up and returned only when the cable was unplugged.
                // allowFamily(AF_INET) explicitly lets IPv4 traffic of other apps use the
                // underlying network (API 21+, Android 7 included).
                .allowFamily(AF_INET)
                .setSession(SESSION_NAME)
                .setMtu(TUN_MTU)
                .setBlocking(true)
                .establish()
                ?: throw IOException("VpnService.establish returned null")
            tun = tunFd
            Log.i(TAG, "vpn tun established address=$linkLocal mtu=$TUN_MTU")

            val ipv6Bridge = Ipv6NcmBridge(ncm, tunFd, hostMac) { error ->
                onTransportError(generation, listener, error)
            }
            ipv6Bridge.start()
            bridge = ipv6Bridge
            Log.i(TAG, "ncm ipv6 bridge started hostMac=${hostMac.joinToString(":") { "%02x".format(it.toInt() and 0xff) }}")

            startAirPlayServer(
                generation,
                AirPlayAttachment(address, config, identity, pairings, mfi, listener, media),
            )
            AttachResult.Started
        } catch (error: Exception) {
            releaseLocked()
            AttachResult.Failed(error.message ?: error.javaClass.simpleName)
        }
    }

    /**
     * Starts the AirPlay listener on the local-only Wi-Fi AP address without establishing a VPN or
     * NCM bridge.
     */
    @Synchronized
    fun attachWireless(
        bindAddress: InetAddress,
        config: AirPlayConfig,
        identity: AirPlayIdentity,
        pairings: PairingStore,
        mfi: MfiAuthenticator?,
        listener: AirPlaySessionListener,
        media: AirPlayMediaHandler,
    ): AttachResult {
        if (active.get()) {
            Log.i(TAG, "replacing stale local-only Wi-Fi attachment")
            releaseLocked()
        }
        active.set(true)
        val generation = ++attachGeneration
        return try {
            startAirPlayServer(
                generation,
                AirPlayAttachment(bindAddress, config, identity, pairings, mfi, listener, media),
            )
            AttachResult.Started
        } catch (error: Exception) {
            releaseLocked()
            AttachResult.Failed(error.message ?: error.javaClass.simpleName)
        }
    }

    /** Releases the active AirPlay listener and whichever VPN/NCM transport resources are active. */
    @Synchronized
    fun detach() {
        releaseLocked()
    }

    fun isAttached(): Boolean = active.get() && attachment != null

    override fun onDestroy() {
        detach()
        super.onDestroy()
    }

    private fun startAirPlayServer(
        generation: Int,
        replacement: AirPlayAttachment,
    ) {
        val port = replacement.config.port
        val servers = mutableListOf<ServerSocket>()

        // The IPv6 wildcard. A socket bound to one link-local IPv6 address would refuse everything
        // else, so this stays the primary listener for both the wired NCM link and wireless IPv6.
        val wildcard = InetAddress.getByName("::")
        Log.i(TAG, "airplay listener bind=$wildcard port=$port " +
            "attachment=${replacement.address.hostAddress}")
        replacement.listener.onDebugLog(
            "airplay listener bind=$wildcard port=$port " +
                "attachment=${replacement.address.hostAddress}",
        )
        val server = ServerSocket()
        server.bind(InetSocketAddress(wildcard, port))
        servers.add(server)

        // `::` is NOT reliably dual-stack: whether it also accepts IPv4 is governed by
        // IPV6_V6ONLY, whose default is platform-specific. On this head unit it is set, so the
        // phone's IPv4 connection to 192.168.49.1:7000 was refused outright while the IPv6 path
        // worked — and the only symptom was a missing `airplay connection accepted from` line,
        // which is indistinguishable from the phone never dialling at all. Bind IPv4 explicitly.
        // When the wildcard *is* dual-stack this fails with "address already in use" and is simply
        // skipped, so the behaviour degrades safely.
        val ipv4 = runCatching {
            ServerSocket().apply {
                bind(InetSocketAddress(InetAddress.getByName("0.0.0.0"), port))
            }
        }.onFailure {
            Log.i(TAG, "airplay IPv4 listener not bound (wildcard may already cover it): ${it.message}")
            replacement.listener.onDebugLog(
                "airplay IPv4 listener skipped: ${it.javaClass.simpleName}",
            )
        }.getOrNull()
        if (ipv4 != null) {
            Log.i(TAG, "airplay IPv4 listener bound port=$port")
            replacement.listener.onDebugLog("airplay IPv4 listener bound port=$port")
            servers.add(ipv4)
        }

        attachment = replacement
        synchronized(this) { serverSockets.addAll(servers) }
        for ((index, bound) in servers.withIndex()) {
            Thread(
                { acceptLoop(generation, bound) },
                "airplay-accept-$index",
            ).apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun acceptLoop(
        generation: Int,
        server: ServerSocket,
    ) {
        try {
            while (active.get()) {
                val socket: Socket = server.accept()
                // The wireless bring-up probes its own port to prove the listener accepts both
                // address families. Those connections arrive here too, and without this they would
                // be logged as a real `airplay connection accepted from ...` and spin up a bogus
                // session — a false positive indistinguishable from the phone finally connecting.
                if (isLocalSource(socket.inetAddress)) {
                    Log.i(TAG, "airplay self-test connection from ${socket.remoteSocketAddress}")
                    attachment?.listener?.onDebugLog(
                        "airplay self-test connection (ours, not the phone)",
                    )
                    runCatching { socket.close() }
                    continue
                }
                Log.i(TAG, "airplay connection accepted from ${socket.remoteSocketAddress}")
                attachment?.listener?.onDebugLog(
                    "airplay connection accepted from ${socket.remoteSocketAddress}",
                )
                socket.tcpNoDelay = true
                socket.keepAlive = true
                socket.setSoLinger(true, 0)
                val session = synchronized(this) {
                    if (!active.get()) {
                        socket.close()
                        return
                    }
                    val current = attachment
                    if (current == null) {
                        socket.close()
                        return
                    }
                    AirPlaySession(
                        socket = socket,
                        config = current.config,
                        identity = current.identity,
                        pairings = current.pairings,
                        mfi = current.mfi,
                        listener = object : AirPlaySessionListener by current.listener {
                            override fun onSessionEnded(session: AirPlaySession) {
                                removeSession(session)
                                current.listener.onSessionEnded(session)
                            }
                        },
                        media = current.media,
                    ).also(::addSession)
                }
                session.start()
            }
        } catch (error: IOException) {
            if (active.get()) {
                attachment?.listener?.let { onTransportError(generation, it, error) }
            }
        }
    }

    /**
     * True when [address] belongs to one of this device's own interfaces.
     *
     * Used to tell our own reachability probe apart from a genuine remote peer: the phone's
     * address is not assigned locally, while the probe's source is by construction.
     */
    private fun isLocalSource(address: InetAddress?): Boolean {
        if (address == null) return false
        return runCatching { NetworkInterface.getByInetAddress(address) }.getOrNull() != null
    }

    private fun addSession(session: AirPlaySession) {
        synchronized(sessionsLock) { sessions.add(session) }
    }

    private fun removeSession(session: AirPlaySession?) {
        if (session == null) return
        synchronized(sessionsLock) { sessions.remove(session) }
    }

    private fun closeSessionsLocked() {
        synchronized(sessionsLock) {
            sessions.toList().forEach { session ->
                try {
                    session.close()
                } catch (error: Exception) {
                    Log.w(TAG, "AirPlay session replacement failed", error)
                }
            }
            sessions.clear()
        }
    }

    private fun onTransportError(
        generation: Int,
        listener: AirPlaySessionListener,
        error: Throwable,
    ) {
        val message = error.message ?: error.javaClass.simpleName
        Log.e(TAG, "CarPlay transport stopped: $message", error)
        Thread(
            {
                synchronized(this) {
                    if (generation != attachGeneration) return@Thread
                    releaseLocked()
                }
                listener.onTransportError(message)
                stopSelf()
            },
            "airplay-teardown",
        ).apply {
            isDaemon = true
            start()
        }
    }

    /** Caller must hold this service's monitor. Closes only resources active for this attachment. */
    private fun releaseLocked() {
        attachGeneration += 1
        active.set(false)
        attachment = null
        serverSockets.forEach { socket -> runCatching { socket.close() } }
        serverSockets.clear()
        closeSessionsLocked()
        bridge?.close()
        bridge = null
        tun?.close()
        tun = null
    }

    companion object {
        private const val TAG = "xcertplay-usb"
        private const val LINK_PREFIX = 64
        private const val LINK_LOCAL_ROUTE = "fe80::"
        private const val SESSION_NAME = "xcertplay CarPlay"
        private const val TUN_MTU = 1500
        /** IPv4 address family for [android.net.VpnService.Builder.allowFamily]. */
        private const val AF_INET = 2 // OsConstants.AF_INET

        /** Returns the VPN consent intent, or null when consent is already granted. */
        fun prepare(context: Context): Intent? = VpnService.prepare(context)
    }
}

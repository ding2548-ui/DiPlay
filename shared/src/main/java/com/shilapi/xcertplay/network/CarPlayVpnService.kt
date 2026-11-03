package com.shilapi.xcertplay.network

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlayListenerIdentity
import com.shilapi.xcertplay.airplay.AirPlayMediaHandler
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.AirPlayTcpAccepted
import com.shilapi.xcertplay.airplay.PairingStore
import com.shilapi.xcertplay.airplay.isInternalAirPlayPeer
import com.shilapi.xcertplay.mfi.MfiAuthenticator
import com.shilapi.xcertplay.transport.NcmUsbBridge
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
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
        val additionalAddresses: List<InetAddress> = emptyList(),
        val listenerIdentity: AirPlayListenerIdentity? = null,
        // The lwIP wired path relays the iPhone's TCP streams into this server over 127.0.0.1;
        // without this flag acceptLoop would drop every relayed connection as a "self-test"
        // (isLocalSource matches the loopback interface) and the session could never start.
        val loopbackRelay: Boolean = false,
        // lwIP mode: the session announces extra ports as it progresses (eventPort, timing, stream
        // data ports). Each announcement opens its lwIP relay listener here.
        val portNotifier: ((Int) -> Unit)? = null,
        /** lwIP mode: announced UDP ports (timing, keepalive) open their datagram relay here. */
        val udpPortNotifier: ((Int) -> Unit)? = null,
    )

    private val binder = LocalBinder()
    private val active = AtomicBoolean(false)
    private val sessionsLock = Any()
    private val sessions = mutableSetOf<AirPlaySession>()
    @Volatile private var attachment: AirPlayAttachment? = null
    private var serverSocket: ServerSocket? = null
    private var additionalServers: List<ServerSocket> = emptyList()
    private var bridge: Ipv6NcmBridge? = null
    private var tun: ParcelFileDescriptor? = null
    private var attachGeneration = 0

    override fun onBind(intent: Intent?): IBinder = binder

    /**
     * Which part of [attach] is running, so a failure names the step instead of only the platform
     * message. Held per thread because attach runs on a caller thread while the bridge runs on its
     * own, and cleared at the start of every attempt.
     */
    private val attachStage = ThreadLocal<String>()

    private fun stage(name: String) {
        attachStage.set(name)
    }

    private fun currentStage(): String = attachStage.get() ?: "unknown"

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
        attachStage.set("builder")
        return try {
            val address = InetAddress.getByName(linkLocal)
            if (address !is Inet6Address || !address.isLinkLocalAddress) {
                throw IllegalArgumentException("linkLocal must be a link-local IPv6 literal")
            }
            require(hostMac.size == 6) { "hostMac must be 6 bytes" }

            stage("tun")
            val tunFd = establishTun(linkLocal, listener)
                ?: throw IOException("VpnService.establish returned null")
            tun = tunFd

            // Stage markers: the failure below used to reach the caller as a bare message such as
            // "Invalid argument", which is indistinguishable from an errno string and sent the
            // investigation after establish() when the real throw was further down.
            stage("bridge")
            val ipv6Bridge = Ipv6NcmBridge(ncm, tunFd, hostMac) { error ->
                onTransportError(generation, listener, error)
            }
            ipv6Bridge.start()
            bridge = ipv6Bridge

            stage("listener")
            startAirPlayServer(
                generation,
                AirPlayAttachment(address, config, identity, pairings, mfi, listener, media),
            )
            AttachResult.Started
        } catch (error: Exception) {
            val detail = "attach failed stage=${currentStage()} ${error.javaClass.simpleName}: ${error.message}"
            Log.w(TAG, detail, error)
            // Log.w never reaches the exported report, which is the only place this is read from,
            // so the stage would be invisible exactly when it is needed.
            runCatching { listener.onDebugLog(detail) }
            releaseLocked()
            AttachResult.Failed(error.message ?: error.javaClass.simpleName)
        }
    }

    /**
     * Brings the tun up, trying the builder configurations in [TUN_VARIANTS] in order.
     *
     * Android 7 rejects a configuration this line sends with a bare EINVAL, which reaches the user
     * as "attach result=failed Invalid argument" — while the very same APK attaches cleanly on
     * Android 16 (report 876, 20:28:42: `attach result=started` 48 ms after the service bound).
     * The message names neither the knob nor the step, so the variants are walked and each outcome
     * is recorded: the report then says which knob Android 7 dislikes, and if a later variant is
     * accepted the wired path comes up instead of the whole attach failing.
     */
    private fun establishTun(linkLocal: String, listener: AirPlaySessionListener): ParcelFileDescriptor? {
        var lastError: Exception? = null
        for (variant in TUN_VARIANTS) {
            val builder = Builder().addAddress(linkLocal, LINK_PREFIX)
            when (variant) {
                // What this line has always sent. allowFamily(AF_INET) is there so the platform does
                // not treat IPv4 as an unconfigured family while apps are covered; setMtu and
                // setBlocking are the upstream defaults.
                TUN_FULL -> builder
                    .addRoute(LINK_LOCAL_ROUTE, LINK_PREFIX)
                    .allowFamily(AF_INET)
                    .setSession(SESSION_NAME)
                    .setMtu(TUN_MTU)
                    .setBlocking(true)
                TUN_NO_ALLOW_FAMILY -> builder
                    .addRoute(LINK_LOCAL_ROUTE, LINK_PREFIX)
                    .setSession(SESSION_NAME)
                    .setMtu(TUN_MTU)
                    .setBlocking(true)
                else -> builder
                    .addRoute(LINK_LOCAL_ROUTE, LINK_PREFIX)
                    .setSession(SESSION_NAME)
            }
            applyAllowlist(builder)?.let { throw it }
            try {
                val tun = builder.establish()
                if (tun == null) {
                    lastError = IOException("VpnService.establish returned null")
                    reportTo(listener, "vpn establish variant=$variant result=null")
                    continue
                }
                reportTo(listener, "vpn tun established variant=$variant address=$linkLocal")
                return tun
            } catch (error: Exception) {
                lastError = error
                reportTo(
                    listener,
                    "vpn establish variant=$variant rejected " +
                        "${error.javaClass.simpleName}: ${error.message}",
                )
            }
        }
        lastError?.let { throw it }
        return null
    }

    /**
     * Restricts the VPN to this package. Covering every app swallowed other apps' link-local IPv6
     * whenever the wired VPN was up. The API was renamed between SDK levels — the Android 7 head
     * unit only has addAllowedPackage, the SDK this module compiles against only carries
     * addAllowedApplication — so neither name can be called directly. Returns the failure to throw,
     * or null on success.
     */
    private fun applyAllowlist(builder: Builder): Exception? {
        val allowlisted = runCatching {
            Builder::class.java.getMethod("addAllowedPackage", String::class.java)
                .invoke(builder, packageName)
        }.recoverCatching {
            try {
                Builder::class.java.getMethod("addAllowedApplication", String::class.java)
                    .invoke(builder, packageName)
            } catch (application: java.lang.reflect.InvocationTargetException) {
                throw application.cause ?: application
            }
        }
        val cause = allowlisted.exceptionOrNull() ?: return null
        // A missing method on both sides would leave the VPN scoped to every app, which is exactly
        // what this allowlist exists to prevent, so refuse instead of continuing.
        if (cause is NoSuchMethodException) return IOException("VPN allowlist is unavailable", cause)
        return cause as? Exception ?: IOException("VPN allowlist failed", cause)
    }

    private fun reportTo(listener: AirPlaySessionListener, message: String) {
        Log.i(TAG, message)
        runCatching { listener.onDebugLog(message) }
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
        additionalBindAddresses: List<InetAddress> = emptyList(),
        listenerIdentity: AirPlayListenerIdentity? = null,
        // The lwIP wired path relays the iPhone's TCP streams into this server over 127.0.0.1;
        // without this flag acceptLoop would drop every relayed connection as a "self-test"
        // (isLocalSource matches the loopback interface) and the session could never start.
        loopbackRelay: Boolean = false,
        // lwIP mode: the session announces extra ports as it progresses (eventPort, timing, stream
        // data ports). Each announcement opens its lwIP relay listener here.
        portNotifier: ((Int) -> Unit)? = null,
        /** lwIP mode: announced UDP ports (timing, keepalive) open their datagram relay here. */
        udpPortNotifier: ((Int) -> Unit)? = null,
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
                AirPlayAttachment(bindAddress, config, identity, pairings, mfi, listener, media,
                    additionalBindAddresses, listenerIdentity,
                    loopbackRelay, portNotifier, udpPortNotifier),
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

    @Synchronized
    fun detachWireless(owner: AirPlayListenerIdentity) {
        if (attachment?.listenerIdentity === owner) releaseLocked()
    }

    fun isAttached(): Boolean = active.get() && attachment != null

    /** Port the AirPlay listener actually bound, which may differ from the configured port. */
    fun boundPort(): Int? = attachment?.config?.port

    override fun onDestroy() {
        detach()
        super.onDestroy()
    }

    private fun startAirPlayServer(
        generation: Int,
        replacement: AirPlayAttachment,
    ) {
        // A bare link-local IPv6 literal carries no scope id, and bind() on a scoped address without
        // one fails with EINVAL. The wired VPN path hands us exactly that: fe80::2 out of the
        // runtime config, which surfaced as "attach result=failed Invalid argument" only after the
        // port selector had walked every fallback port.
        //
        // The substitution is only safe when this is the ONLY address. bindAll has to put every
        // address on one port, and the IPv6 wildcard overlaps all of them, so a set containing it
        // can never be satisfied: run 193 applied the wildcard across the multi-address branch too
        // and every wireless attempt died with "No common AirPlay port available for the selected
        // interface addresses" (the wireless host addresses always include a link-local IPv6).
        val servers = if (replacement.additionalAddresses.isEmpty()) {
            listOf(
                AirPlayPortSelector.bind(replacement.address.wildcardWhenScoped(), replacement.config.port) { busy, bound ->
                    Log.w(TAG, "AirPlay port $busy is in use; listening on $bound instead")
                },
            )
        } else {
            AirPlayPortSelector.bindAll(
                listOf(replacement.address) + replacement.additionalAddresses,
                replacement.config.port) { busy, bound ->
                Log.w(TAG, "AirPlay port $busy is in use; listening on $bound instead")
            }
        }
        val server = servers.first()
        attachment = replacement.copy(config = replacement.config.copy(port = server.localPort))
        serverSocket = server
        additionalServers = servers.drop(1)
        servers.forEach { bound ->
            runCatching { replacement.listener.onDebugLog(
                "airplay listener ready family=${if (bound.inetAddress is Inet6Address) "IPv6" else "IPv4"} " +
                    "port=${bound.localPort} bind=${bound.inetAddress.hostAddress}",
            ) }
            Thread(
                { acceptLoop(generation, bound) },
                "airplay-accept",
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
                val acceptedAtNanos = System.nanoTime()
                Log.i(TAG, "airplay connection accepted from ${socket.remoteSocketAddress}")
                socket.tcpNoDelay = true
                socket.keepAlive = true
                socket.setSoLinger(true, 0)
                val session = synchronized(this) {
                    if (!active.get() || generation != attachGeneration ||
                        (serverSocket !== server && additionalServers.none { it === server })) {
                        socket.close()
                        return
                    }
                    val current = attachment
                    if (current == null) {
                        socket.close()
                        return
                    }
                    // In lwIP relay mode every connection arrives from 127.0.0.1 (the relay
                    // itself), which isInternalAirPlayPeer would classify as one of our own
                    // probes, so the classification is skipped for that attachment.
                    val internalPeer = !current.loopbackRelay &&
                        isInternalAirPlayPeer(socket.inetAddress, socket.localAddress)
                    current.listenerIdentity?.let { owner ->
                        current.listener.onTcpAccepted(AirPlayTcpAccepted(
                            owner, internalPeer, acceptedAtNanos,
                        ))
                    }
                    runCatching { current.listener.onDebugLog(
                        "airplay TCP accepted family=${if (socket.inetAddress is Inet6Address) "IPv6" else "IPv4"}",
                    ) }
                    AirPlaySession(
                        socket = socket,
                        config = current.config,
                        identity = current.identity,
                        pairings = current.pairings,
                        mfi = current.mfi,
                        listener = object : AirPlaySessionListener by current.listener {
                            override fun onSessionActive(session: AirPlaySession) {
                                if (current.listenerIdentity == null || !internalPeer) current.listener.onSessionActive(session)
                            }

                            override fun onRemoteControlMessage(
                                session: AirPlaySession,
                                streamId: Long,
                                message: Map<String, Any?>,
                            ) {
                                current.listener.onRemoteControlMessage(session, streamId, message)
                            }

                            override fun onVideoPlaybackUiRequested(session: AirPlaySession) {
                                current.listener.onVideoPlaybackUiRequested(session)
                            }

                            override fun onSessionEnded(session: AirPlaySession) {
                                removeSession(session)
                                if (current.listenerIdentity == null || !internalPeer) current.listener.onSessionEnded(session)
                            }
                        },
                        media = current.media,
                        loopbackBind = current.loopbackRelay,
                        portNotifier = current.portNotifier,
                        udpPortNotifier = current.udpPortNotifier,
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
                val releasedGeneration = synchronized(this) {
                    if (generation != attachGeneration) return@Thread
                    releaseLocked()
                    attachGeneration
                }
                listener.onTransportError(message)
                synchronized(this) {
                    if (attachGeneration == releasedGeneration && !active.get()) stopSelf()
                }
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
        serverSocket?.close()
        serverSocket = null
        additionalServers.forEach { it.close() }
        additionalServers = emptyList()
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

        /** Builder configurations tried in order by [establishTun]; see there for the reasoning. */
        private val TUN_VARIANTS = listOf(TUN_FULL, TUN_NO_ALLOW_FAMILY, TUN_MINIMAL)
        private const val TUN_FULL = "full"
        private const val TUN_NO_ALLOW_FAMILY = "no-allow-family"
        private const val TUN_MINIMAL = "minimal"

        /** The IPv6 wildcard, substituted for scoped addresses that cannot be bound directly. */
        private val WILDCARD_IPV6: InetAddress = InetAddress.getByName("::")

        /** Returns the VPN consent intent, or null when consent is already granted. */
        fun prepare(context: Context): Intent? = VpnService.prepare(context)
    }

    /**
     * A link-local IPv6 literal parsed from a string has no scope id, and the kernel rejects
     * `bind()` on a scoped address without one with EINVAL. The VPN path's listener address comes
     * straight from the runtime config ("fe80::2"), so it always hits that; the wildcard covers
     * the address without needing to resolve its interface first.
     */
    private fun InetAddress.wildcardWhenScoped(): InetAddress =
        if (this is Inet6Address && isLinkLocalAddress) WILDCARD_IPV6 else this
}

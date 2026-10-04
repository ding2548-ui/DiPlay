package com.shilapi.xcertplay.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlayInfoPlist
import java.io.BufferedReader
import java.io.Closeable
import java.io.IOException
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener

data class CarPlayBonjourEndpoint(
    val serviceName: String,
    val host: String,
    val port: Int,
    val bluetoothId: String?,
)

sealed interface CarPlayBonjourEvent {
    data class Discovery(
        val stage: Stage,
        val serviceType: String? = null,
        val serviceName: String? = null,
        val ipv4Count: Int = 0,
        val ipv6Count: Int = 0,
        /**
         * A short, non-identifying fact that belongs in saved reports (for example which interface
         * mDNS bound to). Unlike [serviceName] this IS rendered by [diagnosticSummary], so anything
         * put here must stay free of phone names, addresses, and pairing identifiers.
         */
        val detail: String? = null,
    ) : CarPlayBonjourEvent {
        enum class Stage {
            ADDED,
            RESOLVED,
            REMOVED,
            NO_MATCHING_ADDRESS,
            INVALID_PORT,

            /** `JmDNS.create` returned; the `_airplay._tcp` publication is about to be attempted. */
            MDNS_STARTED,

            /** Our own `_airplay._tcp` publication was rejected — the phone can never find us. */
            REGISTRATION_FAILED,
        }
    }
    data class Resolved(val endpoint: CarPlayBonjourEndpoint) : CarPlayBonjourEvent

    data class ProbeProgress(val stage: Stage, val attempt: Int, val ipv6: Boolean) : CarPlayBonjourEvent {
        enum class Stage { CONNECTING, TCP_CONNECTED, REQUEST_SENT }
    }
    data class ProbeFailed(val stage: ProbeProgress.Stage, val attempt: Int, val error: IOException) : CarPlayBonjourEvent

    data class Probed(
        val endpoint: CarPlayBonjourEndpoint,
        val attempts: Int,
        val statusLine: String?,
        val error: IOException?,
    ) : CarPlayBonjourEvent
}

/**
 * True when this stage means we observed the phone's *own* service.
 *
 * `MDNS_STARTED` is our own publication and `REGISTRATION_FAILED` is a local failure, so neither
 * says anything about the phone. Counting them made the bring-up verdict report "connect"
 * (discovered, then refused) on runs where nothing had been discovered at all — a misreading that
 * points the investigation at the wrong layer.
 */
val CarPlayBonjourEvent.Discovery.Stage.countsAsPhoneDiscovery: Boolean
    get() = when (this) {
        CarPlayBonjourEvent.Discovery.Stage.ADDED,
        CarPlayBonjourEvent.Discovery.Stage.RESOLVED,
        CarPlayBonjourEvent.Discovery.Stage.REMOVED,
        CarPlayBonjourEvent.Discovery.Stage.NO_MATCHING_ADDRESS,
        CarPlayBonjourEvent.Discovery.Stage.INVALID_PORT,
        -> true

        CarPlayBonjourEvent.Discovery.Stage.MDNS_STARTED,
        CarPlayBonjourEvent.Discovery.Stage.REGISTRATION_FAILED,
        -> false
    }

/**
 * Saved reports need discovery outcomes without phone names, addresses, or pairing identifiers.
 *
 * The [CarPlayBonjourEvent.Discovery] summary keeps the *service type* but deliberately drops the
 * instance name: the type is what tells "the phone never published `_carplay-ctrl._tcp`" apart
 * from "the phone published it and we failed to resolve it", and the type carries no personal
 * data. The instance name is often the owner's iPhone name, so it only reaches the live log.
 * [CarPlayBonjourEvent.Discovery.detail] is the escape hatch for report-safe facts.
 */
fun CarPlayBonjourEvent.diagnosticSummary(): String = when (this) {
    is CarPlayBonjourEvent.Discovery -> buildString {
        append("control discovery stage=").append(stage)
        serviceType?.let { append(" type=").append(it) }
        detail?.let { append(" ").append(it) }
        append(" ipv4=").append(ipv4Count).append(" ipv6=").append(ipv6Count)
    }
    is CarPlayBonjourEvent.Resolved ->
        "control resolved family=${if (':' in endpoint.host) "IPv6" else "IPv4"} port=${endpoint.port}"
    is CarPlayBonjourEvent.ProbeProgress ->
        "control probe stage=$stage attempt=$attempt family=${if (ipv6) "IPv6" else "IPv4"}"
    is CarPlayBonjourEvent.ProbeFailed ->
        "control probe failed after=$stage attempt=$attempt failureClass=${error.javaClass.simpleName}"
    is CarPlayBonjourEvent.Probed -> {
        val status = statusLine?.let { Regex("^HTTP/\\d(?:\\.\\d)? (\\d{3})(?: |$)").find(it)?.groupValues?.get(1) }
        "control probe attempts=$attempts status=${status ?: "none"} error=${error?.javaClass?.simpleName ?: "none"}"
    }
}

/** Pure protocol values shared by the Android runtime and JVM tests. */
object CarPlayBonjourProtocol {
    /**
     * The AirPlay feature bits advertised in the `_airplay._tcp` TXT record.
     *
     * This must agree with the `features` value of the AirPlay `/info` response, otherwise iOS
     * sees a receiver that is not CarPlay-capable during Bonjour discovery and never opens the
     * AirPlay connection at all. `AirPlayInfoPlist.CARPLAY_FEATURES` is the single source of truth.
     *
     * Apple's own receiver builds the TXT value in `AirPlayReceiverServer.c`:
     *
     * ```
     * u32 = (uint32_t)( ( features >> 32 ) & 0xFFFFFFFF );
     * if( u32 != 0 ) snprintf( "0x%X,0x%X", (uint32_t)( features & 0xFFFFFFFF ), u32 );
     * else          snprintf( "0x%X",    (uint32_t)( features & 0xFFFFFFFF ) );
     * ```
     *
     * so the record is `<low 32 bits>,<high 32 bits>` — the 64-bit value is *split*, not printed
     * whole. A whole-value first field hides every bit above 31 from iOS, which is exactly where
     * `kAirPlayFeature_Car` (bit 32, `0x100000000`, "Car support") lives.
     */
    internal fun airPlayFeaturesTxt(features: Long): String {
        val low = features and 0xFFFF_FFFFL
        val high = (features ushr 32) and 0xFFFF_FFFFL
        return if (high != 0L) {
            "0x%X,0x%X".format(low, high)
        } else {
            "0x%X".format(low)
        }
    }

    fun airPlayTxtRecords(
        config: AirPlayConfig,
        identity: AirPlayIdentity,
    ): Map<String, String> = linkedMapOf(
        "deviceid" to config.deviceId,
        "features" to airPlayFeaturesTxt(AirPlayInfoPlist.CARPLAY_FEATURES),
        "flags" to "0x4",
        "model" to config.model,
        "srcvers" to config.sourceVersion,
        "protovers" to "1.1",
        // "pi" (pairing ID) is deliberately NOT advertised: Apple's own receivers never put it in
        // the TXT (AirPlayReceiverServer.c sets only deviceid/features/flags/model/protovers/
        // srcvers/fv/pk). A nonstandard pi lets iOS treat the receiver as paired to a different
        // controller and silently skip the service.
        "pk" to identity.publicKeyHex,
    )

    fun connectProbeRequest(
        host: String,
        port: Int,
        sourceVersion: String,
        deviceId: String,
    ): String {
        val unbracketedHost = host.removeSurrounding("[", "]").substringBefore('%')
        require(unbracketedHost.isNotBlank()) { "host must not be blank" }
        require(port in 1..65535) { "port must be in 1..65535" }
        require(sourceVersion.isNotEmpty()) { "sourceVersion must not be empty" }
        require('\r' !in sourceVersion && '\n' !in sourceVersion) {
            "sourceVersion must not contain a line break"
        }
        require('\r' !in unbracketedHost && '\n' !in unbracketedHost) {
            "host must not contain a line break"
        }
        val receiverDeviceId = deviceId.replace(":", "")
        require(receiverDeviceId.isNotEmpty()) { "deviceId must contain a hexadecimal value" }
        require('\r' !in receiverDeviceId && '\n' !in receiverDeviceId) {
            "deviceId must not contain a line break"
        }
        val hostHeader = if (':' in unbracketedHost) {
            "[$unbracketedHost]:$port"
        } else {
            "$unbracketedHost:$port"
        }
        return "GET /ctrl-int/1/connect HTTP/1.1\r\n" +
            "Host: $hostHeader\r\n" +
            "User-Agent: AirPlay/$sourceVersion\r\n" +
            "AirPlay-Receiver-Device-ID: $receiverDeviceId\r\n" +
            "Connection: close\r\n" +
            "\r\n"
    }
}

/**
 * Publishes the accessory AirPlay service and discovers the iPhone's CarPlay control service.
 *
 * The NSD callbacks only enqueue work. Resolution, probing, and [onEvent] all run on the worker
 * started by [start], so a blocking consumer callback never runs on the caller or main thread.
 */
class CarPlayBonjour(
    context: Context,
    private val config: AirPlayConfig,
    private val identity: AirPlayIdentity,
    private val advertisedHost: String? = null,
    private val useInterfaceMdns: Boolean = false,
    private val onEvent: (CarPlayBonjourEvent) -> Unit = {},
    /**
     * Extra addresses to publish on, beyond [advertisedHost].
     *
     * A JmDNS instance is bound to one address and therefore joins only that family's multicast
     * group, so a receiver published over link-local IPv6 alone is invisible to a peer that browses
     * over IPv4 (224.0.0.251). Wireless CarPlay never got past discovery until both families were
     * published; the interface normally holds both a link-local IPv6 and a 192.168.49.x address.
     */
    advertisedHosts: List<String> = emptyList(),
) : Closeable {
    private val addedCount = AtomicInteger()
    private val resolvedCount = AtomicInteger()
    private val addressMismatchCount = AtomicInteger()
    private val probeCount = AtomicInteger()
    private val successfulProbeCount = AtomicInteger()
    private val lastProbe = AtomicReference("not_started")

    /** Includes zero counts so a silent discovery interval is visible in exported reports. */
    fun diagnosticSnapshot(): String =
        "bonjourAdded=${addedCount.get()} bonjourResolved=${resolvedCount.get()} " +
            "bonjourAddressMismatch=${addressMismatchCount.get()} connectProbes=${probeCount.get()} " +
            "connectProbe2xx=${successfulProbeCount.get()} lastProbe=${lastProbe.get()}"

    /**
     * Resolved lazily: the interface-mDNS path never touches the platform NSD registry, and a
     * device whose NSD service is missing would otherwise throw in the constructor and kill the
     * whole wireless bring-up before the `_airplay._tcp` record was ever published.
     */
    private val nsdManager: NsdManager by lazy {
        (context.applicationContext ?: context).getSystemService(Context.NSD_SERVICE) as NsdManager
    }
    private val services = LinkedBlockingQueue<NsdServiceInfo>()
    private val interfaceServices = LinkedBlockingQueue<Pair<CarPlayBonjourEndpoint, InetAddress>>()

    /**
     * Every discovery outcome of the interface-mDNS path flows through this single queue. Keeping
     * one queue means the worker preserves the real ordering between "service added" and "service
     * resolved", which is what makes a stuck discovery readable in the saved report.
     */
    private val interfaceEvents = LinkedBlockingQueue<CarPlayBonjourEvent.Discovery>(64)
    private val seenServices = ConcurrentHashMap.newKeySet<String>()
    private val lifecycleLock = Any()

    /**
     * Every address this instance publishes on, primary first. One JmDNS per address is what makes
     * the advertisement visible to a peer browsing either address family.
     *
     * The primary is parsed strictly because AirPlay binds to it; the extras are best-effort, so a
     * single unusable literal cannot sink the whole wireless bring-up. Duplicates are collapsed by
     * raw address bytes while preferring a scoped form: the same link-local IPv6 can arrive both
     * with and without its scope, and only the scoped form can join the multicast group.
     */
    private val advertisedAddresses: List<InetAddress> = run {
        val primary = advertisedHost
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let(::parseAdvertisedHost)
        val extras = advertisedHosts.mapNotNull { value ->
            runCatching { parseAdvertisedHost(value) }.getOrNull()
        }
        val byBytes = LinkedHashMap<String, InetAddress>()
        for (address in listOfNotNull(primary) + extras) {
            val key = address.address.joinToString("") { "%02x".format(it.toInt() and 0xff) }
            val existing = byBytes[key]
            val existingUnscoped = existing is Inet6Address && existing.scopeId == 0
            if (existing == null || existingUnscoped) byBytes[key] = address
        }
        byBytes.values.toList()
    }

    /** Primary address: scopes resolved IPv6 peers and sources the connect probe. */
    private val primaryAdvertisedAddress = advertisedAddresses.firstOrNull()
    private val multicastLock = (context.applicationContext ?: context)
        .getSystemService(WifiManager::class.java)
        .createMulticastLock("carplay-bonjour").apply { setReferenceCounted(false) }

    private var started = false
    @Volatile
    private var closed = false
    private var registrationRequested = false
    private var discoveryRequested = false
    @Volatile
    private var worker: Thread? = null
    @Volatile
    private var activeSocket: Socket? = null
    /** One JmDNS per advertised address family; all are closed together. */
    private val interfaceMdns = mutableListOf<JmDNS>()

    /** What [start] registered on each JmDNS, so [reannounce] can replay it. */
    private val interfaceRegistrations = mutableListOf<Pair<JmDNS, ServiceInfo>>()

    private val interfaceListener = object : ServiceListener {
        override fun serviceAdded(event: ServiceEvent) {
            if (closed) return
            // Always re-query: JmDNS caches TXT/address records across sessions, and a record the
            // phone cached before the last features/port change would send it to a dead endpoint.
            event.dns.requestServiceInfo(event.type, event.name, true)
            interfaceEvents.offer(
                CarPlayBonjourEvent.Discovery(
                    CarPlayBonjourEvent.Discovery.Stage.ADDED,
                    serviceType = event.type,
                    serviceName = event.name,
                ),
            )
        }

        override fun serviceRemoved(event: ServiceEvent) {
            seenServices.remove(event.name)
            if (closed) return
            interfaceEvents.offer(
                CarPlayBonjourEvent.Discovery(
                    CarPlayBonjourEvent.Discovery.Stage.REMOVED,
                    serviceType = event.type,
                    serviceName = event.name,
                ),
            )
        }

        override fun serviceResolved(event: ServiceEvent) {
            if (closed) return
            val info = event.info
            // Keep the HTTP probe in the same address family as a family we actually publish on;
            // otherwise the probe would source from an interface the phone cannot answer on.
            val address = info.inetAddresses.firstOrNull { candidate ->
                advertisedAddresses.any { (it is Inet4Address) == (candidate is Inet4Address) }
            }?.let(::applyLocalScope)
            val stage = when {
                address == null -> CarPlayBonjourEvent.Discovery.Stage.NO_MATCHING_ADDRESS
                info.port !in 1..65535 -> CarPlayBonjourEvent.Discovery.Stage.INVALID_PORT
                else -> null
            }
            if (stage != null) {
                interfaceEvents.offer(CarPlayBonjourEvent.Discovery(
                    stage,
                    serviceType = event.type,
                    serviceName = event.name,
                    ipv4Count = info.inetAddresses.count { it is Inet4Address },
                    ipv6Count = info.inetAddresses.count { it is Inet6Address },
                ))
                return
            }
            interfaceEvents.offer(CarPlayBonjourEvent.Discovery(
                CarPlayBonjourEvent.Discovery.Stage.RESOLVED,
                serviceType = event.type,
                serviceName = event.name,
                ipv4Count = info.inetAddresses.count { it is Inet4Address },
                ipv6Count = info.inetAddresses.count { it is Inet6Address },
            ))
            if (!seenServices.add(event.name)) return
            val endpoint = CarPlayBonjourEndpoint(
                event.name, address!!.hostAddress ?: return, info.port,
                info.getPropertyString("id"),
            )
            interfaceServices.offer(endpoint to address)
        }
    }

    /**
     * The AirPlay TXT record has to reach the p2p interface before the phone will open its AirPlay
     * socket, so a failed *publication* is fatal to the whole bring-up and must be visible in the
     * report. This listener only fires on the platform-NSD path; the interface-mDNS path catches
     * its own failures where it registers the service.
     */
    private val registrationListener = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(serviceInfo: NsdServiceInfo) = Unit

        override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            Log.w(TAG, "AirPlay NSD registration failed code=$errorCode")
            if (!closed) {
                interfaceEvents.offer(
                    CarPlayBonjourEvent.Discovery(
                        CarPlayBonjourEvent.Discovery.Stage.REGISTRATION_FAILED,
                        serviceType = serviceInfo.serviceType,
                    ),
                )
            }
        }

        override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit

        override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            Log.w(TAG, "AirPlay NSD unregistration failed code=$errorCode")
        }
    }

    private val discoveryListener = object : NsdManager.DiscoveryListener {
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.w(TAG, "CarPlay control discovery failed code=$errorCode")
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.w(TAG, "CarPlay control discovery stop failed code=$errorCode")
        }

        override fun onDiscoveryStarted(serviceType: String) = Unit

        override fun onDiscoveryStopped(serviceType: String) = Unit

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            if (closed) return
            val name = serviceInfo.serviceName ?: return
            val type = serviceInfo.serviceType ?: CARPLAY_CONTROL_SERVICE_TYPE
            val key = "$type|$name"
            if (!seenServices.add(key)) return
            services.offer(serviceInfo)
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            val name = serviceInfo.serviceName ?: return
            val type = serviceInfo.serviceType ?: CARPLAY_CONTROL_SERVICE_TYPE
            seenServices.remove("$type|$name")
        }
    }

    /** Starts publication and discovery. Calling this more than once is harmless. */
    fun start() {
        synchronized(lifecycleLock) {
            check(!closed) { "CarPlayBonjour is closed" }
            if (started) return
            started = true
            try {
                multicastLock.acquire()
                if (useInterfaceMdns) {
                    check(advertisedAddresses.isNotEmpty()) {
                        "Interface mDNS requires a local advertised address"
                    }
                    val txtFeatures = CarPlayBonjourProtocol
                        .airPlayTxtRecords(config, identity)["features"]
                    // One JmDNS per address family. A single instance joins only its own family's
                    // multicast group, so publishing on link-local IPv6 alone leaves an IPv4
                    // browser (224.0.0.251) unable to see us at all.
                    for (address in advertisedAddresses) {
                        val dns = JmDNS.create(address, "carplay-${config.deviceId.replace(":", "")}")
                        interfaceMdns.add(dns)
                        // `JmDNS.create` returning does NOT prove the mDNS sockets exist: JmDNS
                        // opens its multicast sockets lazily and swallows the failure, so a dead
                        // registry looks identical to a started one from the outside. Log what it
                        // actually bound to, so a silent bind failure shows up in the report.
                        // `interface` is a soft keyword in Kotlin, so the property must be reached
                        // through its getter — `dns.interface` does not parse. Note the getter
                        // returns the bound InetAddress, not a NetworkInterface, so the NIC name
                        // has to be looked up from the address.
                        val boundInterface = runCatching { dns.getInterface() }.getOrNull()
                        val boundInterfaceText = boundInterface
                            ?.let { address ->
                                runCatching { NetworkInterface.getByInetAddress(address) }
                                    .getOrNull()?.name
                            }
                            ?: boundInterface?.hostAddress
                            ?: "unavailable"
                        interfaceEvents.offer(CarPlayBonjourEvent.Discovery(
                            CarPlayBonjourEvent.Discovery.Stage.MDNS_STARTED,
                            serviceType = "$AIRPLAY_SERVICE_TYPE.local.",
                            // `detail`, not `serviceName`: serviceName is stripped from saved
                            // reports (it is usually the owner's phone name), and this fact is the
                            // most useful line when the phone never opens the AirPlay connection.
                            // The *name* is logged rather than the address: it survives the report's
                            // address redaction and is what proves JmDNS bound to p2p0 at all.
                            detail = "published=${familyLabel(address)} mdns-iface=$boundInterfaceText",
                        ))
                        dns.addServiceListener("$CARPLAY_CONTROL_SERVICE_TYPE.local.", interfaceListener)
                        val info = ServiceInfo.create(
                            "$AIRPLAY_SERVICE_TYPE.local.", config.deviceName, config.port,
                            0, 0, CarPlayBonjourProtocol.airPlayTxtRecords(config, identity),
                        )
                        dns.registerService(info)
                        interfaceRegistrations.add(dns to info)
                        Log.i(
                            TAG,
                            "mDNS published _airplay._tcp on ${address.hostAddress} " +
                                "boundTo=$boundInterfaceText features=$txtFeatures",
                        )
                    }
                } else {
                    registerAirPlay()
                    registrationRequested = true
                    nsdManager.discoverServices(
                        CARPLAY_CONTROL_SERVICE_TYPE,
                        NsdManager.PROTOCOL_DNS_SD,
                        discoveryListener,
                    )
                    discoveryRequested = true
                }
                worker = Thread(::runWorker, WORKER_NAME).apply {
                    isDaemon = true
                    start()
                }
            } catch (error: Exception) {
                closed = true
                if (registrationRequested) {
                    registrationRequested = false
                    runCatching { nsdManager.unregisterService(registrationListener) }
                }
                if (discoveryRequested) {
                    discoveryRequested = false
                    runCatching { nsdManager.stopServiceDiscovery(discoveryListener) }
                }
                worker?.interrupt()
                worker = null
                interfaceMdns.toList().forEach { dns -> runCatching { dns.close() } }
                interfaceMdns.clear()
                if (multicastLock.isHeld) multicastLock.release()
                throw error
            }
        }
    }

    /**
     * Replays the registration-time announcement.
     *
     * The announcement JmDNS sends on [start] goes out before ANY phone has joined the hotspot
     * group, so it is necessarily lost — a client that joins later only learns about us if it
     * sends a query (and if that query survives the P2P group-owner multicast path). Re-announcing
     * the moment a client joins gives passive listeners a second chance without waiting for them
     * to query. Unregister+register is the only public JmDNS replay path: the unregister sends a
     * goodbye (TTL 0) on the same multicast path that is otherwise silent, so the risk window is
     * negligible compared with the discovery failure it is trying to break.
     */
    fun reannounce() {
        synchronized(lifecycleLock) {
            if (closed || !started) return
            for ((dns, info) in interfaceRegistrations.toList()) {
                runCatching {
                    dns.unregisterService(info)
                    dns.registerService(info)
                }.onFailure {
                    Log.i(TAG, "mDNS re-announce failed on ${dns.name}: $it")
                }
            }
        }
    }

    override fun close() {
        val workerToJoin: Thread?
        val dnsToClose: List<JmDNS>
        synchronized(lifecycleLock) {
            if (closed) return
            closed = true
            if (registrationRequested) {
                registrationRequested = false
                runCatching { nsdManager.unregisterService(registrationListener) }
            }
            if (discoveryRequested) {
                discoveryRequested = false
                runCatching { nsdManager.stopServiceDiscovery(discoveryListener) }
            }
            activeSocket?.let { socket -> runCatching { socket.close() } }
            activeSocket = null
            services.clear()
            interfaceServices.clear()
            interfaceEvents.clear()
            dnsToClose = interfaceMdns.toList()
            interfaceMdns.clear()
            workerToJoin = worker
            worker = null
            workerToJoin?.interrupt()
            if (multicastLock.isHeld) multicastLock.release()
        }
        dnsToClose.forEach { dns -> runCatching { dns.close() } }
        workerToJoin?.let(::joinWorker)
    }

    @Suppress("DEPRECATION")
    private fun registerAirPlay() {
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = config.deviceName
            serviceType = AIRPLAY_SERVICE_TYPE
            port = config.port
            CarPlayBonjourProtocol.airPlayTxtRecords(config, identity).forEach { (key, value) ->
                setAttribute(key, value)
            }
            primaryAdvertisedAddress?.let(::setHost)
        }
        nsdManager.registerService(
            serviceInfo,
            NsdManager.PROTOCOL_DNS_SD,
            registrationListener,
        )
    }

    /** Parses one advertised address literal; invalid or unusable values fail fast at construction. */
    private fun parseAdvertisedHost(value: String): InetAddress {
        val trimmed = value.trim().removeSurrounding("[", "]")
        require(trimmed.isNotEmpty()) { "advertised host must not be blank" }
        val address = try {
            InetAddress.getByName(trimmed)
        } catch (error: Exception) {
            throw IllegalArgumentException("Invalid advertised host: $value", error)
        }
        require(!address.isLoopbackAddress) {
            "advertisedHost must not be a loopback address"
        }
        require(address !is Inet6Address || address.isLinkLocalAddress) {
            "advertisedHost must be link-local IPv6 or IPv4"
        }
        return address
    }

    /** Report-safe label for an address; the literal itself is redacted from saved reports. */
    private fun familyLabel(address: InetAddress): String = when {
        address !is Inet6Address -> "IPv4"
        address.isLinkLocalAddress -> "IPv6-linklocal"
        else -> "IPv6"
    }

    private fun runWorker() {
        while (!closed) {
            if (useInterfaceMdns) {
                try {
                    while (true) emit(interfaceEvents.poll() ?: break)
                    val (endpoint, address) = interfaceServices.poll(
                        WORKER_POLL_MILLIS, TimeUnit.MILLISECONDS,
                    ) ?: continue
                    emit(CarPlayBonjourEvent.Resolved(endpoint))
                    probe(endpoint, address)?.let(::emit)
                } catch (_: InterruptedException) {
                    return
                } catch (error: Exception) {
                    if (!closed) Log.w(TAG, "Interface CarPlay service handling failed", error)
                }
                continue
            }
            val service = try {
                services.poll(WORKER_POLL_MILLIS, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                return
            } ?: continue
            if (closed) return
            try {
                handleService(service)
            } catch (_: InterruptedException) {
                return
            } catch (error: Exception) {
                if (!closed) Log.w(TAG, "CarPlay control service handling failed", error)
            }
        }
    }

    private fun handleService(service: NsdServiceInfo) {
        val resolved = resolveWithRetry(service) ?: return
        val address = preferredAddress(resolved) ?: return
        val port = resolved.port
        if (port !in 1..65535) return
        val serviceName = resolved.serviceName ?: service.serviceName ?: return
        val host = address.hostAddress ?: return
        val bluetoothId = resolved.attributes
            ?.get("id")
            ?.let(::decodeTxtValue)
            ?.takeIf { it.isNotBlank() }
        val endpoint = CarPlayBonjourEndpoint(
            serviceName = serviceName,
            host = host,
            port = port,
            bluetoothId = bluetoothId,
        )
        emit(CarPlayBonjourEvent.Resolved(endpoint))
        probe(endpoint, address)?.let(::emit)
    }

    @Suppress("DEPRECATION")
    private fun resolveWithRetry(service: NsdServiceInfo): NsdServiceInfo? {
        repeat(RESOLVE_ATTEMPTS) { attempt ->
            if (closed) return null
            val latch = CountDownLatch(1)
            val resolved = AtomicReference<NsdServiceInfo?>()
            val failure = AtomicInteger(FAILURE_NONE)
            val listener = object : NsdManager.ResolveListener {
                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    resolved.set(serviceInfo)
                    latch.countDown()
                }

                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    failure.set(errorCode)
                    latch.countDown()
                }
            }
            val submitted = try {
                synchronized(lifecycleLock) {
                    if (closed) {
                        false
                    } else {
                        nsdManager.resolveService(service, listener)
                        true
                    }
                }
            } catch (error: RuntimeException) {
                Log.w(TAG, "CarPlay control service resolution failed", error)
                false
            }
            if (!submitted) return null
            val completed = try {
                latch.await(RESOLVE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            } catch (error: InterruptedException) {
                throw error
            }
            if (!completed) {
                Log.w(TAG, "CarPlay control service resolution timed out")
                return null
            }
            resolved.get()?.let { return it }
            if (failure.get() != NsdManager.FAILURE_ALREADY_ACTIVE) {
                Log.w(TAG, "CarPlay control service resolution failed code=${failure.get()}")
                return null
            }
            if (!closed && attempt + 1 < RESOLVE_ATTEMPTS) {
                Thread.sleep(RESOLVE_RETRY_DELAY_MILLIS)
            }
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun preferredAddress(serviceInfo: NsdServiceInfo): InetAddress? {
        val addresses = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            serviceInfo.hostAddresses.orEmpty()
        } else {
            listOfNotNull(serviceInfo.host)
        }
        return addresses.firstOrNull { it is Inet6Address && it.isLinkLocalAddress }
            ?.let(::applyLocalScope)
            ?: addresses.firstOrNull { it is Inet4Address }
            ?: addresses.firstOrNull { it is Inet6Address }
            ?: addresses.firstOrNull()
    }

    /**
     * Applies the link-local scope of the matching advertised IPv6 address.
     *
     * A link-local IPv6 without a scope cannot be routed; the scope has to come from an address we
     * actually hold, so it is looked up among the advertised ones rather than from a single field.
     */
    private fun applyLocalScope(address: InetAddress): InetAddress {
        if (address !is Inet6Address || address.scopeId != 0) return address
        val scope = advertisedAddresses
            .filterIsInstance<Inet6Address>()
            .firstOrNull { it.scopeId != 0 }
            ?.scopeId ?: return address
        return try {
            Inet6Address.getByAddress(null, address.address, scope)
        } catch (_: Exception) {
            address
        }
    }

    /** The advertised address to source a connection to [target] from, matching its family. */
    private fun sourceAddressFor(target: InetAddress): InetAddress? =
        advertisedAddresses.firstOrNull { (it is Inet4Address) == (target is Inet4Address) }

    private fun probe(
        endpoint: CarPlayBonjourEndpoint,
        address: InetAddress,
    ): CarPlayBonjourEvent.Probed? {
        var lastError: IOException? = null
        repeat(MAX_PROBE_ATTEMPTS) { attempt ->
            if (closed) return null
            try {
                val statusLine = probeOnce(address, endpoint.port, attempt + 1)
                return CarPlayBonjourEvent.Probed(
                    endpoint = endpoint,
                    attempts = attempt + 1,
                    statusLine = statusLine,
                    error = null,
                )
            } catch (error: IOException) {
                lastError = error
            } catch (error: RuntimeException) {
                lastError = IOException("AirPlay control probe failed", error)
            }
            if (closed) return null
            if (attempt + 1 < MAX_PROBE_ATTEMPTS) {
                Thread.sleep(PROBE_RETRY_DELAY_MILLIS)
            }
        }
        return CarPlayBonjourEvent.Probed(
            endpoint = endpoint,
            attempts = MAX_PROBE_ATTEMPTS,
            statusLine = null,
            error = lastError,
        )
    }

    private fun probeOnce(address: InetAddress, port: Int, attempt: Int): String {
        val socket = Socket()
        synchronized(lifecycleLock) {
            check(!closed) { "CarPlayBonjour is closed" }
            activeSocket = socket
        }
        var stage = CarPlayBonjourEvent.ProbeProgress.Stage.CONNECTING
        try {
            // Source the probe from an address of the same family as the peer, so the phone sees
            // the request arrive on the interface it already knows us on.
            sourceAddressFor(address)?.let { socket.bind(InetSocketAddress(it, 0)) }
            emit(CarPlayBonjourEvent.ProbeProgress(CarPlayBonjourEvent.ProbeProgress.Stage.CONNECTING, attempt, address is Inet6Address))
            socket.connect(InetSocketAddress(address, port), CONNECT_TIMEOUT_MILLIS)
            socket.soTimeout = READ_TIMEOUT_MILLIS
            stage = CarPlayBonjourEvent.ProbeProgress.Stage.TCP_CONNECTED
            emit(CarPlayBonjourEvent.ProbeProgress(CarPlayBonjourEvent.ProbeProgress.Stage.TCP_CONNECTED, attempt, address is Inet6Address))
            val host = address.hostAddress
                ?: throw IOException("AirPlay control service has no host address")
            val request = CarPlayBonjourProtocol.connectProbeRequest(
                host = host,
                port = port,
                sourceVersion = config.sourceVersion,
                deviceId = config.deviceId,
            )
            val output = socket.getOutputStream()
            output.write(request.toByteArray(StandardCharsets.US_ASCII))
            output.flush()
            stage = CarPlayBonjourEvent.ProbeProgress.Stage.REQUEST_SENT
            emit(CarPlayBonjourEvent.ProbeProgress(CarPlayBonjourEvent.ProbeProgress.Stage.REQUEST_SENT, attempt, address is Inet6Address))
            val reader = BufferedReader(
                InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII),
            )
            return reader.readLine()
                ?: throw IOException("AirPlay control probe returned no status line")
        } catch (error: IOException) {
            if (!closed) emit(CarPlayBonjourEvent.ProbeFailed(stage, attempt, error))
            throw error
        } finally {
            synchronized(lifecycleLock) {
                if (activeSocket === socket) activeSocket = null
            }
            runCatching { socket.close() }
        }
    }

    private fun emit(event: CarPlayBonjourEvent) {
        if (closed) return
        when (event) {
            is CarPlayBonjourEvent.Discovery -> when (event.stage) {
                CarPlayBonjourEvent.Discovery.Stage.ADDED -> addedCount.incrementAndGet()
                CarPlayBonjourEvent.Discovery.Stage.NO_MATCHING_ADDRESS -> addressMismatchCount.incrementAndGet()
                else -> Unit
            }
            is CarPlayBonjourEvent.Resolved -> resolvedCount.incrementAndGet()
            is CarPlayBonjourEvent.ProbeProgress -> {
                if (event.stage == CarPlayBonjourEvent.ProbeProgress.Stage.CONNECTING) probeCount.incrementAndGet()
                lastProbe.set(event.stage.name)
            }
            is CarPlayBonjourEvent.ProbeFailed -> lastProbe.set("failed_after_${event.stage}_${event.error.javaClass.simpleName}")
            is CarPlayBonjourEvent.Probed -> {
                val status = event.statusLine?.let { Regex("^HTTP/\\d(?:\\.\\d)? (\\d{3})(?: |$)").find(it)?.groupValues?.get(1)?.toIntOrNull() }
                if (status != null && status in 200..299) successfulProbeCount.incrementAndGet()
                if (event.error == null) lastProbe.set("status_${status ?: "unknown"}")
            }
        }
        try {
            onEvent(event)
        } catch (error: RuntimeException) {
            Log.w(TAG, "CarPlay Bonjour event callback failed", error)
        }
    }

    private fun decodeTxtValue(value: ByteArray): String =
        String(value, StandardCharsets.UTF_8).trimEnd('\u0000')

    private fun joinWorker(worker: Thread) {
        if (worker === Thread.currentThread()) return
        try {
            worker.join(JOIN_TIMEOUT_MILLIS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private companion object {
        const val TAG = "xcertplay-bonjour"
        const val WORKER_NAME = "carplay-bonjour"
        const val AIRPLAY_SERVICE_TYPE = "_airplay._tcp"
        const val CARPLAY_CONTROL_SERVICE_TYPE = "_carplay-ctrl._tcp"
        const val WORKER_POLL_MILLIS = 500L
        const val RESOLVE_ATTEMPTS = 3
        const val RESOLVE_TIMEOUT_MILLIS = 10_000L
        const val RESOLVE_RETRY_DELAY_MILLIS = 250L
        const val MAX_PROBE_ATTEMPTS = 7
        const val PROBE_RETRY_DELAY_MILLIS = 1_500L
        const val CONNECT_TIMEOUT_MILLIS = 3_000
        const val READ_TIMEOUT_MILLIS = 3_000
        const val JOIN_TIMEOUT_MILLIS = 2_000L
        const val FAILURE_NONE = -1
    }
}

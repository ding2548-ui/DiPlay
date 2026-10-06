package com.shilapi.xcertplay.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.MacAddress
import android.net.wifi.SoftApConfiguration
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import com.shilapi.xcertplay.transport.Iap2WirelessSecurity
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketException
import java.util.Collections
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * Owns one Android LocalOnlyHotspot reservation and reports its live configuration.
 *
 * [start] must run on a worker thread because it blocks until the system callback arrives and
 * the AP interface is usable. The reservation and multicast lock stay owned by this instance
 * until [close].
 */
class LocalOnlyHotspotManager(context: Context, private val onDiagnostic: (String) -> Unit = {}) : WirelessHotspotManager {
    private val connectivityManager =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val wifiManager = context.applicationContext.getSystemService(WifiManager::class.java)
        ?: throw IllegalStateException("WifiManager is unavailable")
    private val stateLock = Object()

    private var startAttempt: StartAttempt? = null
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var radioObserver: LocalOnlyHotspotRadioInfo? = null
    private var stopped = false
    private var closed = false

    /**
     * Starts a LocalOnlyHotspot and waits up to [timeoutMillis] for the live configuration and AP
     * interface. The returned credentials are not retained by this manager.
     */
    override fun start(timeoutMillis: Long): WirelessHotspotInfo {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "LocalOnlyHotspotManager.start must not run on the main thread"
        }
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }

        val attempt = StartAttempt()
        synchronized(stateLock) {
            check(!closed) { "LocalOnlyHotspotManager is closed" }
            check(startAttempt == null && reservation == null) {
                "A LocalOnlyHotspot is already starting or active"
            }
            startAttempt = attempt
        }

        var acquiredMulticastLock: WifiManager.MulticastLock? = null
        val radioInfo = LocalOnlyHotspotRadioInfo(wifiManager)
        var observerAdopted = false
        val deadlineNanos = deadlineAfter(timeoutMillis)
        val previousAddresses = activeInterfaces().flatMap { it.siteLocalIpv4Addresses() }.toSet()
        val previousUpstreams = upstreamInterfaceNames()

        try {
            ensureStartActive(attempt)
            onDiagnostic("LocalOnlyHotspot starting with Wi-Fi client enabled=${wifiManager.isWifiEnabled}")
            disconnectTwoPointFourStation()
            val requestedChannel = requestHotspot(createCallback(attempt))

            val activeReservation = awaitStart(attempt, deadlineNanos, timeoutMillis)
            radioInfo.start()
            acquiredMulticastLock = acquireMulticastLock(attempt)
            val configuration = readConfiguration(activeReservation)
            onDiagnostic("LocalOnlyHotspot reservation band=${configuration.bandLabel} channel=${configuration.channel}")
            val apInterface = awaitApInterface(
                bssid = configuration.bssidBytes,
                previousAddresses = previousAddresses,
                previousUpstreams = previousUpstreams,
                attempt = attempt,
                deadlineNanos = deadlineNanos,
            )
            val liveRadio = awaitRadioInfo(radioInfo, apInterface, configuration, attempt, deadlineNanos)
            if (liveRadio?.frequencyMHz?.let { it !in 5160..5895 } ?: (configuration.bandLabel != "5 GHz")) {
                throw IOException("This firmware did not provide the requested 5 GHz local hotspot; choose Wi-Fi Direct or Car hotspot")
            }

            synchronized(stateLock) {
                ensureStartActiveLocked(attempt)
                reservation = activeReservation
                multicastLock = acquiredMulticastLock
                radioObserver = radioInfo
                observerAdopted = true
                startAttempt = null
                stopped = false
                acquiredMulticastLock = null
            }
            radioInfo.watch(apInterface.bssid ?: configuration.bssid, onDiagnostic)

            // With a live radio reading the channel is measured; Android 11/12 BYD units
            // have neither a live callback nor working WEXT, so there the advertised
            // channel degrades to the configuration's, the one this manager requested,
            // or 36 — in that order — and the phone joining is the real verification.
            val advertisedChannel = when {
                liveRadio != null -> wifiFrequencyMhzToChannel(liveRadio.frequencyMHz)
                    ?: configuration.channel.takeIf { it > 0 } ?: requestedChannel ?: 36
                configuration.channel > 0 -> configuration.channel
                requestedChannel != null -> requestedChannel
                else -> 36
            }
            if (liveRadio == null && configuration.channel == 0) {
                onDiagnostic("LocalOnlyHotspot: advertising channel $advertisedChannel (band ${configuration.bandLabel}) without live verification")
            }

            return WirelessHotspotInfo(
                ssid = configuration.ssid,
                passphrase = configuration.passphrase,
                security = configuration.security,
                channel = advertisedChannel,
                frequencyMHz = liveRadio?.frequencyMHz,
                bssid = apInterface.bssid ?: configuration.bssid,
                interfaceName = apInterface.name,
                hostAddress = apInterface.hostAddress,
                bandLabel = when (liveRadio?.frequencyMHz) {
                    in 2412..2484 -> "2.4 GHz"
                    in 5160..5895 -> "5 GHz"
                    in 5955..7115 -> "6 GHz"
                    else -> configuration.bandLabel
                },
                backend = WirelessHotspotBackend.LOCAL_ONLY_HOTSPOT,
            )
        } catch (failure: Exception) {
            cleanupFailedStart(attempt, acquiredMulticastLock)
            throw failure
        } finally {
            if (!observerAdopted) radioInfo.close()
        }
    }

    /**
     * Frees the radio for a 5 GHz hotspot on Android 11/12. Observed on DiLink 5.0 /
     * Android 12: while the car's Wi-Fi client stays associated to a 2.4 GHz network, the
     * Qualcomm stack pins the local hotspot onto the same channel even when 5 GHz was
     * explicitly requested. Disconnecting the station first is best-effort — where the
     * platform ignores it, the 2.4 GHz refusal message still names the Wi-Fi switch.
     */
    @Suppress("DEPRECATION")
    private fun disconnectTwoPointFourStation() {
        if (Build.VERSION.SDK_INT !in 30..32) return
        val connection = runCatching { wifiManager.connectionInfo }.getOrNull() ?: return
        // The BSSID is masked for ordinary apps on BYD builds, so associate on
        // supplicant state + frequency alone; a completed 2.4 GHz association is what
        // pins the hotspot onto 2.4 GHz.
        val associatedOn2Point4 = connection.supplicantState ==
            android.net.wifi.SupplicantState.COMPLETED && connection.frequency in 2412..2484
        if (!associatedOn2Point4) return
        onDiagnostic(
            "LocalOnlyHotspot: car Wi-Fi client is associated on ${connection.frequency} MHz (2.4 GHz); disconnecting it so the hotspot can use 5 GHz",
        )
        runCatching { wifiManager.disconnect() }
            .onFailure {
                onDiagnostic("LocalOnlyHotspot: could not disconnect the Wi-Fi client (${it.javaClass.simpleName}); if the hotspot lands on 2.4 GHz, turn the car's Wi-Fi switch off")
            }
    }

    /**
     * Posts the hotspot request and returns the 5 GHz channel that was explicitly asked
     * for, or null when the platform got the plain Android-generated AP. The caller uses
     * the returned channel as the advertised fallback when no live radio reading exists.
     */
    private fun requestHotspot(callback: WifiManager.LocalOnlyHotspotCallback): Int? {
        // WifiManager.startLocalOnlyHotspot is API 26. This head unit is API 25, where the method
        // does not exist and calling it raises NoSuchMethodError — an Error, so it would kill the
        // process instead of failing this attempt. Fail like the other unsupported-firmware paths
        // do, so the caller can report "choose Wi-Fi Direct or Car hotspot".
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            throw IOException("Local-only hotspot needs Android 8.0 (API 26); this unit is API ${Build.VERSION.SDK_INT}")
        }
        // Android 13's service accepts a custom LOHS configuration from target-33+ callers
        // with Nearby devices permission. BYD's Android 12 builds expose the same entry
        // point but return a 2.4 GHz hotspot regardless of the requested band (observed
        // 2026-09-28 with the Wi-Fi client both on and off), while their plain reservation
        // runs 5 GHz (Hotspot Check on the same build) — so Android 11/12 keep the plain
        // path and rely on the band check below. Android 14/15 also keep the plain path.
        val main = Handler(Looper.getMainLooper())
        val executor = Executor { main.post(it) }
        if (Build.VERSION.SDK_INT == 33 || Build.VERSION.SDK_INT >= 36) {
            try {
                val builder = SoftApConfiguration.Builder()
                SoftApConfiguration.Builder::class.java.getMethod("setSsid", String::class.java)
                    .invoke(builder, "DiPlay-${UUID.randomUUID().toString().take(6)}")
                SoftApConfiguration.Builder::class.java.getMethod("setPassphrase", String::class.java, Int::class.javaPrimitiveType)
                    .invoke(builder, UUID.randomUUID().toString().replace("-", "").take(20), SoftApConfiguration.SECURITY_TYPE_WPA2_PSK)
                // Request the station's 5 GHz channel, or 36 without a 5 GHz station.
                // BYD may override even a fixed channel (observed 40 -> 149), so credentials
                // below always use the settled live callback rather than this preference.
                @Suppress("DEPRECATION")
                val stationFrequency = runCatching { wifiManager.connectionInfo?.frequency }.getOrNull()
                val preferredChannel = stationFrequency?.takeIf { it in 5160..5895 }
                    ?.let(::wifiFrequencyMhzToChannel) ?: 36
                try {
                    SoftApConfiguration.Builder::class.java.getMethod("setChannel", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                        .invoke(builder, preferredChannel, SoftApConfiguration.BAND_5GHZ)
                } catch (_: NoSuchMethodException) {
                    // Android 11 has no setChannel(channel, band); asking for the band
                    // alone still pins 5 GHz and leaves the channel to the firmware.
                    SoftApConfiguration.Builder::class.java.getMethod("setBand", Int::class.javaPrimitiveType)
                        .invoke(builder, SoftApConfiguration.BAND_5GHZ)
                }
                val method = if (Build.VERSION.SDK_INT >= 36) "startLocalOnlyHotspotWithConfiguration" else "startLocalOnlyHotspot"
                WifiManager::class.java.getMethod(method, SoftApConfiguration::class.java, Executor::class.java,
                    WifiManager.LocalOnlyHotspotCallback::class.java).invoke(wifiManager, builder.build(), executor, callback)
                return preferredChannel
            } catch (failure: ReflectiveOperationException) {
                if (failure.cause != null && failure.cause !is SecurityException && failure.cause !is UnsupportedOperationException) {
                    throw IOException("LocalOnlyHotspot custom startup failed", failure.cause)
                }
                // Unsupported firmware retains the ordinary Android-generated AP.
            } catch (_: SecurityException) {
                // No privileged permission is requested to enable this optional path.
            }
        }
        // Main-loop callback delivery survives cancellation to close late reservations.
        wifiManager.startLocalOnlyHotspot(callback, main)
        return null
    }

    private fun awaitRadioInfo(
        observer: LocalOnlyHotspotRadioInfo,
        ap: ApInterface,
        configuration: HotspotConfiguration,
        attempt: StartAttempt,
        deadlineNanos: Long,
    ): LocalOnlyHotspotRadioInfo.Radio? {
        val legacy = LegacyHotspotRadio.Settled()
        val legacyStart = System.nanoTime()
        while (true) {
            ensureStartActive(attempt)
            observer.settledForBssid(ap.bssid ?: configuration.bssid)?.let { return it }
            observer.unavailableReason?.let {
                if (configuration.channel > 0) return null
                if (Build.VERSION.SDK_INT < 33) {
                    val reading = LegacyHotspotRadio.read(ap.name, configuration.bandLabel)
                    val settled = legacy.observe(reading.frequencyMHz, System.nanoTime() / 1_000_000)
                    if (settled != null) {
                        onDiagnostic("LocalOnlyHotspot legacy AP radio iface=${ap.name} frequency=${settled}MHz channel=${wifiFrequencyMhzToChannel(settled)}")
                        return LocalOnlyHotspotRadioInfo.Radio(ap.bssid ?: configuration.bssid, settled)
                    }
                    if (System.nanoTime() - legacyStart >= TimeUnit.MILLISECONDS.toNanos(
                            if (configuration.bandLabel == "5 GHz") 1500 else 6000
                        )
                    ) {
                        if (configuration.bandLabel == "5 GHz") {
                            // Every BYD Qualcomm tested answers WEXT with errno 95 and
                            // Android 11/12 has no live LOHS channel callback, so an
                            // unreadable channel cannot be treated as a broken hotspot.
                            // The framework already confirmed the 5 GHz band, so accept
                            // the reservation; the advertised channel falls back to the
                            // requested one and the phone joining is the live check.
                            onDiagnostic("LocalOnlyHotspot: 5 GHz band confirmed by configuration; live channel unreadable (${reading.error ?: "radio did not settle"}); advertising the requested channel without live verification")
                            return null
                        }
                        if (configuration.bandLabel == "2.4 GHz") {
                            if (Build.VERSION.SDK_INT < 30) {
                                // Android 10 BYD firmware pins the local hotspot to 2.4 GHz
                                // regardless of the Wi-Fi switch (extracted-firmware fact);
                                // switching Wi-Fi off cannot help, so do not suggest it.
                                throw IOException("LocalOnlyHotspot: this Android 10 firmware always places the local hotspot on 2.4 GHz; use the Car hotspot or Wi-Fi Direct")
                            }
                            // Observed on DiLink 5.0 / Android 12: while the car's Wi-Fi
                            // client stays associated to a 2.4 GHz network, the Qualcomm
                            // stack pins the local hotspot onto the same channel even when
                            // 5 GHz was explicitly requested. Name the remedy, not the
                            // unreadable driver channel.
                            throw IOException("LocalOnlyHotspot: this firmware placed the hotspot on 2.4 GHz while the car's Wi-Fi client was using a 2.4 GHz network; turn the car's Wi-Fi client off and try again, or choose the Car hotspot")
                        }
                        throw IOException("LocalOnlyHotspot: Android ${Build.VERSION.SDK_INT} cannot read the AP channel (${reading.error ?: "radio did not settle"}); choose a 5 GHz Car hotspot or Wi-Fi Direct")
                    }
                } else {
                    throw IOException("LocalOnlyHotspot: $it; cannot advertise automatic channel 0 to CarPlay")
                }
            }
            val remaining = deadlineNanos - System.nanoTime()
            if (remaining <= 0) throw IOException("LocalOnlyHotspot did not report its live channel; cannot advertise automatic channel 0 to CarPlay")
            try {
                TimeUnit.NANOSECONDS.sleep(minOf(remaining, INTERFACE_POLL_NANOS))
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("Interrupted while waiting for the LocalOnlyHotspot channel", interrupted)
            }
        }
    }

    override fun close() {
        val activeReservation: WifiManager.LocalOnlyHotspotReservation?
        val activeMulticastLock: WifiManager.MulticastLock?
        val activeObserver: LocalOnlyHotspotRadioInfo?
        synchronized(stateLock) {
            if (closed) return
            closed = true
            startAttempt?.stopped = true
            stateLock.notifyAll()
            activeReservation = reservation
            activeMulticastLock = multicastLock
            activeObserver = radioObserver
            radioObserver = null
            reservation = null
            multicastLock = null
        }

        releaseMulticastLock(activeMulticastLock)
        activeObserver?.close()
        activeReservation?.close()
    }

    private fun createCallback(attempt: StartAttempt): WifiManager.LocalOnlyHotspotCallback =
        object : WifiManager.LocalOnlyHotspotCallback() {
            override fun onStarted(
                reservation: WifiManager.LocalOnlyHotspotReservation,
            ) {
                val closeReservation = synchronized(stateLock) {
                    if (
                        closed ||
                        startAttempt !== attempt ||
                        attempt.stopped ||
                        attempt.reservation != null
                    ) {
                        true
                    } else {
                        attempt.reservation = reservation
                        stateLock.notifyAll()
                        false
                    }
                }
                if (closeReservation) reservation.close()
            }

            override fun onFailed(reason: Int) {
                synchronized(stateLock) {
                    if (startAttempt === attempt && attempt.failure == null) {
                        attempt.failure = IOException(
                            "LocalOnlyHotspot failed: ${failureReason(reason)}",
                        )
                        stateLock.notifyAll()
                    }
                }
            }

            override fun onStopped() {
                var lockToRelease: WifiManager.MulticastLock? = null
                synchronized(stateLock) {
                    if (startAttempt === attempt) attempt.stopped = true
                    if (reservation === attempt.reservation) {
                        stopped = true
                        lockToRelease = multicastLock
                        multicastLock = null
                    }
                    stateLock.notifyAll()
                }
                releaseMulticastLock(lockToRelease)
            }
        }

    private fun awaitStart(
        attempt: StartAttempt,
        deadlineNanos: Long,
        timeoutMillis: Long,
    ): WifiManager.LocalOnlyHotspotReservation = synchronized(stateLock) {
        while (true) {
            if (closed) throw IOException("LocalOnlyHotspot manager closed while starting")
            attempt.failure?.let { throw it }
            if (attempt.stopped) {
                throw IOException("LocalOnlyHotspot stopped before startup completed")
            }
            attempt.reservation?.let { return@synchronized it }

            val remainingNanos = deadlineNanos - System.nanoTime()
            if (remainingNanos <= 0) {
                throw IOException(
                    "Timed out after ${timeoutMillis}ms waiting for LocalOnlyHotspot",
                )
            }
            waitNanos(remainingNanos)
        }
        error("unreachable")
    }

    private fun acquireMulticastLock(attempt: StartAttempt): WifiManager.MulticastLock {
        ensureStartActive(attempt)
        val lock = wifiManager.createMulticastLock(MULTICAST_LOCK_TAG)
        lock.setReferenceCounted(false)
        try {
            lock.acquire()
            synchronized(stateLock) {
                ensureStartActiveLocked(attempt)
            }
            return lock
        } catch (failure: Exception) {
            releaseMulticastLock(lock)
            throw failure
        }
    }

    private fun readConfiguration(
        reservation: WifiManager.LocalOnlyHotspotReservation,
    ): HotspotConfiguration {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            readSoftApConfiguration(reservation.softApConfiguration)
        } else {
            @Suppress("DEPRECATION")
            val configuration = reservation.wifiConfiguration
                ?: throw IOException("LocalOnlyHotspot did not provide a Wi-Fi configuration")
            readWifiConfiguration(configuration)
        }
    }

    @Suppress("DEPRECATION")
    @RequiresApi(Build.VERSION_CODES.R)
    private fun readSoftApConfiguration(configuration: SoftApConfiguration): HotspotConfiguration {
        val ssid = validateSsid(configuration.ssid)
        val security = mapSoftApSecurity(configuration.securityType)
        val passphrase = validatePassphrase(security, configuration.passphrase)
        val channel = if (Build.VERSION.SDK_INT >= 36) {
            readConfiguredChannel(configuration)
        } else {
            readLegacySoftApChannel(configuration)
        }
        val bssid = configuration.bssid

        return HotspotConfiguration(
            ssid = ssid,
            passphrase = passphrase,
            security = security,
            channel = channel.first,
            bssid = bssid?.toString(),
            bssidBytes = bssid?.toByteArray(),
            bandLabel = channel.second,
        )
    }

    @Suppress("DEPRECATION")
    private fun readWifiConfiguration(configuration: WifiConfiguration): HotspotConfiguration {
        val ssid = validateSsid(configuration.SSID)
        val security = mapWifiConfigurationSecurity(configuration)
        val passphrase = validatePassphrase(security, unquote(configuration.preSharedKey))
        val bssid = configuration.BSSID?.let {
            try {
                MacAddress.fromString(it)
            } catch (failure: IllegalArgumentException) {
                throw IOException("LocalOnlyHotspot reported an invalid BSSID: $it", failure)
            }
        }
        val channel = readWifiConfigurationChannel(configuration)

        return HotspotConfiguration(
            ssid = ssid,
            passphrase = passphrase,
            security = security,
            channel = channel,
            bssid = bssid?.toString(),
            bssidBytes = bssid?.toByteArray(),
            bandLabel = readWifiConfigurationBandLabel(configuration, channel),
        )
    }

    @RequiresApi(36)
    private fun readConfiguredChannel(configuration: SoftApConfiguration): Pair<Int, String> {
        val channels = configuration.channels
        if (channels.size() != 1) {
            throw IOException("Expected one LocalOnlyHotspot channel, got ${channels.size()}")
        }
        val band = channels.keyAt(0)
        val channel = requireChannel(
            channels.valueAt(0),
            "SoftApConfiguration.channels",
            allowAuto = true,
        )
        return channel to softApBandLabel(band)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun readLegacySoftApChannel(configuration: SoftApConfiguration): Pair<Int, String> {
        val channel = try {
            val getter = SoftApConfiguration::class.java.getMethod("getChannel")
            val value = getter.invoke(configuration) as? Number
                ?: throw IOException("SoftApConfiguration.getChannel returned no channel")
            requireChannel(
                value.toInt(),
                "SoftApConfiguration.getChannel",
                allowAuto = true,
            )
        } catch (failure: ReflectiveOperationException) {
            throw IOException(
                "Android ${Build.VERSION.RELEASE} does not expose SoftApConfiguration.getChannel",
                failure,
            )
        }
        return channel to readLegacySoftApBandLabel(configuration, channel)
    }

    private fun readWifiConfigurationChannel(configuration: WifiConfiguration): Int {
        val channel = try {
            WifiConfiguration::class.java.getField("apChannel").getInt(configuration)
        } catch (failure: ReflectiveOperationException) {
            throw IOException(
                "Android ${Build.VERSION.RELEASE} does not expose WifiConfiguration.apChannel",
                failure,
            )
        }
        return requireChannel(channel, "WifiConfiguration.apChannel", allowAuto = true)
    }

    private fun readWifiConfigurationBandLabel(
        configuration: WifiConfiguration,
        channel: Int,
    ): String {
        // WifiConfiguration.apBand uses its own constants (0=2.4 GHz, 1=5 GHz), which do
        // not line up with SoftApConfiguration's band values — map them explicitly.
        val band = try {
            (WifiConfiguration::class.java.getField("apBand").get(configuration) as? Number)
                ?.toInt()
        } catch (_: ReflectiveOperationException) {
            null
        }
        return when (band) {
            0 -> "2.4 GHz"
            1 -> "5 GHz"
            else -> legacyBandLabel(channel)
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun readLegacySoftApBandLabel(
        configuration: SoftApConfiguration,
        channel: Int,
    ): String {
        val band = try {
            (SoftApConfiguration::class.java.getMethod("getBand").invoke(configuration) as? Number)
                ?.toInt()
        } catch (_: ReflectiveOperationException) {
            null
        }
        return band?.let(::softApBandLabel) ?: legacyBandLabel(channel)
    }

    private fun awaitApInterface(
        bssid: ByteArray?,
        previousAddresses: Set<String>,
        previousUpstreams: Set<String>,
        attempt: StartAttempt,
        deadlineNanos: Long,
    ): ApInterface {
        while (true) {
            ensureStartActive(attempt)
            val networkInterface = findInterface(bssid, previousAddresses, previousUpstreams)
            if (networkInterface != null) {
                networkInterface.hotspotAddress()?.let { hostAddress ->
                    val interfaceBssid = networkInterface.interfaceBssid()
                    if (bssid == null && interfaceBssid == null) {
                        return@let
                    }
                    return ApInterface(
                        name = networkInterface.name,
                        hostAddress = hostAddress,
                        bssid = interfaceBssid,
                    )
                }
            }

            val remainingNanos = deadlineNanos - System.nanoTime()
            if (remainingNanos <= 0) {
                throw IOException("LocalOnlyHotspot started but its AP interface/address could not be identified")
            }
            try {
                TimeUnit.NANOSECONDS.sleep(minOf(remainingNanos, INTERFACE_POLL_NANOS))
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("Interrupted while waiting for the LocalOnlyHotspot interface", interrupted)
            }
        }
    }

    private fun findInterface(
        bssid: ByteArray?,
        previousAddresses: Set<String>,
        previousUpstreams: Set<String>,
    ): NetworkInterface? {
        val interfaces = activeInterfaces()
        val candidates = interfaces.map { net ->
            LocalOnlyHotspotInterfacePolicy.Candidate(net.name, net.siteLocalIpv4Addresses(), net.interfaceBssid())
        }
        val selected = LocalOnlyHotspotInterfacePolicy.select(
            candidates, previousAddresses, previousUpstreams + upstreamInterfaceNames(), bssid?.toMacAddressString(),
        ) ?: return null
        return interfaces.singleOrNull { it.name == selected.name }
    }

    private fun activeInterfaces(): List<NetworkInterface> =
        NetworkInterface.getNetworkInterfaces()?.let { Collections.list(it) }.orEmpty().filter { networkInterface ->
            try {
                networkInterface.isUp && !networkInterface.isLoopback
            } catch (_: SocketException) {
                false
            }
        }

    @Suppress("DEPRECATION")
    private fun upstreamInterfaceNames(): Set<String> = connectivityManager?.allNetworks.orEmpty()
        .mapNotNull { connectivityManager?.getLinkProperties(it)?.interfaceName }.toSet()

    private fun NetworkInterface.siteLocalIpv4Addresses(): Set<String> =
        Collections.list(inetAddresses).filterIsInstance<Inet4Address>()
            .filter { it.isSiteLocalAddress }.mapNotNull { it.hostAddress }.toSet()

    private fun NetworkInterface.interfaceBssid(): String? =
        runCatching { hardwareAddress?.toMacAddressString() }.getOrNull()
            ?.takeUnless { it == "02:00:00:00:00:00" || it == "00:00:00:00:00:00" }
            ?: Collections.list(inetAddresses).filterIsInstance<Inet6Address>()
                .firstNotNullOfOrNull { it.toEui64MacAddress() }

    private fun NetworkInterface.hotspotAddress(): InetAddress? {
        val address = HotspotAddressPolicy.select(Collections.list(inetAddresses)) ?: return null
        if (address !is Inet6Address || address.scopeId == index) return address
        return runCatching { Inet6Address.getByAddress(null, address.address, this) }
            .getOrDefault(address)
    }

    private fun ensureStartActive(attempt: StartAttempt) {
        synchronized(stateLock) {
            ensureStartActiveLocked(attempt)
        }
    }

    private fun ensureStartActiveLocked(attempt: StartAttempt) {
        if (closed) throw IOException("LocalOnlyHotspot manager closed while starting")
        if (startAttempt !== attempt) throw IOException("LocalOnlyHotspot startup was cancelled")
        if (attempt.stopped) throw IOException("LocalOnlyHotspot stopped while starting")
    }

    private fun cleanupFailedStart(
        attempt: StartAttempt,
        multicastLock: WifiManager.MulticastLock?,
    ) {
        val failedReservation: WifiManager.LocalOnlyHotspotReservation?
        synchronized(stateLock) {
            if (startAttempt === attempt) startAttempt = null
            attempt.stopped = true
            stateLock.notifyAll()
            failedReservation = attempt.reservation
        }
        releaseMulticastLock(multicastLock)
        failedReservation?.close()
    }

    private fun waitNanos(nanos: Long) {
        val millis = nanos / NANOS_PER_MILLISECOND
        val remainder = (nanos % NANOS_PER_MILLISECOND).toInt()
        try {
            stateLock.wait(millis, remainder)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Interrupted while waiting for LocalOnlyHotspot", interrupted)
        }
    }

    private fun deadlineAfter(timeoutMillis: Long): Long {
        val now = System.nanoTime()
        val delta = timeoutMillis * NANOS_PER_MILLISECOND
        return if (Long.MAX_VALUE - now < delta) Long.MAX_VALUE else now + delta
    }

    private fun validateSsid(value: String?): String {
        val ssid = unquote(value)
        if (ssid.isNullOrEmpty() || ssid == WifiManager.UNKNOWN_SSID) {
            throw IOException("LocalOnlyHotspot did not report a usable SSID")
        }
        if ('\u0000' in ssid) throw IOException("LocalOnlyHotspot SSID contains U+0000")
        return ssid
    }

    private fun validatePassphrase(
        security: Iap2WirelessSecurity,
        value: String?,
    ): String {
        val passphrase = value.orEmpty()
        if (security != Iap2WirelessSecurity.NONE && passphrase.isEmpty()) {
            throw IOException("LocalOnlyHotspot did not report a passphrase for secured Wi-Fi")
        }
        if ('\u0000' in passphrase) {
            throw IOException("LocalOnlyHotspot passphrase contains U+0000")
        }
        return passphrase
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun mapSoftApSecurity(securityType: Int): Iap2WirelessSecurity = when (securityType) {
        SoftApConfiguration.SECURITY_TYPE_OPEN -> Iap2WirelessSecurity.NONE
        SoftApConfiguration.SECURITY_TYPE_WPA2_PSK -> Iap2WirelessSecurity.WPA_WPA2
        SoftApConfiguration.SECURITY_TYPE_WPA3_SAE_TRANSITION ->
            Iap2WirelessSecurity.WPA3_TRANSITION
        SoftApConfiguration.SECURITY_TYPE_WPA3_SAE -> Iap2WirelessSecurity.WPA3_ONLY
        SoftApConfiguration.SECURITY_TYPE_WPA3_OWE,
        SoftApConfiguration.SECURITY_TYPE_WPA3_OWE_TRANSITION,
        -> throw IOException("Unsupported LocalOnlyHotspot security type: OWE")
        else -> throw IOException(
            "Unsupported LocalOnlyHotspot security type: $securityType",
        )
    }

    private fun mapWifiConfigurationSecurity(
        configuration: WifiConfiguration,
    ): Iap2WirelessSecurity {
        val keyManagement = configuration.allowedKeyManagement
            ?: throw IOException("LocalOnlyHotspot did not report its key management")
        val open = keyManagement.get(WifiConfiguration.KeyMgmt.NONE)
        val wpa2 = keyManagement.get(WifiConfiguration.KeyMgmt.WPA2_PSK)
        val sae = keyManagement.get(WifiConfiguration.KeyMgmt.SAE)
        val owe = keyManagement.get(WifiConfiguration.KeyMgmt.OWE)

        return when {
            owe -> throw IOException("Unsupported LocalOnlyHotspot security type: OWE")
            open && !wpa2 && !sae -> Iap2WirelessSecurity.NONE
            wpa2 && sae -> Iap2WirelessSecurity.WPA3_TRANSITION
            wpa2 -> Iap2WirelessSecurity.WPA_WPA2
            sae -> Iap2WirelessSecurity.WPA3_ONLY
            else -> throw IOException(
                "Unsupported LocalOnlyHotspot key management: $keyManagement",
            )
        }
    }

    private fun requireChannel(channel: Int, source: String): Int {
        return requireChannel(channel, source, allowAuto = false)
    }

    private fun requireChannel(channel: Int, source: String, allowAuto: Boolean): Int {
        if (channel !in 0..0xff || (!allowAuto && channel == 0)) {
            throw IOException("$source returned invalid channel: $channel")
        }
        return channel
    }

    private fun ByteArray.toMacAddressString(): String =
        joinToString(":") { "%02x".format(it.toInt() and 0xff) }

    private fun Inet6Address.toEui64MacAddress(): String? {
        val bytes = address
        if (!isLinkLocalAddress || bytes.size != 16 || bytes[11] != 0xff.toByte() ||
            bytes[12] != 0xfe.toByte()
        ) {
            return null
        }
        return byteArrayOf(
            (bytes[8].toInt() xor 0x02).toByte(),
            bytes[9],
            bytes[10],
            bytes[13],
            bytes[14],
            bytes[15],
        ).toMacAddressString()
    }

    private fun softApBandLabel(band: Int): String = when (band) {
        SoftApConfiguration.BAND_2GHZ -> "2.4 GHz"
        SoftApConfiguration.BAND_5GHZ -> "5 GHz"
        SoftApConfiguration.BAND_6GHZ -> "6 GHz"
        SoftApConfiguration.BAND_60GHZ -> "60 GHz"
        else -> "Unknown band ($band)"
    }

    private fun legacyBandLabel(channel: Int): String = when (channel) {
        in 1..14 -> "2.4 GHz"
        in 32..177 -> "5 GHz"
        else -> "Unknown band"
    }

    private fun unquote(value: String?): String? {
        if (value == null) return null
        return if (value.length >= 2 && value.first() == '"' && value.last() == '"') {
            value.substring(1, value.length - 1)
        } else {
            value
        }
    }

    private fun failureReason(reason: Int): String = when (reason) {
        WifiManager.LocalOnlyHotspotCallback.ERROR_NO_CHANNEL -> "no channel available"
        WifiManager.LocalOnlyHotspotCallback.ERROR_GENERIC -> "generic error"
        WifiManager.LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE -> "incompatible Wi-Fi mode"
        WifiManager.LocalOnlyHotspotCallback.ERROR_TETHERING_DISALLOWED -> "tethering disallowed"
        else -> "reason $reason"
    }

    private fun releaseMulticastLock(lock: WifiManager.MulticastLock?) {
        if (lock == null) return
        try {
            if (lock.isHeld) lock.release()
        } catch (_: RuntimeException) {
            // close() is best-effort; the hotspot reservation remains the authoritative owner.
        }
    }

    private class StartAttempt {
        var reservation: WifiManager.LocalOnlyHotspotReservation? = null
        var failure: IOException? = null
        var stopped = false
    }

    private class HotspotConfiguration(
        val ssid: String,
        val passphrase: String,
        val security: Iap2WirelessSecurity,
        val channel: Int,
        val bssid: String?,
        val bssidBytes: ByteArray?,
        val bandLabel: String,
    )

    private class ApInterface(
        val name: String,
        val hostAddress: InetAddress?,
        val bssid: String?,
    )

    private companion object {
        const val MULTICAST_LOCK_TAG = "xcertplay-local-only-hotspot-mdns"
        const val NANOS_PER_MILLISECOND = 1_000_000L
        val INTERFACE_POLL_NANOS: Long = TimeUnit.MILLISECONDS.toNanos(100)
    }
}

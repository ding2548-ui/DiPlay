package com.shilapi.xcertplay.network

import android.Manifest
import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.SupplicantState
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.HandlerThread
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.annotation.RequiresApi
import com.shilapi.xcertplay.transport.Iap2WirelessSecurity
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketException
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Creates a temporary Wi-Fi Direct group owner, preferring 5 GHz, that can also be joined as a legacy AP.
 *
 * The group is deliberately not persistent. [close] removes it and releases the callback thread.
 */
class WifiP2pGroupManager(
    context: Context,
    private val diagnostic: (String) -> Unit = {},
    private val preferredChannel: Int = WifiP2pChannels.AUTO,
) : WirelessHotspotManager {
    init {
        require(WifiP2pChannels.isValid(preferredChannel)) { "Unsupported Wi-Fi Direct channel: $preferredChannel" }
    }
    private val appContext = context.applicationContext
    private val p2pManager = appContext.getSystemService(WifiP2pManager::class.java)
        ?: throw IllegalStateException("WifiP2pManager is unavailable")
    private val stateLock = Object()
    private val random = SecureRandom()
    private val configurationMemory = P2pConfigurationMemory(appContext)
    private var rememberedAttempt: P2pConfigurationMemory.Record? = null
    private var pendingSuccess: (() -> Boolean)? = null
    private var carPlayConfirmed = false
    private val ownership = appContext.getSharedPreferences("carplay_wifi_p2p", Context.MODE_PRIVATE)
    private val ssidPrefix by lazy {
        // ANDROID_ID is scoped to the signing key, user and device on our supported OS versions.
        val id = runCatching { Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID) }
            .getOrNull()?.takeIf { it.isNotBlank() }
            ?: ownership.getString("fallback_id", null)
            ?: java.util.UUID.randomUUID().toString().also {
                check(ownership.edit().putString("fallback_id", it).commit())
            }
        P2pOwnership.prefix(appContext.packageName, id)
    }

    private var channel: WifiP2pManager.Channel? = null
    private var callbackThread: HandlerThread? = null
    private var created = false
    private var closed = false
    private var startAttempt: StartAttempt? = null
    @Volatile private var observedCreatedName: String? = null
    @Volatile private var requestedName: String? = null

    // Wi-Fi P2P needs a permission the platform can refuse; every call below reports a failure
    // through the returned diagnostic or a rejected-attempt exception instead of crashing.
    @SuppressLint("MissingPermission")
    override fun connectionDiagnosticSnapshot(): String {
        val current = synchronized(stateLock) { if (closed) null else channel }
            ?: return "p2pGroup=unavailable association=unknown"
        val result = AtomicReference<WifiP2pGroup?>()
        val latch = CountDownLatch(1)
        return try {
            p2pManager.requestGroupInfo(current) { result.set(it); latch.countDown() }
            if (!latch.await(500, TimeUnit.MILLISECONDS)) {
                "p2pGroup=callback_timeout association=unknown"
            } else {
                val group = result.get()
                if (group == null) "p2pGroup=absent association=unknown"
                else "p2pGroup=present owner=${group.isGroupOwner} " +
                    "sameGroup=${group.networkName == observedCreatedName} " +
                    "reportedP2pClients=${group.clientList.size} association=unknown legacyClients=not_exposed"
            }
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            "p2pGroup=interrupted association=unknown"
        } catch (error: RuntimeException) {
            "p2pGroup=unavailable failureClass=${error.javaClass.simpleName} association=unknown"
        }
    }

    @SuppressLint("MissingPermission")
    override fun start(timeoutMillis: Long): WirelessHotspotInfo {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "WifiP2pGroupManager.start must not run on the main thread"
        }
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
        // A pinned channel is expressed through WifiP2pConfig.Builder, which arrived in API 29.
        // Below that the framework always selects the channel itself, so report the setting
        // before any group is created instead of creating one and rejecting it afterwards.
        if (preferredChannel != WifiP2pChannels.AUTO && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            throw P2pChannelUnavailableException(preferredChannel,
                "This Android version lets the system select the Wi-Fi Direct channel.")
        }

        val attempt = StartAttempt()
        synchronized(stateLock) {
            check(!closed) { "WifiP2pGroupManager is closed" }
            check(startAttempt == null && !created) {
                "A Wi-Fi P2P group is already starting or active"
            }
            startAttempt = attempt
        }

        val thread = HandlerThread("xcertplay-wifi-p2p").apply { start() }
        attempt.thread = thread
        val deadlineNanos = deadlineAfter(timeoutMillis)
        val credentials = randomCredentials()

        try {
            val station = readStation()
            val stationFrequency = station.alignmentFrequency
            checkPrerequisites(station)
            val remembered = configurationMemory.read()
            val preferred = remembered?.takeIf {
                preferredChannel == WifiP2pChannels.AUTO && it.stationMHz == stationFrequency
            }
            diagnostic("Wi-Fi P2P channel preference=${if (preferredChannel == WifiP2pChannels.AUTO) "auto" else preferredChannel} " +
                "frequencyMHz=${WifiP2pChannels.frequencyMhz(preferredChannel) ?: "auto"}")
            diagnostic(when {
                preferredChannel != WifiP2pChannels.AUTO -> "Wi-Fi P2P remembered skipped=manual_channel"
                preferred != null -> "Wi-Fi P2P remembered first mode=${preferred.request.mode} frequencyMHz=${preferred.request.frequencyMHz ?: "auto"}"
                remembered != null -> "Wi-Fi P2P remembered skipped=station_channel_changed"
                else -> "Wi-Fi P2P remembered unavailable"
            })
            val p2pChannel = p2pManager.initialize(
                appContext,
                thread.looper,
                createChannelListener(attempt),
            )
            attempt.channel = p2pChannel
            synchronized(stateLock) {
                ensureStartActiveLocked(attempt)
                channel = p2pChannel
                callbackThread = thread
            }

            // requestP2pState arrived in API 29. The state line is diagnostic only, so an older
            // framework skips it instead of failing the bring-up it was called from.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) logP2pState(attempt, p2pChannel)
            // Preferences disappear on reinstall, but the scoped namespace survives.
            val existing = requestGroupInfo(attempt, p2pChannel, REQUEST_POLL_NANOS, requireResponse = true)
            diagnostic("Wi-Fi P2P existingGroup=${existing != null}")
            if (existing != null) {
                if (!P2pOwnership.canReclaim(existing.isGroupOwner, existing.networkName,
                        ownership.getString("owned_ssid", null), ssidPrefix)) {
                    throw P2pResetRequiredException()
                }
                diagnostic("Wi-Fi P2P reclaiming retained owned group")
                removeGroupBlocking(p2pChannel, existing.networkName)
                val removalDeadline = minOf(deadlineNanos, deadlineAfter(REMOVE_GROUP_TIMEOUT_MILLIS))
                while (requestGroupInfo(attempt, p2pChannel, REQUEST_POLL_NANOS, requireResponse = true) != null) {
                    if (remainingNanos(removalDeadline) == 0L) throw IOException("Wi-Fi Direct reset did not finish")
                    synchronized(stateLock) { waitNanos(TimeUnit.MILLISECONDS.toNanos(100)) }
                }
            }

            val requestGroup: (P2pCreationRequest) -> Unit = { selection ->
                ensureStartActive(attempt)
                if (remainingNanos(deadlineNanos) == 0L) throw IOException("Wi-Fi Direct startup timed out")
                val config = if (selection.mode == P2pCreationMode.SYSTEM_DEFAULT) null else {
                    P2pConfigBuildDiagnostics.build(Build.VERSION.SDK_INT, selection, diagnostic) {
                        val builder = WifiP2pConfig.Builder()
                            .setNetworkName(credentials.ssid)
                            .setPassphrase(credentials.passphrase)
                        builder.setGroupOperatingFrequency(requireNotNull(selection.frequencyMHz))
                        builder.build()
                    }
                }
                if (config != null && !ownership.edit().putString("owned_ssid", credentials.ssid).commit()) {
                    throw IOException("Could not record Wi-Fi P2P group ownership")
                }
                val createRequest = CreateRequest()
                // Keep the requested SSID from the value we supplied. Some Android 10
                // vendor frameworks omit WifiP2pConfig.getNetworkName(), even though they
                // support the API 29 Builder methods used above. Reading config.networkName
                // therefore throws NoSuchMethodError on those devices.
                requestedName = if (config == null) null else credentials.ssid
                synchronized(stateLock) {
                    ensureStartActiveLocked(attempt)
                    attempt.request = createRequest
                }
                diagnostic("Wi-Fi P2P create mode=${selection.mode} frequencyMHz=${selection.frequencyMHz ?: "auto"}")
                // The API 29 overload with a null config uses system-generated credentials without
                // requesting a persistent group, unlike the two-argument API. That overload does not
                // exist below API 29, where the two-argument form is the only way to ask the
                // framework for a group whose credentials it generates itself.
                val usingRemembered = preferred?.request == selection
                if (usingRemembered) synchronized(stateLock) { rememberedAttempt = preferred }
                try {
                    if (config == null && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                        p2pManager.createGroup(p2pChannel, createActionListener(attempt, createRequest))
                    } else {
                        p2pManager.createGroup(p2pChannel, config, createActionListener(attempt, createRequest))
                    }
                    awaitGroupCreated(attempt, createRequest, deadlineNanos, timeoutMillis)
                } catch (failure: P2pCreateRejected) {
                    if (usingRemembered && failure.reason == WifiP2pManager.ERROR && preferred != null) {
                        if (configurationMemory.forget(preferred)) diagnostic("Wi-Fi P2P remembered cleared=create_rejected")
                    }
                    throw failure
                }
            }
            // Below API 29 WifiP2pConfig.Builder does not exist, so every explicit-frequency mode
            // P2pStartupRecovery.plan would offer throws before createGroup is reached. The
            // framework's own default configuration is the only request those releases can make,
            // and it is also the one whose credentials the framework generates.
            val creation = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                val systemDefault = P2pCreationRequest(P2pCreationMode.SYSTEM_DEFAULT)
                requestGroup(systemDefault)
                systemDefault
            } else {
                P2pStartupRecovery.create(
                    stationFrequency = stationFrequency,
                    preferred = preferred?.request,
                    preferredChannel = preferredChannel,
                    beforeRetry = {
                        ensureStartActive(attempt)
                        // Do not cancel discovery, toggle Wi-Fi, or remove a newly observed group.
                        // A competing app may have acquired the radio since our rejected request.
                        synchronized(stateLock) { waitNanos(TimeUnit.MILLISECONDS.toNanos(500)) }
                        ensureStartActive(attempt)
                        checkPrerequisites(readStation())
                        if (requestGroupInfo(attempt, p2pChannel, REQUEST_POLL_NANOS, requireResponse = true) != null) {
                            throw P2pResetRequiredException()
                        }
                    },
                    request = requestGroup,
                )
            }
            val group = awaitUsableGroup(
                attempt = attempt,
                channel = p2pChannel,
                credentials = if (creation.mode == P2pCreationMode.SYSTEM_DEFAULT) null else credentials,
                deadlineNanos = deadlineNanos,
                timeoutMillis = timeoutMillis,
            )
            if (!ownership.edit().putString("owned_ssid", group.ssid).commit()) {
                throw IOException("Could not record Wi-Fi P2P group ownership")
            }
            diagnostic("Wi-Fi P2P ready mode=${creation.mode} band=${group.bandLabel} channel=${group.channel} frequencyMHz=${group.frequencyMHz}")
            diagnostic("Wi-Fi P2P channel requestedMHz=${creation.frequencyMHz ?: "auto"} actualMHz=${group.frequencyMHz} matched=${creation.frequencyMHz?.let { it == group.frequencyMHz } ?: "system_selected"}")
            if (preferredChannel != WifiP2pChannels.AUTO && group.frequencyMHz != creation.frequencyMHz) {
                throw P2pChannelUnavailableException(preferredChannel,
                    "The car selected channel ${group.channel} instead.")
            }
            synchronized(stateLock) {
                ensureStartActiveLocked(attempt)
                created = true
                startAttempt = null
                // Manual experiments must not replace the proven automatic configuration.
                // Nothing to remember when the framework did not report a frequency (API < 29):
                // the record is keyed on the channel the car actually chose.
                pendingSuccess = if (preferredChannel == WifiP2pChannels.AUTO && group.frequencyMHz != null) {
                    {
                        configurationMemory.remember(creation, requireNotNull(group.frequencyMHz), stationFrequency)
                    }
                } else null
            }
            return group
        } catch (failure: Exception) {
            diagnostic("Wi-Fi P2P startup stopped type=${failure.javaClass.simpleName}")
            cleanupFailedStart(attempt)
            throw failure
        }
    }

    override fun onCarPlayConfirmed() = synchronized(stateLock) {
        if (closed || !created || carPlayConfirmed) return@synchronized
        val save = pendingSuccess ?: return@synchronized
        // Storage failure must not interrupt a working CarPlay session.
        carPlayConfirmed = true
        val saved = runCatching { save() }.getOrDefault(false)
        diagnostic("Wi-Fi P2P remembered saved=$saved proof=authenticated_first_frame")
    }

    override fun close() {
        val attempt: StartAttempt?
        val activeChannel: WifiP2pManager.Channel?
        val activeThread: HandlerThread?
        val removeGroup: Boolean
        synchronized(stateLock) {
            if (closed) return
            closed = true
            if (!carPlayConfirmed) rememberedAttempt?.let {
                if (configurationMemory.forget(it)) diagnostic("Wi-Fi P2P remembered cleared=session_unconfirmed")
            }
            pendingSuccess = null
            attempt = startAttempt
            attempt?.stopped = true
            stateLock.notifyAll()
            activeChannel = channel ?: attempt?.channel
            activeThread = callbackThread ?: attempt?.thread
            removeGroup = created || attempt?.createSucceeded == true
            channel = null
            callbackThread = null
            startAttempt = null
        }

        if (removeGroup && activeChannel != null) {
            removeGroupBlocking(activeChannel)
        }
        closeChannel(activeChannel)
        activeThread?.quitSafely()
    }

    private fun createChannelListener(
        attempt: StartAttempt,
    ): WifiP2pManager.ChannelListener = object : WifiP2pManager.ChannelListener {
        override fun onChannelDisconnected() {
            failAttempt(attempt, IOException("Wi-Fi P2P channel disconnected"))
        }
    }

    private fun createActionListener(
        attempt: StartAttempt,
        request: CreateRequest,
    ): WifiP2pManager.ActionListener = object : WifiP2pManager.ActionListener {
        override fun onSuccess() {
            val activeChannel = attempt.channel
            val removeDetachedGroup = synchronized(stateLock) {
                if (request.completed) return
                request.completed = true
                attempt.createSucceeded = true
                if (startAttempt === attempt && attempt.request === request && !attempt.stopped && !closed) {
                    created = true
                    stateLock.notifyAll()
                    false
                } else true
            }
            if (removeDetachedGroup && activeChannel != null) {
                removeGroup(activeChannel, waitForCallback = false)
            }
        }

        override fun onFailure(reason: Int) {
            synchronized(stateLock) {
                if (request.completed || attempt.request !== request) return
                request.completed = true
                request.failure = P2pCreateRejected(reason,
                    "Wi-Fi P2P createGroup failed: ${failureReason(reason)} (code=$reason)")
                stateLock.notifyAll()
            }
            diagnostic("Wi-Fi P2P create rejected code=$reason reason=${failureReason(reason)}")
        }
    }

    private fun failAttempt(attempt: StartAttempt, failure: IOException) {
        synchronized(stateLock) {
            if (startAttempt === attempt && !attempt.stopped && !closed) {
                if (attempt.failure == null) attempt.failure = failure
                stateLock.notifyAll()
            }
        }
    }

    private fun awaitGroupCreated(
        attempt: StartAttempt,
        request: CreateRequest,
        deadlineNanos: Long,
        timeoutMillis: Long,
    ) {
        synchronized(stateLock) {
            while (true) {
                ensureStartActiveLocked(attempt)
                attempt.failure?.let { throw it }
                request.failure?.let { throw it }
                if (attempt.createSucceeded) return

                val remainingNanos = remainingNanos(deadlineNanos)
                if (remainingNanos <= 0) {
                    throw IOException(
                        "Timed out after ${timeoutMillis}ms waiting for Wi-Fi P2P group creation",
                    )
                }
                waitNanos(remainingNanos)
            }
        }
    }

    private fun awaitUsableGroup(
        attempt: StartAttempt,
        channel: WifiP2pManager.Channel,
        credentials: Credentials?,
        deadlineNanos: Long,
        timeoutMillis: Long,
    ): WirelessHotspotInfo {
        var lastReason = "group information was not available"
        while (true) {
            ensureStartActive(attempt)
            val remainingNanos = remainingNanos(deadlineNanos)
            if (remainingNanos <= 0) {
                throw IOException(
                    "Timed out after ${timeoutMillis}ms waiting for a usable Wi-Fi P2P group: " +
                        lastReason,
                )
            }

            val group = requestGroupInfo(
                attempt = attempt,
                channel = channel,
                timeoutNanos = minOf(remainingNanos, REQUEST_POLL_NANOS),
            )
            if (group == null) continue
            if (!group.isGroupOwner) {
                throw IOException("Wi-Fi P2P device became a group client instead of owner")
            }

            val networkName = group.networkName?.takeIf { it.isNotBlank() }
            if (credentials != null && networkName != null && networkName != credentials.ssid) {
                throw IOException("Wi-Fi Direct returned an unexpected group")
            }
            val passphrase = group.passphrase?.takeIf { it.isNotBlank() }
                ?: credentials?.passphrase
            val interfaceName = group.getInterface()?.takeIf { it.isNotBlank() }
            // WifiP2pGroup.getFrequency is API 29. Below that the framework does not expose the
            // group's channel at all, so it stays unknown instead of being invented: the band is
            // reported as system-selected and the channel as 0, which is what the iPhone is told.
            val frequencyMHz = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) group.frequency else null
            val channelNumber = frequencyMHz?.let(::wifiFrequencyMhzToChannel)
            if (
                networkName == null || passphrase == null ||
                interfaceName == null ||
                (frequencyMHz != null && (frequencyMHz <= 0 || channelNumber == null))
            ) {
                lastReason = "incomplete group details frequencyMHz=${frequencyMHz ?: "unknown"}"
                continue
            }
            val band = when (frequencyMHz) {
                null -> "Unknown (system selected)"
                else -> when {
                    is5Ghz(frequencyMHz) -> "5 GHz"
                    frequencyMHz in 2412..2484 -> "2.4 GHz"
                    else -> throw IOException("Wi-Fi P2P returned an unsupported band at ${frequencyMHz}MHz")
                }
            }

            val hostAddress = awaitInterfaceAddress(attempt, interfaceName, deadlineNanos)
                ?: requestConnectionAddress(
                    attempt = attempt,
                    channel = channel,
                    timeoutNanos = minOf(remainingNanos(deadlineNanos), REQUEST_POLL_NANOS),
                )
            if (hostAddress == null) {
                lastReason = "interface $interfaceName has no usable IPv6 or IPv4 address"
                continue
            }

            return WirelessHotspotInfo(
                ssid = networkName,
                passphrase = passphrase,
                security = groupSecurity(group),
                // 0 means "the framework selected it": [channelNumber] is only known from API 29.
                channel = channelNumber ?: 0,
                frequencyMHz = frequencyMHz,
                bssid = interfaceHardwareAddress(interfaceName)
                    ?: group.owner?.deviceAddress?.takeIf { it.isNotBlank() },
                interfaceName = interfaceName,
                hostAddress = hostAddress,
                bandLabel = band,
                backend = WirelessHotspotBackend.WIFI_P2P,
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestGroupInfo(
        attempt: StartAttempt,
        channel: WifiP2pManager.Channel,
        timeoutNanos: Long,
        requireResponse: Boolean = false,
    ): WifiP2pGroup? {
        val result = AtomicReference<WifiP2pGroup?>()
        val latch = CountDownLatch(1)
        p2pManager.requestGroupInfo(channel) {
            // Keep the first identity returned after our successful creation. A later global
            // broadcast may describe a replacement group belonging to another app.
            if (attempt.createSucceeded && observedCreatedName == null && it?.isGroupOwner == true &&
                (requestedName == null || it.networkName == requestedName)) {
                observedCreatedName = it.networkName
            }
            result.set(it)
            latch.countDown()
        }
        if (!await(latch, timeoutNanos)) {
            if (requireResponse) throw IOException("Wi-Fi Direct did not respond")
            return null
        }
        ensureStartActive(attempt)
        return result.get()
    }

    private fun requestConnectionAddress(
        attempt: StartAttempt,
        channel: WifiP2pManager.Channel,
        timeoutNanos: Long,
    ): InetAddress? {
        val result = AtomicReference<WifiP2pInfo?>()
        val latch = CountDownLatch(1)
        p2pManager.requestConnectionInfo(channel) {
            result.set(it)
            latch.countDown()
        }
        if (!await(latch, timeoutNanos)) return null
        ensureStartActive(attempt)
        val info = result.get() ?: return null
        if (!info.groupFormed) return null
        return info.groupOwnerAddress?.takeUnless(InetAddress::isAnyLocalAddress)
    }

    private fun await(latch: CountDownLatch, timeoutNanos: Long): Boolean = try {
        val waitNanos = timeoutNanos.coerceAtLeast(1L)
        latch.await(waitNanos, TimeUnit.NANOSECONDS)
    } catch (interrupted: InterruptedException) {
        Thread.currentThread().interrupt()
        throw IOException("Interrupted while waiting for Wi-Fi P2P", interrupted)
    }

    private fun interfaceAddress(interfaceName: String): InetAddress? {
        val networkInterface = networkInterface(interfaceName) ?: return null
        val address = HotspotAddressPolicy.select(Collections.list(networkInterface.inetAddresses))
            ?: return null
        if (address !is Inet6Address || address.scopeId == networkInterface.index) return address
        return runCatching { Inet6Address.getByAddress(null, address.address, networkInterface) }
            .getOrDefault(address)
    }

    private fun awaitInterfaceAddress(
        attempt: StartAttempt,
        interfaceName: String,
        startupDeadlineNanos: Long,
    ): InetAddress? {
        val addressDeadline = minOf(startupDeadlineNanos, deadlineAfter(2_000))
        var pending: InetAddress? = null
        while (true) {
            ensureStartActive(attempt)
            val address = interfaceAddress(interfaceName)
            if (address is Inet4Address) return address
            if (address != null) pending = address
            if (remainingNanos(addressDeadline) <= 0) return pending
            Thread.sleep(100)
        }
    }

    private data class Station(val state: SupplicantState?, val reportedFrequency: Int?) {
        // A vendor may retain the last frequency after disconnecting. Only align to an
        // established Wi-Fi association; internet validation is deliberately irrelevant.
        val alignmentFrequency: Int?
            get() = reportedFrequency?.takeIf { state == SupplicantState.COMPLETED && it > 0 }
    }

    @Suppress("DEPRECATION")
    private fun readStation(): Station = runCatching {
        val info = appContext.getSystemService(WifiManager::class.java)?.connectionInfo
        Station(info?.supplicantState, info?.frequency?.takeIf { it > 0 })
    }.getOrDefault(Station(null, null))

    private fun checkPrerequisites(station: Station) {
        val wifi = appContext.getSystemService(WifiManager::class.java)
        val fiveGhzSupported = runCatching { wifi?.is5GHzBandSupported }.getOrNull()
        val wifiEnabled = runCatching { wifi?.isWifiEnabled }.getOrNull()
        val locationEnabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { appContext.getSystemService(LocationManager::class.java)?.isLocationEnabled }.getOrNull()
        } else {
            // LocationManager.isLocationEnabled is API 28; runCatching would not contain the
            // NoSuchMethodError on this API 25 unit, so ask the legacy providers instead. They
            // answer Boolean?, which the || below cannot take, hence the comparisons.
            val manager = appContext.getSystemService(LocationManager::class.java)
            runCatching {
                manager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true ||
                    manager?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true
            }.getOrNull()
        }
        val required = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES
            else Manifest.permission.ACCESS_FINE_LOCATION
        val granted = appContext.checkSelfPermission(required) == PackageManager.PERMISSION_GRANTED
        val locationAccessMode = if (Build.VERSION.SDK_INT in 29..32) runCatching {
            appContext.getSystemService(AppOpsManager::class.java)?.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_FINE_LOCATION, android.os.Process.myUid(), appContext.packageName)
        }.getOrNull() else null
        diagnostic("Wi-Fi P2P preflight wifiEnabled=$wifiEnabled locationEnabled=$locationEnabled permissionGranted=$granted locationAccessMode=${locationAccessMode ?: "unknown"} stationMHz=${station.alignmentFrequency ?: "unknown"} stationState=${station.state ?: "unknown"} reportedStationMHz=${station.reportedFrequency ?: "unknown"} fiveGhzSupported=${fiveGhzSupported ?: "unknown"}")
        if (!granted) throw IOException(if (Build.VERSION.SDK_INT >= 33)
            "Allow Nearby devices for DiPlay in the head unit's app permissions"
            else "Allow precise Location for DiPlay in the head unit's app permissions")
        if (wifiEnabled == false) throw IOException("Turn on Wi-Fi in the head unit's settings, then reconnect")
        // Location mode is diagnostic only: AOSP createGroup does not require it to be on.
        // Do not block firmware where Wi-Fi Direct works with Location services disabled.
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun logP2pState(attempt: StartAttempt, channel: WifiP2pManager.Channel) {
        val result = AtomicReference<Int?>()
        val latch = CountDownLatch(1)
        p2pManager.requestP2pState(channel) { state -> result.set(state); latch.countDown() }
        await(latch, REQUEST_POLL_NANOS)
        ensureStartActive(attempt)
        diagnostic("Wi-Fi P2P frameworkState=${result.get() ?: "unknown"}")
    }

    private fun interfaceHardwareAddress(interfaceName: String): String? =
        networkInterface(interfaceName)
            ?.hardwareAddress
            ?.takeIf { it.isNotEmpty() }
            ?.joinToString(":") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun networkInterface(interfaceName: String): NetworkInterface? = try {
        NetworkInterface.getByName(interfaceName)
    } catch (_: SocketException) {
        null
    }

    private fun groupSecurity(group: WifiP2pGroup): Iap2WirelessSecurity {
        if (Build.VERSION.SDK_INT < 36) return Iap2WirelessSecurity.WPA_WPA2
        return when (group.securityType) {
            WifiP2pGroup.SECURITY_TYPE_WPA2_PSK -> Iap2WirelessSecurity.WPA_WPA2
            WifiP2pGroup.SECURITY_TYPE_WPA3_COMPATIBILITY ->
                Iap2WirelessSecurity.WPA3_TRANSITION
            WifiP2pGroup.SECURITY_TYPE_WPA3_SAE -> Iap2WirelessSecurity.WPA3_ONLY
            // Several vendor supplicants report an authentication key management the framework
            // cannot classify, which surfaces as UNKNOWN rather than as a concrete cipher suite.
            // The group itself is still the plain WPA2-PSK group this device creates by default,
            // so keep the assumption every older Android release relies on instead of aborting a
            // bring-up that has already resolved its SSID, band, interface and host address.
            WifiP2pGroup.SECURITY_TYPE_UNKNOWN -> {
                diagnostic("Wi-Fi P2P security type not reported by the framework; assuming WPA2-PSK")
                Iap2WirelessSecurity.WPA_WPA2
            }
            else -> throw IOException(
                "Unsupported Wi-Fi P2P security type: ${group.securityType}",
            )
        }
    }

    private fun randomCredentials(): Credentials = Credentials(
        ssid = "$ssidPrefix${randomToken(4)}",
        passphrase = randomToken(16),
    )

    private fun randomToken(length: Int): String =
        buildString(length) {
            repeat(length) {
                append(TOKEN_ALPHABET[random.nextInt(TOKEN_ALPHABET.length)])
            }
        }

    private fun ensureStartActive(attempt: StartAttempt) {
        synchronized(stateLock) {
            ensureStartActiveLocked(attempt)
        }
    }

    private fun ensureStartActiveLocked(attempt: StartAttempt) {
        if (closed) throw IOException("WifiP2pGroupManager closed while starting")
        if (startAttempt !== attempt) throw IOException("Wi-Fi P2P startup was cancelled")
        if (attempt.stopped) throw IOException("Wi-Fi P2P group stopped before startup completed")
        attempt.failure?.let { throw it }
    }

    private fun cleanupFailedStart(attempt: StartAttempt) {
        val failedChannel: WifiP2pManager.Channel?
        val failedThread: HandlerThread?
        val removeGroup: Boolean
        synchronized(stateLock) {
            if (startAttempt === attempt) startAttempt = null
            attempt.stopped = true
            stateLock.notifyAll()
            failedChannel = attempt.channel
            failedThread = attempt.thread
            removeGroup = attempt.createSucceeded
            created = false
            if (channel === failedChannel) channel = null
            if (callbackThread === failedThread) callbackThread = null
        }
        if (removeGroup && failedChannel != null) {
            removeGroupBlocking(failedChannel)
        }
        closeChannel(failedChannel)
        failedThread?.quitSafely()
    }

    /**
     * `WifiP2pManager.Channel.close()` is API 27. On Android 7 the channel had no close method at
     * all, so calling it raises NoSuchMethodError and takes the process down; the looper quit below
     * is what actually released the channel there.
     */
    private fun closeChannel(channel: WifiP2pManager.Channel?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) return
        if (channel != null) runCatching { channel.close() }
            .onFailure { Log.w(TAG, "Wi-Fi P2P channel close failed", it) }
    }

    private fun removeGroupBlocking(channel: WifiP2pManager.Channel, expectedName: String? = observedCreatedName ?: requestedName) {
        removeGroup(channel, waitForCallback = true, expectedName = expectedName)
    }

    @SuppressLint("MissingPermission")
    private fun removeGroup(channel: WifiP2pManager.Channel, waitForCallback: Boolean,
        expectedName: String? = observedCreatedName ?: requestedName) {
        val latch = CountDownLatch(1)
        try {
            p2pManager.requestGroupInfo(channel) { current ->
                if (current == null) {
                    latch.countDown()
                    return@requestGroupInfo
                }
                // Cleanup needs this attempt's exact identity. The reinstall namespace used at
                // startup is broader and could also match a newer DiPlay session.
                val ours = current.isGroupOwner && !expectedName.isNullOrBlank() &&
                    current.networkName == expectedName
                if (!ours) {
                    diagnostic("Wi-Fi P2P cleanup skipped=another_app_owns_group")
                    latch.countDown()
                    return@requestGroupInfo
                }
                p2pManager.removeGroup(channel, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        latch.countDown()
                    }

                    override fun onFailure(reason: Int) {
                        diagnostic("Wi-Fi P2P remove rejected code=$reason reason=${failureReason(reason)}")
                        latch.countDown()
                    }
                })
            }
        } catch (failure: RuntimeException) {
            Log.w(TAG, "Wi-Fi P2P removeGroup could not be issued", failure)
            latch.countDown()
        }
        if (!waitForCallback) return
        try {
            latch.await(REMOVE_GROUP_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun waitNanos(nanos: Long) {
        val millis = nanos / NANOS_PER_MILLISECOND
        val remainder = (nanos % NANOS_PER_MILLISECOND).toInt()
        try {
            stateLock.wait(millis, remainder)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Interrupted while waiting for Wi-Fi P2P", interrupted)
        }
    }

    private fun deadlineAfter(timeoutMillis: Long): Long {
        val now = System.nanoTime()
        val delta = timeoutMillis * NANOS_PER_MILLISECOND
        return if (Long.MAX_VALUE - now < delta) Long.MAX_VALUE else now + delta
    }

    private fun remainingNanos(deadlineNanos: Long): Long =
        (deadlineNanos - System.nanoTime()).coerceAtLeast(0L)

    private fun failureReason(reason: Int): String = when (reason) {
        WifiP2pManager.P2P_UNSUPPORTED -> "Wi-Fi P2P is unsupported"
        WifiP2pManager.BUSY -> "Wi-Fi P2P is busy"
        WifiP2pManager.ERROR -> "generic error"
        WifiP2pManager.NO_PERMISSION -> "permission denied"
        else -> "reason $reason"
    }

    private fun is5Ghz(frequencyMHz: Int): Boolean = frequencyMHz in 5150..5895

    private class StartAttempt {
        var channel: WifiP2pManager.Channel? = null
        var thread: HandlerThread? = null
        var createSucceeded = false
        var request: CreateRequest? = null
        var failure: IOException? = null
        var stopped = false
    }

    private class CreateRequest {
        var completed = false
        var failure: P2pCreateRejected? = null
    }

    private class Credentials(
        val ssid: String,
        val passphrase: String,
    )

    private companion object {
        const val TAG = "xcertplay-usb"
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val REMOVE_GROUP_TIMEOUT_MILLIS = 2_000L
        val REQUEST_POLL_NANOS: Long = TimeUnit.MILLISECONDS.toNanos(500)
        const val TOKEN_ALPHABET =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
    }
}

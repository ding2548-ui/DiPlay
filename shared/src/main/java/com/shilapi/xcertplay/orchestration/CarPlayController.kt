package com.shilapi.xcertplay.orchestration

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import android.provider.Settings
import android.util.Log
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayContact
import com.shilapi.xcertplay.airplay.AirPlayDeviceInfo
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlayKnobState
import com.shilapi.xcertplay.airplay.AirPlayMediaHandler
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.PairingStore
import com.shilapi.xcertplay.airplay.VideoInCar
import com.shilapi.xcertplay.hud.BydNavigationOutputs
import com.shilapi.xcertplay.iap2.session.Iap2Session
import com.shilapi.xcertplay.mfi.Iap2MfiAuthenticationClient
import com.shilapi.xcertplay.mfi.MfiAuthenticationClient
import com.shilapi.xcertplay.mfi.RemoteMfiAuthenticationClient
import com.shilapi.xcertplay.mfi.LocalMfiAuthenticationClient
import com.shilapi.xcertplay.network.CarPlayBonjour
import com.shilapi.xcertplay.network.CarPlayBonjourEvent
import com.shilapi.xcertplay.network.diagnosticSummary
import com.shilapi.xcertplay.network.countsAsPhoneDiscovery
import com.shilapi.xcertplay.network.CarPlayVpnService
import com.shilapi.xcertplay.network.LWIP_LISTEN_PORT
import com.shilapi.xcertplay.network.LwipNative
import com.shilapi.xcertplay.network.LwipSessionNetwork
import com.shilapi.xcertplay.network.ExternalWifiManager
import com.shilapi.xcertplay.network.LocalOnlyHotspotManager
import com.shilapi.xcertplay.network.ManualHotspotManager
import com.shilapi.xcertplay.network.MdnsSniffer
import com.shilapi.xcertplay.network.PhoneProbe
import com.shilapi.xcertplay.network.P2pResetRequiredException
import com.shilapi.xcertplay.network.WifiP2pGroupManager
import com.shilapi.xcertplay.network.WirelessHotspotInfo
import com.shilapi.xcertplay.network.WirelessHotspotBackend
import com.shilapi.xcertplay.network.WirelessHotspotManager
import com.shilapi.xcertplay.transport.BlockingDuplexByteStream
import com.shilapi.xcertplay.transport.BluetoothRfcommDuplexStream
import com.shilapi.xcertplay.transport.Ch341DeviceMatcher
import com.shilapi.xcertplay.transport.Ch341I2cTransport
import com.shilapi.xcertplay.transport.Ch341UsbHost
import com.shilapi.xcertplay.transport.Ch341UsbSession
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import com.shilapi.xcertplay.transport.Iap2LocationProvider
import com.shilapi.xcertplay.transport.Iap2UsbMuxHost
import com.shilapi.xcertplay.transport.Iap2UsbSession
import com.shilapi.xcertplay.transport.Iap2WiredCarPlayEndpoint
import com.shilapi.xcertplay.transport.Iap2WiredControlClient
import com.shilapi.xcertplay.transport.Iap2WiredControlTerminal
import com.shilapi.xcertplay.transport.Iap2WirelessCarPlayEndpoint
import com.shilapi.xcertplay.transport.Iap2WirelessControlClient
import com.shilapi.xcertplay.transport.Iap2WirelessControlResult
import com.shilapi.xcertplay.transport.Iap2WirelessControlTerminal
import com.shilapi.xcertplay.transport.Iap2WirelessIdentification
import com.shilapi.xcertplay.transport.Iap2WirelessLinkRole
import com.shilapi.xcertplay.transport.forWirelessLink
import com.shilapi.xcertplay.transport.I2cTransport
import com.shilapi.xcertplay.transport.I2cTransportException
import com.shilapi.xcertplay.transport.IphoneCarPlayConfiguration
import com.shilapi.xcertplay.transport.IphoneUsbException
import com.shilapi.xcertplay.transport.IphoneUsbHost
import com.shilapi.xcertplay.transport.IphoneUsbMatcher
import com.shilapi.xcertplay.transport.LinuxI2cTransport
import com.shilapi.xcertplay.transport.LockdownCarKitClient
import com.shilapi.xcertplay.transport.LockdownPairingClient
import com.shilapi.xcertplay.transport.LockdownPairRecord
import com.shilapi.xcertplay.transport.NcmFunctionDiscovery
import com.shilapi.xcertplay.transport.NcmUsbBridge
import java.io.Closeable
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.Collections
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

sealed class CarPlayStatus {
    data object DiscoveringMfi : CarPlayStatus()
    data object WaitingForMfi : CarPlayStatus()
    data object RequestingMfiPermission : CarPlayStatus()
    data object MfiReady : CarPlayStatus()
    data object StartingHotspot : CarPlayStatus()
    data class HotspotReady(
        val ssid: String,
        val band: String,
        val channel: Int,
        val bssid: String,
        val address: String,
        val backend: String,
    ) : CarPlayStatus()
    data object WaitingForPairedIphone : CarPlayStatus()
    data object ConnectingBluetooth : CarPlayStatus()
    data object RunningWireless : CarPlayStatus()
    data object WirelessActive : CarPlayStatus()
    data object DiscoveringIphone : CarPlayStatus()
    data object WaitingForIphone : CarPlayStatus()
    data object RequestingIphonePermission : CarPlayStatus()
    data object WaitingForReenumeration : CarPlayStatus()
    data object SelectingConfiguration : CarPlayStatus()
    data object OpeningDataPaths : CarPlayStatus()
    data object Pairing : CarPlayStatus()
    data object ConnectingControl : CarPlayStatus()
    data object AttachingNetwork : CarPlayStatus()
    data object RunningControl : CarPlayStatus()
    data object ControlEnded : CarPlayStatus()
    data class Failed(val message: String, val wifiResetRequired: Boolean = false) : CarPlayStatus()
}

internal fun isWirelessHandoffInProgress(
    handoffRequested: Boolean,
    tunnelActive: Boolean,
    sessionActive: Boolean,
): Boolean = handoffRequested || tunnelActive || sessionActive

/**
 * Wires the complete wired or wireless CarPlay path: MFi coprocessor discovery, iPhone bring-up,
 * iAP2 control, transport setup, and the AirPlay media/input sessions.
 *
 * All blocking USB/I2C work runs on one worker executor. Status callbacks are delivered on the
 * main thread. This class is the integration seam only and is not evidence of hardware operation.
 */
class CarPlayController(
    context: Context,
    private val config: CarPlayRuntimeConfig,
    private val airPlayConfig: AirPlayConfig,
    private val identity: AirPlayIdentity,
    private val pairings: PairingStore,
    listener: AirPlaySessionListener,
    private val media: AirPlayMediaHandler,
    reportStatus: (CarPlayStatus) -> Unit,
    private val loadPairRecord: () -> LockdownPairRecord? = { null },
    private val savePairRecord: (LockdownPairRecord) -> Unit = {},
    private val clearPairRecord: () -> Unit = {},
    private val locationProvider: Iap2LocationProvider? = null,
) : Closeable {
    init {
        require(!config.locationReportingEnabled || locationProvider != null) {
            "A location provider is required when location reporting is enabled"
        }
        BydNavigationOutputs.start(context.applicationContext)
    }

    private enum class Phase { IDLE, MFI, WIRELESS, IPHONE, REENUMERATION, DATAPATHS, CONTROL }

    private val appContext = context.applicationContext
    private val diagnosticAttempt = diagnosticAttempts.incrementAndGet()
    private val diagnosticRun = AtomicInteger()
    private val usbManager = context.getSystemService(UsbManager::class.java)
    private val bluetoothAdapter =
        appContext.getSystemService(BluetoothManager::class.java)?.adapter
    private val iphoneHost = IphoneUsbHost(
        appContext,
        usbManager,
        if (config.iphoneDevices.isNotEmpty()) {
            IphoneUsbMatcher(config.iphoneDevices)
        } else {
            IphoneUsbMatcher.appleVendor()
        },
    )
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val touchExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val tunnelExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val hostId = UUID.randomUUID().toString().uppercase(Locale.US)
    private val systemBuid = UUID.randomUUID().toString().uppercase(Locale.US)
    private val lifecycleLock = Any()
    @Volatile private var uiListener: AirPlaySessionListener? = listener
    @Volatile private var uiStatusReporter: ((CarPlayStatus) -> Unit)? = reportStatus
    private val permissionGrant = AtomicBoolean(false)
    private val availabilityPollGeneration = AtomicInteger(0)
    private var permissionPollGeneration = 0
    /** Vendor-request transitions issued this bring-up (first entry into CarPlay USB mode). */
    private var configurationTransitions = 0
    /** Device node handed to the transition request; a different node proves the switch. */
    private var transitionSourceDeviceName: String? = null
    private var lastReportedStatus: CarPlayStatus? = null
    private var mfiResetLogged = false

    @Volatile private var closed = false
    @Volatile private var phase = Phase.IDLE
    @Volatile private var ch341Host: Ch341UsbHost? = null
    @Volatile private var mfiSession: MfiSession? = null
    @Volatile private var mux: Iap2UsbMuxHost? = null

    /**
     * The open USBMUX pipe. Tracked separately from [mux] because [Iap2UsbMuxHost.open] can block
     * for the whole 60 s handshake: if the user closes the app mid-handshake, `mux` is still null
     * and the connection would never be released. That leak leaves the phone's bulk endpoints bound
     * to a dead connection, and the next bring-up then fails with "could not queue USBMUX read
     * request" until the cable is physically re-plugged.
     */
    @Volatile private var wiredUsbSession: Iap2UsbSession? = null
    @Volatile private var csm: Iap2Session? = null
    @Volatile private var activeSession: AirPlaySession? = null
    // Ported from upstream 0.2.7: the iPhone's media playback state, fed by iAP2 NowPlayingUpdate.
    // It is the prerequisite for the media-button layer (steering-wheel keys) on this car line.
    private val playbackStatus = com.shilapi.xcertplay.media.CarPlayPlaybackStatus()

    /** Told when the iPhone starts or stops playing media; may run on any thread. */
    @Volatile var playbackListener: ((Boolean) -> Unit)? = null

    /** Told when retained iPhone now-playing metadata changes; may run on any thread. */
    @Volatile var nowPlayingListener: ((com.shilapi.xcertplay.media.CarPlayNowPlaying) -> Unit)? = null

    /** Told when an iAP2 Now Playing artwork transfer completes; may run on the link worker. */
    @Volatile var artworkListener: ((Int, ByteArray) -> Unit)? = null

    /** Video in car; set before [start] to offer it to the iPhone (with AirPlayConfig.videoInCar). */
    @Volatile var videoListener: CarPlayVideoListener? = null
    @Volatile private var videoGate: VideoInCarGate? = null
    @Volatile private var hotspot: WirelessHotspotManager? = null
    @Volatile private var bonjour: CarPlayBonjour? = null
    @Volatile private var bluetoothSocket: BluetoothSocket? = null
    @Volatile private var bluetoothStream: BluetoothRfcommDuplexStream? = null
    @Volatile private var wirelessTunnelChannel: Iap2Session? = null
    @Volatile private var wirelessIdentification: Iap2IdentificationConfig? = null
    @Volatile private var wirelessAirPlayEndpoint: Iap2WirelessCarPlayEndpoint? = null
    @Volatile private var vpnService: CarPlayVpnService? = null
    @Volatile private var lwip: LwipSessionNetwork? = null
    @Volatile private var vpnBound = false
    private val wirelessHandoffRequested = AtomicBoolean(false)
    private val wirelessTunnelReady = AtomicBoolean(false)
    private val wirelessActiveReported = AtomicBoolean(false)
    private val wirelessGeneration = AtomicInteger(0)
    private val wirelessConnectionProof = WirelessConnectionProof<AirPlaySession>()

    /**
     * Evidence counters for [logWirelessBringUpVerdict]. Every wireless failure boils down to
     * "which of these three never happened", and that is impossible to tell from the log by eye
     * because the absent lines are exactly the ones you cannot grep for in a large report.
     */
    private val wirelessControlDiscoveryEvents = AtomicInteger(0)
    private val wirelessAirPlayConnections = AtomicInteger(0)

    /**
     * True once this run actually got a hotspot up, so the verdict has something to report.
     *
     * `runWireless` calls [closeWirelessStack] first to clear any previous run, and that cleanup
     * would otherwise emit a verdict with freshly-reset counters — a line that reads like a real
     * result ("verdict=discovery, session ended early") but is pure noise, and it buries the one
     * meaningful verdict under a duplicate.
     */
    @Volatile private var wirelessRunReachedHotspot = false

    private var permissionCloseable: Closeable? = null
    private var attachCloseable: Closeable? = null
    private var ch341PermissionCloseable: Closeable? = null
    private var vpnLatch = CountDownLatch(1)
    private val teardownComplete = CountDownLatch(1)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            vpnService = (binder as CarPlayVpnService.LocalBinder).service
            vpnLatch.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            vpnService = null
            fail(IphoneUsbException.DeviceUnavailable("CarPlay VPN service disconnected"))
        }
    }

    private val sessionListener = object : AirPlaySessionListener {
        override fun onSessionActive(session: AirPlaySession) {
            if (activeSession !== session) {
                BydNavigationOutputs.start(appContext)
                // The gear may have changed since /info.
                if (videoListener != null) {
                    val delivery = session.setVideoPlaybackAllowed(VideoInCar.allowed)
                    debugLog("video in car session allowed=${VideoInCar.allowed} delivery=$delivery")
                }
            }
            activeSession = session
            wirelessAirPlayConnections.incrementAndGet()
            debugLog(
                "AirPlay session active controller=${session.controllerId ?: "unknown"} " +
                    "peer=${session.host}",
            )
            uiListener?.onSessionActive(session)
        }

        override fun onSessionEnded(session: AirPlaySession) {
            if (activeSession === session) {
                activeSession = null
                BydNavigationOutputs.endNow()
                videoListener?.onVideoSessionEnded()
            }
            synchronized(playbackStatus) {
                val wasPlaying = playbackStatus.playing
                playbackStatus.clearAll()?.let { it to wasPlaying }
            }?.let { (cleared, wasPlaying) ->
                nowPlayingListener?.invoke(cleared)
                if (wasPlaying) playbackListener?.invoke(false)
            }
            debugLog("AirPlay session ended peer=${session.host}")
            uiListener?.onSessionEnded(session)
        }

        override fun onTransportError(message: String) {
            debugLog("AirPlay transport error: $message")
            uiListener?.onTransportError(message)
        }

        override fun onDeviceInfo(session: AirPlaySession, info: AirPlayDeviceInfo) {
            debugLog(
                "AirPlay device info name=${info.name} deviceId=${info.deviceId} " +
                    "wifiMac=${info.wifiMac} model=${info.model}",
            )
            uiListener?.onDeviceInfo(session, info)
        }

        // The user tapped the car icon in CarPlay: show the head unit's own menu, like its Home button.
        // The session keeps running in the background, so returning to DiPlay resumes CarPlay.
        override fun onHostUiRequested(session: AirPlaySession) {
            debugLog("CarPlay requested the car UI; opening the head-unit home screen")
            runCatching {
                appContext.startActivity(
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.onFailure { debugLog("Car home screen could not open: ${it.javaClass.simpleName}") }
            uiListener?.onHostUiRequested(session)
        }

        override fun onRemoteControlMessage(session: AirPlaySession, streamId: Long, message: Map<String, Any?>) {
            if (activeSession === session) videoListener?.onVideoMessage(streamId, message)
        }

        override fun onVideoPlaybackUiRequested(session: AirPlaySession) {
            debugLog("CarPlay requested the car's video player")
            if (activeSession === session) videoListener?.onVideoUiRequested()
        }

        override fun onCommand(session: AirPlaySession, type: String, params: Map<String, Any?>) {
            debugLog(
                "AirPlay command type=$type params=${params.keys.sorted().joinToString(",")}",
            )
            if (
                config.transport == CarPlayTransport.WIRELESS &&
                !closed &&
                activeSession === session &&
                isBluetoothHandoffCommand(type) &&
                wirelessHandoffRequested.compareAndSet(false, true)
            ) {
                debugLog(
                    "wireless CarPlay Bluetooth handoff requested; " +
                        "waiting for tunnel iAP2 readiness",
                )
                armWirelessHandoffWatchdog(wirelessGeneration.get())
                maybeCompleteWirelessHandoff()
            }
            uiListener?.onCommand(session, type, params)
        }

        override fun onDebugLog(message: String) {
            debugLog(message)
        }
    }

    fun attachUi(
        listener: AirPlaySessionListener,
        reportStatus: (CarPlayStatus) -> Unit,
    ) {
        uiListener = listener
        uiStatusReporter = reportStatus
        mainHandler.post {
            if (uiListener === listener) lastReportedStatus?.let(reportStatus)
        }
    }

    fun isClosed(): Boolean = closed

    fun hasActiveAirPlayAttachment(): Boolean = synchronized(lifecycleLock) {
        !closed && vpnService?.isAttached() == true
    }

    fun start() {
        synchronized(this) {
            if (closed) return
        }
        connectionDiagnostic("start transport=${config.transport}")
        videoListener?.let { listener ->
            videoGate = VideoInCarGate(
                readVideoAllowed = listener::readVideoAllowed,
                onChanged = { allowed ->
                    val delivery = activeSession?.setVideoPlaybackAllowed(allowed)
                    debugLog("video in car allowed=$allowed delivery=${delivery ?: "no session"}")
                    listener.onVideoAllowedChanged(allowed)
                },
            ).also { it.start() }
        }
        if (config.transport == CarPlayTransport.WIRED) {
            permissionCloseable = iphoneHost.registerPermissionReceiver(::onIphonePermission)
            attachCloseable = iphoneHost.registerAttachReceiver(::onIphoneAttached)
        }
        startMfi()
    }

    /** Reopens the CH341/MFi path without restarting the app. */
    fun reconnectMfi() = synchronized(lifecycleLock) {
        if (closed) return
        closeMfiSession()
        startMfi()
    }

    /** Re-runs iPhone discovery/bring-up using the already-open MFi session. */
    fun reconnectIphone() = synchronized(lifecycleLock) {
        if (closed) return
        if (mfiSession == null) {
            startMfi()
        } else if (config.transport == CarPlayTransport.WIRELESS) {
            restartWireless()
        } else {
            startIphone()
        }
    }

    fun sendTouch(contacts: List<AirPlayContact>): Boolean {
        if (closed) return false
        val session = activeSession ?: return false
        return try {
            touchExecutor.execute { session.sendTouch(contacts) }
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Sends a CarPlay knob/touchpad movement or button state through the AirPlay HID channel. */
    fun sendKnob(state: AirPlayKnobState, momentary: Boolean = true): Boolean {
        if (closed) return false
        val session = activeSession ?: return false
        return try {
            touchExecutor.execute { session.sendKnob(state, momentary) }
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * A hardware media key of the head unit (steering wheel) as a CarPlay media press; the index
     * comes from [com.shilapi.xcertplay.airplay.CarPlayButton]. The Leapmotor car sends its keys
     * as `car.meter.music.BROADCAST` commands, which [com.shilapi.xcertplay.LeapmotorMediaKeys]
     * turns into these presses. May be called from any thread.
     */
    fun sendMediaButton(index: Int): Boolean {
        if (closed) return false
        val session = activeSession ?: return false
        return try {
            touchExecutor.execute { session.sendMedia(index) }
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Opens Siri on the iPhone; used by the head unit's voice key. */
    fun invokeSiri(): Boolean {
        if (closed) return false
        val session = activeSession ?: return false
        return try {
            touchExecutor.execute { session.invokeSiri() }
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Answers the iPhone on a video in car remote control session; a network write, any thread. */
    fun sendVideoMessage(streamId: Long, message: Map<String, Any?>): Boolean =
        activeSession?.sendRemoteControlMessage(streamId, message) ?: false

    override fun close() {
        synchronized(this) {
            if (closed) return
            closed = true
        }
        val teardownStarted = System.nanoTime()
        connectionDiagnostic("teardown begin transport=${config.transport}")
        videoGate?.close()
        BydNavigationOutputs.endNow()
        closeReceivers()
        availabilityPollGeneration.incrementAndGet()
        wirelessGeneration.incrementAndGet()
        permissionPollGeneration += 1
        touchExecutor.shutdownNow()
        tunnelExecutor.shutdownNow()
        val service = vpnService
        unbindVpn()
        Thread(
            {
                try {
                    if (config.transport == CarPlayTransport.WIRELESS) {
                        closeBestEffort("wireless stack") { closeWirelessStack(service) }
                    } else {
                        closeBestEffort("CSM") { csm?.close() }
                        csm = null
                    }
                    closeBestEffort("USBMUX") { mux?.close() }
                    mux = null
                    // Release the pipe directly as well: when the app is closed mid-handshake the
                    // host above was never constructed, so `mux` is null and only this closes the
                    // USB connection. Leaving it open is what forces a physical re-plug.
                    closeBestEffort("USBMUX pipe") { wiredUsbSession?.close() }
                    wiredUsbSession = null
                    if (config.transport == CarPlayTransport.WIRED) {
                        closeBestEffort("VPN/NCM") { service?.detach() }
                        closeBestEffort("lwIP") { lwip?.close() }
                        lwip = null
                    }
                    closeBestEffort("MFi") { mfiSession?.close() }
                    mfiSession = null
                    wirelessIdentification = null
                    wirelessAirPlayEndpoint = null
                    closeBestEffort("location provider") { locationProvider?.close() }
                } finally {
                    executor.shutdownNow()
                    var executorTerminated = false
                    try {
                        executorTerminated = executor.awaitTermination(EXECUTOR_CLOSE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                    connectionDiagnostic(
                        "teardown end elapsedMs=${elapsedMillis(teardownStarted)} " +
                            "executorTerminated=$executorTerminated",
                    )
                    teardownComplete.countDown()
                }
            },
            "xcertplay-controller-teardown",
        ).apply {
            isDaemon = true
            start()
        }
    }

    /** Waits for USB, iAP2, MFi and VPN teardown; intended for a non-main lifecycle thread. */
    fun awaitClosed(timeoutMillis: Long): Boolean {
        require(timeoutMillis >= 0) { "timeoutMillis must not be negative" }
        return try {
            teardownComplete.await(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    // HUD (SOME/IP) and cluster (AMap broadcast) keep separate state so one failing cannot stall the other.
    private fun onRouteFrame(frame: com.shilapi.xcertplay.iap2.wire.Iap2Frame) {
        BydNavigationOutputs.onFrame(frame)
        synchronized(playbackStatus) {
            val previousPlaying = playbackStatus.playing
            playbackStatus.acceptUpdate(frame)?.let { it to (it.playing != previousPlaying) }
        }?.let { (update, playingChanged) ->
            nowPlayingListener?.invoke(update)
            if (playingChanged) playbackListener?.invoke(update.playing)
        }
    }

    private fun onArtworkTransfer(transfer: com.shilapi.xcertplay.transport.Iap2ArtworkTransfer) {
        debugLog("iap2 artwork transfer id=0x${transfer.id.toString(16)} bytes=${transfer.bytes.size}")
        artworkListener?.invoke(transfer.id, transfer.bytes)
    }

    private fun startMfi() {
        availabilityPollGeneration.incrementAndGet()
        phase = Phase.MFI
        onStatus(CarPlayStatus.DiscoveringMfi)
        val offlineDirectory = java.io.File(appContext.noBackupFilesDir, LocalMfiAuthenticationClient.DIRECTORY)
        if (offlineDirectory.exists()) {
            openLocalMfi(offlineDirectory)
            return
        }
        when (config.mfiTarget) {
            MfiTarget.LOCAL -> openLocalMfi(offlineDirectory)
            MfiTarget.USB_CH341 -> {
                debugLog("mfi discovery backend=CH341 devices=${config.ch341Devices}")
                val host = ch341Host ?: Ch341UsbHost(
                    appContext,
                    usbManager,
                    Ch341DeviceMatcher(config.ch341Devices),
                ).also {
                    ch341Host = it
                    ch341PermissionCloseable = it.registerPermissionReceiver(::onCh341Permission)
                }
                checkCh341Mfi(host)
            }
            MfiTarget.I2C -> {
                debugLog("mfi discovery backend=Linux I2C path=${config.linuxI2cPath}")
                openLinuxMfi()
            }
            MfiTarget.REMOTE -> {
                debugLog("mfi discovery backend=Remote server=${config.remoteMfiServer.orEmpty()}")
                openRemoteMfi()
            }
        }
    }

    private fun openLocalMfi(directory: java.io.File) {
        debugLog("mfi discovery backend=LocalOffline remoteFallback=disabled")
        executor.execute {
            try {
                val signatures = AtomicInteger(0)
                val client = LocalMfiAuthenticationClient.load(directory) { size ->
                    debugLog("mfi local signature count=${signatures.incrementAndGet()} digestBytes=$size")
                }
                if (closed || phase != Phase.MFI) return@execute
                mfiSession = MfiSession(client, null)
                debugLog("mfi local offline ready protocolMajor=${client.protocolMajor()} certificateBytes=${client.readCertificate().size}")
                onStatus(CarPlayStatus.MfiReady)
                startPhone()
            } catch (error: Throwable) {
                // A broken local identity must fail closed rather than silently use the helper.
                fail(error)
            }
        }
    }

    private fun openRemoteMfi() {
        executor.execute {
            try {
                val client = RemoteMfiAuthenticationClient(
                    serverAddress = checkNotNull(config.remoteMfiServer),
                    token = config.remoteMfiToken,
                )
                client.reset()
                val protocolMajor = client.protocolMajor()
                if (closed || phase != Phase.MFI) return@execute
                mfiSession = MfiSession(client, null)
                debugLog(
                    "mfi remote service ready server=${config.remoteMfiServer} " +
                        "protocolMajor=$protocolMajor",
                )
                onStatus(CarPlayStatus.MfiReady)
                startPhone()
            } catch (error: Throwable) {
                fail(error)
            }
        }
    }

    private fun checkCh341Mfi(host: Ch341UsbHost) {
        if (closed || phase != Phase.MFI) return
        val device = host.discover().firstOrNull()
        if (device == null) {
            waitForMfi()
        } else {
            availabilityPollGeneration.incrementAndGet()
            requestCh341Permission(device)
        }
    }

    private fun openLinuxMfi() {
        executor.execute {
            try {
                val transport = LinuxI2cTransport.open(config.linuxI2cPath!!)
                try {
                    mfiSession = MfiSession(MfiRuntime.scan(transport), transport)
                    debugLog("mfi Linux I2C coprocessor ready path=${config.linuxI2cPath}")
                    onStatus(CarPlayStatus.MfiReady)
                    startPhone()
                } catch (error: Throwable) {
                    transport.close()
                    throw error
                }
            } catch (error: MfiCoprocessorNotFoundException) {
                debugLog("mfi Linux discovery failed: ${error.message}")
                waitForMfi()
            } catch (error: Throwable) {
                fail(error)
            }
        }
    }

    private fun requestCh341Permission(device: UsbDevice) {
        try {
            when (val request = ch341Host!!.requestPermission(device)) {
                is Ch341UsbHost.PermissionRequest.AlreadyGranted -> {
                    permissionGrant.set(false)
                    onCh341Permission(Ch341UsbHost.PermissionResult.Granted(request.device))
                }
                is Ch341UsbHost.PermissionRequest.Requested -> {
                    permissionGrant.set(false)
                    onStatus(CarPlayStatus.RequestingMfiPermission)
                    pollCh341Permission(device)
                }
            }
        } catch (error: Throwable) {
            fail(error)
        }
    }

    private fun onCh341Permission(result: Ch341UsbHost.PermissionResult) {
        if (closed || phase != Phase.MFI) return
        when (result) {
            is Ch341UsbHost.PermissionResult.Granted -> {
                // The system broadcast and CarUsbHandler's direct grant can both observe success.
                if (!permissionGrant.compareAndSet(false, true)) return
                permissionPollGeneration++
                openCh341(result.device)
            }
            is Ch341UsbHost.PermissionResult.Denied -> {
                permissionGrant.set(true)
                onStatus(CarPlayStatus.Failed("CH341 USB permission was denied"))
            }
        }
    }

    /** Some car systems grant USB access through CarUsbHandler without delivering a broadcast. */
    private fun pollCh341Permission(device: UsbDevice) {
        val generation = ++permissionPollGeneration
        val deadlineNanos = System.nanoTime() + PERMISSION_POLL_TIMEOUT_MILLIS * 1_000_000L
        val check = object : Runnable {
            override fun run() {
                if (closed || phase != Phase.MFI || generation != permissionPollGeneration) return
                if (usbManager.hasPermission(device)) {
                    onCh341Permission(Ch341UsbHost.PermissionResult.Granted(device))
                    return
                }
                if (System.nanoTime() >= deadlineNanos) {
                    if (permissionGrant.compareAndSet(false, true)) {
                        onStatus(
                            CarPlayStatus.Failed(
                                "MFi USB permission was not granted; reconnect the CH341 to retry",
                            ),
                        )
                    }
                    return
                }
                mainHandler.postDelayed(this, PERMISSION_POLL_INTERVAL_MILLIS)
            }
        }
        mainHandler.postDelayed(check, PERMISSION_POLL_INTERVAL_MILLIS)
    }

    private fun openCh341(device: UsbDevice) {
        ch341Host!!.openAsync(device, executor) { result ->
            when (result) {
                is Ch341UsbHost.OpenResult.Connected -> {
                    val session: Ch341UsbSession = result.session
                    if (closed || phase != Phase.MFI) {
                        session.close()
                        return@openAsync
                    }
                    try {
                        val transport = Ch341I2cTransport(session)
                        config.ch341MfiResetGpio?.let { gpio ->
                            transport.pulseActiveLowReset(gpio)
                            if (!mfiResetLogged) {
                                mfiResetLogged = true
                                debugLog("mfi reset pulse gpio=D$gpio mode=low/high-z")
                            }
                        }
                        val client = MfiRuntime.scan(transport)
                        val probes = mfiCandidateAddresses
                            .associateWith { address -> probeMfiCandidate(transport, address) }
                        for ((address, probe) in probes) {
                            debugLog("mfi probe address=0x${address.toString(16)} ${probe.describe()}")
                        }
                        val selected = preferCertificateBearingAddress(client, transport, probes)
                        debugLog(
                            "mfi coprocessor address=0x${selected.address7Bit.toString(16)} " +
                                "protocolMajor=${selected.protocolMajor()}",
                        )
                        mfiSession = MfiSession(selected, session)
                        debugLog("mfi CH341 session ready")
                        onStatus(CarPlayStatus.MfiReady)
                        startPhone()
                    } catch (error: MfiCoprocessorNotFoundException) {
                        debugLog("mfi CH341 discovery failed: ${error.message}")
                        Log.w(IphoneCarPlayConfiguration.TAG, error.message ?: "MFi discovery failed")
                        session.close()
                        waitForMfi()
                    } catch (error: Throwable) {
                        session.close()
                        fail(error)
                    }
                }
                is Ch341UsbHost.OpenResult.Failed -> when (result.error) {
                    is I2cTransportException.DeviceUnavailable -> waitForMfi()
                    else -> fail(result.error)
                }
            }
        }
    }

    private val mfiCandidateAddresses = listOf(0x10, 0x11)
    private val maxMfiCertificateBytes = 1280

    private data class MfiCandidateProbe(
        val deviceVersion: Int?,
        val firmwareVersion: Int?,
        val protocolMajor: Int?,
        val accessoryCertificateLength: Int?,
        val appleCertificateLength: Int?,
        val failure: String?,
    ) {
        val hasAccessoryCertificate: Boolean
            get() = (accessoryCertificateLength ?: 0) in 1..1280

        fun describe(): String {
            if (failure != null) return failure
            return "deviceVersion=" + hex(deviceVersion) +
                " firmwareVersion=" + hex(firmwareVersion) +
                " protocolMajor=" + hex(protocolMajor) +
                " accessoryCertificateLength=" + accessoryCertificateLength +
                " appleCertificateLength=" + appleCertificateLength
        }

        private fun hex(value: Int?): String =
            if (value == null) "?" else "0x" + value.toString(16).padStart(2, '0')
    }

    private fun probeMfiCandidate(transport: I2cTransport, address7Bit: Int): MfiCandidateProbe = try {
        MfiCandidateProbe(
            deviceVersion = readMfiRegister(transport, address7Bit, 0x00, 1),
            firmwareVersion = readMfiRegister(transport, address7Bit, 0x01, 1),
            protocolMajor = readMfiRegister(transport, address7Bit, 0x02, 1),
            accessoryCertificateLength = readMfiRegister(transport, address7Bit, 0x30, 2),
            appleCertificateLength = readMfiRegister(transport, address7Bit, 0x50, 2),
            failure = null,
        )
    } catch (error: Throwable) {
        MfiCandidateProbe(
            deviceVersion = null,
            firmwareVersion = null,
            protocolMajor = null,
            accessoryCertificateLength = null,
            appleCertificateLength = null,
            failure = "failed: " + error.javaClass.simpleName + ": " + error.message,
        )
    }

    private fun readMfiRegister(
        transport: I2cTransport,
        address7Bit: Int,
        register: Int,
        length: Int,
    ): Int {
        transport.transaction(address7Bit, byteArrayOf(register.toByte()), 0)
        var value = 0
        for (byte in transport.transaction(address7Bit, ByteArray(0), length)) {
            value = (value shl 8) or (byte.toInt() and 0xff)
        }
        return value
    }

    private fun preferCertificateBearingAddress(
        client: MfiAuthenticationClient,
        transport: I2cTransport,
        probes: Map<Int, MfiCandidateProbe>,
    ): MfiAuthenticationClient {
        if (probes[client.address7Bit]?.hasAccessoryCertificate == true) return client
        val alternative = probes.entries.firstOrNull { (address, probe) ->
            address != client.address7Bit && probe.hasAccessoryCertificate
        } ?: return client
        debugLog(
            "mfi address override: 0x" + client.address7Bit.toString(16) +
                " has no accessory certificate; using 0x" + alternative.key.toString(16),
        )
        return MfiAuthenticationClient(transport, alternative.key)
    }

    private fun waitForMfi() {
        if (closed || phase != Phase.MFI) return
        onStatus(CarPlayStatus.WaitingForMfi)
        scheduleAvailabilityPoll(Phase.MFI) {
            when (config.mfiTarget) {
                MfiTarget.USB_CH341 -> ch341Host?.let(::checkCh341Mfi)
                MfiTarget.I2C -> openLinuxMfi()
                MfiTarget.REMOTE, MfiTarget.LOCAL -> Unit
            }
        }
    }

    private fun startPhone() {
        if (config.locationReportingEnabled) {
            val started = try {
                locationProvider?.start() == true
            } catch (error: Throwable) {
                Log.w(
                    IphoneCarPlayConfiguration.TAG,
                    "Could not prewarm the Android location provider",
                    error,
                )
                false
            }
            debugLog("location provider prewarmed=$started")
        }
        if (config.transport == CarPlayTransport.WIRELESS) {
            startWireless()
        } else {
            startIphone()
        }
    }

    private fun startWireless() {
        diagnosticRun.incrementAndGet()
        availabilityPollGeneration.incrementAndGet()
        phase = Phase.WIRELESS
        wirelessHandoffRequested.set(false)
        wirelessTunnelReady.set(false)
        wirelessActiveReported.set(false)
        wirelessControlDiscoveryEvents.set(0)
        wirelessAirPlayConnections.set(0)
        wirelessRunReachedHotspot = false
        onStatus(CarPlayStatus.StartingHotspot)
        val generation = wirelessGeneration.incrementAndGet()
        executor.execute {
            runWireless(generation)
        }
    }

    private fun restartWireless() {
        wirelessGeneration.incrementAndGet()
        Thread(
            {
                closeWirelessStack()
                if (!closed) startWireless()
            },
            "xcertplay-wireless-restart",
        ).apply {
            isDaemon = true
            start()
        }
    }

    private fun runWireless(generation: Int) {
        try {
            debugLog("wireless bring-up generation=$generation starting")
            closeWirelessStack()
            if (
                closed ||
                phase != Phase.WIRELESS ||
                generation != wirelessGeneration.get()
            ) {
                return
            }

            val mfi = mfiSession?.client
                ?: throw IOException("MFi coprocessor client is unavailable")
            val hotspotInfo = startWirelessHotspot(generation)
            // From here on a verdict is meaningful, so teardown may report one.
            wirelessRunReachedHotspot = true
            if (isStaleWirelessRun(generation)) {
                closeWirelessStack()
                return
            }
            val startedHotspot = hotspot
            wirelessConnectionProof.begin(generation) {
                if (!isStaleWirelessRun(generation)) startedHotspot?.onCarPlayConfirmed()
            }
            val hostAddress = hotspotInfo.hostAddress
                ?: throw IOException(
                    "Wireless hotspot did not provide a usable host address",
                )
            if (
                hostAddress is Inet6Address &&
                (!hostAddress.isLinkLocalAddress || hostAddress.scopeId == 0)
            ) {
                throw IOException(
                    "Wireless hotspot link-local IPv6 address is not scoped",
                )
            }
            val hostAddressText = hostAddressText(hostAddress)
            // Every family the interface offers. This list is both what Bonjour publishes on and
            // what the phone is told to dial, so the two can never disagree about the family.
            val advertisedAddresses = hostAddressTexts(hotspotInfo.interfaceName, hostAddressText)
            debugLog(
                "wireless interface addresses iface=${hotspotInfo.interfaceName ?: "unknown"} " +
                    "families=${advertisedAddresses.joinToString(",") { familyLabel(it) }}",
            )
            val deviceIdentifier = hotspotInfo.bssid
                ?.takeUnless { it.equals(ADAPTER_ADDRESS_PLACEHOLDER, ignoreCase = true) }
                ?: airPlayConfig.deviceId
            debugLog(
                "wireless hotspot backend=${hotspotInfo.backend.label} " +
                    "iface=${hotspotInfo.interfaceName ?: "unknown"} " +
                    "family=${if (hostAddress is Inet6Address) "IPv6" else "IPv4"} " +
                    "identitySource=${if (deviceIdentifier == hotspotInfo.bssid) "interface" else "saved"} " +
                    "host=$hostAddressText " +
                    "band=${hotspotInfo.bandLabel} channel=${hotspotInfo.channel} " +
                    "frequency=${hotspotInfo.frequencyMHz?.toString() ?: "unknown"}MHz",
            )
            onStatus(
                CarPlayStatus.HotspotReady(
                    ssid = hotspotInfo.ssid,
                    band = hotspotInfo.bandLabel,
                    channel = hotspotInfo.channel,
                    bssid = deviceIdentifier,
                    address = hostAddressText,
                    backend = hotspotInfo.backend.label,
                ),
            )
            onStatus(CarPlayStatus.WaitingForPairedIphone)
            hotspot?.let { startWirelessClientWatch(generation, it, hotspotInfo.interfaceName) }

            val adapter = bluetoothAdapter
                ?: throw IOException("Bluetooth adapter is unavailable")
            if (!adapter.isEnabled) throw IOException("Bluetooth is not enabled")
            val device = selectWirelessBluetoothDevice(adapter)
            val hostBluetoothMac = accessoryBluetoothMac(adapter)
            debugLog(
                "wireless selected Bluetooth target name=${device.name ?: "unknown"} " +
                    "address=${device.address} localBt=$hostBluetoothMac",
            )
            val wirelessAirPlayConfig = airPlayConfig.copy(
                deviceId = deviceIdentifier,
                btMac = hostBluetoothMac,
            )

            onStatus(CarPlayStatus.AttachingNetwork)
            val service = awaitVpnService()
                ?: throw IOException("Could not bind the CarPlay AirPlay service")
            when (
                val result = service.attachWireless(
                    bindAddress = hostAddress,
                    config = wirelessAirPlayConfig,
                    identity = identity,
                    pairings = pairings,
                    mfi = mfi,
                    listener = wirelessSessionListener(generation),
                    media = media,
                )
            ) {
                CarPlayVpnService.AttachResult.Started -> Unit
                CarPlayVpnService.AttachResult.AlreadyStarted ->
                    throw IOException("Wireless AirPlay transport is already attached")
                is CarPlayVpnService.AttachResult.Failed ->
                    throw IOException(result.message)
            }
            val listenerPort = service.boundPort() ?: wirelessAirPlayConfig.port
            val advertisedAirPlayConfig = wirelessAirPlayConfig.copy(port = listenerPort)
            debugLog(
                "wireless AirPlay listener attached bind=$hostAddressText " +
                    "port=$listenerPort" +
                    (if (listenerPort != airPlayConfig.port) " (preferred ${airPlayConfig.port} in use)" else ""),
            )
            if (isStaleWirelessRun(generation)) {
                closeWirelessStack()
                return
            }

            val bonjourClient = CarPlayBonjour(
                context = appContext,
                config = advertisedAirPlayConfig,
                identity = identity,
                advertisedHost = hostAddress.hostAddress,
                // Publish on every family the interface offers, not just the primary one. A JmDNS
                // instance joins only its own address family's multicast group, so a receiver
                // advertised over link-local IPv6 alone is invisible to an IPv4 browser — which is
                // exactly how wireless CarPlay kept stalling at discovery while the phone was
                // already joined to our group.
                advertisedHosts = advertisedAddresses.filter { it != hostAddressText },
                // Bind discovery and its connect probe to the same AP/address family as AirPlay.
                // The car hotspot previously used system NSD, which could resolve another interface
                // or IPv6 while the listener/probe was bound to the AP's IPv4 address.
                useInterfaceMdns = true,
                onEvent = { event ->
                    // Only events that mean we saw the phone. Counting our own publication
                    // (MDNS_STARTED) made the verdict claim "connect" — discovered, then refused —
                    // on runs where nothing had been discovered, sending the next investigation to
                    // the wrong layer.
                    if (event is CarPlayBonjourEvent.Discovery &&
                        event.stage.countsAsPhoneDiscovery
                    ) {
                        wirelessControlDiscoveryEvents.incrementAndGet()
                    }
                    debugLog("wireless bonjour: ${event.diagnosticSummary()}")
                },
            )
            bonjour = bonjourClient
            bonjourClient.start()
            debugLog(
                "wireless Bonjour services started mode=interface " +
                    "iface=${hotspotInfo.interfaceName ?: "unknown"} " +
                    "host=${hostAddressText} port=${wirelessAirPlayConfig.port}",
            )
            // Passive observer for phone-side mDNS traffic: the 42 build proved both listener
            // families accept and both families publish, so the remaining unknown is whether
            // the phone's queries ever reach us. Runs beside JmDNS, sends nothing.
            MdnsSniffer.start(hotspotInfo.interfaceName) { debugLog(it) }
            // Run after Bonjour so the log reads in causal order. Own thread, not the shared
            // executor: this can block for the connect timeout on each address and must never
            // delay the bring-up work already queued there.
            Thread(
                { runCatching { selfTestAirPlayPort(hotspotInfo.interfaceName, wirelessAirPlayConfig.port) } },
                "xcertplay-airplay-selftest",
            ).apply { isDaemon = true }.start()
            if (isStaleWirelessRun(generation)) {
                closeWirelessStack()
                return
            }

            onStatus(CarPlayStatus.ConnectingBluetooth)
            debugLog(
                "wireless RFCOMM connecting address=${device.address} " +
                    "uuid=$IAP2_IPHONE_UUID",
            )
            val socket = device
                    .createRfcommSocketToServiceRecord(UUID.fromString(IAP2_IPHONE_UUID))
                    .also { bluetoothSocket = it }
            logBluetoothConnectionSnapshot(device, "before-connect")
            val bluetoothStarted = System.nanoTime()
            try {
                connectBluetoothSocket(socket, device.address)
                connectionDiagnostic("Bluetooth connect completed elapsedMs=${elapsedMillis(bluetoothStarted)}")
            } catch (error: Throwable) {
                connectionDiagnostic(
                    "Bluetooth connect failed elapsedMs=${elapsedMillis(bluetoothStarted)} " +
                        "failureClass=${diagnosticFailureClass(error)}",
                )
                logBluetoothConnectionSnapshot(device, "after-failure")
                throw error
            }
            debugLog("wireless RFCOMM connected address=${device.address}")
            if (isStaleWirelessRun(generation)) {
                closeWirelessStack()
                return
            }
            val stream = BluetoothRfcommDuplexStream(socket).also { bluetoothStream = it }
            val channel = Iap2Session.openWireless(
                stream,
                traceContext = "wireless-rfcomm",
                onTrace = ::debugLog,
                onArtwork = ::onArtworkTransfer,
            ).also { csm = it }
            debugLog("wireless iAP2 CSM channel opened over RFCOMM")
            if (isStaleWirelessRun(generation)) {
                closeWirelessStack()
                return
            }
            val linkWirelessIdentification = Iap2WirelessIdentification(hostBluetoothMac, hotspotInfo.ssid)
            // Upstream 0.2.11: the Bluetooth RFCOMM link is a short-lived bootstrap; long-lived
            // accessory data (location) must only be advertised on the runtime Wi-Fi tunnel, or
            // iOS binds it to the RFCOMM endpoint and rejects it there once the link closes.
            val bootstrapIdentification = config.identification.forWirelessLink(
                Iap2WirelessLinkRole.BLUETOOTH_BOOTSTRAP,
                linkWirelessIdentification,
            )
            val runtimeIdentification = config.identification.forWirelessLink(
                Iap2WirelessLinkRole.RUNTIME_TUNNEL,
                linkWirelessIdentification,
            )
            // LIVI (f-io/LIVI), the working reference implementation, puts ONLY the link-local
            // IPv6 in 0x4301's wireless ip_address list — the iPhone dials it directly on the
            // interface it joined with (phone-side pcap: a v4-first list is never dialled, and
            // a v4-only list is ignored too). On the external-Wi-Fi route the car is a plain
            // station client, so this link-local dial is normal LAN traffic with no tether
            // firewall in the way — that is the whole point of the route.
            val linkLocalV6 = (hostAddress as? Inet6Address)
                ?.takeIf { it.isLinkLocalAddress }
                ?.hostAddress?.substringBefore('%')
            val endpoint = Iap2WirelessCarPlayEndpoint(
                ssid = hotspotInfo.ssid,
                passphrase = hotspotInfo.passphrase,
                channel = hotspotInfo.channel,
                security = hotspotInfo.security,
                ipAddresses = listOfNotNull(linkLocalV6).ifEmpty { advertisedAddresses },
                airPlayPort = listenerPort,
                deviceIdentifier = deviceIdentifier,
                publicKey = identity.publicKeyHex,
                sourceVersion = airPlayConfig.sourceVersion,
                bssid = bssidBytes(hotspotInfo.bssid),
            )
            wirelessIdentification = runtimeIdentification
            wirelessAirPlayEndpoint = endpoint
            media.setIapTunnelHandler(::startWirelessTunnelControl)

            onStatus(CarPlayStatus.RunningWireless)
            debugLog("wireless Bluetooth iAP2 control starting location=false")
            val result = Iap2WirelessControlClient(
                session = channel,
                mfi = Iap2MfiAuthenticationClient(mfi),
            ).run(
                identification = bootstrapIdentification,
                endpoint = endpoint,
                timeoutMillis = controlLoopTimeoutMillis(),
                onIncoming = ::onRouteFrame,
                onProgress = ::debugLog,
            )
            if (isStaleWirelessRun(generation)) {
                closeWirelessStack()
                return
            }
            when (result.terminal) {
                Iap2WirelessControlTerminal.CHANNEL_CLOSED -> {
                    debugLog(
                        "wireless RFCOMM EOF: iap2State=${result.stage} " +
                            "wirelessCarPlayAvailable=${result.wirelessCarPlayAvailableSeen} " +
                            "transportIdentifier=${result.transportNotificationSeen} " +
                            "carPlayStartSessions=${result.carPlayStartSessionsSent} " +
                            "postTransportConfigs=${result.postTransportWiFiConfigurationsSent} " +
                            "handoffRequested=${wirelessHandoffRequested.get()} " +
                            "tunnelReady=${wirelessTunnelReady.get()} " +
                            "wirelessActive=${wirelessActiveReported.get()}",
                    )
                    // The single most useful line in a failed wireless report: it separates the
                    // three failure families (discovery, listener, handshake) by declaring which
                    // evidence never arrived, instead of leaving the reader to grep for absences.
                    logWirelessBringUpVerdict(result)
                    if (!wirelessActiveReported.get()) {
                        val handoffInProgress = isWirelessHandoffInProgress(
                            handoffRequested = wirelessHandoffRequested.get(),
                            tunnelActive = wirelessTunnelChannel != null,
                            sessionActive = activeSession != null,
                        )
                        if (!handoffInProgress) {
                            throw IOException(
                                "Wireless CarPlay control channel closed before tunnel iAP2 ready",
                            )
                        }
                        debugLog(
                            "wireless Bluetooth bootstrap closed during handoff; " +
                                "keeping the Wi-Fi AirPlay tunnel alive",
                        )
                    }
                }
                Iap2WirelessControlTerminal.TIMED_OUT -> {
                    logWirelessBringUpVerdict(result)
                    if (!wirelessActiveReported.get()) {
                        onStatus(CarPlayStatus.ControlEnded)
                    }
                }
            }
        } catch (error: Throwable) {
            if (closed || generation != wirelessGeneration.get()) {
                return
            }
            if (wirelessActiveReported.get() && error !is Error) {
                debugLog("wireless RFCOMM control ended after tunnel handoff: ${error.message}")
            } else {
                debugLog("wireless bring-up failed", error)
                closeWirelessStack()
                if (error is Error) throw error
                fail(error)
            }
        }
    }

    /**
     * Declares which piece of evidence never arrived when a wireless attempt ends without a
     * session. The three families need completely different fixes, and the failing one is always
     * identified by an *absent* log line — the one thing a reader cannot grep for.
     *
     * - discovery: the phone never opened our AirPlay port, so it never learned where we are.
     *   Check the TXT `features` and whether `_airplay._tcp` really reached the p2p interface.
     * - connect: we reached the phone's CarPlay control port but it declined. Compare the feature
     *   bits and protocol version it advertises against what we answer in `/info`.
     * - carplay: control and discovery were both fine, so the failure is inside the AirPlay
     *   handshake (`/pair-*`, `/auth-setup`, SETUP) — read those lines, not the discovery ones.
     */
    private fun logWirelessBringUpVerdict(result: Iap2WirelessControlResult?) {
        // Nothing to report until a hotspot exists; otherwise this fires from the pre-run cleanup.
        if (!wirelessRunReachedHotspot) return
        val discoveryEvents = wirelessControlDiscoveryEvents.get()
        val airPlayConnections = wirelessAirPlayConnections.get()
        // A session that already came up must never be reported as a handshake failure. This line
        // also runs on a timer during a healthy session, and the run-80 report showed it claiming
        // "问题在 AirPlay 握手" in a run whose own log carried handoff/complete and 23 fps video.
        val sessionEstablished = wirelessActiveReported.get()
        val verdict = when {
            sessionEstablished -> "session"
            airPlayConnections > 0 -> "carplay"
            discoveryEvents > 0 -> "connect"
            else -> "discovery"
        }
        val hint = when (verdict) {
            "session" -> "；无线会话此前已建立（AirPlay 连接与蓝牙交接均完成），本行只是进行中的摘要"
            "discovery" -> "；手机从未连上 AirPlay 端口：看 group clients 判断手机有没有入网"
            "connect" -> "；已发现手机但连接被拒：比对 features 与 /info 的协议版本"
            else -> "；已连上 AirPlay 端口但会话未建立：问题在 AirPlay 握手（/pair-* /auth-setup SETUP）"
        }
        val stage = result?.let {
            "iap2Stage=${it.stage} carPlayStartSessions=${it.carPlayStartSessionsSent}"
        } ?: if (sessionEstablished) {
            "iap2Stage=session-established"
        } else {
            "iap2Stage=unknown (进行中摘要，尚未建立会话)"
        }
        debugLog(
            "wireless bring-up verdict=$verdict " +
                "discoveryEvents=$discoveryEvents airPlayConnections=$airPlayConnections " +
                (bonjour?.diagnosticSnapshot() ?: "bonjour=not_started") + " " +
                stage + hint,
        )
    }

    /**
     * Reports when a device joins or leaves the Wi-Fi Direct group.
     *
     * Two wireless failures look identical everywhere else in the log — "the phone never joined our
     * Wi-Fi network" and "the phone joined but never opened the AirPlay connection" both end with no
     * `airplay connection accepted from` line — yet they need opposite fixes. This is the only line
     * that tells them apart, so it runs for the whole bring-up window and logs every change.
     */
    private fun startWirelessClientWatch(
        generation: Int,
        manager: WirelessHotspotManager,
        interfaceName: String?,
    ) {
        Thread(
            {
                var lastReported = -1
                var nextVerdictNanos = System.nanoTime() +
                    WIRELESS_VERDICT_INTERVAL_MILLIS * 1_000_000L
                val deadline = System.nanoTime() +
                    WIRELESS_CLIENT_WATCH_MILLIS * 1_000_000L
                while (
                    !closed &&
                    generation == wirelessGeneration.get() &&
                    System.nanoTime() < deadline
                ) {
                    val count = manager.joinedClientCount()
                    if (count != null && count != lastReported) {
                        val justJoined = count > 0 && lastReported == 0
                        lastReported = count
                        debugLog(
                            if (count == 0) {
                                "wireless group clients=0；手机尚未加入本机 Wi-Fi"
                            } else {
                                "wireless group clients=$count；已有设备加入本机 Wi-Fi"
                            },
                        )
                        // The registration-time Bonjour announcement went out before anyone had
                        // joined, so it was necessarily lost. Replay it the moment the phone
                        // shows up: passive listeners get a second chance without having to
                        // query across the (lossy) P2P multicast path. Once per session.
                        if (justJoined) {
                            debugLog("wireless re-announcing Bonjour on client join")
                            bonjour?.let { client ->
                                runCatching { client.reannounce() }
                                    .onFailure { debugLog("wireless re-announce failed: $it") }
                            }
                            // The 45 logs left one question open: did the phone even get an IP,
                            // and is its mDNS stack alive? Without that, "no phone traffic" is
                            // ambiguous between DHCP failure and phone-side CarPlay policy.
                            PhoneProbe.start(interfaceName) { debugLog(it) }
                        }
                    }
                    // Repeat the verdict on a timer. The teardown verdict is unreliable in exactly
                    // the case that matters most: when the user gives up and force-closes, the app
                    // dies before teardown finishes and the report ends with no verdict at all
                    // (observed on the run-34 report). A periodic line guarantees the report always
                    // carries a recent summary.
                    val now = System.nanoTime()
                    if (now >= nextVerdictNanos) {
                        nextVerdictNanos = now + WIRELESS_VERDICT_INTERVAL_MILLIS * 1_000_000L
                        logWirelessBringUpVerdict(null)
                    }
                    try {
                        Thread.sleep(WIRELESS_CLIENT_WATCH_POLL_MILLIS)
                    } catch (_: InterruptedException) {
                        return@Thread
                    }
                }
            },
            "xcertplay-wifi-clients",
        ).apply { isDaemon = true }.start()
    }

    private fun startWirelessTunnelControl(stream: BlockingDuplexByteStream): Boolean {
        if (closed || config.transport != CarPlayTransport.WIRELESS) return false
        val identification = wirelessIdentification ?: return false
        val endpoint = wirelessAirPlayEndpoint ?: return false
        val mfi = mfiSession?.client ?: return false
        debugLog("wireless type-130 tunnel data stream accepted")
        val channel = try {
            Iap2Session.openTunnel(
                stream,
                traceContext = "wireless-tunnel",
                onTrace = ::debugLog,
                onArtwork = ::onArtworkTransfer,
            )
        } catch (error: Throwable) {
            debugLog("Could not open the tunneled iAP2 link", error)
            return false
        }
        wirelessTunnelChannel = channel
        val generation = wirelessGeneration.get()
        debugLog("wireless iAP2 tunnel control starting")
        return try {
            tunnelExecutor.execute {
                try {
                    val result = Iap2WirelessControlClient(
                        session = channel,
                        mfi = Iap2MfiAuthenticationClient(mfi),
                    ).run(
                        identification = identification,
                        endpoint = endpoint,
                        timeoutMillis = Iap2WirelessControlClient.NO_TIMEOUT_MILLIS,
                        locationProvider = locationProvider,
                        onReady = {
                            onWirelessTunnelReady(generation)
                        },
                        onIncoming = ::onRouteFrame,
                        onProgress = { message -> debugLog("iAP tunnel $message") },
                    )
                    if (closed || generation != wirelessGeneration.get()) return@execute
                    when (result.terminal) {
                        Iap2WirelessControlTerminal.TIMED_OUT ->
                            onStatus(CarPlayStatus.ControlEnded)
                        Iap2WirelessControlTerminal.CHANNEL_CLOSED ->
                            onStatus(CarPlayStatus.Failed("Wireless iAP2 tunnel closed"))
                    }
                } catch (error: Throwable) {
                    if (!closed && generation == wirelessGeneration.get()) {
                        debugLog("tunneled iAP2 control failed", error)
                        onStatus(
                            CarPlayStatus.Failed(
                                error.message ?: error.javaClass.simpleName,
                            ),
                        )
                    }
                } finally {
                    if (wirelessTunnelChannel === channel) wirelessTunnelChannel = null
                }
            }
            true
        } catch (error: Throwable) {
            if (wirelessTunnelChannel === channel) wirelessTunnelChannel = null
            closeBestEffort("tunneled iAP2 link") { channel.close() }
            debugLog("iAP2 tunnel executor rejected the link", error)
            false
        }
    }

    private fun wirelessSessionListener(generation: Int): AirPlaySessionListener =
        object : AirPlaySessionListener by sessionListener {
            override fun onSessionActive(session: AirPlaySession) {
                if (isStaleWirelessRun(generation)) return
                wirelessConnectionProof.activate(generation, session)
                sessionListener.onSessionActive(session)
            }

            override fun onSessionEnded(session: AirPlaySession) {
                if (isStaleWirelessRun(generation)) return
                wirelessConnectionProof.end(generation, session)
                sessionListener.onSessionEnded(session)
            }

            override fun onVideoFrameRendered(session: AirPlaySession) {
                if (isStaleWirelessRun(generation) || activeSession !== session) return
                wirelessConnectionProof.rendered(generation, session)
            }
        }

    private fun onWirelessTunnelReady(generation: Int) {
        if (
            closed ||
            phase != Phase.WIRELESS ||
            generation != wirelessGeneration.get()
        ) {
            return
        }
        wirelessTunnelReady.set(true)
        wirelessConnectionProof.authenticated(generation)
        debugLog(
            "wireless iAP2 tunnel ready; " +
                "handoffRequested=${wirelessHandoffRequested.get()}",
        )
        maybeCompleteWirelessHandoff()
    }

    private fun maybeCompleteWirelessHandoff() {
        if (!wirelessHandoffRequested.get() || !wirelessTunnelReady.get()) return
        if (!wirelessActiveReported.compareAndSet(false, true)) return
        val generation = wirelessGeneration.get()
        Thread(
            {
                if (
                    closed ||
                    phase != Phase.WIRELESS ||
                    generation != wirelessGeneration.get()
                ) {
                    return@Thread
                }
                debugLog("wireless handoff ready; closing Bluetooth bootstrap transport")
                closeBluetoothBootstrapTransport()
                onStatus(CarPlayStatus.WirelessActive)
            },
            "xcertplay-wireless-handoff",
        ).apply {
            isDaemon = true
            start()
        }
    }

    private fun armWirelessHandoffWatchdog(generation: Int) {
        mainHandler.postDelayed(
            {
                if (
                    closed ||
                    phase != Phase.WIRELESS ||
                    generation != wirelessGeneration.get() ||
                    !wirelessHandoffRequested.get() ||
                    wirelessActiveReported.get()
                ) {
                    return@postDelayed
                }
                if (!wirelessConnectionProof.hasRenderedFrame(generation)) {
                    // No frame ever rendered: the session never really came up, so teardown-and-retry
                    // is the right response (upstream 0.2.8 gate).
                    debugLog(
                        "wireless handoff timed out with no rendered frame; " +
                            "restarting the wireless stack",
                    )
                    closeWirelessStack()
                    fail(IOException("Wireless CarPlay handoff timed out waiting for tunnel iAP2"))
                    return@postDelayed
                }
                debugLog(
                    "wireless handoff timed out waiting for tunnel iAP2 readiness; " +
                        "keeping the Bluetooth control link and the live AirPlay session " +
                        "(the reference implementation runs iAP2 over Bluetooth for the whole session)",
                )
                // LIVI (f-io/LIVI), the working reference, never migrates iAP2 off Bluetooth:
                // the RFCOMM control link lives for the whole session. Tearing the stack down
                // here killed sessions that were streaming fine (~60s reconnect loop, 2026-09-29
                // log: disableBluetooth -> 45s watchdog -> closeWirelessStack -> AirPlay died).
                // Keep the session; if the tunnel shows up later, maybeCompleteWirelessHandoff
                // will still fire, and if the phone drops RFCOMM itself, the EOF path already
                // keeps the Wi-Fi AirPlay tunnel alive.
                onStatus(CarPlayStatus.WirelessActive)
            },
            WIRELESS_HANDOFF_TIMEOUT_MILLIS,
        )
    }

    private fun closeBluetoothBootstrapTransport() {
        val activeCsm = csm
        csm = null
        if (activeCsm != null) closeBestEffort("wireless CSM") { activeCsm.close() }

        val activeStream = bluetoothStream
        bluetoothStream = null
        if (activeStream != null) closeBestEffort("wireless RFCOMM stream") { activeStream.close() }

        val activeSocket = bluetoothSocket
        bluetoothSocket = null
        if (activeSocket != null) closeBestEffort("wireless Bluetooth socket") { activeSocket.close() }
    }

    private fun startIphone() {
        diagnosticRun.incrementAndGet()
        availabilityPollGeneration.incrementAndGet()
        phase = Phase.IPHONE
        configurationTransitions = 0
        transitionSourceDeviceName = null
        onStatus(CarPlayStatus.DiscoveringIphone)
        checkIphoneAvailability()
    }

    private fun checkIphoneAvailability() {
        if (closed || phase != Phase.IPHONE) return
        val device = iphoneHost.discover().firstOrNull()
        if (device == null) {
            onStatus(CarPlayStatus.WaitingForIphone)
            scheduleAvailabilityPoll(Phase.IPHONE, ::checkIphoneAvailability)
        } else {
            debugLog(
                "wired iPhone discovered vid=0x${device.vendorId.toString(16)} " +
                    "pid=0x${device.productId.toString(16)}",
            )
            availabilityPollGeneration.incrementAndGet()
            requestIphonePermission(device)
        }
    }

    private fun requestIphonePermission(device: UsbDevice) {
        mainHandler.post { doRequestIphonePermission(device, 0) }
    }

    private fun doRequestIphonePermission(device: UsbDevice, attempt: Int) {
        if (closed) return
        // The ROM's "default app" grant (USB_DEVICE_ATTACHED filter + 始终) lands asynchronously
        // after the attach/re-enumeration uevent. Requesting immediately pops the dialog while
        // the system is about to auto-grant; poll briefly first (run-143 report: double prompts).
        if (attempt < AUTO_GRANT_POLL_ATTEMPTS) {
            val granted = runCatching { iphoneHost.hasPermission(device) }.getOrDefault(false)
            if (granted) {
                debugLog("wired iPhone USB permission auto-granted by system default (poll=$attempt)")
            } else {
                mainHandler.postDelayed({ doRequestIphonePermission(device, attempt + 1) }, AUTO_GRANT_POLL_MILLIS)
                return
            }
        }
        try {
            when (val request = iphoneHost.requestPermission(device)) {
                is IphoneUsbHost.PermissionRequest.AlreadyGranted -> {
                    debugLog("wired iPhone USB permission already granted")
                    permissionGrant.set(false)
                    onIphonePermission(IphoneUsbHost.PermissionResult.Granted(request.device))
                }
                is IphoneUsbHost.PermissionRequest.Requested -> {
                    debugLog("wired iPhone USB permission requested")
                    permissionGrant.set(false)
                    onStatus(CarPlayStatus.RequestingIphonePermission)
                    pollIphonePermission(device)
                }
            }
        } catch (error: Throwable) {
            fail(error)
        }
    }

    private fun onIphonePermission(result: IphoneUsbHost.PermissionResult) {
        when (result) {
            is IphoneUsbHost.PermissionResult.Granted -> {
                // The system broadcast and the polling fallback can both observe the grant.
                if (!permissionGrant.compareAndSet(false, true)) return
                debugLog("wired iPhone USB permission granted")
                permissionPollGeneration++
                when (phase) {
                    Phase.REENUMERATION, Phase.IPHONE -> {
                        val configuration = IphoneCarPlayConfiguration.find(result.device)
                        connectionDiagnostic(
                            "USB configuration ready=${configuration != null} " +
                                "configurationId=${configuration?.id ?: "none"} " +
                                "transitions=$configurationTransitions " +
                                "action=${if (configuration != null) "reuse-descriptors" else "request-carplay-mode"}",
                        )
                        when {
                            // The CarPlay configuration is exposed: open it directly. AutoKit
                            // parity — never re-enumerate a device that already offers the
                            // configuration; a re-enumeration invalidates the USB grant and
                            // cost a second permission dialog on every cable plug (run-146
                            // report). Reopening + setConfiguration is safe now that teardown
                            // releases every endpoint (fixed in v2.0-84).
                            configuration != null -> openDataPaths(result.device)
                            // The iPhone is still in its default USB mode. Entry into the
                            // CarPlay configuration REQUIRES the Apple vendor request, and iOS
                            // re-enumerates whenever it changes configuration — this is the
                            // protocol, not the removed redundancy. The system auto-grants the
                            // new node once "always" was ticked, and the 2.4 s permission
                            // window below covers the grant landing.
                            configurationTransitions < MAX_CONFIGURATION_TRANSITIONS ->
                                beginCarPlayModeTransition(result.device)
                            else -> fail(
                                IphoneUsbException.Protocol(
                                    "iPhone did not expose a complete CarPlay USB configuration",
                                ),
                            )
                        }
                    }
                    else -> Unit
                }
            }
            is IphoneUsbHost.PermissionResult.Denied -> {
                permissionGrant.set(true)
                onStatus(CarPlayStatus.Failed("iPhone USB permission was denied"))
            }
        }
    }

    /** Some Android builds grant the dialog without delivering the permission broadcast. */
    private fun pollIphonePermission(device: UsbDevice) {
        val generation = ++permissionPollGeneration
        val deadlineNanos = System.nanoTime() + PERMISSION_POLL_TIMEOUT_MILLIS * 1_000_000L
        val check = object : Runnable {
            override fun run() {
                if (closed || generation != permissionPollGeneration) return
                if (usbManager.hasPermission(device)) {
                    onIphonePermission(IphoneUsbHost.PermissionResult.Granted(device))
                    return
                }
                if (System.nanoTime() >= deadlineNanos) {
                    if (permissionGrant.compareAndSet(false, true)) {
                        onStatus(
                            CarPlayStatus.Failed(
                                "iPhone USB permission was not granted; tap Reconnect iPhone to retry",
                            ),
                        )
                    }
                    return
                }
                mainHandler.postDelayed(this, PERMISSION_POLL_INTERVAL_MILLIS)
            }
        }
        mainHandler.postDelayed(check, PERMISSION_POLL_INTERVAL_MILLIS)
    }

    private fun onIphoneAttached(device: UsbDevice) {
        when (phase) {
            Phase.REENUMERATION, Phase.IPHONE -> {
                availabilityPollGeneration.incrementAndGet()
                requestIphonePermission(device)
            }
            else -> Unit
        }
    }

    private fun scheduleAvailabilityPoll(phase: Phase, check: () -> Unit) {
        val generation = availabilityPollGeneration.get()
        mainHandler.postDelayed(
            {
                if (
                    !closed &&
                    this.phase == phase &&
                    generation == availabilityPollGeneration.get()
                ) {
                    check()
                }
            },
            DEVICE_AVAILABILITY_POLL_INTERVAL_MILLIS,
        )
    }

    /**
     * First entry into the CarPlay USB configuration: the Apple vendor request makes the iPhone
     * switch modes, which re-enumerates the device (iOS behaviour on any configuration change).
     * The new node returns through [onIphoneAttached] / the poll below; the permission window
     * auto-grants it when "always" was ticked, so a returning user plugs in with zero dialogs.
     */
    private fun beginCarPlayModeTransition(device: UsbDevice) {
        configurationTransitions += 1
        transitionSourceDeviceName = device.deviceName
        connectionDiagnostic("USB CarPlay mode transition requested count=$configurationTransitions")
        onStatus(CarPlayStatus.SelectingConfiguration)
        iphoneHost.requestCarPlayReenumerationAsync(device, executor) { transition ->
            when (transition) {
                IphoneUsbHost.TransitionResult.ReenumerationRequested -> {
                    onStatus(CarPlayStatus.WaitingForReenumeration)
                    // Backstop for a missed USB attach broadcast while waiting for the new node.
                    scheduleTransitionPoll()
                }
                is IphoneUsbHost.TransitionResult.Failed -> fail(transition.error)
            }
        }
    }

    private fun scheduleTransitionPoll() {
        val generation = availabilityPollGeneration.get()
        mainHandler.postDelayed(
            {
                if (closed || phase == Phase.IDLE) return@postDelayed
                if (generation != availabilityPollGeneration.get()) return@postDelayed
                val replacement = iphoneHost.discover()
                    .firstOrNull { it.deviceName != transitionSourceDeviceName }
                if (replacement != null) {
                    debugLog("wired re-enumerated iPhone appeared; requesting USB access")
                    requestIphonePermission(replacement)
                } else {
                    scheduleTransitionPoll()
                }
            },
            TRANSITION_POLL_INTERVAL_MILLIS,
        )
    }

    private fun openDataPaths(device: UsbDevice) {
        phase = Phase.DATAPATHS
        debugLog("wired opening iPhone USB data paths")
        onStatus(CarPlayStatus.SelectingConfiguration)
        onStatus(CarPlayStatus.OpeningDataPaths)
        iphoneHost.openIap2UsbSessionAsync(device, executor) { result ->
            when (result) {
                is IphoneUsbHost.Iap2SessionResult.Connected -> {
                    // Publish the pipe before touching the phone: everything past this point can
                    // block for the full USBMUX handshake timeout, and teardown has to be able to
                    // release it even when `mux` is still unset.
                    wiredUsbSession = result.session
                    try {
                        val ncm = openNcm(device)
                        runStack(result.session, ncm)
                    } catch (error: Throwable) {
                        fail(error)
                    } finally {
                        // Always release the pipe, including when the handshake never returned. A
                        // connection left open keeps the phone's bulk endpoints bound to a dead
                        // host, and the next bring-up then fails with "could not queue USBMUX read
                        // request" until the cable is physically re-plugged.
                        closeBestEffort("USBMUX pipe") { result.session.close() }
                        if (wiredUsbSession === result.session) wiredUsbSession = null
                    }
                }
                is IphoneUsbHost.Iap2SessionResult.Failed -> fail(result.error)
            }
        }
    }

    private fun openNcm(device: UsbDevice): NcmUsbBridge {
        val configuration = IphoneCarPlayConfiguration.find(device)
            ?: throw IphoneUsbException.Protocol(
                "iPhone exposes no CarPlay configuration for NCM",
            )
        val function = NcmFunctionDiscovery.find(configuration)
            ?: throw IphoneUsbException.Protocol("iPhone configuration does not expose an NCM function")
        debugLog(
            "ncm config=${configuration.id} control=${function.control.id}/${function.control.alternateSetting}" +
                " data=${function.data.id}/${function.data.alternateSetting}" +
                " status=${function.statusIn?.address?.let { "0x${it.toString(16)}" } ?: "none"}" +
                " in=0x${function.bulkIn.address.toString(16)} out=0x${function.bulkOut.address.toString(16)}",
        )
        val connection = usbManager.openDevice(device)
            ?: throw IphoneUsbException.DeviceUnavailable("Could not open the iPhone NCM connection")
        return NcmUsbBridge.open(connection, function)
    }

    private fun runStack(usbSession: Iap2UsbSession, ncm: NcmUsbBridge) {
        phase = Phase.CONTROL
        var ncmOwnedLocally = true
        try {
            if (closed) return
            // The USBMUX handshake waits up to 60 s for the phone's version reply, so without this
            // marker a stale-endpoint failure is indistinguishable from a frozen app in the report.
            debugLog("wired opening the USBMUX host")
            val mux = Iap2UsbMuxHost.open(usbSession, onDiagnostic = ::connectionDiagnostic)
            this.mux = mux
            debugLog("wired USBMUX host opened")
            onStatus(CarPlayStatus.Pairing)
            val pairingClient = LockdownPairingClient(mux)
            val savedPairRecord = loadPairRecord()
            var pairRecord = savedPairRecord ?: pairNewRecord(pairingClient)
            debugLog(
                if (savedPairRecord != null) {
                    "wired using saved Lockdown pair record"
                } else {
                    "wired created a new Lockdown pair record"
                },
            )
            onStatus(CarPlayStatus.ConnectingControl)
            val carKitClient = LockdownCarKitClient(mux)
            // Temporary lab capture, limited to accessory/authentication messages and two minutes.
            try {
                val relay = carKitClient.openService(pairRecord, config.label, "com.apple.syslog_relay")
                Thread({
                    try {
                        relay.use {
                            val deadline = System.nanoTime() + 120_000_000_000L
                            val pending = StringBuilder()
                            val relevant = Regex(" (accessoryd|ACCCarPlayService|iap2d|CarPlay)([\\[(])", RegexOption.IGNORE_CASE)
                            while (!closed && System.nanoTime() < deadline) {
                                val bytes = relay.recv(8192, 1000) ?: continue
                                if (bytes.isEmpty()) break
                                pending.append(bytes.toString(Charsets.UTF_8).replace('\u0000', '\n'))
                                while (true) {
                                    val end = pending.indexOf("\n")
                                    if (end < 0) break
                                    val line = pending.substring(0, end)
                                    pending.delete(0, end + 1)
                                    if (relevant.containsMatchIn(line)) debugLog("PHONE ${line.take(2000)}")
                                }
                                if (pending.length > 65536) pending.clear()
                            }
                        }
                        debugLog("phone authentication diagnostic capture ended")
                    } catch (error: Exception) {
                        debugLog("phone authentication diagnostic capture ended: ${error.javaClass.simpleName}")
                    }
                }, "carplay-lab-phone-diagnostics").apply { isDaemon = true; start() }
                debugLog("phone authentication diagnostic capture started")
            } catch (error: Exception) {
                debugLog("phone authentication diagnostics unavailable: ${error.message}")
            }
            val carkit = try {
                carKitClient.open(pairRecord, config.label)
            } catch (error: Throwable) {
                val rejection = rejectedPairRecordError(error)
                if (savedPairRecord == null || rejection == null) throw error
                debugLog("saved Lockdown pair record rejected by Lockdown error=$rejection; clearing and pairing again")
                clearPairRecord()
                pairRecord = pairNewRecord(pairingClient)
                carKitClient.open(pairRecord, config.label)
            }
            debugLog("wired com.apple.carkit.service stream opened")
            // Lab transport diagnostics: packet headers only, never certificate or challenge data.
            fun wireSummary(bytes: ByteArray): String {
                if (bytes.size < 9 || bytes[0].toInt() and 0xff != 0xff ||
                    bytes[1].toInt() and 0xff != 0x5a) return "bytes=${bytes.size}"
                fun value(index: Int) = bytes[index].toInt() and 0xff
                return "bytes=${bytes.size} length=${(value(2) shl 8) or value(3)} " +
                    "flags=${value(4)} seq=${value(5)} ack=${value(6)} session=${value(7)}"
            }
            val tracedCarkit = object : com.shilapi.xcertplay.transport.BlockingDuplexByteStream {
                private val io = ConnectionIoDiagnostics(::connectionDiagnostic)
                override fun send(data: ByteArray) {
                    val started = System.nanoTime()
                    var result = ConnectionIoDiagnostics.Result.FAILED
                    try {
                        // The per-frame "TX begin" line was trimmed: "TX completed" carries the
                        // same event with less noise (run-143 report triage).
                        for (offset in data.indices step 256) {
                            carkit.send(data.copyOfRange(offset, minOf(offset + 256, data.size)))
                        }
                        debugLog("wired link TX completed bytes=${data.size}")
                        result = ConnectionIoDiagnostics.Result.COMPLETED
                    } finally {
                        io.record(ConnectionIoDiagnostics.Operation.WRITE, result, elapsedMillis(started))
                    }
                }
                override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? {
                    val started = System.nanoTime()
                    var result = ConnectionIoDiagnostics.Result.FAILED
                    try {
                        return carkit.recv(maxBytes, timeoutMillis).also { bytes ->
                            result = when {
                                bytes == null -> ConnectionIoDiagnostics.Result.TIMED_OUT
                                bytes.isEmpty() -> ConnectionIoDiagnostics.Result.ENDED
                                else -> ConnectionIoDiagnostics.Result.COMPLETED
                            }
                            if (bytes != null) debugLog("wired link RX ${wireSummary(bytes)}")
                        }
                    } finally {
                        io.record(ConnectionIoDiagnostics.Operation.READ, result, elapsedMillis(started))
                    }
                }
                override fun close() {
                    try { carkit.close() } finally { io.finish() }
                }
            }
            val csm = Iap2Session.open(
                tracedCarkit,
                traceContext = "wired",
                onTrace = ::debugLog,
                onArtwork = ::onArtworkTransfer,
            )
            this.csm = csm
            debugLog("wired iAP2 CSM channel opened")

            val ncmHostMac = ncm.hostMac ?: config.hostMac
            debugLog("ncm using hostMac=${ncmHostMac.macString()}")
            val transportAttached = if (config.wiredLwip) attachLwip(ncm, ncmHostMac) else attachVpn(ncm, ncmHostMac)
            if (!transportAttached) {
                throw IphoneUsbException.DeviceUnavailable("Could not attach the NCM/AirPlay transport")
            }
            ncmOwnedLocally = false
            debugLog("wired NCM/VPN AirPlay transport attached")
            if (closed) {
                vpnService?.detach()
                return
            }

            val mfi = mfiSession?.client
                ?: throw IphoneUsbException.DeviceUnavailable("MFi coprocessor client is unavailable")
            val advertisedLinkLocal = lwip?.localAddress()?.hostAddress?.substringBefore('%') ?: config.linkLocal
            val endpoint = Iap2WiredCarPlayEndpoint(
                ipv6Addresses = listOf(advertisedLinkLocal),
                airPlayPort = (if (config.wiredLwip) LWIP_LISTEN_PORT else vpnService?.boundPort())
                    ?: airPlayConfig.port,
                publicKey = identity.publicKeyHex,
                sourceVersion = airPlayConfig.sourceVersion,
                deviceIdentifier = ncmHostMac.macString(),
            )
            onStatus(CarPlayStatus.RunningControl)
            debugLog("wired iAP2 control starting")
            val result = Iap2WiredControlClient(csm, Iap2MfiAuthenticationClient(mfi)).run(
                identification = config.identification,
                endpoint = endpoint,
                availableCurrentMilliAmps = config.availableCurrentMilliAmps,
                timeoutMillis = controlLoopTimeoutMillis(),
                locationProvider = locationProvider,
                onIncoming = ::onRouteFrame,
                onProgress = { message -> debugLog("wired $message") },
            )
            onStatus(
                when (result.terminal) {
                    Iap2WiredControlTerminal.TIMED_OUT -> CarPlayStatus.ControlEnded
                    Iap2WiredControlTerminal.CHANNEL_CLOSED ->
                        CarPlayStatus.Failed("CarPlay control channel closed")
                },
            )
        } catch (error: Throwable) {
            // One line that names the stage, so a failure deep in USB bring-up does not have to be
            // reconstructed by diffing this log against a known-good one.
            debugLog(
                "wired bring-up verdict=failed stage=$phase " +
                    "reason=${error.message ?: error.javaClass.simpleName}",
            )
            debugLog("wired bring-up failed", error)
            if (!ncmOwnedLocally) vpnService?.detach()
            fail(error)
        } finally {
            if (ncmOwnedLocally) ncm.close()
        }
    }

    private fun pairNewRecord(client: LockdownPairingClient): LockdownPairRecord =
        client.pair(
            label = config.label,
            hostId = hostId,
            systemBuid = systemBuid,
            totalTimeoutMillis = PAIR_TIMEOUT_MILLIS,
            isCancelled = { closed },
        ).pairRecord.also(savePairRecord)

    private fun rejectedPairRecordError(error: Throwable): String? {
        var cause: Throwable? = error
        while (cause != null) {
            val message = cause.message.orEmpty()
            if (message.contains("InvalidPairRecord", ignoreCase = true)) return "InvalidPairRecord"
            if (message.contains("InvalidHostID", ignoreCase = true)) return "InvalidHostID"
            cause = cause.cause
        }
        return null
    }

    private fun isBluetoothHandoffCommand(type: String): Boolean =
        type.equals("disableBluetooth", ignoreCase = true) ||
            type.equals("disable-bluetooth", ignoreCase = true)

    private fun startWirelessHotspot(generation: Int): WirelessHotspotInfo {
        // Android 10+ can choose the Wi-Fi Direct group SSID, passphrase and frequency directly.
        // Android 8/9 cannot, so a LocalOnlyHotspot is preferred there. Android 7/7.1 have neither:
        // LocalOnlyHotspot only exists from API 26, so the legacy Wi-Fi Direct group — platform
        // chosen credentials and an unreported channel — is the only wireless option.
        val hotspotMode = when {
            config.wirelessHotspotMode == WirelessHotspotMode.MANUAL -> WirelessHotspotMode.MANUAL
            config.wirelessHotspotMode == WirelessHotspotMode.EXTERNAL_WIFI ->
                WirelessHotspotMode.EXTERNAL_WIFI
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> config.wirelessHotspotMode
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> WirelessHotspotMode.LOCAL_ONLY_HOTSPOT
            else -> WirelessHotspotMode.WIFI_P2P
        }
        if (hotspotMode == WirelessHotspotMode.MANUAL &&
            com.shilapi.xcertplay.network.CarHotspotStatus.isEnabled(appContext) == false
        ) {
            throw IOException("The car hotspot is off. Turn it on in the car settings and connect again.")
        }
        val manager: WirelessHotspotManager = when (hotspotMode) {
            WirelessHotspotMode.WIFI_P2P -> WifiP2pGroupManager(
                appContext,
                ::debugLog,
                preferredChannel = config.wifiP2pPreferredChannel,
            )
            WirelessHotspotMode.LOCAL_ONLY_HOTSPOT -> LocalOnlyHotspotManager(appContext)
            WirelessHotspotMode.EXTERNAL_WIFI -> ExternalWifiManager(
                context = appContext,
                expectedSsid = config.manualHotspotSsid.orEmpty(),
                passphrase = config.manualHotspotPassphrase.orEmpty(),
                onDiagnostic = ::debugLog,
            )
            WirelessHotspotMode.MANUAL -> ManualHotspotManager(
                context = appContext,
                ssid = config.manualHotspotSsid
                    ?: throw IOException("Manual hotspot SSID is not configured"),
                passphrase = config.manualHotspotPassphrase.orEmpty(),
                band = config.manualHotspotBand,
                channel = config.manualHotspotChannel,
                security = config.manualHotspotSecurity,
                onDiagnostic = ::debugLog,
            )
        }
        hotspot = manager
        val timeoutMillis = if (hotspotMode == WirelessHotspotMode.WIFI_P2P) {
            WIFI_P2P_START_TIMEOUT_MILLIS
        } else {
            HOTSPOT_START_TIMEOUT_MILLIS
        }
        return try {
            manager.start(timeoutMillis)
        } catch (failure: Exception) {
            // Wi-Fi Direct is a single global resource. When the phone's own "reset the connection"
            // flow hands the group away it stays group owner with our SSID prefix but under a
            // DIFFERENT random passphrase, so no reinstall of DiPlay can reclaim it and the iPhone
            // keeps rejoining a group we cannot log into. Reclaim it unconditionally; a foreign
            // group is rejected by the check inside WifiP2pGroupManager and still surfaces below.
            val reclaimed = failure is P2pResetRequiredException &&
                isStaleWirelessRun(generation) &&
                reclaimWiFiDirectGroupAfter(manager)
            if (reclaimed) {
                debugLog("无线启动已恢复：已清除残留的 Wi-Fi Direct 分组")
                try {
                    return manager.start(timeoutMillis)
                } catch (retryFailure: Exception) {
                    if (hotspot === manager) hotspot = null
                    closeBestEffort(hotspotMode.name) { manager.close() }
                    if (isStaleWirelessRun(generation)) throw retryFailure
                    throw IOException(
                        "Could not establish ${hotspotMode.name} hotspot: " +
                            (retryFailure.message ?: retryFailure.javaClass.simpleName),
                        retryFailure,
                    )
                }
            }
            if (hotspot === manager) hotspot = null
            closeBestEffort(hotspotMode.name) { manager.close() }
            if (isStaleWirelessRun(generation)) throw failure
            throw IOException(
                "Could not establish ${hotspotMode.name} hotspot: " +
                    (failure.message ?: failure.javaClass.simpleName),
                failure,
            )
        }
    }

    /**
     * Removes the Wi-Fi Direct group that [WifiP2pGroupManager] just refused to reclaim, then waits
     * for it to actually disappear. A refused group belongs to another install, so its recorded
     * passphrase is useless and there is nothing worth preserving -- but a group that is still
     * running must never be removed, so the reset is skipped while the peer is connected.
     *
     * Returns true when the group is confirmed gone and a second `start` is worth attempting.
     */
    private fun reclaimWiFiDirectGroupAfter(manager: WirelessHotspotManager): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val p2p = appContext.getSystemService(WifiP2pManager::class.java) ?: return false
        // WifiP2pManager.Channel.close() is API 27; on Android 7 the framework drops the channel
        // when this short-lived one stops being referenced.
        val channel = p2p.initialize(appContext, mainHandler.looper, null)
        val lock = Object()
        var removed = false
        try {
            val group = requestP2pGroupInfo(p2p, channel, lock)
            if (group == null) {
                debugLog("无线重置：没有需要清除的 Wi-Fi Direct 分组")
                return true
            }
            debugLog("无线重置：正在清除分组 owner=${group.isGroupOwner} 客户端数=${group.clientList?.size ?: 0}")
            if (group.clientList?.isNotEmpty() == true) {
                debugLog("无线重置已跳过：仍有其他设备连接在该分组上")
                return false
            }
            val removedLatch = java.util.concurrent.CountDownLatch(1)
            try {
                p2p.removeGroup(channel, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        removed = true
                        removedLatch.countDown()
                    }

                    override fun onFailure(reason: Int) {
                        debugLog("无线重置：removeGroup 被拒绝 reason=$reason")
                        removedLatch.countDown()
                    }
                })
            } catch (error: Exception) {
                debugLog("无线重置：无法发出 removeGroup", error)
                return false
            }
            try {
                removedLatch.await(P2P_RESET_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
            if (!removed) return false
            val drainDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(P2P_RESET_DRAIN_MILLIS)
            while (System.nanoTime() < drainDeadline) {
                if (requestP2pGroupInfo(p2p, channel, lock) == null) {
                    debugLog("无线重置：Wi-Fi Direct 分组已释放")
                    return true
                }
                Thread.sleep(P2P_RESET_POLL_MILLIS)
            }
            debugLog("无线重置：Wi-Fi Direct 分组未在限期内消失")
            return false
        } finally {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                runCatching { channel.close() }
            }
        }
    }

    /** WifiP2pManager reporters are delivered on the handler passed to initialize. */
    private fun requestP2pGroupInfo(
        p2p: WifiP2pManager,
        channel: WifiP2pManager.Channel,
        lock: Object,
    ): WifiP2pGroup? {
        var group: WifiP2pGroup? = null
        var answered = false
        synchronized(lock) {
            try {
                p2p.requestGroupInfo(channel) {
                    synchronized(lock) {
                        group = it
                        answered = true
                        lock.notifyAll()
                    }
                }
                val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(P2P_RESET_TIMEOUT_MILLIS)
                while (!answered) {
                    val remaining = deadline - System.nanoTime()
                    if (remaining <= 0L) break
                    lock.wait(remaining / 1_000_000L, (remaining % 1_000_000L).toInt())
                }
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (error: Exception) {
                debugLog("无线重置：requestGroupInfo 失败", error)
            }
        }
        return if (answered) group else null
    }

    private fun isStaleWirelessRun(generation: Int): Boolean =
        closed || phase != Phase.WIRELESS || generation != wirelessGeneration.get()

    private fun selectWirelessBluetoothDevice(adapter: BluetoothAdapter): BluetoothDevice {
        val bonded = adapter.bondedDevices.orEmpty()
        config.wirelessBluetoothDeviceAddress?.let { selected ->
            return bonded.firstOrNull { it.address.equals(selected, ignoreCase = true) }
                ?: throw IOException("The selected iPhone is no longer paired. Choose it again in DiPlay.")
        }
        val iPhones = bonded.filter { device ->
            device.name?.contains("iPhone", ignoreCase = true) == true
        }
        val directlyConnectedIPhones = iPhones.filter(::isBluetoothDeviceConnected)
        Log.i(
            IphoneCarPlayConfiguration.TAG,
            "wireless Bluetooth bondedIPhones=${iPhones.size} " +
                "directlyConnected=${directlyConnectedIPhones.size}",
        )
        val connectedIPhones = if (directlyConnectedIPhones.isNotEmpty()) {
            directlyConnectedIPhones
        } else {
            val connectedAddresses = connectedBluetoothDevices(adapter).mapTo(mutableSetOf()) {
                it.address
            }
            iPhones.filter { it.address in connectedAddresses }
        }
        if (connectedIPhones.size == 1) return connectedIPhones.single()
        if (connectedIPhones.size > 1) {
            throw IOException(
                "Multiple connected iPhones found: " +
                    connectedIPhones.joinToString { "${it.name ?: "iPhone"} (${it.address})" },
            )
        }
        if (iPhones.size == 1) return iPhones.single()
        if (iPhones.size > 1) {
            throw IOException(
                "Multiple bonded iPhones found and none is currently connected; " +
                    "connect one iPhone and retry",
            )
        }
        if (bonded.size == 1) return bonded.single()
        throw IOException(
            "No unambiguous bonded iPhone found; pair one iPhone and retry",
        )
    }

    private fun connectBluetoothSocket(socket: BluetoothSocket, address: String) {
        val result = AtomicReference<Throwable?>()
        val connected = CountDownLatch(1)
        Thread(
            {
                try {
                    socket.connect()
                } catch (error: Throwable) {
                    result.set(error)
                } finally {
                    connected.countDown()
                }
            },
            "wireless-rfcomm-connect",
        ).apply {
            isDaemon = true
            start()
        }
        val completed = try {
            connected.await(RFCOMM_CONNECT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            runCatching { socket.close() }
            throw IOException("Interrupted while connecting RFCOMM to $address", error)
        }
        if (!completed) {
            debugLog(
                "wireless RFCOMM connect timed out after " +
                    "${RFCOMM_CONNECT_TIMEOUT_MILLIS}ms address=$address",
            )
            runCatching { socket.close() }
            throw IOException(
                "Timed out after ${RFCOMM_CONNECT_TIMEOUT_MILLIS}ms connecting RFCOMM to $address",
            )
        }
        when (val failure = result.get()) {
            null -> Unit
            is IOException -> throw failure
            else -> throw IOException("Could not connect RFCOMM to $address", failure)
        }
    }

    /** Reads cached service metadata only; it does not start/cancel discovery or require SCAN. */
    private fun logBluetoothConnectionSnapshot(device: BluetoothDevice, point: String) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                appContext.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
            ) {
                connectionDiagnostic("Bluetooth snapshot point=$point unavailable reason=connect-permission")
                return
            }
            val uuids = device.uuids
            val service = UUID.fromString(IAP2_IPHONE_UUID)
            connectionDiagnostic(
                "Bluetooth snapshot point=$point enabled=${bluetoothAdapter?.isEnabled} " +
                    "bondState=${device.bondState} cachedServiceCount=${uuids?.size ?: "unknown"} " +
                    "cachedIap2Service=${uuids?.any { it.uuid == service } ?: "unknown"}",
            )
        } catch (error: RuntimeException) {
            connectionDiagnostic("Bluetooth snapshot point=$point unavailable failureClass=${diagnosticFailureClass(error)}")
        }
    }

    private fun closeWirelessStack(service: CarPlayVpnService? = vpnService) {
        // Always emit the verdict, even when the user closes the app mid-attempt. The control loop
        // only reaches its own verdict when it terminates on its own, which is exactly what does
        // NOT happen when someone gives up and force-closes — the case where the log matters most.
        logWirelessBringUpVerdict(null)
        wirelessConnectionProof.clear()
        media.setIapTunnelHandler(null)
        val activeTunnel = wirelessTunnelChannel
        wirelessTunnelChannel = null
        if (activeTunnel != null) closeBestEffort("tunneled iAP2 link") { activeTunnel.close() }

        closeBluetoothBootstrapTransport()

        val activeBonjour = bonjour
        bonjour = null
        if (activeBonjour != null) closeBestEffort("Bonjour") { activeBonjour.close() }
        closeBestEffort("mdns sniff") { MdnsSniffer.stop() }

        val activeHotspot = hotspot
        hotspot = null
        if (activeHotspot != null) closeBestEffort("wireless hotspot") { activeHotspot.close() }
        wirelessIdentification = null
        wirelessAirPlayEndpoint = null
        wirelessHandoffRequested.set(false)
        wirelessTunnelReady.set(false)
        wirelessActiveReported.set(false)

        if (service != null) closeBestEffort("AirPlay service") { service.detach() }
    }

    private fun isBluetoothDeviceConnected(device: BluetoothDevice): Boolean = try {
        val method = BluetoothDevice::class.java.getMethod("isConnected")
        method.invoke(device) as? Boolean == true
    } catch (error: ReflectiveOperationException) {
        false
    } catch (error: RuntimeException) {
        Log.w(IphoneCarPlayConfiguration.TAG, "Could not read Bluetooth connection state", error)
        false
    }

    private fun connectedBluetoothDevices(adapter: BluetoothAdapter): Set<BluetoothDevice> =
        buildSet {
            addAll(connectedBluetoothDevices(adapter, BluetoothProfile.HEADSET, BluetoothHeadset::class.java))
            addAll(connectedBluetoothDevices(adapter, BluetoothProfile.A2DP, BluetoothA2dp::class.java))
        }

    private fun <T : BluetoothProfile> connectedBluetoothDevices(
        adapter: BluetoothAdapter,
        profile: Int,
        profileClass: Class<T>,
    ): Set<BluetoothDevice> {
        val latch = CountDownLatch(1)
        val devices = java.util.Collections.synchronizedSet(mutableSetOf<BluetoothDevice>())
        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profileId: Int, proxy: BluetoothProfile) {
                try {
                    if (profileClass.isInstance(proxy)) {
                        devices.addAll(proxy.connectedDevices.orEmpty())
                    }
                } catch (error: SecurityException) {
                    Log.w(IphoneCarPlayConfiguration.TAG, "Could not read connected Bluetooth devices", error)
                } finally {
                    adapter.closeProfileProxy(profileId, proxy)
                    latch.countDown()
                }
            }

            override fun onServiceDisconnected(profileId: Int) {
                latch.countDown()
            }
        }
        if (!adapter.getProfileProxy(appContext, listener, profile)) return emptySet()
        if (!latch.await(3, TimeUnit.SECONDS)) {
            Log.w(IphoneCarPlayConfiguration.TAG, "Timed out reading Bluetooth profile $profile")
        }
        return synchronized(devices) { devices.toSet() }
    }

    @Suppress("DEPRECATION")
    private fun accessoryBluetoothMac(adapter: BluetoothAdapter): String {
        val address = try {
            adapter.address
        } catch (_: SecurityException) {
            null
        }
        val settingsAddress = try {
            Settings.Secure.getString(appContext.contentResolver, "bluetooth_address")
        } catch (_: SecurityException) {
            null
        }
        return listOfNotNull(address, settingsAddress)
            .firstOrNull {
                BLUETOOTH_ADDRESS.matches(it) &&
                    !it.equals(ADAPTER_ADDRESS_PLACEHOLDER, ignoreCase = true)
            }
            ?: airPlayConfig.btMac
    }

    private fun hostAddressText(address: InetAddress): String {
        val text = address.hostAddress?.substringBefore('%')
        if (text.isNullOrBlank()) {
            throw IOException("LocalOnlyHotspot host address is unavailable")
        }
        return text
    }

    /** Parses "aa:bb:cc:dd:ee:ff" into the 0x5703 BSSID bytes; null when absent or malformed. */
    private fun bssidBytes(text: String?): ByteArray? {
        if (text == null) return null
        val parts = text.split(':', '-')
        if (parts.size != 6) return null
        val bytes = ByteArray(6)
        for ((index, part) in parts.withIndex()) {
            if (part.length != 2) return null
            bytes[index] = part.toIntOrNull(16)?.toByte() ?: return null
        }
        return bytes
    }

    /**
     * Every routable address of the hotspot interface, most-likely-usable first.
     *
     * IPv4 deliberately leads. A Wi-Fi Direct group owner normally holds 192.168.49.1, whereas the
     * interface's other address is a *link-local* IPv6 whose scope has to be stripped for this text
     * field — as a bare literal that is unroutable. Handing the phone the unroutable one first
     * risks it trying that and giving up, so the routable address goes first and IPv6 stays as a
     * fallback for a peer that negotiated IPv6.
     */
    private fun hostAddressTexts(interfaceName: String?, preferred: String): List<String> {
        val addresses = interfaceName
            ?.let { name -> runCatching { NetworkInterface.getByName(name) }.getOrNull() }
            ?.let { nic -> Collections.list(nic.inetAddresses) }
            .orEmpty()
        val candidates = listOf(
            addresses.filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress }?.hostAddress,
            addresses.filterIsInstance<Inet6Address>()
                .firstOrNull { !it.isLoopbackAddress }?.hostAddress?.substringBefore('%'),
        ).filterNotNull().filter { it.isNotBlank() }
        return (candidates + preferred).distinct()
    }

    /** Report-safe label for an address literal; the address itself is redacted from reports. */
    private fun familyLabel(addressText: String): String = when {
        addressText.startsWith("fe80:", ignoreCase = true) -> "IPv6-linklocal"
        ':' in addressText -> "IPv6"
        else -> "IPv4"
    }

    /**
     * Connects to our own AirPlay port over every address the hotspot interface holds.
     *
     * A listener bound to the IPv6 wildcard `::` does **not** necessarily accept IPv4: that depends
     * on `IPV6_V6ONLY`, whose default is platform-specific. Nothing else in the log can tell that
     * apart from "the phone never dialled", because both look identical — no
     * `airplay connection accepted from` line. This self-connect is the only check that does not
     * depend on the phone doing anything at all.
     */
    private fun selfTestAirPlayPort(interfaceName: String?, port: Int) {
        val nic = interfaceName
            ?.let { name -> runCatching { NetworkInterface.getByName(name) }.getOrNull() }
            ?: return
        val targets = Collections.list(nic.inetAddresses).filter { !it.isLoopbackAddress }
        if (targets.isEmpty()) {
            debugLog("wireless self-test skipped: iface=$interfaceName has no address")
            return
        }
        debugLog("wireless self-test probing ${targets.size} address(es) on iface=$interfaceName")
        for (target in targets) {
            val label = if (target is Inet4Address) "IPv4" else "IPv6"
            // Log the attempt before connecting so a family that is silently skipped (or one whose
            // result never lands) is visible as a missing line rather than an invisible gap.
            debugLog("wireless self-test $label port=$port connecting")
            val outcome = runCatching {
                Socket().use { socket ->
                    socket.bind(InetSocketAddress(target, 0))
                    socket.connect(
                        InetSocketAddress(target, port),
                        SELF_TEST_CONNECT_TIMEOUT_MILLIS,
                    )
                }
            }
            debugLog(
                if (outcome.isSuccess) {
                    "wireless self-test $label port=$port reachable=true"
                } else {
                    "wireless self-test $label port=$port reachable=false " +
                        "reason=${outcome.exceptionOrNull()?.javaClass?.simpleName}"
                },
            )
        }
    }

    private fun closeBestEffort(name: String, close: () -> Unit) {
        val started = System.nanoTime()
        var completed = false
        try {
            close()
            completed = true
        } catch (error: Throwable) {
            debugLog("$name teardown failed", error)
        } finally {
            connectionDiagnostic("teardown resource=$name completed=$completed elapsedMs=${elapsedMillis(started)}")
        }
    }

    private fun controlLoopTimeoutMillis(): Long = when {
        config.transport == CarPlayTransport.WIRED -> Iap2WiredControlClient.NO_TIMEOUT_MILLIS
        config.locationReportingEnabled -> LOCATION_CONTROL_LOOP_TIMEOUT_MILLIS
        else -> CONTROL_LOOP_TIMEOUT_MILLIS
    }

    private fun attachVpn(ncm: NcmUsbBridge, hostMac: ByteArray): Boolean {
        onStatus(CarPlayStatus.AttachingNetwork)
        val service = awaitVpnService() ?: run {
            debugLog("wired VPN service bind failed")
            ncm.close()
            return false
        }
        debugLog("wired VPN service bound; attaching NCM transport")
        val result = try {
            service.attach(
                ncm = ncm,
                linkLocal = config.linkLocal,
                hostMac = hostMac,
                config = airPlayConfig,
                identity = identity,
                pairings = pairings,
                mfi = mfiSession?.client,
                listener = sessionListener,
                media = media,
            )
        } catch (error: Throwable) {
            ncm.close()
            onStatus(CarPlayStatus.Failed(error.message ?: error.javaClass.simpleName))
            return false
        }
        return when (result) {
            CarPlayVpnService.AttachResult.Started -> {
                debugLog("wired VPN/NCM transport attach result=started")
                true
            }
            CarPlayVpnService.AttachResult.AlreadyStarted -> {
                debugLog("wired VPN/NCM transport attach result=already-started")
                ncm.close()
                false
            }
            is CarPlayVpnService.AttachResult.Failed -> {
                debugLog("wired VPN/NCM transport attach result=failed ${result.message}")
                ncm.close()
                onStatus(CarPlayStatus.Failed(result.message))
                false
            }
        }
    }

    /**
     * Beta wired transport: the userspace lwIP stack consumes the NCM frames (no VpnService),
     * the AirPlay server keeps running on the JVM wildcard as usual, and a loopback proxy
     * relays the iPhone's lwIP connections into it. Requires a 32-bit process.
     */
    private fun attachLwip(ncm: NcmUsbBridge, hostMac: ByteArray): Boolean {
        onStatus(CarPlayStatus.AttachingNetwork)
        if (!LwipNative.available) {
            // The v7a library cannot load in a 64-bit process: fall back to the kernel path
            // instead of losing the wired transport entirely.
            debugLog(
                "wired lwip unavailable, falling back to the VPN transport " +
                    "is64Bit=${android.os.Process.is64Bit()} error=${LwipNative.loadError}",
            )
            return attachVpn(ncm, hostMac)
        }
        return try {
            val session = LwipSessionNetwork(
                ncm,
                { message -> debugLog(message) },
                { failure -> debugLog("wired lwip failure", failure) },
            )
            session.start()
            lwip = session
            debugLog(
                "wired lwip transport started availability=${LwipNative.available} " +
                    "is64Bit=${android.os.Process.is64Bit()} linkLocal=${session.localAddress().hostAddress}",
            )
            val service = awaitVpnService() ?: run {
                debugLog("wired VPN service bind failed (lwip mode)")
                ncm.close()
                return false
            }
            when (val result = service.attachWireless(
                bindAddress = java.net.InetAddress.getLoopbackAddress(),
                config = airPlayConfig,
                identity = identity,
                pairings = pairings,
                mfi = mfiSession?.client,
                listener = sessionListener,
                media = media,
                loopbackRelay = true,
                // The session announces extra TCP ports as it progresses (eventPort,
                // mirror stream data port): each one gets its lwIP relay listener here.
                portNotifier = { port -> lwip?.startProxyPort(port) },
                // Announced UDP ports (timing/NTP, keepalive) get a datagram relay — the
                // phone polls our timing port and waits for valid answers before streaming.
                udpPortNotifier = { port -> lwip?.startUdpProxy(port) },
            )) {
                CarPlayVpnService.AttachResult.Started -> {
                    val targetPort = service.boundPort() ?: airPlayConfig.port
                    session.startProxy(targetPort)
                    debugLog("wired lwip proxy started target=127.0.0.1:$targetPort")
                    true
                }
                CarPlayVpnService.AttachResult.AlreadyStarted -> {
                    debugLog("wired lwip airplay attach result=already-started")
                    session.close()
                    ncm.close()
                    false
                }
                is CarPlayVpnService.AttachResult.Failed -> {
                    debugLog("wired lwip airplay attach result=failed ${result.message}")
                    session.close()
                    ncm.close()
                    onStatus(CarPlayStatus.Failed(result.message))
                    false
                }
            }
        } catch (failure: Throwable) {
            debugLog("wired lwip attach failed", failure)
            lwip?.close()
            lwip = null
            ncm.close()
            onStatus(CarPlayStatus.Failed(failure.message ?: failure.javaClass.simpleName))
            false
        }
    }

    private fun ByteArray.macString(): String =
        joinToString(":") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun awaitVpnService(): CarPlayVpnService? {
        vpnService?.let { return it }
        bindVpn()
        return try {
            if (vpnLatch.await(VPN_CONNECT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) vpnService else null
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }
    }

    private fun bindVpn() {
        if (vpnBound) return
        vpnBound = true
        try {
            val intent = Intent(appContext, CarPlayVpnService::class.java)
            if (!appContext.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)) {
                vpnBound = false
                vpnLatch.countDown()
            }
        } catch (_: Throwable) {
            vpnBound = false
            vpnLatch.countDown()
        }
    }

    private fun unbindVpn() {
        if (!vpnBound) return
        vpnBound = false
        try {
            appContext.unbindService(serviceConnection)
        } catch (_: Exception) {
            // The service may have already been unbound.
        }
        vpnService = null
    }

    private fun closeReceivers() {
        listOfNotNull(permissionCloseable, attachCloseable, ch341PermissionCloseable).forEach {
            try {
                it.close()
            } catch (_: Exception) {
                // Receiver is already unregistered.
            }
        }
        permissionCloseable = null
        attachCloseable = null
        ch341PermissionCloseable = null
    }

    private fun closeMfiSession() {
        val session = mfiSession
        mfiSession = null
        if (session != null) {
            executor.execute {
                try {
                    session.close()
                } catch (_: Exception) {
                    // Best effort.
                }
            }
        }
    }

    private fun fail(error: Throwable) {
        if (closed) return
        onStatus(CarPlayStatus.Failed(error.message ?: error.javaClass.simpleName,
            generateSequence(error) { it.cause }.any { it is P2pResetRequiredException }))
    }

    private fun debugLog(message: String) {
        Log.i(IphoneCarPlayConfiguration.TAG, message)
        try {
            uiListener?.onDebugLog(message)
        } catch (error: Exception) {
            Log.w(IphoneCarPlayConfiguration.TAG, "debug log callback failed", error)
        }
    }

    private fun connectionDiagnostic(message: String) {
        try {
            // The redactor reserves "PHONE " for private phone-side log captures.
            val diagnosticPhase = if (phase == Phase.IPHONE) "USB_DISCOVERY" else phase.name
            debugLog("$CONNECTION_DIAGNOSTIC_PREFIX attempt=$diagnosticAttempt run=${diagnosticRun.get()} phase=$diagnosticPhase $message")
        } catch (_: Exception) {
            // Optional diagnostics must not change transport or shutdown behavior.
        }
    }

    private fun diagnosticFailureClass(error: Throwable): String =
        error.javaClass.simpleName.take(80).replace(Regex("[^A-Za-z0-9_$]"), "?")

    private fun elapsedMillis(startedNanos: Long): Long =
        ((System.nanoTime() - startedNanos) / 1_000_000L).coerceAtLeast(0)

    private fun debugLog(message: String, error: Throwable) {
        Log.w(IphoneCarPlayConfiguration.TAG, message, error)
        try {
            uiListener?.onDebugLog(
                "$message: ${error.message ?: error.javaClass.simpleName}",
            )
        } catch (callbackError: Exception) {
            Log.w(IphoneCarPlayConfiguration.TAG, "debug log callback failed", callbackError)
        }
    }

    private fun onStatus(status: CarPlayStatus) {
        if (closed) return
        mainHandler.post {
            if (!closed && status != lastReportedStatus) {
                lastReportedStatus = status
                connectionDiagnostic("stage=${status.javaClass.simpleName}")
                uiListener?.onDebugLog(status.debugLogMessage())
                uiStatusReporter?.invoke(status)
            }
        }
    }

    private fun CarPlayStatus.debugLogMessage(): String = when (this) {
        CarPlayStatus.DiscoveringMfi ->
            "STEP mfi/start: preparing the configured MFi authentication provider"
        CarPlayStatus.WaitingForMfi ->
            "STEP mfi/wait: MFi coprocessor not present; polling"
        CarPlayStatus.RequestingMfiPermission ->
            "STEP mfi/permission: requesting CH341 USB access"
        CarPlayStatus.MfiReady ->
            "STEP mfi/ready: MFi authentication provider is ready"
        CarPlayStatus.StartingHotspot ->
            "STEP wifi/ap: starting the wireless CarPlay access point"
        is CarPlayStatus.HotspotReady ->
            "STEP wifi/ap-ready: backend=$backend ssid=$ssid band=$band " +
                "channel=$channel bssid=$bssid address=$address"
        CarPlayStatus.WaitingForPairedIphone ->
            "STEP bt/select: waiting for a paired or connected iPhone"
        CarPlayStatus.ConnectingBluetooth ->
            "STEP bt/rfcomm: connecting to the iPhone iAP2 RFCOMM service"
        CarPlayStatus.RunningWireless ->
            "STEP iap2/wireless: Bluetooth control loop running"
        CarPlayStatus.WirelessActive ->
            "STEP handoff/complete: tunnel iAP2 ready; Bluetooth bootstrap released"
        CarPlayStatus.DiscoveringIphone ->
            "STEP usb/discover: searching for an iPhone USB device"
        CarPlayStatus.WaitingForIphone ->
            "STEP usb/wait: iPhone USB device not present; polling"
        CarPlayStatus.RequestingIphonePermission ->
            "STEP usb/permission: requesting USB access to the iPhone"
        CarPlayStatus.WaitingForReenumeration ->
            "STEP usb/reenum: waiting for the CarPlay USB configuration"
        CarPlayStatus.SelectingConfiguration ->
            "STEP usb/config: selecting the iPhone CarPlay configuration"
        CarPlayStatus.OpeningDataPaths ->
            "STEP usb/data: opening iAP2 and NCM USB data paths"
        CarPlayStatus.Pairing ->
            "STEP lockdown/pair: loading or creating the pairing record"
        CarPlayStatus.ConnectingControl ->
            "STEP lockdown/carkit: opening com.apple.carkit.service"
        CarPlayStatus.AttachingNetwork ->
            "STEP network/attach: attaching the AirPlay network transport"
        CarPlayStatus.RunningControl ->
            "STEP iap2/wired: wired iAP2 control loop running"
        CarPlayStatus.ControlEnded ->
            "STEP control/end: the control window ended"
        is CarPlayStatus.Failed ->
            "ERROR $message"
    }

    companion object {
        const val CONNECTION_DIAGNOSTIC_PREFIX = "CONNECTION_DIAGNOSTIC"
        private val diagnosticAttempts = AtomicInteger()
        private const val IAP2_IPHONE_UUID = "00000000-deca-fade-deca-deafdecacafe"
        private const val HOTSPOT_START_TIMEOUT_MILLIS = 60_000L
        private const val WIFI_P2P_START_TIMEOUT_MILLIS = 20_000L
        /** How long a stale Wi-Fi Direct group gets to acknowledge removeGroup. */
        private const val P2P_RESET_TIMEOUT_MILLIS = 6_000L
        /** How long the group must keep reporting as absent before it is really gone. */
        private const val P2P_RESET_DRAIN_MILLIS = 8_000L
        private const val P2P_RESET_POLL_MILLIS = 250L
        /** How long the wireless bring-up keeps watching for a device joining the group. */
        private const val WIRELESS_CLIENT_WATCH_MILLIS = 120_000L
        private const val WIRELESS_CLIENT_WATCH_POLL_MILLIS = 2_000L
        /** How often the wireless verdict is repeated while a bring-up is still running. */
        private const val WIRELESS_VERDICT_INTERVAL_MILLIS = 15_000L
        /** Bound on the loopback-free self-connect that proves the AirPlay port is reachable. */
        private const val SELF_TEST_CONNECT_TIMEOUT_MILLIS = 2_000
        private const val PAIR_TIMEOUT_MILLIS = 5 * 60_000L
        private const val VPN_CONNECT_TIMEOUT_MILLIS = 10_000L
        private const val CONTROL_LOOP_TIMEOUT_MILLIS = 5 * 60_000L
        private const val LOCATION_CONTROL_LOOP_TIMEOUT_MILLIS = 24 * 60 * 60 * 1_000L
        private const val PERMISSION_POLL_INTERVAL_MILLIS = 500L
        /** Backstop cadence for a missed USB attach broadcast after re-enumeration. */
        private const val PERMISSION_POLL_TIMEOUT_MILLIS = 120_000L
        private const val DEVICE_AVAILABILITY_POLL_INTERVAL_MILLIS = 2_000L
        private const val MAX_CONFIGURATION_TRANSITIONS = 2
        private const val TRANSITION_POLL_INTERVAL_MILLIS = 400L
        private const val WIRELESS_HANDOFF_TIMEOUT_MILLIS = 45_000L
        private const val RFCOMM_CONNECT_TIMEOUT_MILLIS = 15_000L
        private const val AUTO_GRANT_POLL_ATTEMPTS = 6
        private const val AUTO_GRANT_POLL_MILLIS = 400L
        private const val EXECUTOR_CLOSE_TIMEOUT_MILLIS = 2_000L
        private const val ADAPTER_ADDRESS_PLACEHOLDER = "02:00:00:00:00:00"
        private val BLUETOOTH_ADDRESS = Regex("^[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}$")
    }
}

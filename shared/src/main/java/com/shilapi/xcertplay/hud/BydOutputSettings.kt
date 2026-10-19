package com.shilapi.xcertplay.hud

import android.content.Context
import com.shilapi.xcertplay.transport.EvChargingConnectors

/**
 * Which protocol the advanced vehicle-data panel drives.
 *
 * The first two are BYD DiLink: [DEFAULT] reads the addresses verified on DiLink 5.0, [LEGACY] reads
 * the fields a saved head-unit probe confirmed. Both go through the network ADB shell.
 *
 * [LEAPMOTOR] is a different car, not a third DiLink variant: the signals arrive on the
 * `leapmotor.cantoapp.update` broadcast (`LeapmotorVehicleSignals`), and a Leapmotor head unit has
 * no adbd at all, so every ADB step is skipped. The gear letter also differs where it matters most:
 * BYD reports P while parked, Leapmotor only reports R/N/D and treats **N** as the video-allowed
 * gear (`LeapmotorParkedState`).
 */
enum class BydVehicleDataMode { DEFAULT, LEGACY, LEAPMOTOR }

/**
 * One user switch for BYD navigation output. On the tested car the windshield HUD mirrors what the
 * instrument cluster receives, so separate HUD/cluster switches cannot behave independently.
 */
object BydOutputSettings {
    private const val PREFS = "diplay_byd_outputs"
    private const val KEY_ENABLED = "navigation_enabled"
    private const val KEY_CLUSTER_STREAM_PAUSE = "cluster_stream_pause"
    private const val KEY_BATTERY_TO_IPHONE = "battery_to_iphone"
    private const val KEY_LOW_CHARGE_PERCENT = "low_charge_percent"
    private const val KEY_CHARGING_CONNECTORS = "charging_connectors"
    private const val KEY_WHEEL_SPEED_TO_IPHONE = "wheel_speed_to_iphone"
    private const val KEY_VIDEO_WHILE_PARKED = "video_while_parked"
    private const val KEY_CLUSTER_SONG = "cluster_song"
    private const val KEY_CLUSTER_SONG_ON_CHANGE = "cluster_song_on_change"
    private const val KEY_HUD_SONG = "hud_song"
    private const val KEY_CARPLAY_CALLS = "carplay_calls"
    private const val KEY_CARPLAY_CALL_CONTROLS = "carplay_call_controls_experimental"
    private const val KEY_PHONE_AUDIO_ROUTE = "phone_audio_route_experimental"
    private const val KEY_PHONE_AUDIO_ROUTE_APPS = "phone_audio_route_apps_experimental"
    private const val KEY_FCE_CLUSTER = "fce_cluster_guidance_experimental"
    private const val KEY_OEM_CLUSTER_HOLD = "oem_cluster_hold"
    private const val KEY_LEGACY_VEHICLE_PROBE = "legacy_vehicle_probe"
    private const val KEY_VEHICLE_DATA_MODE = "vehicle_data_mode"
    const val DEFAULT_LOW_CHARGE_PERCENT = 20
    val lowChargePresets = listOf(10, 15, 20, 25, 30)

    fun enabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, true)

    /**
     * True on a Leapmotor head unit, where the OEM outputs (vehicle data, call card) use the car's
     * broadcast API instead of BYD's ADB shell. Exposed here so the `common` module — which cannot see
     * `LmCarProbe` (`internal` to `shared`) — can gate its settings without duplicating the probe.
     */
    fun leapmotorHeadUnit(): Boolean = com.shilapi.xcertplay.LmCarProbe.isLeapmotor()

    /**
     * Record the driver's manual head-unit pick from the setup guide, on the `common` side of the
     * module boundary. `true` = "零跑 S01/T03" so every Leapmotor protocol turns on without a file
     * probe; `false` = a BYD DiLink unit, which also suppresses a stale Leapmotor probe. The setup
     * guide's `DiLinkGeneration.save` is the only caller.
     */
    fun setLeapmotorUserChoice(context: Context, leapmotor: Boolean) =
        com.shilapi.xcertplay.LmCarProbe.setUserChoice(context, leapmotor)

    /** Drop the manual pick ("Not sure") and go back to the runtime file probe. */
    fun clearLeapmotorUserChoice(context: Context) =
        com.shilapi.xcertplay.LmCarProbe.clearUserChoice(context)

    /** Load the saved manual pick once at startup so gating matches the driver's choice immediately. */
    fun loadLeapmotorUserChoice(context: Context) = com.shilapi.xcertplay.LmCarProbe.loadUserChoice(context)

    fun setEnabled(context: Context, enabled: Boolean) = prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()

    /** Ask the iPhone to stop drawing the cluster map while the cluster hides it (needs ADB over network). */
    fun clusterStreamPause(context: Context): Boolean = prefs(context).getBoolean(KEY_CLUSTER_STREAM_PAUSE, false)

    fun setClusterStreamPause(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean(KEY_CLUSTER_STREAM_PAUSE, enabled).apply()

    /** Tell the iPhone the car's charge and range (needs ADB over network); applies on the next connection. */
    fun batteryToIphone(context: Context): Boolean = prefs(context).getBoolean(KEY_BATTERY_TO_IPHONE, false)

    fun setBatteryToIphone(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_BATTERY_TO_IPHONE, enabled).commit()
    }

    /** Default mode uses the DiLink 5.0 addresses; legacy mode exposes only fields its saved probe confirmed. */
    fun batteryToIphoneActive(context: Context): Boolean =
        batteryToIphone(context) && supportedInSelectedMode(context) { it.batterySupported }

    /** The charging inlets the iPhone is told about; applies on the next connection. */
    fun chargingConnectors(context: Context): EvChargingConnectors =
        prefs(context).getString(KEY_CHARGING_CONNECTORS, null)
            ?.let { saved -> EvChargingConnectors.entries.firstOrNull { it.name == saved } }
            ?: EvChargingConnectors.CCS2_TYPE2

    fun setChargingConnectors(context: Context, connectors: EvChargingConnectors) {
        prefs(context).edit().putString(KEY_CHARGING_CONNECTORS, connectors.name).commit()
    }

    /** Send wheel speed and gear with the car's GPS (needs ADB over network); applies on the next connection. */
    fun wheelSpeedToIphone(context: Context): Boolean = prefs(context).getBoolean(KEY_WHEEL_SPEED_TO_IPHONE, false)

    fun setWheelSpeedToIphone(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_WHEEL_SPEED_TO_IPHONE, enabled).commit()
    }

    fun wheelSpeedToIphoneActive(context: Context): Boolean =
        wheelSpeedToIphone(context) && supportedInSelectedMode(context) { it.motionSupported }
    /**
     * Offer iOS 27 video in car, played only while the car is parked.
     *
     * "Parked" is per car line: BYD reports P and it is read over ADB, a Leapmotor unit reports only
     * R/N/D on the CAN bus and allows video in N. The default follows that: the N-gear gate needs
     * nothing but the broadcast, so a Leapmotor unit starts with this on; the BYD path needs ADB over
     * network and stays opt-in.
     */
    fun videoWhileParked(context: Context): Boolean =
        prefs(context).getBoolean(KEY_VIDEO_WHILE_PARKED, com.shilapi.xcertplay.LmCarProbe.isLeapmotor())

    fun setVideoWhileParked(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_VIDEO_WHILE_PARKED, enabled).commit()
    }

    /** Show the CarPlay song in the dashboard's music card (needs ADB over network); applies at once. */
    fun clusterSong(context: Context): Boolean = prefs(context).getBoolean(KEY_CLUSTER_SONG, false)

    fun setClusterSong(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean(KEY_CLUSTER_SONG, enabled).apply()

    /** Show CarPlay calls on the dashboard and HUD like BYD's CarPlay app (needs ADB over network); applies at once. */
    fun carPlayCalls(context: Context): Boolean = prefs(context).getBoolean(KEY_CARPLAY_CALLS, false)

    fun setCarPlayCalls(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean(KEY_CARPLAY_CALLS, enabled).apply()

    /** Unverified DiLink 3 call/voice/media key handling requires a separate explicit opt-in. */
    fun carPlayCallControls(context: Context): Boolean =
        prefs(context).getBoolean(KEY_CARPLAY_CALL_CONTROLS, false)

    fun setCarPlayCallControls(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean(KEY_CARPLAY_CALL_CONTROLS, enabled).apply()

    /**
     * Hold a Telecom call during CarPlay calls so the car's own phone audio state, and its echo canceller,
     * handle the call. Replaces DiPlay's own canceller for those calls; applies at the next call.
     */
    fun phoneAudioRoute(context: Context): Boolean = prefs(context).getBoolean(KEY_PHONE_AUDIO_ROUTE, false)

    fun setPhoneAudioRoute(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean(KEY_PHONE_AUDIO_ROUTE, enabled).apply()

    /** The same for voice-over-IP calls in other apps; only calls that record from VOICE_COMMUNICATION qualify. */
    fun phoneAudioRouteApps(context: Context): Boolean = prefs(context).getBoolean(KEY_PHONE_AUDIO_ROUTE_APPS, false)

    fun setPhoneAudioRouteApps(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean(KEY_PHONE_AUDIO_ROUTE_APPS, enabled).apply()

    /** Show a new song on the dashboard for a few seconds only, then an empty card. */
    fun clusterSongOnChange(context: Context): Boolean = prefs(context).getBoolean(KEY_CLUSTER_SONG_ON_CHANGE, false)

    fun setClusterSongOnChange(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean(KEY_CLUSTER_SONG_ON_CHANGE, enabled).apply()

    fun videoWhileParkedActive(context: Context): Boolean =
        videoWhileParked(context) && supportedInSelectedMode(context) { it.gearSupported }

    /**
     * The vehicle-data protocol in use. An explicit pick always wins; with nothing saved, a
     * Leapmotor head unit answers on the CAN bus and can use nothing else, so it defaults to
     * [BydVehicleDataMode.LEAPMOTOR]. Everything else keeps the historical decision: a saved probe
     * means [BydVehicleDataMode.LEGACY], a fresh installation [BydVehicleDataMode.DEFAULT].
     */
    fun vehicleDataMode(context: Context): BydVehicleDataMode {
        val settings = prefs(context)
        settings.getString(KEY_VEHICLE_DATA_MODE, null)
            ?.let { saved -> BydVehicleDataMode.entries.firstOrNull { it.name == saved } }
            ?.let { return it }
        if (com.shilapi.xcertplay.LmCarProbe.isLeapmotor()) return BydVehicleDataMode.LEAPMOTOR
        return if (BydVehicleFieldStore.load(context) != null) BydVehicleDataMode.LEGACY
        else BydVehicleDataMode.DEFAULT
    }

    /** Persists the mode and keeps the historical [KEY_LEGACY_VEHICLE_PROBE] flag in step. */
    fun setVehicleDataMode(context: Context, mode: BydVehicleDataMode) {
        check(prefs(context).edit()
            .putString(KEY_VEHICLE_DATA_MODE, mode.name)
            .putBoolean(KEY_LEGACY_VEHICLE_PROBE, mode == BydVehicleDataMode.LEGACY)
            .commit()) { "Could not persist vehicle-data mode" }
    }

    /**
     * Whether the saved legacy head-unit probe should be used. Derived from [vehicleDataMode] so the
     * legacy mode and the Leapmotor mode can never both claim the vehicle-data switches.
     */
    fun legacyVehicleProbe(context: Context): Boolean =
        vehicleDataMode(context) == BydVehicleDataMode.LEGACY

    /** Kept for the existing call sites: `true` = legacy probe, `false` = the DiLink 5.0 default. */
    fun setLegacyVehicleProbe(context: Context, enabled: Boolean) =
        setVehicleDataMode(context,
            if (enabled) BydVehicleDataMode.LEGACY else BydVehicleDataMode.DEFAULT)

    /** Optional title/lyrics when navigation is absent; only the verified HUD output can send it. */
    fun hudSong(context: Context): Boolean = prefs(context).getBoolean(KEY_HUD_SONG, false)
    fun setHudSong(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean(KEY_HUD_SONG, enabled).apply()

    /** OEM changes require an explicit selection; fresh installations leave the stock map alone. */
    fun oemClusterHold(context: Context): BydOemClusterHold {
        val settings = prefs(context)
        BydOemClusterHold.fromName(settings.getString(KEY_OEM_CLUSTER_HOLD, null))?.let { return it }
        return if (settings.getBoolean("oem_cluster_freeze", false)) BydOemClusterHold.PACKAGE
            else BydOemClusterHold.OFF
    }
    fun setOemClusterHold(context: Context, hold: BydOemClusterHold) =
        prefs(context).edit().putString(KEY_OEM_CLUSTER_HOLD, hold.name).apply()

    /** At or below this charge the iPhone gets the low-range warning. */
    fun lowChargePercent(context: Context): Int = prefs(context).getInt(KEY_LOW_CHARGE_PERCENT, DEFAULT_LOW_CHARGE_PERCENT)

    fun setLowChargePercent(context: Context, percent: Int) =
        prefs(context).edit().putInt(KEY_LOW_CHARGE_PERCENT, percent).apply()

    fun standaloneHudAvailable(context: Context): Boolean = BydStandaloneHudOutput.available(context)
    fun standaloneHudDiagnosticReport(context: Context): String = BydStandaloneHudOutput.diagnostics(context)

    /** Whether the head unit has a BYD navigation receiver. This says nothing about ADB vehicle data. */
    fun navigationAvailable(context: Context): Boolean =
        BydStandaloneHudOutput.available(context) ||
            BydAmapAdapter.find { installed(context, it) } != null ||
            installed(context, "com.ts.car.someip.service")

    /** Whether the head unit has the FCE vehicle-property service that feeds its cluster's navigation view. */
    fun fceClusterAvailable(): Boolean = FceClusterBridge.available()

    /**
     * Opt-in for [FceClusterBridge]: it writes vendor vehicle properties whose meaning was confirmed on one
     * car only, and another head unit with the same service could read them differently.
     */
    fun fceCluster(context: Context): Boolean = prefs(context).getBoolean(KEY_FCE_CLUSTER, false)

    fun setFceCluster(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean(KEY_FCE_CLUSTER, enabled).apply()

    /** Whether the head unit has a BYD navigation receiver or is a BYD head unit, so settings can show navigation/map options. */
    fun available(context: Context): Boolean =
        navigationAvailable(context) ||
            installed(context, "com.byd.carsettings") ||
            installed(context, "com.byd.appmgr") ||
            installed(context, "com.byd.deviceinfo") ||
            installed(context, "com.byd.service") ||
            android.os.Build.FINGERPRINT.contains("BYD", ignoreCase = true) ||
            android.os.Build.BRAND.contains("BYD", ignoreCase = true) ||
            android.os.Build.MANUFACTURER.contains("BYD", ignoreCase = true) ||
            android.os.Build.PRODUCT.contains("BYD", ignoreCase = true) ||
            android.os.Build.DEVICE.contains("BYD", ignoreCase = true)

    private fun installed(context: Context, pkg: String): Boolean =
        runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess

    /**
     * Whether a switch is usable in the mode that is selected.
     *
     * [BydVehicleDataMode.DEFAULT] reads fixed DiLink 5.0 addresses and needs no probe, so it is
     * always usable. [BydVehicleDataMode.LEAPMOTOR] reads the CAN broadcast, which carries every
     * field regardless of the head unit, so it needs no probe either. Only the legacy mode has to
     * check what its saved probe actually confirmed.
     */
    private fun supportedInSelectedMode(
        context: Context,
        supported: (BydVehicleCapabilities) -> Boolean,
    ): Boolean = when (vehicleDataMode(context)) {
        BydVehicleDataMode.LEAPMOTOR, BydVehicleDataMode.DEFAULT -> true
        BydVehicleDataMode.LEGACY -> BydVehicleFieldStore.load(context)?.let(supported) == true
    }

    /** True when the selected mode reads through the ADB shell, i.e. never on a Leapmotor unit. */
    fun vehicleDataNeedsAdb(context: Context): Boolean =
        vehicleDataMode(context) != BydVehicleDataMode.LEAPMOTOR

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

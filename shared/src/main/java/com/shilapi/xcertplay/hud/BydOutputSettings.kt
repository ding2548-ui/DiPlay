package com.shilapi.xcertplay.hud

import android.content.Context
import com.shilapi.xcertplay.transport.EvChargingConnectors

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
    private const val KEY_OEM_CLUSTER_HOLD = "oem_cluster_hold"
    private const val KEY_LEGACY_VEHICLE_PROBE = "legacy_vehicle_probe"
    const val DEFAULT_LOW_CHARGE_PERCENT = 20
    val lowChargePresets = listOf(10, 15, 20, 25, 30)

    fun enabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, true)

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
    /** Offer iOS 27 video in car, played only while the gear reads P (needs ADB over network). */
    fun videoWhileParked(context: Context): Boolean = prefs(context).getBoolean(KEY_VIDEO_WHILE_PARKED, false)

    fun setVideoWhileParked(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_VIDEO_WHILE_PARKED, enabled).commit()
    }

    /** Show the CarPlay song in the dashboard's music card (needs ADB over network); applies at once. */
    fun clusterSong(context: Context): Boolean = prefs(context).getBoolean(KEY_CLUSTER_SONG, false)

    fun setClusterSong(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean(KEY_CLUSTER_SONG, enabled).apply()

    /** Show a new song on the dashboard for a few seconds only, then an empty card. */
    fun clusterSongOnChange(context: Context): Boolean = prefs(context).getBoolean(KEY_CLUSTER_SONG_ON_CHANGE, false)

    fun setClusterSongOnChange(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean(KEY_CLUSTER_SONG_ON_CHANGE, enabled).apply()

    fun videoWhileParkedActive(context: Context): Boolean =
        videoWhileParked(context) && supportedInSelectedMode(context) { it.gearSupported }

    /**
     * Use addresses saved by the legacy head-unit probe. Existing installations with a saved probe
     * migrate to this mode; a fresh installation stays on the default DiLink 5.0 path.
     */
    fun legacyVehicleProbe(context: Context): Boolean {
        val settings = prefs(context)
        return if (settings.contains(KEY_LEGACY_VEHICLE_PROBE)) {
            settings.getBoolean(KEY_LEGACY_VEHICLE_PROBE, false)
        } else {
            BydVehicleFieldStore.load(context) != null
        }
    }

    fun setLegacyVehicleProbe(context: Context, enabled: Boolean) {
        check(prefs(context).edit().putBoolean(KEY_LEGACY_VEHICLE_PROBE, enabled).commit()) {
            "Could not persist BYD vehicle-data mode"
        }
    }

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

    private fun supportedInSelectedMode(
        context: Context,
        supported: (BydVehicleCapabilities) -> Boolean,
    ): Boolean = !legacyVehicleProbe(context) || BydVehicleFieldStore.load(context)?.let(supported) == true

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

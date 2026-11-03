package com.shilapi.xcertplay

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.NetworkInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock

/**
 * PSA line: the two event-driven auto-start triggers ported from the MiAuto reference
 * implementation. Both only ever open [DiPlayActivity] on the auto-connect path — the same
 * [PhoneBluetoothReceiver.EXTRA_AUTO_CONNECT] extra the phone-wake receiver uses — so the
 * connection decision stays in one place ([DiPlayActivity.consumeBluetoothAutoConnectIntent]).
 *
 * Neither trigger starts a connection itself, and neither runs while a session is up: the car
 * can deliver several ACL or network events during one wake-up, so the throttle below — not the
 * receivers — is what keeps one event burst from opening a second connection.
 */
internal object AutoStartLauncher {
    private const val PREFS = "diplay_auto_start"
    private const val LAST_LAUNCH = "last_launch_elapsed"

    /** Same window [PhoneBluetoothReceiver] uses; the two prefs files are independent on purpose. */
    private const val THROTTLE_MS = 15_000L

    /** One launch per event burst; commit (not apply) so another receiver instance cannot race it. */
    fun throttled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = SystemClock.elapsedRealtime()
        val previous = prefs.getLong(LAST_LAUNCH, 0)
        if (prefs.contains(LAST_LAUNCH) && now >= previous && now - previous < THROTTLE_MS) return false
        prefs.edit().putLong(LAST_LAUNCH, now).commit()
        return true
    }

    fun launch(context: Context, source: String) {
        PhoneWakeDiagnostics.record(context, "matched-$source")
        val launch = Intent(context, DiPlayActivity::class.java).apply {
            putExtra(PhoneBluetoothReceiver.EXTRA_AUTO_CONNECT, true)
            putExtra(EXTRA_LAUNCH_SOURCE, source)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        runCatching { context.startActivity(launch) }
            .onSuccess { PhoneWakeDiagnostics.record(context, "startActivity-returned") }
            .onFailure { error ->
                PhoneWakeDiagnostics.record(context, "failed", error)
                android.util.Log.w("DiPlayAutoStart", "$source auto-start launch failed: ${error.javaClass.simpleName}")
            }
    }

    const val EXTRA_LAUNCH_SOURCE = "com.shihab.diplay.AUTO_START_SOURCE"
}

/**
 * Opens DiPlay when one of the user-selected paired iPhones connects over Bluetooth.
 *
 * Deliberately separate from [PhoneBluetoothReceiver]: that one trusts the single phone chosen in
 * connection setup and only fires for it, while this one trusts a set of paired devices — the
 * MiAuto behaviour, useful when more than one iPhone drives this car.
 */
class AutoStartBluetoothReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BluetoothDevice.ACTION_ACL_CONNECTED ||
            !DiPlayPreferences.autoStartBluetooth(context) ||
            CarPlayBackgroundSession.hasSession()) return
        val macs = DiPlayPreferences.autoStartBluetoothMacs(context)
        if (macs.isEmpty()) return
        val device = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
        } ?: return
        if (Build.VERSION.SDK_INT >= 31 &&
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
        val address = runCatching { device.address }.getOrNull() ?: return
        if (macs.none { it.equals(address, ignoreCase = true) }) return
        if (!AutoStartLauncher.throttled(context)) return
        AutoStartLauncher.launch(context, "bluetooth")
    }
}

/**
 * Opens DiPlay when the head unit joins the Wi-Fi network named in the auto-start setting.
 *
 * The SSID comes from [WifiManager.getConnectionInfo], not from the intent: the broadcast carries
 * the network state but a stable name is easier to read off the current connection, and the
 * receiver only acts on the connected transition anyway.
 */
class AutoStartWifiReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != WifiManager.NETWORK_STATE_CHANGED_ACTION ||
            !DiPlayPreferences.autoStartWifi(context) ||
            CarPlayBackgroundSession.hasSession()) return
        @Suppress("DEPRECATION")
        val info = intent.getParcelableExtra<NetworkInfo>(WifiManager.EXTRA_NETWORK_INFO)
        if (info == null || !info.isConnected) return
        val target = DiPlayPreferences.autoStartWifiSsid(context).trim().removeSurrounding("\"")
        if (target.isEmpty()) return
        val current = runCatching {
            val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
            wifi?.connectionInfo?.ssid?.removeSurrounding("\"")
        }.getOrNull() ?: return
        if (current.isEmpty() || current == UNKNOWN_SSID) return
        if (!current.equals(target, ignoreCase = true)) return
        if (!AutoStartLauncher.throttled(context)) return
        AutoStartLauncher.launch(context, "wifi")
    }

    private companion object {
        /** What [WifiManager.getConnectionInfo] reports before an association has a name. */
        const val UNKNOWN_SSID = "<unknown ssid>"
    }
}

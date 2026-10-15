package com.shilapi.xcertplay.network

import android.content.Context
import android.net.wifi.WifiConfiguration
import java.security.SecureRandom

/** FreeAuto-parity hotspot name rule (this build's own lineage prefix: DiPlay-XXXX). The
 *  hotspot is switched on under this install's own name with the system password, security and
 *  band untouched: a configuration whose SSID differs from the stored one pushes the framework
 *  through its full AP start path, and a hotspot already carrying our name is switched on with
 *  a null configuration — exactly like FreeAuto. */
internal object DiPlayHotspotName {
    private const val PREFIX = "DiPlay-"
    private val OURS = Regex("DiPlay-([A-Za-z0-9]{4})")
    private val random = SecureRandom()

    fun nameFor(suffix: String): String = PREFIX + suffix

    /** FreeAuto namedConfiguration parity: null means "switch on as the system has it" (either
     *  the configuration is hidden or the name already belongs to this install); otherwise the
     *  saved configuration comes back with only the SSID replaced by our name. */
    fun namedConfiguration(context: Context, saved: WifiConfiguration?, log: (String) -> Unit): WifiConfiguration? {
        if (saved == null) {
            log("hotspot name rule: the system hides the hotspot configuration; switching on with its current name")
            return null
        }
        val target = nameFor(suffix(context, saved.SSID, log))
        if (!needsRename(saved.SSID, target)) {
            log("hotspot name rule: the saved name is already this install's (password left as system)")
            return null
        }
        log("hotspot name rule: the saved name is ${unquoted(saved.SSID)}; switching on with the DiPlay- name only (system password/security/band unchanged)")
        return withNameOnly(saved, target)
    }

    fun suffix(context: Context, currentSsid: String?, log: (String) -> Unit): String {
        val prefs = context.applicationContext
            .getSharedPreferences("diplay_hotspot_name", Context.MODE_PRIVATE)
        prefs.getString("suffix", null)?.let { return it }
        // Adopt the suffix of an existing DiPlay-… name so renames keep the same hotspot.
        OURS.find(unquoted(currentSsid))?.groupValues?.get(1)?.let { adopted ->
            log("hotspot name rule: adopting the existing DiPlay- suffix")
            prefs.edit().putString("suffix", adopted).commit()
            return adopted
        }
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        val generated = buildString { repeat(4) { append(alphabet[random.nextInt(alphabet.length)]) } }
        prefs.edit().putString("suffix", generated).commit()
        return generated
    }

    fun needsRename(currentSsid: String?, target: String): Boolean = unquoted(currentSsid) != target

    fun unquoted(ssid: String?): String {
        val value = ssid?.takeIf { it.length >= 2 && it.startsWith("\"") && it.endsWith("\"") }
            ?: return ssid.orEmpty()
        return value.substring(1, value.length - 1)
    }

    /** The system password, security and band are left exactly as saved; only the name moves. */
    fun withNameOnly(config: WifiConfiguration, target: String): WifiConfiguration {
        config.SSID = ssidValue(config.SSID, target)
        return config
    }

    private fun ssidValue(current: String?, target: String): String =
        if (current != null && current.length >= 2 &&
            current.startsWith("\"") && current.endsWith("\"")
        ) "\"$target\"" else target
}

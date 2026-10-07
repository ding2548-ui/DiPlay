package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.mfi.LocalMfiAuthenticationClient
import com.shilapi.xcertplay.orchestration.MfiTarget
import java.io.File
import java.security.MessageDigest

/** Installs the private beta's experimental identity. It has no remote fallback. */
internal object DiPlayBootstrap {
    @Volatile private var ready = false

    @Synchronized fun ensure(context: Context, mfiTarget: MfiTarget) {
        if (mfiTarget != MfiTarget.LOCAL) return
        if (ready) return
        val target = File(context.noBackupFilesDir, LocalMfiAuthenticationClient.DIRECTORY)
        if (!target.exists()) {
            val staging = File(context.noBackupFilesDir, "offline-mfi-staging")
            staging.deleteRecursively()
            check(staging.mkdirs()) { "Could not prepare local authentication" }
            staging.setReadable(false, false); staging.setReadable(true, true)
            staging.setExecutable(false, false); staging.setExecutable(true, true)
            try {
                for (name in listOf("identity.pk8", "certificate.p7b")) {
                    val file = File(staging, name)
                    context.assets.open("offline-mfi/$name").use { input ->
                        file.outputStream().use { output -> input.copyTo(output) }
                    }
                    file.setReadable(false, false); file.setReadable(true, true)
                    file.setWritable(false, false); file.setWritable(true, true)
                }
                LocalMfiAuthenticationClient.load(staging)
                check(staging.renameTo(target)) { "Could not install local authentication" }
            } finally {
                staging.deleteRecursively()
            }
        }
        LocalMfiAuthenticationClient.load(target)
        AirPlayPersistence.saveDebugLogsEnabled(context, false)
        ready = true
    }

    fun deviceId(identity: AirPlayIdentity): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(identity.publicKey).take(6).toByteArray()
        bytes[0] = ((bytes[0].toInt() and 0xfc) or 0x02).toByte()
        return bytes.joinToString(":") { "%02X".format(it.toInt() and 0xff) }
    }
}

internal object DiPlayPreferences {
    private fun prefs(context: Context) = context.getSharedPreferences("diplay", Context.MODE_PRIVATE)
    fun phoneAddress(context: Context): String? = prefs(context).getString("phone_address", null)
    fun phoneName(context: Context): String = prefs(context).getString("phone_name", null) ?: "Your iPhone"
    fun savePhone(context: Context, address: String, name: String) {
        prefs(context).edit().putString("phone_address", address).putString("phone_name", name).apply()
    }
    fun autoConnect(context: Context) = prefs(context).getBoolean("auto_connect", false)
    fun saveAutoConnect(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean("auto_connect", value).apply()
    }

    /**
     * The wired VPN/NCM transport is the default. lwIP has to be switched on by hand: on this head
     * unit it loses audio (no sound while it renders video), it needs an app restart before it can
     * reconnect after a manual disconnect, and it is less stable than the VPN path overall.
     */
    fun wiredLwip(context: Context): Boolean {
        val store = prefs(context)
        // Earlier builds defaulted this to on, so an upgrade would keep lwIP selected without the
        // user ever choosing it. Move each install onto the VPN default exactly once; the switch in
        // settings still overrides it afterwards.
        if (!store.getBoolean(KEY_WIRED_LWIP_DEFAULTED, false)) {
            store.edit()
                .putBoolean("wired_lwip", false)
                .putBoolean(KEY_WIRED_LWIP_DEFAULTED, true)
                .apply()
        }
        return store.getBoolean("wired_lwip", false)
    }
    fun saveWiredLwip(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean("wired_lwip", value).apply()
    }

    /**
     * Whether CarPlay's audio takeover also drops the phone's Bluetooth audio profiles. On by
     * default: the car's A2DP sink otherwise keeps playing the phone's audio on top of CarPlay.
     * Only A2DP is dropped — see [BluetoothAudioHandoff] for why the headset profiles are left
     * alone on this line.
     */
    fun a2dpHandoff(context: Context) = prefs(context).getBoolean("a2dp_handoff", true)
    fun saveA2dpHandoff(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean("a2dp_handoff", value).apply()
    }

    /** Set once the lwIP-default migration has run; see [wiredLwip]. */
    private const val KEY_WIRED_LWIP_DEFAULTED = "wired_lwip_defaulted_v2"
}

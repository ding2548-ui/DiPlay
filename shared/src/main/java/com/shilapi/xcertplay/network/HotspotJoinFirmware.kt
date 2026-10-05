package com.shilapi.xcertplay.network

import android.os.Build
import java.io.File
import java.security.MessageDigest

/** Wi-Fi Mainline modules and vendor images can change independently of Build.FINGERPRINT. */
internal object HotspotJoinFirmware {
    fun identity(properties: Map<String, String>, modules: Map<String, String>): String? {
        if (modules.isEmpty() || modules.values.any { it.isBlank() }) return null
        val parts = properties.toSortedMap().entries + modules.toSortedMap().entries
        // Length-prefix every component; do not depend on delimiters occurring in OEM build names.
        val bytes = parts.joinToString("") { "${it.key.length}:${it.key}${it.value.length}:${it.value}" }.toByteArray()
        return hex(HotspotJoinJournal.digest(bytes))
    }

    fun current(): String? {
        val get = Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
        val properties = linkedMapOf(
            "api" to Build.VERSION.SDK_INT.toString(), "framework" to Build.FINGERPRINT,
            "incremental" to Build.VERSION.INCREMENTAL,
        )
        for (key in listOf("ro.vendor.build.fingerprint", "ro.vendor.build.version.incremental", "ro.bootimage.build.fingerprint"))
            properties[key] = get.invoke(null, key) as String
        val modules = linkedMapOf<String, String>()
        for (path in listOf("/apex/com.android.wifi/javalib/framework-wifi.jar", "/system/framework/framework-wifi.jar")) {
            val file = File(path)
            if (!file.exists()) continue
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(16384)
                while (true) { val size = input.read(buffer); if (size < 0) break; digest.update(buffer, 0, size) }
            }
            modules[path] = hex(digest.digest())
        }
        return identity(properties, modules)
    }
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
}

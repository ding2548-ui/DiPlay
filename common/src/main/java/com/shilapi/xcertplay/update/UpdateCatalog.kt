package com.shilapi.xcertplay.update

import org.json.JSONArray

/** The published release that a newer build can be downloaded from. */
internal data class UpdateRelease(
    val tagName: String,
    val apkName: String,
    val apkUrl: String,
    val checksumsUrl: String,
)

internal object UpdateCatalog {
    internal const val CHECKSUMS_FILE = "SHA256SUMS.txt"

    /** Returns the first published release that carries an APK and its checksums, or null. */
    internal fun parse(json: String): UpdateRelease? {
        val releases = JSONArray(json)
        for (index in 0 until releases.length()) {
            val release = releases.optJSONObject(index) ?: continue
            if (release.optBoolean("draft")) continue
            val assets = release.optJSONArray("assets") ?: return null
            var apkName = ""
            var apkUrl = ""
            var checksumsUrl = ""
            for (assetIndex in 0 until assets.length()) {
                val asset = assets.optJSONObject(assetIndex) ?: continue
                val name = asset.optString("name")
                val url = asset.optString("browser_download_url")
                if (name.endsWith(".apk")) {
                    apkName = name
                    apkUrl = url
                }
                if (name.equals(CHECKSUMS_FILE, ignoreCase = true)) checksumsUrl = url
            }
            if (apkUrl.isEmpty() || checksumsUrl.isEmpty()) return null
            return UpdateRelease(release.optString("tag_name"), apkName, apkUrl, checksumsUrl)
        }
        return null
    }
}

package com.shilapi.xcertplay.update

import java.net.URL
import java.net.URLConnection

internal object UpdateReleaseLookup {
    fun latest(
        installedVersion: String,
        userAgent: String,
        openConnection: (URL) -> URLConnection = { it.openConnection() },
    ): UpdateRelease? {
        val json = UpdateClient.fetchText(
            UpdateClient.RELEASES_URL,
            "application/vnd.github+json",
            userAgent,
            openConnection,
        )
        val release = UpdateCatalog.parse(json) ?: return null
        return release.takeIf { UpdateVersion.isNewer(it.tagName, installedVersion) }
    }
}

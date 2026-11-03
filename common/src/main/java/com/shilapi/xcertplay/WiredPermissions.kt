package com.shilapi.xcertplay

import android.Manifest
import android.os.Build

/**
 * Runtime permissions a wired CarPlay start needs per Android release. On Android 17 the iPhone's
 * AirPlay connection over the USB network is local traffic too: on emteria's Android 17 for
 * Raspberry Pi 5 the iPhone's SYN from its link-local address to `[fe80::2]:7000` arrived and was
 * never answered until [Manifest.permission.ACCESS_LOCAL_NETWORK] was granted, so link-local IPv6
 * cannot be relied on to stay exempt (see [WirelessPermissions]).
 */
internal object WiredPermissions {
    fun required(sdkInt: Int): List<String> =
        if (sdkInt >= Build.VERSION_CODES.CINNAMON_BUN) listOf(Manifest.permission.ACCESS_LOCAL_NETWORK) else emptyList()
}

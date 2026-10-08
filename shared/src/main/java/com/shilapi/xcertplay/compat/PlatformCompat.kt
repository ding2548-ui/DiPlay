package com.shilapi.xcertplay.compat

import android.location.LocationManager
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build

/** Releases the channel on API 27+. Earlier releases have no close call and free it when collected. */
fun WifiP2pManager.Channel.closeCompat() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) close()
}

/** [LocationManager.isLocationEnabled] needs API 28; before that a location provider must be on. */
fun LocationManager.isLocationEnabledCompat(): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        isLocationEnabled
    } else {
        isProviderEnabled(LocationManager.GPS_PROVIDER) || isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

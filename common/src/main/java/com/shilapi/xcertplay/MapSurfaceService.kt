package com.shilapi.xcertplay

import android.os.Bundle
import android.view.Surface
import com.shilapi.xcertplay.glance.CarPlayGlance

/** Surface bridge for non-Android clusters; retains the existing explicit sharing consent. */
class MapSurfaceService : MapSurfaceEndpoint() {
    private var stopObserving: (() -> Unit)? = null
    private val changed: (Boolean) -> Unit = { publishState() }
    override fun onCreate() {
        super.onCreate()
        stopObserving = AirPlayPersistence.observeLauncherMapSharing(this) { publishState() }
        MapMirrors.addStreamListener(changed)
    }
    override fun sharingAllowed() = AirPlayPersistence.loadLauncherMapSharing(this)
    override fun mirror(key: String, surface: Surface?) = MapMirrors.set(key, surface)
    override fun streamActive() = MapMirrors.streamActive
    override fun streamAspect() = MapMirrors.streamAspect
    override fun navigating(): Boolean = CarPlayGlance.snapshot().let { it.connected && it.maneuverType != null }
    /**
     * Raw iAP2 guidance for clients that draw their own turn card (Xiaoling's native cluster card).
     * Keys: maneuver (Apple RouteGuidanceManeuverType), side, dist (m), road, rdist (m), rtime (s), arrival (epoch s);
     * supported (boolean) whenever the navigation app has declared SourceSupportsRouteGuidance.
     */
    override fun guidance(out: Bundle) {
        val s = CarPlayGlance.snapshot()
        if (s.connected) s.guidanceSupported?.let { out.putBoolean("supported", it) }
        val type = s.maneuverType
        if (!s.connected || type == null) return
        out.putInt("maneuver", type)
        out.putInt("side", s.drivingSide)
        out.putInt("dist", s.distanceMeters)
        out.putString("road", s.road)
        s.remainingMeters?.let { out.putLong("rdist", it) }
        s.remainingSeconds?.let { out.putLong("rtime", it) }
        s.arrivalEpochSeconds?.let { out.putLong("arrival", it) }
    }
    override fun onDestroy() {
        stopObserving?.invoke()
        MapMirrors.removeStreamListener(changed)
        super.onDestroy()
    }
}

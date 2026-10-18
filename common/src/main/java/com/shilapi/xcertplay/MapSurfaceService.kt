package com.shilapi.xcertplay

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
    override fun onDestroy() {
        stopObserving?.invoke()
        MapMirrors.removeStreamListener(changed)
        super.onDestroy()
    }
}

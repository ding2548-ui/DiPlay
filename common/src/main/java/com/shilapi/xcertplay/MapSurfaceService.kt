package com.shilapi.xcertplay

import android.os.Bundle
import android.view.Surface
import com.shilapi.xcertplay.glance.CarPlayGlance

/** Surface bridge for non-Android clusters; retains the existing explicit sharing consent. */
class MapSurfaceService : MapSurfaceEndpoint() {
    private var stopObserving: (() -> Unit)? = null
    private var stopMusic: (() -> Unit)? = null
    private var lastMusic: ClusterMusicState.Snapshot? = null
    private val changed: (Boolean) -> Unit = { publishState() }
    override fun onCreate() {
        super.onCreate()
        stopObserving = AirPlayPersistence.observeLauncherMapSharing(this) { publishState() }
        stopMusic = ClusterMusicState.observe { lastMusic = it; publishState() }
        MapMirrors.addStreamListener(changed)
    }
    override fun sharingAllowed() = AirPlayPersistence.loadLauncherMapSharing(this)
    override fun mirror(key: String, surface: Surface?) = MapMirrors.set(key, surface)
    override fun streamActive(): Boolean =
        // MUSIC has no video stream, so report "active" only for a source that actually carries one.
        MapMirrors.streamActive && ClusterScreenProfile.source(this).streamType != null
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
    /**
     * Music metadata for clients that draw their own song card. `music` is true only when the content
     * source is MUSIC (no video); the track fields are filled whenever a track is known so the client
     * can show the card. There is no album-art bitmap over Messenger — `hasArtwork` only tells the
     * client one is available.
     */
    override fun music(out: Bundle) {
        val musicSource = ClusterScreenProfile.source(this) == ClusterScreenProfile.Source.MUSIC
        out.putBoolean("music", musicSource)
        val info = lastMusic?.info
        val hasTrack = info != null && (!info.title.isNullOrBlank() || !info.artist.isNullOrBlank())
        if (musicSource || hasTrack) {
            info?.title?.let { out.putString("title", it) }
            info?.artist?.let { out.putString("artist", it) }
            info?.album?.let { out.putString("album", it) }
            out.putBoolean("playing", CarPlayGlance.snapshot().playing)
            out.putBoolean("hasArtwork", lastMusic?.artwork != null)
        }
    }
    override fun onDestroy() {
        stopObserving?.invoke()
        stopMusic?.invoke()
        MapMirrors.removeStreamListener(changed)
        super.onDestroy()
    }
}

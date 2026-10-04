package com.shilapi.xcertplay

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import com.shilapi.xcertplay.orchestration.CarPlayController
import org.json.JSONObject
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Keeps CarPlay the single audio source while the iPhone plays.
 *
 * The Leapmotor head unit sends its steering-wheel keys as a **parallel** broadcast, so the stock
 * player receives the same press we do and starts (or resumes) playing on top of CarPlay. Nothing
 * in the broadcast can be aborted, so ownership is claimed instead:
 *
 *  1. Android audio focus is held while CarPlay plays, which is what well-behaved players honour
 *     (they pause on AUDIOFOCUS_LOSS), plus an active [MediaSession] so the car's media UI points
 *     at CarPlay.
 *  2. The car's own protocol is used in the other direction: a `pause` command is sent once on the
 *     same actions the stock player listens to, for players that ignore audio focus. Our own
 *     broadcast carries `"src":"diplay"` and [LeapmotorMediaKeys] drops it, so the command cannot
 *     come back as a CarPlay key press.
 */
internal object CarPlayAudioOwnership {

    /** Marks our own broadcasts so our receiver never treats a self-sent command as a key press. */
    const val SELF_MARKER_KEY = "src"
    const val SELF_MARKER_VALUE = "diplay"

    private var appContext: Context? = null
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null
    private var session: MediaSession? = null
    private var focusHeld = false
    private var mediaActive = false
    private var controller: CarPlayController? = null

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val artworkQueue = NowPlayingArtworkQueue(
        worker = Executors.newSingleThreadExecutor { task ->
            Thread(task, "diplay-now-playing-artwork").apply { isDaemon = true }
        },
        main = Executor { mainHandler.post(it) },
        decode = ::decodeArtwork,
        publish = ::onArtworkDecoded,
        discard = Bitmap::recycle,
    )
    private var artworkOwner: Any? = null
    private var nowPlaying = CarPlayNowPlaying()
    private var elapsedUpdatedAt = 0L
    private var artwork: Bitmap? = null
    private val artworkCache = LinkedHashMap<Int, Bitmap?>()

    /** Media-key de-duplication: some head units deliver one press on BOTH the broadcast and
     *  the media-session path; a single key press must only ever reach CarPlay once. */
    private var lastMediaKeyDispatchAt = 0L

    @Volatile
    var onDiagnostic: ((String) -> Unit)? = null

    @Synchronized
    fun attach(context: Context, next: CarPlayController) {
        appContext = context.applicationContext
        audioManager = appContext?.getSystemService(AudioManager::class.java)
        if (controller !== next) {
            // A new CarPlay session starts with clean now-playing state (upstream 0.2.11 semantics).
            artworkOwner = artworkQueue.newSession()
            nowPlaying = CarPlayNowPlaying()
            artwork = null
            artworkCache.clear()
        }
        controller = next
        next.playbackListener = { playing -> onIphonePlaying(playing) }
        next.nowPlayingListener = { update -> onNowPlayingChanged(next, update) }
        next.artworkListener = { id, bytes -> onArtworkChanged(next, id, bytes) }
        report("audio ownership attached (focus + stock-player pause)")
    }

    @Synchronized
    fun detach(expected: CarPlayController?) {
        if (controller != null && expected != null && controller !== expected) return
        expected?.playbackListener = null
        expected?.nowPlayingListener = null
        expected?.artworkListener = null
        controller = null
        releaseLocked("detached")
        appContext = null
        audioManager = null
    }

    /** A music ("media") audio stream started or stopped; may run on any thread. */
    fun onMediaAudioChanged(active: Boolean) = update(active)

    /** The iPhone reported play or pause for media; may run on any thread. */
    fun onIphonePlaying(playing: Boolean) = update(playing)

    /** Publishes the iPhone's retained metadata through Android's system media session. */
    private fun onNowPlayingChanged(expected: CarPlayController, update: CarPlayNowPlaying) {
        mainHandler.post {
            synchronized(this) {
                if (controller !== expected) return@synchronized
                val previousArtwork = artwork
                if (nowPlaying.artworkTransferId != update.artworkTransferId) {
                    artwork = update.artworkTransferId?.let { id ->
                        if (artworkCache.containsKey(id)) artworkCache[id] else null
                    }
                }
                if (nowPlaying.elapsedMillis != update.elapsedMillis) elapsedUpdatedAt = SystemClock.elapsedRealtime()
                val metadataChanged = metadataChanged(nowPlaying, update) || artwork !== previousArtwork
                nowPlaying = update
                // The iPhone repeats NowPlayingUpdate about twice a second for the position alone.
                // Republishing the metadata each time sent a copy of the artwork through system_server
                // to every media listener, exhausting memory on weak head units. The position goes
                // in the playback state. (Upstream 0.2.11.)
                if (metadataChanged) session?.setMetadata(androidMetadata(update, artwork))
                publishPlaybackStateLocked()
            }
        }
    }

    @Synchronized
    private fun onArtworkChanged(expected: CarPlayController, id: Int, bytes: ByteArray) {
        if (controller !== expected) return
        artworkOwner?.let { artworkQueue.submit(it, id, bytes) }
    }

    @Synchronized
    private fun onArtworkDecoded(expected: Any, id: Int, decoded: Bitmap?) {
        if (artworkOwner !== expected) {
            decoded?.recycle()
            return
        }
        artworkCache.remove(id)
        artworkCache[id] = decoded
        while (artworkCache.size > MAX_CACHED_ARTWORK) artworkCache.remove(artworkCache.keys.first())
        if (nowPlaying.artworkTransferId == id) {
            artwork = decoded
            session?.setMetadata(androidMetadata(nowPlaying, artwork))
        }
    }

    /** Whether [next] changes what the media session's metadata shows; position and play state do not. */
    internal fun metadataChanged(previous: CarPlayNowPlaying, next: CarPlayNowPlaying): Boolean =
        previous.copy(elapsedMillis = null, playing = false) != next.copy(elapsedMillis = null, playing = false)

    internal fun androidMetadata(info: CarPlayNowPlaying, artwork: Bitmap? = null): MediaMetadata =
        MediaMetadata.Builder().apply {
            info.title?.let {
                putString(MediaMetadata.METADATA_KEY_TITLE, it)
                putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, it)
            }
            info.artist?.let {
                putString(MediaMetadata.METADATA_KEY_ARTIST, it)
                putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, it)
            }
            info.album?.let { putString(MediaMetadata.METADATA_KEY_ALBUM, it) }
            info.durationMillis?.let { putLong(MediaMetadata.METADATA_KEY_DURATION, it) }
            info.sourceApp?.let { putString(MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION, it) }
            artwork?.let {
                putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it)
                putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON, it)
            }
        }.build()

    private fun publishPlaybackStateLocked() {
        val playing = if (nowPlaying.elapsedMillis != null || nowPlaying.title != null) {
            nowPlaying.playing
        } else {
            mediaActive
        }
        session?.setPlaybackState(
            PlaybackState.Builder()
                .setActions(MEDIA_SESSION_ACTIONS)
                .setState(
                    if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    nowPlaying.elapsedMillis ?: PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                    if (playing) 1f else 0f,
                    // The iPhone sends elapsed time only on play, pause or seek, so Android must
                    // extrapolate from when it arrived, not from this republish.
                    elapsedUpdatedAt,
                )
                .build(),
        )
    }

    @Synchronized
    private fun update(active: Boolean) {
        if (active == mediaActive) return
        mediaActive = active
        if (active) acquireLocked() else releaseLocked("CarPlay media stopped")
    }

    private fun acquireLocked() {
        val manager = audioManager ?: return
        // CarPlay now carries the audio: drop the phone's Bluetooth audio profiles so the
        // sound cannot keep leaking through the car's A2DP sink (beta feature, toggleable).
        appContext?.let { BluetoothAudioHandoff.onCarPlayMediaActive(it) }
        if (!focusHeld) {
            focusHeld = requestFocus(manager)
            report("audio focus request granted=$focusHeld")
        }
        if (session == null) {
            session = runCatching {
                MediaSession(appContext!!, "DiPlay CarPlay").apply {
                    // The car's media keys land here when this is the media button session:
                    // forward them into CarPlay exactly like the broadcast path does.
                    setCallback(object : MediaSession.Callback() {
                        override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                            val event = runCatching {
                                @Suppress("DEPRECATION")
                                mediaButtonIntent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                            }.getOrNull()
                            val keyCode = event?.keyCode
                            val action = when (keyCode) {
                                KeyEvent.KEYCODE_MEDIA_NEXT -> "next"
                                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> "previous"
                                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> "playpause"
                                KeyEvent.KEYCODE_MEDIA_PLAY -> "play"
                                KeyEvent.KEYCODE_MEDIA_PAUSE -> "pause"
                                else -> null
                            }
                            if (action == null) {
                                return super.onMediaButtonEvent(mediaButtonIntent)
                            }
                            dispatchToCarPlay(action, "media-key=$keyCode")
                            return true
                        }

                        override fun onSkipToNext() = dispatchToCarPlay("next", "session-callback")
                        override fun onSkipToPrevious() = dispatchToCarPlay("previous", "session-callback")
                        override fun onPlay() = dispatchToCarPlay("play", "session-callback")
                        override fun onPause() = dispatchToCarPlay("pause", "session-callback")
                    })
                    setMetadata(androidMetadata(nowPlaying, artwork))
                    isActive = true
                }
            }.getOrElse {
                report("media session unavailable: ${it.javaClass.simpleName}: ${it.message}")
                null
            }
        }
        if (session != null) publishPlaybackStateLocked()
        // The stock-player pause broadcast was REMOVED in v2.0-80 (manual §58): the car echoes it
        // back on the same actions without our marker, and the car ALSO emits its own `pause` when
        // another app takes audio focus. Both came back as "wheel presses" and paused CarPlay
        // itself, so pressing a key ended with nothing playing at all. Focus alone is kept.
    }

    /**
     * Forwards a media-session key into CarPlay through the shared wheel dispatch (video
     * gating, built-in mapping, learned bindings). De-duplicates head units that deliver one
     * press on both the broadcast and the media-session path (250 ms window, same as the
     * broadcast receivers).
     */
    private fun dispatchToCarPlay(action: String, source: String) {
        val now = System.currentTimeMillis()
        synchronized(this) {
            if (now - lastMediaKeyDispatchAt < 250) {
                report("media session key ignored (duplicate window) action=$action source=$source")
                return
            }
            lastMediaKeyDispatchAt = now
        }
        val sent = LeapmotorMediaKeys.dispatch(action, "media-session")
        report("media session key action=$action source=$source sent=$sent")
    }

    private fun releaseLocked(reason: String) {
        artworkOwner = null
        artworkQueue.clear()
        session?.let {
            runCatching {
                it.isActive = false
                it.release()
            }
        }
        session = null
        nowPlaying = CarPlayNowPlaying()
        artwork = null
        artworkCache.clear()
        val manager = audioManager
        if (focusHeld && manager != null) {
            val request = focusRequest
            if (request != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                runCatching { manager.abandonAudioFocusRequest(request) }
            } else {
                @Suppress("DEPRECATION")
                runCatching { manager.abandonAudioFocus(null) }
            }
        }
        if (focusHeld) report("audio focus released ($reason)")
        focusHeld = false
    }

    private fun requestFocus(manager: AudioManager): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener { change ->
                    // Forensics: the run-80-era question is what the car does to audio when the
                    // driver engages reverse. Record every change into the diagnostic report.
                    report("audio focus change=$change")
                    // A permanent loss moves the keys elsewhere; transient losses come back.
                    if (change == AudioManager.AUDIOFOCUS_LOSS) synchronized(this) { focusHeld = false }
                }
                .build()
            focusRequest = request
            manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            manager.requestAudioFocus(
                { change ->
                    report("audio focus change=$change")
                    if (change == AudioManager.AUDIOFOCUS_LOSS) synchronized(this) { focusHeld = false }
                },
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN,
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }.getOrElse {
        report("audio focus request failed: ${it.message}")
        false
    }

    private fun report(message: String) {
        Log.i(TAG, message)
        runCatching { onDiagnostic?.invoke(message) }
    }

    private fun decodeArtwork(bytes: ByteArray): Bitmap? {
        if (bytes.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth !in 1..MAX_ARTWORK_SOURCE_DIMENSION ||
            bounds.outHeight !in 1..MAX_ARTWORK_SOURCE_DIMENSION
        ) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_ARTWORK_DIMENSION * 2) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return null
        val largest = maxOf(decoded.width, decoded.height)
        if (largest <= MAX_ARTWORK_DIMENSION) return decoded
        val scale = MAX_ARTWORK_DIMENSION.toFloat() / largest
        return Bitmap.createScaledBitmap(
            decoded,
            (decoded.width * scale).toInt().coerceAtLeast(1),
            (decoded.height * scale).toInt().coerceAtLeast(1),
            true,
        ).also { scaled -> if (scaled !== decoded) decoded.recycle() }
    }

    private const val TAG = "DiPlay-AudioOwner"
    private const val MEDIA_SESSION_ACTIONS = PlaybackState.ACTION_PLAY_PAUSE or
        PlaybackState.ACTION_PLAY or
        PlaybackState.ACTION_PAUSE or
        PlaybackState.ACTION_SKIP_TO_NEXT or
        PlaybackState.ACTION_SKIP_TO_PREVIOUS
    private const val MAX_ARTWORK_DIMENSION = 384
    private const val MAX_ARTWORK_SOURCE_DIMENSION = 8_192
    private const val MAX_CACHED_ARTWORK = 4
}

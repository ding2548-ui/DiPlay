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
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import androidx.core.graphics.drawable.toBitmap
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import com.shilapi.xcertplay.orchestration.CarPlayController
import java.util.concurrent.Executors
import java.util.concurrent.Executor

/**
 * Steering-wheel and other hardware media buttons for CarPlay.
 *
 * Android delivers media keys to a media session; the Leapmotor head unit picks the session of the
 * audio-focus owner, so DiPlay holds audio focus and an active session while CarPlay is the car's
 * audio source. Without that the car's own player keeps the keys and every press moves both it and
 * CarPlay. Keys go to the iPhone as CarPlay media HID presses ([CarPlayMediaButton]).
 *
 * Ownership is claimed when the iPhone reports play ([onIphonePlaying]) as well as when a music
 * stream starts ([onMediaAudioChanged]): the phone may leave its audio on the car's Bluetooth link,
 * in which case no music stream ever reaches us.
 */
internal object CarPlayMediaKeys {
    private const val TAG = "DiPlay-MediaKeys"

    /** Both the logcat line and the app's diagnostic report receive these. */
    @Volatile
    var onDiagnostic: ((String) -> Unit)? = null

    private const val ACTIONS = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
        PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS

    private val mainHandler = Handler(Looper.getMainLooper())
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
    private var controller: CarPlayController? = null
    private var session: MediaSession? = null
    private var focusRequest: AudioFocusRequest? = null
    private var focusHeld = false
    private var appContext: Context? = null
    private var mediaAudioActive = false

    /**
     * Whether the car's audio ownership is currently claimed. Dedupes the two triggers
     * ([onMediaAudioChanged] and [onIphonePlaying]) so a repeated event does not re-run the
     * acquisition, and — more importantly — makes the release/re-acquire cycle around a track skip
     * possible at all.
     */
    private var ownershipActive = false
    private var nowPlaying = CarPlayNowPlaying()
    private var elapsedUpdatedAt = 0L
    private var artwork: Bitmap? = null
    private val artworkCache = LinkedHashMap<Int, Bitmap?>()
    private var placeholder: Bitmap? = null

    /**
     * Android 7's focus API has no [AudioFocusRequest], so the pre-26 overload needs a separate
     * listener object. It shares the focus-loss handling with the modern path.
     */
    private val legacyFocusListener =
        AudioManager.OnAudioFocusChangeListener { change -> onFocusChange(change) }

    @Synchronized
    fun attach(context: Context, next: CarPlayController) {
        if (controller !== next) {
            releaseLocked()
            artworkOwner = artworkQueue.newSession()
        }
        appContext = context.applicationContext
        controller = next
        next.playbackListener = { playing -> onIphonePlaying(next, playing) }
        next.nowPlayingListener = { update -> onNowPlayingChanged(next, update) }
        next.artworkListener = { id, bytes -> onArtworkChanged(next, id, bytes) }
    }

    /** Ends key handling for [expected]; a newer controller's state is left alone. */
    @Synchronized
    fun detach(expected: CarPlayController?) {
        if (expected == null || controller !== expected) return
        expected.playbackListener = null
        expected.nowPlayingListener = null
        expected.artworkListener = null
        controller = null
        releaseLocked()
    }

    /** Called when CarPlay music starts or stops; may run on any thread. */
    fun onMediaAudioChanged(active: Boolean) {
        mainHandler.post { synchronized(this) { updateLocked(active) } }
    }

    /**
     * The iPhone started or stopped playing; may run on any thread.
     *
     * This — not [onMediaAudioChanged] — is the signal that carries on this head unit. The phone is
     * free to keep its audio on the car's Bluetooth link, in which case no music stream ever reaches
     * us and the media-audio callback never fires, while the now-playing state still arrives over
     * CarPlay.
     *
     * A pause is not merely a state change: it gives the ownership up, and the resume after it takes
     * it again. That release/re-claim cycle is what re-takes the focus after a track skip — the stock
     * player grabs the focus for its own new track and has to be pushed back down.
     */
    private fun onIphonePlaying(expected: CarPlayController, playing: Boolean) {
        mainHandler.post {
            synchronized(this) {
                if (controller === expected) updateLocked(playing)
            }
        }
    }

    /** Publishes the iPhone's retained metadata through Android's system media session. */
    private fun onNowPlayingChanged(expected: CarPlayController, update: CarPlayNowPlaying) {
        mainHandler.post {
            synchronized(this) {
                if (controller !== expected) return@synchronized
                val previousArtwork = artwork
                if (nowPlaying.artworkTransferId != update.artworkTransferId) {
                    artwork = nextArtwork(update.artworkTransferId, artworkCache, artwork)
                }
                if (nowPlaying.elapsedMillis != update.elapsedMillis) elapsedUpdatedAt = SystemClock.elapsedRealtime()
                val metadataChanged = metadataChanged(nowPlaying, update) || artwork !== previousArtwork
                nowPlaying = update
                // The iPhone repeats NowPlayingUpdate about twice a second for the position alone.
                // Republishing the metadata each time sent a copy of the artwork through system_server
                // to every media listener, and on a DiLink 5.0 Tang that exhausted memory within
                // minutes. The position goes in the playback state.
                if (metadataChanged) session?.setMetadata(androidMetadata(update, shownArtworkLocked()))
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
            session?.setMetadata(androidMetadata(nowPlaying, shownArtworkLocked()))
        }
    }

    /**
     * Takes the car's audio ownership: Bluetooth audio handoff, then audio focus, then an active
     * MediaSession.
     *
     * Modelled on the Leapmotor fork's `CarPlayAudioOwnership`, and the *sequence* is what makes the
     * stock player yield. Focus is requested on every acquisition rather than once per session:
     * skipping a track makes the stock player grab focus for its own new track, which costs us
     * AUDIOFOCUS_LOSS, and the iPhone's pause/resume around that skip runs [releaseLocked] and then
     * this — so the re-request is what pushes the stock player back down. Holding the focus for the
     * whole session instead (what this used to do) left it playing.
     */
    private fun acquireLocked() {
        val context = appContext ?: return
        if (controller == null) return
        // Before the focus request, as in the Leapmotor fork: drop the phone's Bluetooth audio
        // profiles so the sound cannot keep leaking through the car's A2DP sink.
        BluetoothAudioHandoff.onCarPlayMediaActive(context)
        if (!focusHeld) {
            focusHeld = requestFocus(context)
            report("audio focus request granted=$focusHeld")
        }
        if (session == null) {
            // A MediaSession that cannot be built must not take the process down: this unit runs
            // API 25, where several media-session entry points are missing.
            session = runCatching {
                MediaSession(context, "DiPlay CarPlay").apply {
                    setCallback(callback, mainHandler)
                    setMetadata(androidMetadata(nowPlaying, shownArtworkLocked()))
                    isActive = true
                }
            }.getOrElse {
                report("media session unavailable: ${it.javaClass.simpleName}: ${it.message}")
                null
            }
        }
        report("media keys active focusGranted=$focusHeld session=${session != null}")
        publishPlaybackStateLocked()
    }

    /**
     * Requests audio focus on both API levels.
     *
     * `AudioFocusRequest` and the matching `requestAudioFocus` overload are API 26; on this API 25
     * unit building one throws `NoSuchMethodError`, which is an Error and not a RuntimeException, so
     * it would take the process down. Android 7 has only the legacy overload. Either way the same
     * listener handles the loss.
     */
    private fun requestFocus(context: Context): Boolean {
        val audio = context.getSystemService(AudioManager::class.java) ?: return false
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build(),
                    )
                    .setOnAudioFocusChangeListener({ change -> onFocusChange(change) }, mainHandler)
                    .build()
                focusRequest = request
                audio.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            } else {
                @Suppress("DEPRECATION")
                audio.requestAudioFocus(
                    legacyFocusListener,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN,
                ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            }
        }.getOrElse {
            report("audio focus request failed: ${it.javaClass.simpleName}: ${it.message}")
            false
        }
    }

    private fun onFocusChange(change: Int) {
        report("audio focus change=$change")
        // Only a permanent loss moves the car's media keys elsewhere; transient losses come back.
        if (change == AudioManager.AUDIOFOCUS_LOSS) synchronized(this) { focusHeld = false }
    }

    /**
     * The single state machine behind both triggers. A repeated event is dropped, and a real change
     * claims or gives up the ownership — giving it up matters as much as claiming it, because the
     * stock player only yields while we hold the focus.
     */
    private fun updateLocked(active: Boolean) {
        mediaAudioActive = active
        if (active == ownershipActive) return
        ownershipActive = active
        if (active) acquireLocked() else releaseLocked()
    }

    private fun releaseLocked() {
        artworkOwner = null
        artworkQueue.clear()
        session?.let {
            it.isActive = false
            it.release()
        }
        session = null
        mediaAudioActive = false
        nowPlaying = CarPlayNowPlaying()
        artwork = null
        artworkCache.clear()
        // On Android 7 focus was taken through the legacy overload, so it is abandoned by listener;
        // abandonAudioFocusRequest only exists from API 26.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { request ->
                appContext?.getSystemService(AudioManager::class.java)?.abandonAudioFocusRequest(request)
            }
        } else {
            @Suppress("DEPRECATION")
            appContext?.getSystemService(AudioManager::class.java)?.abandonAudioFocus(legacyFocusListener)
        }
        focusRequest = null
        focusHeld = false
        ownershipActive = false
        report("media keys released")
    }

    private fun publishPlaybackStateLocked() {
        val playing = if (nowPlaying.elapsedMillis != null || nowPlaying.title != null) {
            nowPlaying.playing
        } else {
            mediaAudioActive
        }
        session?.setPlaybackState(
            PlaybackState.Builder()
                .setActions(ACTIONS)
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

    /**
     * A steering-wheel press that arrived through the media session instead of the car's broadcast.
     * It goes through [LeapmotorMediaKeys.dispatch] so that one physical press, which this unit can
     * deliver on both paths at once, reaches CarPlay exactly once: both paths share the dispatch
     * de-duplication window, and the learned bindings and the video gate apply here too.
     */
    private fun sendWheel(index: Int, source: String) {
        val action = when (index) {
            CarPlayMediaButton.NEXT -> "next"
            CarPlayMediaButton.PREVIOUS -> "previous"
            CarPlayMediaButton.PLAY_PAUSE -> "playpause"
            else -> null
        }
        if (action == null) {
            send(index, source)
            return
        }
        LeapmotorMediaKeys.dispatch(action, "media-session")
    }

    private fun send(index: Int, source: String) {
        // While the car's video player is on screen the wheel drives it: a CarPlay play/pause would
        // make the iPhone end the video session.
        if (CarPlayVideo.onMediaKey(index)) {
            report("media key $source -> car video player $index")
            return
        }
        val sent = synchronized(this) { controller }?.sendMediaButton(index) ?: false
        report("media key $source -> CarPlay $index sent=$sent")
    }

    private fun report(message: String) {
        Log.i(TAG, message)
        runCatching { onDiagnostic?.invoke(message) }
    }

    private val callback = CarPlayMediaCallback(::sendWheel)

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

    // Without art the car draws DiPlay's bright launcher icon instead.
    private fun shownArtworkLocked(): Bitmap? =
        artwork ?: placeholder ?: appContext?.let(::placeholderArt)?.also { placeholder = it }

    internal fun placeholderArt(context: Context): Bitmap? = context
        .getDrawable(R.drawable.art_now_playing_placeholder)
        ?.toBitmap(MAX_ARTWORK_DIMENSION, MAX_ARTWORK_DIMENSION)

    /**
     * The art to show once the iPhone names transfer [id]. A pending transfer keeps [current], so the
     * placeholder does not flash between tracks.
     */
    internal fun nextArtwork(id: Int?, cache: Map<Int, Bitmap?>, current: Bitmap?): Bitmap? = when {
        id == null -> null
        cache.containsKey(id) -> cache[id]
        else -> current
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

    private const val MAX_ARTWORK_DIMENSION = 384
    private const val MAX_ARTWORK_SOURCE_DIMENSION = 8_192
    private const val MAX_CACHED_ARTWORK = 4
}

/**
 * Media-session input → CarPlay presses. Hardware keys arrive as button events and keep the toggle;
 * media controllers (not hardware keys) call [onPlay] and [onPause] with an explicit intent.
 */
internal class CarPlayMediaCallback(private val send: (index: Int, source: String) -> Unit) : MediaSession.Callback() {
    override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
        @Suppress("DEPRECATION")
        val event = mediaButtonIntent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return false
        val index = CarPlayMediaButton.forKeyCode(event.keyCode) ?: return super.onMediaButtonEvent(mediaButtonIntent)
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            send(index, KeyEvent.keyCodeToString(event.keyCode))
        }
        return true
    }

    override fun onPlay() = send(CarPlayMediaButton.PLAY, "play")
    override fun onPause() = send(CarPlayMediaButton.PAUSE, "pause")
    override fun onSkipToNext() = send(CarPlayMediaButton.NEXT, "next")
    override fun onSkipToPrevious() = send(CarPlayMediaButton.PREVIOUS, "previous")
}

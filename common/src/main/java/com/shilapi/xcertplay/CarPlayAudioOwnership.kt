package com.shilapi.xcertplay

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.util.Log
import com.shilapi.xcertplay.orchestration.CarPlayController
import org.json.JSONObject

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
    private const val TAG = "DiPlay-AudioOwner"

    /** Marks our own broadcasts so our receiver never treats a self-sent command as a key press. */
    const val SELF_MARKER_KEY = "src"
    const val SELF_MARKER_VALUE = "diplay"

    private var appContext: Context? = null
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null
    private var session: MediaSession? = null
    private var focusHeld = false
    private var mediaActive = false

    @Volatile
    var onDiagnostic: ((String) -> Unit)? = null

    @Synchronized
    fun attach(context: Context, controller: CarPlayController) {
        appContext = context.applicationContext
        audioManager = appContext?.getSystemService(AudioManager::class.java)
        controller.playbackListener = { playing -> onIphonePlaying(playing) }
        report("audio ownership attached (focus + stock-player pause)")
    }

    @Synchronized
    fun detach(expected: CarPlayController?) {
        expected?.playbackListener = null
        releaseLocked("detached")
        appContext = null
        audioManager = null
    }

    /** A music ("media") audio stream started or stopped; may run on any thread. */
    fun onMediaAudioChanged(active: Boolean) = update(active)

    /** The iPhone reported play or pause for media; may run on any thread. */
    fun onIphonePlaying(playing: Boolean) = update(playing)

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
                    setPlaybackState(
                        PlaybackState.Builder()
                            .setActions(PlaybackState.ACTION_PLAY_PAUSE)
                            .setState(PlaybackState.STATE_PLAYING, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                            .build(),
                    )
                    isActive = true
                }
            }.getOrElse {
                report("media session unavailable: ${it.javaClass.simpleName}")
                null
            }
        }
        // The stock-player pause broadcast was REMOVED in v2.0-80 (manual §58): the car echoes it
        // back on the same actions without our marker, and the car ALSO emits its own `pause` when
        // another app takes audio focus. Both came back as "wheel presses" and paused CarPlay
        // itself, so pressing a key ended with nothing playing at all. Focus alone is kept.
    }

    private fun releaseLocked(reason: String) {
        session?.let {
            runCatching {
                it.isActive = false
                it.release()
            }
        }
        session = null
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
}

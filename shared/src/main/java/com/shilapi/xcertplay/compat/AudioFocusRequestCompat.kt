package com.shilapi.xcertplay.compat

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * One audio focus request that works on every supported Android version.
 * API 26+ uses [AudioFocusRequest]. Android 7.x uses the stream-based calls, whose callbacks are
 * moved to [handler] so that both paths deliver them on the same thread.
 */
class AudioFocusRequestCompat(
    val gain: Int,
    val attributes: AudioAttributes,
    private val listener: AudioManager.OnAudioFocusChangeListener,
    private val handler: Handler,
) {
    private val platformRequest: Any? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        AudioFocusRequest.Builder(gain)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener(listener, handler)
            .build()
    } else {
        null
    }

    private val legacyListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (Looper.myLooper() == handler.looper) listener.onAudioFocusChange(change)
        else handler.post { listener.onAudioFocusChange(change) }
    }

    fun request(manager: AudioManager): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.requestAudioFocus(platformRequest as AudioFocusRequest)
        } else {
            @Suppress("DEPRECATION")
            manager.requestAudioFocus(legacyListener, legacyStreamType(attributes.usage), gain)
        }

    fun abandon(manager: AudioManager): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.abandonAudioFocusRequest(platformRequest as AudioFocusRequest)
        } else {
            @Suppress("DEPRECATION")
            manager.abandonAudioFocus(legacyListener)
        }

    internal companion object {
        /** The stream Android 7.x derives from these usages for its own focus bookkeeping. */
        fun legacyStreamType(usage: Int): Int = when (usage) {
            AudioAttributes.USAGE_VOICE_COMMUNICATION -> AudioManager.STREAM_VOICE_CALL
            AudioAttributes.USAGE_ALARM -> AudioManager.STREAM_ALARM
            AudioAttributes.USAGE_NOTIFICATION_RINGTONE -> AudioManager.STREAM_RING
            AudioAttributes.USAGE_NOTIFICATION -> AudioManager.STREAM_NOTIFICATION
            else -> AudioManager.STREAM_MUSIC
        }
    }
}

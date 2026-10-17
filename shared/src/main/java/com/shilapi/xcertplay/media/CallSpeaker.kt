package com.shilapi.xcertplay.media

import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

/**
 * Puts a call in communication mode on the built-in loudspeaker. A phone or tablet used as the head unit plays
 * that mode on its earpiece and records it with the handset microphone, so a CarPlay call was heard only close
 * to the screen (#701). Android 12+ chooses the communication device; older versions use the speakerphone flag.
 */
internal class CallSpeaker private constructor(private val undo: () -> Unit) {
    fun restore() {
        runCatching(undo)
    }

    companion object {
        /** Null when this device has no loudspeaker for calls or refused it; the call keeps its route. */
        fun on(manager: AudioManager, sdk: Int = Build.VERSION.SDK_INT): CallSpeaker? = runCatching {
            if (sdk >= Build.VERSION_CODES.S) {
                val speaker = manager.availableCommunicationDevices
                    .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER } ?: return null
                if (!manager.setCommunicationDevice(speaker)) return null
                CallSpeaker { manager.clearCommunicationDevice() }
            } else {
                @Suppress("DEPRECATION")
                val before = manager.isSpeakerphoneOn
                @Suppress("DEPRECATION")
                manager.isSpeakerphoneOn = true
                @Suppress("DEPRECATION")
                CallSpeaker { manager.isSpeakerphoneOn = before }
            }
        }.getOrNull()
    }
}

package com.shilapi.xcertplay

import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build

/**
 * HEVC is worth asking for only when a hardware decoder plays it. Without one, Android picks its own
 * software decoder, which a low-end head unit cannot keep up with while music plays (#727).
 */
internal object HevcSupport {
    data class Decoder(val name: String, val hardware: Boolean)

    /** The driver's software-HEVC choice keeps HEVC whenever any decoder exists. */
    fun usable(requested: Boolean, softwareChosen: Boolean, decoders: List<Decoder>): Boolean =
        requested && (decoders.any { it.hardware } || softwareChosen && decoders.isNotEmpty())

    fun decoders(): List<Decoder> = runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { info ->
            !info.isEncoder && info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true) }
        }.map { info ->
            Decoder(info.name, if (Build.VERSION.SDK_INT >= 29) info.isHardwareAccelerated
                else !info.name.startsWith("OMX.google.") && !info.name.startsWith("c2.android."))
        }
    }.getOrDefault(emptyList())
}

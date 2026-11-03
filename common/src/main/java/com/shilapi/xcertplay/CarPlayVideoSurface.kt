package com.shilapi.xcertplay

import com.shilapi.xcertplay.media.CarPlayVideoLayout
import kotlin.math.roundToInt

internal enum class CarPlayVideoSurfaceMode { TEXTURE, SURFACE }

/**
 * Decide from the attached window, not the declared manifest flag or vehicle model. Smooth video also
 * needs a SurfaceView: only the compositor honours the frame timestamps it releases with. Direct video
 * output (a setting) picks one too: frames go straight to the compositor instead of through the app's
 * own drawing, which can save about a refresh. So does a decoder whose pictures the app's drawing
 * cannot take as a texture ([textureUnsafeDecoder]).
 */
internal fun carPlayVideoSurfaceMode(
    hardwareAccelerated: Boolean,
    smoothVideo: Boolean = false,
    directVideoOutput: Boolean = false,
    textureUnsafeDecoder: Boolean = false,
): CarPlayVideoSurfaceMode =
    if (hardwareAccelerated && !smoothVideo && !directVideoOutput && !textureUnsafeDecoder) CarPlayVideoSurfaceMode.TEXTURE
    else CarPlayVideoSurfaceMode.SURFACE

/** An installed video decoder, in MediaCodecList order. */
internal data class VideoDecoderInfo(val name: String, val types: List<String>)

/**
 * The decoder MediaCodec.createDecoderByType picks for one of [mimes] (the first that lists it) when its
 * pictures cannot be shown through a TextureView, otherwise null. Emteria's Android 17 for Raspberry Pi 5
 * decodes with c2.ffmpeg.h264.decoder, which reports itself as hardware accelerated: the app's renderer
 * aborts with "Invalid GrBackendTexture" right after the first frame drawn as a texture, while a
 * SurfaceView shows the same pictures, composited by the system. Other FFmpeg-backed Codec2 decoders
 * share its output, so the family is matched by name.
 */
internal fun textureUnsafeDecoder(decoders: List<VideoDecoderInfo>, mimes: List<String>): String? =
    mimes.asSequence()
        .mapNotNull { mime -> decoders.firstOrNull { decoder -> decoder.types.any { it.equals(mime, ignoreCase = true) } } }
        .firstOrNull { it.name.startsWith(FFMPEG_CODEC2_PREFIX, ignoreCase = true) }
        ?.name

private const val FFMPEG_CODEC2_PREFIX = "c2.ffmpeg."

/** Integer compositor bounds retain overscan when a CarPlay view area crops the canvas. */
internal data class CarPlaySurfaceBounds(val left: Int, val top: Int, val width: Int, val height: Int) {
    companion object {
        fun from(content: CarPlayVideoLayout): CarPlaySurfaceBounds {
            val left = content.left.roundToInt()
            val top = content.top.roundToInt()
            return CarPlaySurfaceBounds(left, top,
                ((content.left + content.width).roundToInt() - left).coerceAtLeast(1),
                ((content.top + content.height).roundToInt() - top).coerceAtLeast(1))
        }
    }
}

/**
 * Smooth video's starting delay over the link's base delay; it then adjusts to the frames the decoder
 * releases. On my Tang the Qualcomm decoder released a frame only after about two more had been queued
 * (p90 69-77 ms at about 56 fps, runs A1/A2 in docs/SMOOTH_WIRELESS.md), so it starts at three frame
 * intervals plus 40 ms: 90 ms at 60 fps.
 */
internal fun smoothVideoDelayMillis(fps: Int): Int = 40 + 3_000 / fps.coerceIn(30, 60)

/**
 * Whether the last picture shown over a SurfaceView that came back can go: a frame was rendered since it came
 * back ([rendered] moved on from [baseline], also from 0 on a new decoder), or [maxMillis] passed.
 */
internal fun lastPictureDone(rendered: Long, baseline: Long, elapsedMillis: Long, maxMillis: Long): Boolean =
    (rendered > 0 && rendered != baseline) || elapsedMillis > maxMillis

/**
 * A retained background session can be adopted only when its sink's pacing matches the video view this
 * host built: the sink fixes pacing at creation and the host picks its view once.
 */
internal fun backgroundSessionMatchesView(sinkPaces: Boolean, viewSmoothVideo: Boolean): Boolean =
    sinkPaces == viewSmoothVideo

/** Texture wrappers are ours to release; SurfaceHolder surfaces belong to the framework. */
internal class CarPlayVideoSurfaceOwner<T : Any>(
    private val detach: (T) -> Unit,
    private val release: (T) -> Unit,
) {
    var current: T? = null
        private set
    private var owned = false

    fun replace(surface: T, releaseOnDetach: Boolean) {
        if (current === surface) return
        clear()
        current = surface
        owned = releaseOnDetach
    }

    /** Ignore a late callback from a surface replaced by a newer output. */
    fun clear(surface: T) {
        if (current === surface) clear()
    }

    fun clear() {
        val previous = current ?: return
        val releasePrevious = owned
        current = null
        owned = false
        try {
            detach(previous)
        } finally {
            if (releasePrevious) release(previous)
        }
    }
}

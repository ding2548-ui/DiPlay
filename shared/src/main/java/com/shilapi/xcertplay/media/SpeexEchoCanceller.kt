package com.shilapi.xcertplay.media

import android.util.Log
import java.io.Closeable

/** One active mono call canceller; the uplink owns its processing and teardown. */
internal interface CallEchoCanceller : Closeable {
    val frameSamples: Int
    fun process(frame: ByteArray, reference: ShortArray): Boolean
}

/**
 * SpeexDSP's acoustic echo canceller with residual echo suppression, for 16-bit mono call audio.
 * Apple expects the accessory to cancel echo; head units that play third-party calls as music do
 * not run their own canceller on them, so the far end otherwise hears itself.
 */
internal class SpeexEchoCanceller private constructor(
    private var handle: Long,
    override val frameSamples: Int,
) : CallEchoCanceller {
    private val mic = ShortArray(frameSamples)
    private val out = ShortArray(frameSamples)

    /** Cancels [reference]'s echo from one PCM16LE [frame] in place; false leaves the frame untouched. */
    @Synchronized
    override fun process(frame: ByteArray, reference: ShortArray): Boolean {
        if (handle == 0L || frame.size < frameSamples * 2 || reference.size < frameSamples) return false
        for (i in 0 until frameSamples) {
            mic[i] = ((frame[2 * i + 1].toInt() shl 8) or (frame[2 * i].toInt() and 0xff)).toShort()
        }
        if (!nativeProcess(handle, mic, reference, out)) return false
        for (i in 0 until frameSamples) {
            frame[2 * i] = out[i].toInt().toByte()
            frame[2 * i + 1] = (out[i].toInt() shr 8).toByte()
        }
        return true
    }

    @Synchronized
    override fun close() {
        val current = handle
        handle = 0L
        if (current != 0L) nativeDestroy(current)
    }

    companion object {
        private const val TAG = "xcertplay-usb"
        private const val ECHO_SUPPRESS_DB = -40
        private const val ECHO_SUPPRESS_ACTIVE_DB = -15

        val available: Boolean by lazy {
            runCatching { System.loadLibrary("speex_echo"); true }
                .onFailure { Log.w(TAG, "echo canceller library unavailable", it) }
                .getOrDefault(false)
        }

        /** Null when the native library is missing or the canceller cannot start. */
        fun create(frameSamples: Int, sampleRate: Int, tailMillis: Int): SpeexEchoCanceller? {
            if (!available || frameSamples <= 0) return null
            val filter = maxOf(frameSamples, sampleRate * tailMillis / 1000)
            val handle = runCatching {
                nativeCreate(frameSamples, filter, sampleRate, ECHO_SUPPRESS_DB, ECHO_SUPPRESS_ACTIVE_DB)
            }.getOrDefault(0L)
            return if (handle == 0L) null else SpeexEchoCanceller(handle, frameSamples)
        }

        @JvmStatic external fun nativeCreate(frame: Int, filter: Int, rate: Int, suppress: Int, suppressActive: Int): Long
        @JvmStatic external fun nativeProcess(handle: Long, mic: ShortArray, reference: ShortArray, out: ShortArray): Boolean
        @JvmStatic external fun nativeDestroy(handle: Long)
    }
}

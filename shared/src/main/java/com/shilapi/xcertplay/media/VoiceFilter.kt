package com.shilapi.xcertplay.media

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Removes the low bass from call audio before it is played. Head units such as BYD's DiLink play a
 * third-party call as music, with the music equaliser's bass boost, so voices sound boomy. Speech
 * needs little below 200 Hz (phone calls stop at 300 Hz), so a fourth-order Butterworth high-pass
 * at [cutoffHz] keeps voices clear and also feeds less energy back into the microphone.
 */
internal class VoiceFilter(sampleRate: Int, private val channels: Int, cutoffHz: Double = DEFAULT_CUTOFF_HZ) {
    private class Biquad(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double)

    private val stages: List<Biquad> = BUTTERWORTH_Q.map { highPass(sampleRate, cutoffHz, it) }
    // Direct form I history per stage and channel: x1, x2, y1, y2.
    private val state = Array(stages.size * channels) { DoubleArray(4) }

    /** Filters 16-bit little-endian interleaved PCM in place. */
    fun process(pcm: ByteArray, offset: Int, length: Int) {
        val frameBytes = 2 * channels
        for (frame in 0 until length / frameBytes) {
            for (channel in 0 until channels) {
                val at = offset + frame * frameBytes + channel * 2
                var x = ((pcm[at + 1].toInt() shl 8) or (pcm[at].toInt() and 0xff)).toDouble()
                stages.forEachIndexed { index, stage ->
                    val h = state[index * channels + channel]
                    val y = stage.b0 * x + stage.b1 * h[0] + stage.b2 * h[1] - stage.a1 * h[2] - stage.a2 * h[3]
                    h[1] = h[0]; h[0] = x
                    h[3] = h[2]; h[2] = y
                    x = y
                }
                val sample = x.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                pcm[at] = sample.toByte()
                pcm[at + 1] = (sample shr 8).toByte()
            }
        }
    }

    private fun highPass(rate: Int, cutoff: Double, q: Double): Biquad {
        val w = 2 * PI * cutoff / rate
        val alpha = sin(w) / (2 * q)
        val cosW = cos(w)
        val a0 = 1 + alpha
        return Biquad(
            b0 = (1 + cosW) / 2 / a0,
            b1 = -(1 + cosW) / a0,
            b2 = (1 + cosW) / 2 / a0,
            a1 = -2 * cosW / a0,
            a2 = (1 - alpha) / a0,
        )
    }

    companion object {
        const val DEFAULT_CUTOFF_HZ = 200.0
        private val BUTTERWORTH_Q = listOf(0.5411961, 1.3065630)
    }
}

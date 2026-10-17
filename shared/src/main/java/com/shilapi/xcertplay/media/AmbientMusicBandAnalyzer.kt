package com.shilapi.xcertplay.media

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Band-pass energy estimate built from two one-pole low-passes, at the real PCM sample rate.
 *
 * [AmbientMusicBassAnalyzer] is the original 40–180 Hz specialisation and stays untouched; this
 * class exists because the Leapmotor flow-light engine drives one lamp channel per spectrum band
 * (`VehicleControlService$6`: `lampR=|f0|*2`, `lampG=|f1|*2`, `lampB=|f2|*2`), so the lamp path
 * needs three adjacent bands rather than the one low band the BYD path reads.
 *
 * `low - high` is the band: subtracting the *higher* cutoff leaves the energy strictly above
 * [lowHz] and below [highHz], which is what a single one-pole pair can approximate cheaply.
 */
internal class AmbientMusicBandAnalyzer(sampleRate: Int, private val channels: Int,
    lowHz: Double, highHz: Double) {
    private val rate = sampleRate.coerceAtLeast(1)
    private val alphaHigh = 1.0 - exp(-2.0 * PI * highHz / rate)
    private val alphaLow = 1.0 - exp(-2.0 * PI * lowHz / rate)
    private var lowState = 0.0
    private var highState = 0.0

    fun rms(data: ByteArray, offset: Int, length: Int): Double {
        val count = channels.coerceAtLeast(1)
        val frameBytes = count * 2
        if (offset < 0 || length < frameBytes || offset > data.size - length) return 0.0
        var square = 0.0
        var frames = 0
        var i = offset
        while (i + frameBytes <= offset + length) {
            var sample = 0.0
            for (channel in 0 until count) {
                val p = i + channel * 2
                sample += ((data[p].toInt() and 255) or (data[p + 1].toInt() shl 8)).toShort().toDouble() / 32768.0
            }
            sample /= count
            highState += alphaHigh * (sample - highState)
            lowState += alphaLow * (sample - lowState)
            val band = lowState - highState
            square += band * band
            frames++
            i += frameBytes
        }
        return if (frames == 0) 0.0 else sqrt(square / frames)
    }
}

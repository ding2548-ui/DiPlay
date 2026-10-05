package com.shilapi.xcertplay

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.sin

/** Plays one short tone through the same legacy stream route used by CarPlay audio. */
internal class AudioChannelPreview(private val onUnavailable: (Int) -> Unit) : Closeable {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "diplay-channel-preview").apply { isDaemon = true }
    }
    private val generation = AtomicInteger()
    private val activeTrack = AtomicReference<AudioTrack?>()
    private var pending: Future<*>? = null
    @Volatile private var closed = false

    fun play(channel: Int, navigation: Boolean) {
        if (closed) return
        require(channel in AirPlayPersistence.AUDIO_CHANNELS)
        val request = generation.incrementAndGet()
        pending?.cancel(true)
        activeTrack.get()?.let { runCatching { it.stop() } }
        pending = worker.submit {
            var track: AudioTrack? = null
            try {
                if (closed || generation.get() != request) return@submit
                val pcm = tone()
                val minimum = AudioTrack.getMinBufferSize(
                    SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                )
                check(minimum > 0) { "No PCM output buffer is available" }
                val bufferBytes = maxOf(minimum, SAMPLE_RATE / 10 * 2)
                val built = if (channel == 0) {
                    AudioTrack.Builder()
                        .setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(if (navigation) AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
                                    else AudioAttributes.USAGE_MEDIA)
                                .setContentType(if (navigation) AudioAttributes.CONTENT_TYPE_SPEECH
                                    else AudioAttributes.CONTENT_TYPE_MUSIC)
                                .build(),
                        )
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(SAMPLE_RATE)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .build(),
                        )
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .setBufferSizeInBytes(bufferBytes)
                        .build()
                } else {
                    // Match playback and let the head unit handle vendor-specific stream types.
                    @Suppress("DEPRECATION")
                    AudioTrack(channel, SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, bufferBytes, AudioTrack.MODE_STREAM)
                }
                track = built
                check(built.state == AudioTrack.STATE_INITIALIZED) { "Audio output did not initialize" }
                if (closed || generation.get() != request) return@submit
                activeTrack.set(built)
                built.setVolume(0.6f)
                built.play()
                var written = 0
                while (written < pcm.size && !closed && generation.get() == request) {
                    val count = built.write(
                        pcm, written, minOf(4096, pcm.size - written), AudioTrack.WRITE_BLOCKING,
                    )
                    check(count > 0) { "Could not write preview tone" }
                    written += count
                }
                if (!closed && generation.get() == request && written == pcm.size) {
                    Log.i(TAG, "Preview started channel=$channel navigation=$navigation")
                }
                Thread.sleep(120L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (error: Exception) {
                Log.w(TAG, "Channel preview unavailable channel=$channel", error)
                mainHandler.post {
                    if (!closed && generation.get() == request) onUnavailable(channel)
                }
            } finally {
                activeTrack.compareAndSet(track, null)
                track?.let { runCatching { it.stop() }; it.release() }
            }
        }
    }

    override fun close() {
        closed = true
        generation.incrementAndGet()
        pending?.cancel(true)
        activeTrack.get()?.let { runCatching { it.stop() } }
        worker.shutdownNow()
    }

    private fun tone(): ByteArray {
        val sampleCount = SAMPLE_RATE * TONE_MILLIS / 1000
        val fadeSamples = SAMPLE_RATE / 100
        return ByteArray(sampleCount * 2).also { pcm ->
            for (index in 0 until sampleCount) {
                val fade = minOf(1.0, index.toDouble() / fadeSamples,
                    (sampleCount - index - 1).toDouble() / fadeSamples).coerceAtLeast(0.0)
                val sample = (sin(2.0 * Math.PI * 880.0 * index / SAMPLE_RATE) *
                    fade * Short.MAX_VALUE * 0.45).toInt()
                pcm[index * 2] = sample.toByte()
                pcm[index * 2 + 1] = (sample ushr 8).toByte()
            }
        }
    }

    private companion object {
        private const val TAG = "DiPlayAudioPreview"
        private const val SAMPLE_RATE = 48_000
        private const val TONE_MILLIS = 600
    }
}

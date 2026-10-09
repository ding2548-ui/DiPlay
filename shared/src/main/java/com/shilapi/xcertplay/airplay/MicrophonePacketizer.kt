package com.shilapi.xcertplay.airplay

import java.net.InetAddress

/** Everything one captured microphone stream needs to send samples to the phone. */
data class MicrophoneConfig(
    val audioType: String,
    val sampleRate: Int,
    val channels: Int,
    val payloadType: Int,
    val frameMillis: Int,
    val host: InetAddress,
    val port: Int,
    val key: ByteArray,
    val codec: AudioCodecKind = AudioCodecKind.LPCM,
    val bitrate: Int? = null,
    /**
     * The observed CarPlay clock for an Opus stream. Capture and encoding remain at 48 kHz.
     * Captured Siri packets for audioFormat 0x20000000 advance by 480 per 20 ms; calls advance by
     * 960. This private-protocol exception does not establish a 16 kHz RTP clock: unobserved formats
     * keep the standard 48 kHz Opus RTP clock until equivalent packet evidence is available.
     */
    val opusClockRate: Int = OPUS_CAPTURE_RATE,
) {
    val samplesPerPacket: Int
        get() = if (codec == AudioCodecKind.OPUS) {
            OPUS_SAMPLES_PER_PACKET
        } else {
            maxOf(1, sampleRate * frameMillis / 1000)
        }

    /** How far each packet moves the RTP timestamp, on the negotiated clock. */
    val rtpSamplesPerPacket: Int
        get() = if (codec == AudioCodecKind.OPUS) opusClockRate * OPUS_FRAME_MILLIS / 1000 else samplesPerPacket

    val frameBytes: Int
        get() = samplesPerPacket * channels * 2

    companion object {
        private const val OPUS_SAMPLES_PER_PACKET = 960
        private const val OPUS_CAPTURE_RATE = 48_000
        private const val OPUS_FRAME_MILLIS = 20

        /** Use the observed Siri 24 kHz clock, retaining 48 kHz for calls and unobserved formats. */
        fun opusClockRate(formatBits: Long): Int = when {
            formatBits and 0x40000000L != 0L -> 48_000
            formatBits and 0x20000000L != 0L -> 24_000
            else -> OPUS_CAPTURE_RATE
        }
    }
}

/** Mutable RTP/ChaCha counters for one microphone uplink. */
class MicrophoneCounters(
    var sequence: Int = 0,
    var timestamp: Int = 0,
    var nonce: Long = 0,
)

/**
 * Seals one PCM microphone frame in the wired CarPlay RTP layout.
 *
 * The 12-byte RTP header is clear; its timestamp and SSRC are authenticated as AAD. The packet
 * tail repeats the eight-byte little-endian nonce counter after the 16-byte Poly1305 tag.
 */
object MicrophonePacketizer {
    const val RTP_HEADER_LEN = 12
    const val TAG_LEN = 16
    const val NONCE_LEN = 8

    fun sealPacket(
        key: ByteArray,
        payloadType: Int,
        counters: MicrophoneCounters,
        body: ByteArray,
        samples: Int,
    ): ByteArray {
        val header = ByteArray(RTP_HEADER_LEN)
        header[0] = 0x80.toByte()
        header[1] = (payloadType and 0x7f).toByte()
        putU16Be(header, 2, counters.sequence)
        putU32Be(header, 4, counters.timestamp)

        val nonce = ByteArray(12)
        putU64Le(nonce, 4, counters.nonce)
        val sealed = AirPlayCrypto.chachaSeal(
            key = key,
            nonce = nonce,
            plaintext = body,
            aad = header.copyOfRange(4, RTP_HEADER_LEN),
        )

        val packet = ByteArray(RTP_HEADER_LEN + sealed.size + NONCE_LEN)
        header.copyInto(packet, 0)
        sealed.copyInto(packet, RTP_HEADER_LEN)
        putU64Le(packet, RTP_HEADER_LEN + sealed.size, counters.nonce)

        counters.sequence = (counters.sequence + 1) and 0xffff
        counters.timestamp += samples
        counters.nonce++
        return packet
    }

    /** Captured Android PCM is little-endian; CarPlay's wired microphone payload is big-endian. */
    fun toWirePcm(pcmLittleEndian: ByteArray): ByteArray {
        val output = pcmLittleEndian.copyOf()
        var index = 0
        while (index + 1 < output.size) {
            val first = output[index]
            output[index] = output[index + 1]
            output[index + 1] = first
            index += 2
        }
        return output
    }

    private fun putU16Be(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value ushr 8).toByte()
        target[offset + 1] = value.toByte()
    }

    private fun putU32Be(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value ushr 24).toByte()
        target[offset + 1] = (value ushr 16).toByte()
        target[offset + 2] = (value ushr 8).toByte()
        target[offset + 3] = value.toByte()
    }

    private fun putU64Le(target: ByteArray, offset: Int, value: Long) {
        var remaining = value
        for (index in 0 until 8) {
            target[offset + index] = remaining.toByte()
            remaining = remaining ushr 8
        }
    }
}

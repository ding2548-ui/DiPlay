package com.shilapi.xcertplay.transport

internal data class UsbMuxFrame(
    val protocol: Int,
    val length: Int,
    val word8: Int,
    val sequence: Int,
    val payload: ByteArray,
)

/** Incremental framing; callers serialize access with their USBMUX state lock. */
internal class UsbMuxFrameBuffer(private val diagnostic: (String) -> Unit = {}) {
    private var bytes = ByteArray(0)
    private var optionalReplyPadding: UsbMuxFrame? = null
    private var paddingReports = 0
    private var lastUsbReadBytes = 0
    val bufferedBytes: Int get() = bytes.size

    fun append(transfer: ByteArray) {
        lastUsbReadBytes = transfer.size
        bytes += transfer
    }

    fun takeFrame(): UsbMuxFrame? {
        if (bytes.size < HEADER_BYTES) return null
        var length = readU32(bytes, 4)
        if (length !in HEADER_BYTES..MAX_FRAME_BYTES) {
            val previous = optionalReplyPadding
            // The captured iOS 27 VERSION and payload-free SYN-ACK replies each have four
            // extra bytes. Do not scan for an arbitrary header or discard a USB completion:
            // only this single four-byte boundary is eligible, and only before a TCP frame.
            // A normal or split next header at offset zero is always preserved.
            // Wait for the complete candidate MUX header and base TCP header. A timeout
            // leaves these bytes in place; a legitimate split frame must never be lost.
            if (previous != null && readU32(bytes, 0) != PROTOCOL_TCP &&
                bytes.size < PADDING_BYTES + HEADER_BYTES) return null
            val followingLength = readU32(bytes, PADDING_BYTES + 4)
            val candidateHeader = previous != null && readU32(bytes, 0) != PROTOCOL_TCP &&
                readU32(bytes, PADDING_BYTES) == PROTOCOL_TCP &&
                followingLength in (HEADER_BYTES + TCP_HEADER_BYTES)..MAX_FRAME_BYTES &&
                readU32(bytes, PADDING_BYTES + 8) == CAPTURED_REPLY_MAGIC
            if (candidateHeader && bytes.size < PADDING_BYTES + HEADER_BYTES + TCP_HEADER_BYTES) return null
            val followingTcpHeaderBytes = if (candidateHeader) {
                ((bytes[PADDING_BYTES + HEADER_BYTES + 12].toInt() ushr 4) and 0x0f) * 4
            } else 0
            if (candidateHeader && followingTcpHeaderBytes in TCP_HEADER_BYTES..(followingLength - HEADER_BYTES) &&
                readU16(bytes, PADDING_BYTES + HEADER_BYTES) != 0 &&
                readU16(bytes, PADDING_BYTES + HEADER_BYTES + 2) != 0) {
                bytes = bytes.copyOfRange(PADDING_BYTES, bytes.size)
                optionalReplyPadding = null
                if (paddingReports++ < MAX_PADDING_REPORTS) report(
                    "USBMUX optional reply padding skipped bytes=$PADDING_BYTES " +
                        "previousProtocol=${previous.protocol} previousLength=${previous.length} " +
                        "nextProtocol=$PROTOCOL_TCP nextLength=$followingLength " +
                        "lastUsbReadBytes=$lastUsbReadBytes bufferedBytes=${bytes.size}")
                if (bytes.size < HEADER_BYTES) return null
                length = readU32(bytes, 4)
            } else {
                report("USBMUX framing rejected declaredLength=$length bufferedBytes=${bytes.size} " +
                    "lastUsbReadBytes=$lastUsbReadBytes optionalReplyPadding=${previous != null}")
                throw IphoneUsbException.Protocol("Invalid USBMUX frame length $length")
            }
        }
        if (bytes.size < length) return null
        val frame = UsbMuxFrame(
            protocol = readU32(bytes, 0), length = length, word8 = readU32(bytes, 8),
            sequence = ((bytes[12].toInt() and 0xff) shl 8) or (bytes[13].toInt() and 0xff),
            payload = bytes.copyOfRange(HEADER_BYTES, length),
        )
        bytes = bytes.copyOfRange(length, bytes.size)
        optionalReplyPadding = frame.takeIf(::canHaveOptionalReplyPadding)
        return frame
    }

    private fun canHaveOptionalReplyPadding(frame: UsbMuxFrame): Boolean =
        (frame.protocol == PROTOCOL_VERSION && frame.length == VERSION_BYTES && frame.word8 == 2) ||
            (frame.protocol == PROTOCOL_TCP && frame.word8 == CAPTURED_REPLY_MAGIC &&
                frame.length == HEADER_BYTES + TCP_HEADER_BYTES &&
                ((frame.payload[12].toInt() ushr 4) and 0x0f) == 5 &&
                (frame.payload[13].toInt() and 0xff) == SYN_ACK &&
                readU16(frame.payload, 0) != 0 && readU16(frame.payload, 2) != 0)

    private fun report(line: String) { runCatching { diagnostic(line) } }

    private fun readU16(source: ByteArray, offset: Int): Int =
        ((source[offset].toInt() and 0xff) shl 8) or (source[offset + 1].toInt() and 0xff)

    private fun readU32(source: ByteArray, offset: Int): Int =
        ((source[offset].toInt() and 0xff) shl 24) or
            ((source[offset + 1].toInt() and 0xff) shl 16) or
            ((source[offset + 2].toInt() and 0xff) shl 8) or
            (source[offset + 3].toInt() and 0xff)

    private companion object {
        const val HEADER_BYTES = 16
        const val TCP_HEADER_BYTES = 20
        const val MAX_FRAME_BYTES = 65_536
        const val VERSION_BYTES = 20
        const val PADDING_BYTES = 4
        const val MAX_PADDING_REPORTS = 4
        const val PROTOCOL_VERSION = 0
        const val PROTOCOL_TCP = 6
        const val SYN_ACK = 0x12
        val CAPTURED_REPLY_MAGIC = 0xfaceface.toInt()
    }
}

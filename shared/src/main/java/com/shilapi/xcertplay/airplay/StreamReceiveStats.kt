package com.shilapi.xcertplay.airplay

/** Receive-thread timing only: no payloads, endpoint addresses, or route data. */
internal class StreamReceiveStats(
    private val label: String,
    private val report: (String) -> Unit,
    private val nowNs: () -> Long = System::nanoTime,
) {
    private var windowStart = nowNs()
    private var readStart = windowStart
    private var processingStart = windowStart
    private var packets = 0
    private var bytes = 0L
    private var maxReadNs = 0L
    private var readsOver250Ms = 0
    private var seqGapAfterReadOver250Ms = 0
    private var seqMissingAfterReadOver250Ms = 0
    private var lastReceivedNs = 0L
    private var maxInterArrivalNs = 0L
    private var maxProcessNs = 0L
    private var lastTimestamp: Int? = null
    private var nextSequence: Int? = null
    private var forwardGapPackets = 0
    private var sequenceGapEvents = 0
    private var maxSequenceGap = 0
    private var lastSequenceGap = "none"
    private var lastSequenceGapAtMs = -1L
    private var lateOrDuplicate = 0

    fun reading() { readStart = nowNs() }

    fun received(size: Int, sequence: Int? = null, timestamp: Int? = null) {
        processingStart = nowNs()
        val readNs = processingStart - readStart
        maxReadNs = maxOf(maxReadNs, readNs)
        if (readNs >= LONG_READ_NS) readsOver250Ms++
        if (lastReceivedNs != 0L) {
            maxInterArrivalNs = maxOf(maxInterArrivalNs, processingStart - lastReceivedNs)
        }
        lastReceivedNs = processingStart
        packets++
        bytes += size
        if (sequence != null) {
            val expected = nextSequence
            val delta = if (expected == null) 0 else (sequence - expected) and 0xffff
            if (delta < 0x8000) {
                forwardGapPackets += delta
                if (delta > 0) {
                    sequenceGapEvents++
                    if (readNs >= LONG_READ_NS) {
                        seqGapAfterReadOver250Ms++
                        seqMissingAfterReadOver250Ms += delta
                    }
                    maxSequenceGap = maxOf(maxSequenceGap, delta)
                    lastSequenceGap = "expected=$expected received=$sequence missing=$delta " +
                        "previousRtpTs=${lastTimestamp?.toUnsignedLong() ?: "unknown"} " +
                        "receivedRtpTs=${timestamp?.toUnsignedLong() ?: "unknown"}"
                    lastSequenceGapAtMs = (processingStart - windowStart) / 1_000_000L
                }
                nextSequence = (sequence + 1) and 0xffff
            } else lateOrDuplicate++
        }
        if (timestamp != null) lastTimestamp = timestamp
    }

    fun processed() {
        maxProcessNs = maxOf(maxProcessNs, nowNs() - processingStart)
        flush()
    }

    fun flush(ended: Boolean = false) {
        val now = nowNs()
        if (!ended && now - windowStart < 5_000_000_000L) return
        runCatching { report("Receive: $label packets=$packets bytes=$bytes readMaxMs=${maxReadNs / 1_000_000} " +
            "processMaxUs=${maxProcessNs / 1000} seqForwardGaps=$forwardGapPackets " +
            "lateOrDuplicate=$lateOrDuplicate interArrivalMaxMs=${maxInterArrivalNs / 1_000_000} " +
            "seqGapEvents=$sequenceGapEvents seqGapMax=$maxSequenceGap " +
            "seqGapLast=[$lastSequenceGap] seqGapAtMs=$lastSequenceGapAtMs ended=$ended " +
            "windowMs=${(now - windowStart).coerceAtLeast(0) / 1_000_000} " +
            "readsOver250Ms=$readsOver250Ms seqGapAfterReadOver250Ms=$seqGapAfterReadOver250Ms " +
            "seqMissingAfterReadOver250Ms=$seqMissingAfterReadOver250Ms") }
        windowStart = now
        packets = 0
        bytes = 0
        maxReadNs = 0
        readsOver250Ms = 0
        seqGapAfterReadOver250Ms = 0
        seqMissingAfterReadOver250Ms = 0
        maxInterArrivalNs = 0
        maxProcessNs = 0
        forwardGapPackets = 0
        sequenceGapEvents = 0
        maxSequenceGap = 0
        lastSequenceGap = "none"
        lastSequenceGapAtMs = -1L
        lateOrDuplicate = 0
    }

    private fun Int.toUnsignedLong(): Long = toLong() and 0xffff_ffffL

    private companion object { const val LONG_READ_NS = 250_000_000L }

}

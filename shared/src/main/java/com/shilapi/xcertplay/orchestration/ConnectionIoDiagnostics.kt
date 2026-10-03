package com.shilapi.xcertplay.orchestration

/** Scalar transport timings only. No payloads, addresses, names, UUIDs or failure messages. */
internal class ConnectionIoDiagnostics(
    private val report: (String) -> Unit,
    private val clockNanos: () -> Long = System::nanoTime,
) {
    enum class Operation { READ, WRITE }
    enum class Result { COMPLETED, TIMED_OUT, ENDED, FAILED }

    private var reads = 0L
    private var writes = 0L
    private var timeouts = 0L
    private var ends = 0L
    private var failures = 0L
    private var maxReadMillis = 0L
    private var maxWriteMillis = 0L
    private var firstRead = true
    private var firstWrite = true
    private var firstFailure = true
    private var finished = false
    private var lastReportNanos = clockNanos()

    @Synchronized
    fun record(operation: Operation, result: Result, elapsedMillis: Long) {
        if (finished) return
        val duration = elapsedMillis.coerceAtLeast(0)
        val first = when (operation) {
            Operation.READ -> {
                reads++
                maxReadMillis = maxOf(maxReadMillis, duration)
                firstRead.also { firstRead = false }
            }
            Operation.WRITE -> {
                writes++
                maxWriteMillis = maxOf(maxWriteMillis, duration)
                firstWrite.also { firstWrite = false }
            }
        }
        when (result) {
            Result.TIMED_OUT -> timeouts++
            Result.ENDED -> ends++
            Result.FAILED -> failures++
            Result.COMPLETED -> Unit
        }
        val failedFirst = result == Result.FAILED && firstFailure
        if (failedFirst) firstFailure = false
        val now = clockNanos()
        if (first || failedFirst || now - lastReportNanos >= REPORT_INTERVAL_NANOS) {
            lastReportNanos = now
            emit("wired io operation=$operation result=$result elapsedMs=$duration ${counters()}")
        }
    }

    @Synchronized
    fun finish() {
        if (finished) return
        finished = true
        emit("wired io final ${counters()}")
    }

    private fun counters() =
        "readCalls=$reads writeCalls=$writes readTimeouts=$timeouts ends=$ends failures=$failures " +
            "maxReadMs=$maxReadMillis maxWriteMs=$maxWriteMillis"

    private fun emit(message: String) {
        try { report(message) } catch (_: Exception) {
            // Diagnostics must not change connection or cleanup behavior.
        }
    }

    private companion object {
        const val REPORT_INTERVAL_NANOS = 10_000_000_000L
    }
}

package com.shilapi.xcertplay.hud

/** Shared wire contract; keep DiPlay and DiAuto copies identical apart from their package. */
internal object BydHudProtocol {
    const val SERVICE_TOPIC = 0x000B010A00010000L
    const val NAVIGATION_TOPIC = 0x0004010A00018001L
    const val REPEAT_MILLIS = 300L
    const val MAX_BIND_RETRY_MILLIS = 60_000L

    /**
     * The wait before binding the SOME/IP gateway again after [failures] refused binds in a row: from one
     * repeat interval, doubling to a minute. A refused bind used to be retried on every 300 ms tick, about
     * 200 times a minute, where the gateway's package exists but cannot be bound (#609).
     */
    fun bindRetryMillis(failures: Int): Long =
        if (failures <= 0) 0L else (REPEAT_MILLIS shl (failures - 1).coerceAtMost(10)).coerceAtMost(MAX_BIND_RETRY_MILLIS)
    fun serviceStarted(code: Int): Boolean = code == 0 || code == 13
    fun eventAccepted(code: Int): Boolean = code == 0
}

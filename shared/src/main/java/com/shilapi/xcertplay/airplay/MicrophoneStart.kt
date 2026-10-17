package com.shilapi.xcertplay.airplay

import java.util.concurrent.Future
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Starts one stream's microphone at most once: at the first downlink packet, or [SILENT_START_MILLIS]
 * after SETUP when the iPhone asks for input but plays nothing. An app voice message (Douyin, #566)
 * opens a speech-recognition stream without downlink audio, and capture waited for a packet that
 * never came. Calls still wait for their downlink: call capture uses the far-end audio as its echo
 * reference.
 */
internal class MicrophoneStart(private val start: () -> Unit) {
    private val claimed = AtomicBoolean()
    @Volatile private var silentStart: Future<*>? = null

    /** The first downlink packet arrived. */
    fun onDownlink() {
        silentStart?.cancel(false)
        if (claimed.compareAndSet(false, true)) start()
    }

    /**
     * Starts capture after [delayMillis] unless the downlink started it first. [whileCurrent] runs the
     * given start only while the stream still owns its microphone; the start returns true when it
     * started capture.
     */
    fun scheduleSilentStart(
        scheduler: ScheduledExecutorService,
        delayMillis: Long,
        whileCurrent: (startNow: () -> Boolean) -> Unit,
    ) {
        silentStart = scheduler.schedule({
            whileCurrent {
                if (claimed.compareAndSet(false, true)) {
                    start()
                    true
                } else {
                    false
                }
            }
        }, delayMillis, TimeUnit.MILLISECONDS)
    }

    companion object {
        const val SILENT_START_MILLIS = 500L

        fun startsWithoutDownlink(audioType: String): Boolean = audioType != "telephony"
    }
}

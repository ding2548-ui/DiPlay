package com.shilapi.xcertplay

import android.os.SystemClock
import com.shilapi.xcertplay.orchestration.CarPlayStatus

enum class ConnectionPhase {
    IDLE,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    STUCK,
}

/**
 * Immutable snapshot of the connection progress, mirroring FreeAuto's
 * ConnectionProgress.State (the subset this tree tracks).
 */
data class ConnectionState(
    val phase: ConnectionPhase = ConnectionPhase.IDLE,
    val wireless: Boolean = false,
    val step: Int = 1,
    val stepSince: Long = 0,
    val startedAt: Long = 0,
    val connectedAt: Long = 0,
    val stuckDetail: String? = null,
    val stepsExpanded: Boolean = false,
)

/**
 * Tracks which connection step CarPlay is on, the FreeAuto way: a singleton
 * whose every mutator is synchronized, advanced by [status] (driven from the
 * CarPlayStatus stream) and finished by [connected] / [lost] / [failed].
 * The clock is injectable so tests can exercise the stall detection.
 */
object ConnectionProgress {
    // FreeAuto flags a stalled connection after this long on one step.
    const val STALL_MILLIS = 60_000L

    @Volatile
    private var clock: () -> Long = { SystemClock.elapsedRealtime() }

    @Volatile
    var state: ConnectionState = ConnectionState()
        private set

    @Synchronized
    fun setClock(customClock: () -> Long) {
        clock = customClock
    }

    fun now(): Long = clock()

    /** A connection attempt started; reset to step 1. */
    @Synchronized
    fun begin(wireless: Boolean, automatic: Boolean) {
        val timestamp = clock()
        state = ConnectionState(
            phase = ConnectionPhase.CONNECTING,
            wireless = wireless,
            step = 1,
            stepSince = timestamp,
            startedAt = timestamp,
            stepsExpanded = state.stepsExpanded,
        )
    }

    /** Advance the current step from a CarPlayStatus, the FreeAuto ConnectionProgress.status way. */
    @Synchronized
    fun status(wireless: Boolean, status: CarPlayStatus) {
        if (state.phase == ConnectionPhase.CONNECTED) return
        val mapped = ConnectionStepMap.forStatus(wireless, status) ?: return
        val timestamp = clock()
        val reset = state.phase == ConnectionPhase.IDLE ||
            state.phase == ConnectionPhase.STUCK ||
            state.wireless != wireless
        if (reset) {
            state = ConnectionState(
                phase = ConnectionPhase.CONNECTING,
                wireless = wireless,
                step = mapped,
                stepSince = timestamp,
                startedAt = timestamp,
                stepsExpanded = state.stepsExpanded,
            )
            return
        }
        val phase = if (state.phase == ConnectionPhase.RECONNECTING) {
            ConnectionPhase.RECONNECTING
        } else {
            ConnectionPhase.CONNECTING
        }
        val stepSince = if (mapped != state.step) timestamp else state.stepSince
        state = state.copy(phase = phase, step = mapped, stepSince = stepSince)
    }

    /** The session is live: stop at the current step and mark connected. */
    @Synchronized
    fun connected(wireless: Boolean) {
        if (state.wireless != wireless && state.phase != ConnectionPhase.CONNECTING) return
        if (state.phase != ConnectionPhase.CONNECTING && state.phase != ConnectionPhase.RECONNECTING) return
        state = state.copy(phase = ConnectionPhase.CONNECTED, connectedAt = clock(), stuckDetail = null)
    }

    /** The live session dropped: keep the last step and wait for the automatic reconnect. */
    @Synchronized
    fun lost(wireless: Boolean) {
        if (state.phase == ConnectionPhase.CONNECTED) {
            state = state.copy(phase = ConnectionPhase.RECONNECTING, stepSince = clock())
        }
    }

    /** The attempt failed with a readable message: park on the current step as STUCK. */
    @Synchronized
    fun failed(wireless: Boolean, message: String) {
        if (state.phase != ConnectionPhase.CONNECTING && state.phase != ConnectionPhase.RECONNECTING) return
        state = state.copy(
            phase = ConnectionPhase.STUCK,
            wireless = wireless,
            stuckDetail = message,
        )
    }

    /** Called once a second from the UI tick: a CONNECTING step that has not advanced stalls out. */
    @Synchronized
    fun markStalledIfExpired(currentTime: Long = clock()) {
        if (state.phase != ConnectionPhase.CONNECTING) return
        if (state.stepSince <= 0 || currentTime - state.stepSince < STALL_MILLIS) return
        state = state.copy(phase = ConnectionPhase.STUCK)
    }

    @Synchronized
    fun toggleStepsExpanded() {
        state = state.copy(stepsExpanded = !state.stepsExpanded)
    }

    @Synchronized
    fun collapseSteps() {
        state = state.copy(stepsExpanded = false)
    }

    /** The connection UI closed or the controller stopped: back to idle. */
    @Synchronized
    fun stopped() {
        state = ConnectionState(phase = ConnectionPhase.IDLE, stepsExpanded = state.stepsExpanded)
    }
}

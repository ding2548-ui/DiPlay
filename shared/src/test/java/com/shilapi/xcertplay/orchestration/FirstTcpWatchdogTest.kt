package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.airplay.AirPlayListenerIdentity
import com.shilapi.xcertplay.airplay.AirPlayTcpAccepted
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class FirstTcpWatchdogTest {
    private var now = 0L
    private var deadline: Long? = null
    private var timer: (() -> Unit)? = null
    private var failures = 0
    private val identity = AirPlayListenerIdentity(7)
    private fun watchdog() = FirstTcpWatchdog(identity, { delay, action ->
        deadline = now + delay
        timer = action
        val cancel: () -> Unit = { deadline = null }
        cancel
    }, { failures++ }, { now * 1_000_000 })
    private fun event(owner: AirPlayListenerIdentity = identity, internal: Boolean = false) =
        AirPlayTcpAccepted(owner, internal, now * 1_000_000)

    @Test fun noSuccessfulStartSessionDoesNotArmTimer() {
        watchdog()
        now = 60_000
        assertNull(timer)
        assertEquals(0, failures)
    }

    @Test fun repeatedStartDoesNotExtendDeadline() {
        val watch = watchdog()
        watch.startSessionSent(0)
        now = 20_000
        watch.startSessionSent(now * 1_000_000)
        assertEquals(30_000L, deadline)
        now = 30_000
        timer!!()
        assertEquals(1, failures)
        assertTrue(watch.terminated)
        timer!!()
        assertEquals(1, failures)
    }

    @Test fun earlyAcceptAvoidsTimerAndDoesNotDeclareSessionActive() {
        val logs = mutableListOf<String>()
        val watch = FirstTcpWatchdog(identity, { _, _ -> error("Unexpected timer") }, {}, log = logs::add)
        assertTrue(watch.accepted(event()))
        watch.startSessionSent(1)
        assertFalse(logs.any { it.contains("session established") })
        assertFalse(watch.terminated)
    }

    @Test fun localProbeAndOtherListenerCannotCancelTimer() {
        val watch = watchdog()
        watch.startSessionSent(0)
        assertFalse(watch.accepted(event(internal = true)))
        assertFalse(watch.accepted(event(AirPlayListenerIdentity(6))))
        assertFalse(watch.accepted(event(AirPlayListenerIdentity(7))))
        now = 30_000
        timer!!()
        assertEquals(1, failures)
    }

    @Test fun acceptWinsAgainstAlreadyQueuedTimeout() {
        val watch = watchdog()
        watch.startSessionSent(0)
        now = 30_000
        assertTrue(watch.accepted(event()))
        timer!!()
        assertEquals(0, failures)
        assertFalse(watch.terminated)
    }

    @Test fun timeoutWinsAndLateAcceptCannotRevive() {
        val watch = watchdog()
        watch.startSessionSent(0)
        now = 30_000
        timer!!()
        assertFalse(watch.accepted(event()))
        assertFalse(watch.sessionEstablished())
        assertFalse(watch.terminate())
        assertEquals(1, failures)
    }

    @Test fun sessionSuccessAndUserCancellationInvalidateQueuedTimer() {
        for (success in listOf(true, false)) {
            val watch = watchdog()
            watch.startSessionSent(0)
            if (success) watch.sessionEstablished() else watch.terminate()
            now = 60_000
            timer!!()
            assertEquals(0, failures)
        }
    }

    @Test fun oldTimerCannotEndNewAttempt() {
        val old = watchdog()
        old.startSessionSent(0)
        val queued = timer!!
        old.terminate()
        val current = watchdog()
        current.startSessionSent(0)
        queued()
        assertFalse(current.terminated)
        assertEquals(0, failures)
    }

    @Test fun concurrentAcceptAndTimeoutHaveExactlyOneOutcome() {
        repeat(50) {
            lateinit var expire: () -> Unit
            val outcomes = AtomicInteger()
            val watch = FirstTcpWatchdog(identity, { _, action -> expire = action; {} }, { outcomes.incrementAndGet() })
            watch.startSessionSent(0)
            val start = CountDownLatch(1)
            val tcp = thread { start.await(); if (watch.accepted(event())) outcomes.incrementAndGet() }
            val timeout = thread { start.await(); expire() }
            start.countDown()
            tcp.join(2_000); timeout.join(2_000)
            assertFalse(tcp.isAlive); assertFalse(timeout.isAlive)
            assertEquals(1, outcomes.get())
        }
    }
}

package com.shilapi.xcertplay.hud

import android.content.Context
import com.shilapi.xcertplay.iap2.message.Iap2Messages
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, shadows = [BydCarPlayCallLifecycleTest.Shell::class])
class BydCarPlayCallLifecycleTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val writer get() = ReflectionHelpers.getField<ExecutorService>(BydCarPlayCall, "writer")
    private fun drain() { writer.submit {}.get(5, TimeUnit.SECONDS) }
    private fun ringing(id: String) = Iap2Messages.buildRaw(CarPlayCallState.CALL_STATE_UPDATE) {
        u8(2, 2); string(4, id)
    }

    @Before fun setup() {
        BydCarPlayCall.watcherReadyMillis = 500L
        drain()
        ReflectionHelpers.getField<CarPlayCallState>(BydCarPlayCall, "state").clear()
        ReflectionHelpers.setField(BydCarPlayCall, "shown", null)
        ReflectionHelpers.setField(BydCarPlayCall, "watcherToken", null)
        ReflectionHelpers.setField(BydCarPlayCall, "watcherRunning", false)
        ReflectionHelpers.setField(BydCarPlayCall, "cleanupPending", false)
        ReflectionHelpers.setField(BydCarPlayCall, "prepared", false)
        ReflectionHelpers.setField(BydCarPlayCall, "sessionActive", false)
        BydCarPlayCall.attach(app)
        BydOutputSettings.setCarPlayCalls(app, false)
        Shell.commands.clear()
        Shell.rejectPhase = null
        Shell.endFailures = 0
        Shell.omitCompletion = false
        Shell.rejectWatcher = false
        Shell.omitReadiness = false
        Shell.omitCancelCompletion = false
        Shell.emptyWatcherReply = false
    }

    @After fun cleanup() {
        Shell.endFailures = 0
        Shell.omitCompletion = false
        Shell.omitCancelCompletion = false
        BydCarPlayCall.end(); drain()
    }

    @Test fun defaultOffTracksCallWithoutAnyShellOrWatcherMutation() {
        BydCarPlayCall.onFrame(ringing("a")); drain()
        assertNotNull(BydCarPlayCall.current())
        assertTrue(Shell.commands.isEmpty())
    }

    @Test fun backToBackCallsUseDifferentTokensForWritesWatcherAndCleanup() {
        BydOutputSettings.setCarPlayCalls(app, true)
        BydCarPlayCall.onFrame(ringing("a")); drain()
        val first = tokenFrom(Shell.commands.first { it.contains(" ringing ") })
        assertTrue(Shell.commands.any { it.contains(" watch $first ${app.packageName} ") })
        BydCarPlayCall.end(); drain()
        assertTrue(Shell.commands.any { it.endsWith(" end - $first") })
        BydCarPlayCall.onFrame(ringing("b")); drain()
        val next = tokenFrom(Shell.commands.last { it.contains(" ringing ") })
        assertNotEquals(first, next)
        assertTrue(Shell.commands.any { it.contains(" watch $next ${app.packageName} ") })
        assertTrue(Shell.commands.none { it.contains("rm -f") })
    }

    @Test fun disablingBeforeQueuedUpdateExecutesPreventsLateCallWrites() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        writer.execute { entered.countDown(); release.await(5, TimeUnit.SECONDS) }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        try {
            BydOutputSettings.setCarPlayCalls(app, true)
            BydCarPlayCall.onFrame(ringing("a"))
            BydOutputSettings.setCarPlayCalls(app, false)
            BydCarPlayCall.settingChanged(false)
        } finally { release.countDown() }
        drain()
        assertTrue(Shell.commands.isEmpty())
    }

    @Test fun failedFirstShowRetainsCleanupOwnershipAndStartsTheWatcher() {
        BydOutputSettings.setCarPlayCalls(app, true)
        Shell.rejectPhase = "ringing"
        BydCarPlayCall.onFrame(ringing("a")); drain()
        val token = tokenFrom(Shell.commands.first { it.contains(" ringing ") })
        assertEquals(token, ReflectionHelpers.getField<String?>(BydCarPlayCall, "watcherToken"))
        assertNull(ReflectionHelpers.getField<Any?>(BydCarPlayCall, "shown"))
        assertTrue(Shell.commands.any { it.contains(" watch $token ${app.packageName} ") })
        BydCarPlayCall.end(); drain()
        assertTrue(Shell.commands.any { it.endsWith(" end - $token") })
    }

    @Test fun failedCleanupRemainsRetryableEvenWhenNoCallWasMarkedShown() {
        BydOutputSettings.setCarPlayCalls(app, true)
        Shell.rejectPhase = "ringing"
        BydCarPlayCall.onFrame(ringing("a")); drain()
        val token = tokenFrom(Shell.commands.first { it.contains(" ringing ") })
        Shell.endFailures = 1
        BydCarPlayCall.end(); drain()
        assertEquals(token, ReflectionHelpers.getField<String?>(BydCarPlayCall, "watcherToken"))
        BydOutputSettings.setCarPlayCalls(app, false)
        BydCarPlayCall.settingChanged(false); drain()
        assertEquals(2, Shell.commands.count { it.endsWith(" end - $token") })
        assertNull(ReflectionHelpers.getField<String?>(BydCarPlayCall, "watcherToken"))
    }

    @Test fun failedActiveUpdateMustCleanUpBeforeShowingAnotherCall() {
        BydOutputSettings.setCarPlayCalls(app, true)
        BydCarPlayCall.onFrame(ringing("a")); drain()
        val oldToken = tokenFrom(Shell.commands.first { it.contains(" ringing ") })
        Shell.rejectPhase = "active"
        BydCarPlayCall.onFrame(Iap2Messages.buildRaw(CarPlayCallState.CALL_STATE_UPDATE) {
            u8(2, 4); string(4, "a")
        }); drain()
        Shell.rejectPhase = null
        BydCarPlayCall.onFrame(ringing("b")); drain()
        val end = Shell.commands.indexOfFirst { it.endsWith(" end - $oldToken") }
        val next = Shell.commands.indexOfLast { it.contains(" ringing ") }
        assertTrue(end >= 0 && end < next)
        assertNotEquals(oldToken, tokenFrom(Shell.commands[next]))
    }

    @Test fun missingCompletionAckCannotMarkAnUnknownWriteAsShown() {
        BydOutputSettings.setCarPlayCalls(app, true)
        Shell.omitCompletion = true
        BydCarPlayCall.onFrame(ringing("a")); drain()
        assertNull(ReflectionHelpers.getField<Any?>(BydCarPlayCall, "shown"))
        val token = tokenFrom(Shell.commands.first { it.contains(" ringing ") })
        assertEquals(token, ReflectionHelpers.getField<String?>(BydCarPlayCall, "watcherToken"))
        Shell.omitCompletion = false
        BydCarPlayCall.end(); drain()
        assertTrue(Shell.commands.any { it.endsWith(" end - $token") })
    }

    @Test fun unknownShowAndCleanupRepliesBlockFurtherCallMutationUntilCleanupIsConfirmed() {
        BydOutputSettings.setCarPlayCalls(app, true)
        Shell.omitCompletion = true
        BydCarPlayCall.onFrame(ringing("a")); drain()
        val token = tokenFrom(Shell.commands.first { it.contains(" ringing ") })
        BydCarPlayCall.onFrame(Iap2Messages.buildRaw(CarPlayCallState.CALL_STATE_UPDATE) {
            u8(2, 4); string(4, "a")
        }); drain()
        assertTrue(Shell.commands.any { it.endsWith(" end - $token") })
        assertTrue(Shell.commands.none { it.contains(" active ") })
        assertNull(ReflectionHelpers.getField<Any?>(BydCarPlayCall, "shown"))
        Shell.omitCompletion = false
        BydCarPlayCall.settingChanged(true); drain()
        assertTrue(Shell.commands.any { it.contains(" active ") })
        assertNotNull(ReflectionHelpers.getField<Any?>(BydCarPlayCall, "shown"))
    }

    @Test fun refusedWatcherLaunchCannotBeMarkedRunningOrWriteCallFields() {
        BydOutputSettings.setCarPlayCalls(app, true)
        Shell.rejectWatcher = true
        BydCarPlayCall.onFrame(ringing("unprotected")); drain()
        assertFalse(ReflectionHelpers.getField<Boolean>(BydCarPlayCall, "watcherRunning"))
        assertTrue(Shell.commands.none { it.contains(" ringing ") })
    }

    @Test fun aMissingChildReadinessRecordCannotPermitVehicleMutation() {
        BydOutputSettings.setCarPlayCalls(app, true)
        Shell.omitReadiness = true
        BydCarPlayCall.onFrame(ringing("not-ready")); drain()
        assertTrue(Shell.commands.none { it.contains(" ringing ") })
    }

    @Test fun anEmptyBackgroundLaunchReplyStillNeedsTheChildReadinessRecord() {
        BydOutputSettings.setCarPlayCalls(app, true)
        Shell.emptyWatcherReply = true
        Shell.omitReadiness = true
        BydCarPlayCall.onFrame(ringing("empty-launch")); drain()
        assertFalse(ReflectionHelpers.getField<Boolean>(BydCarPlayCall, "watcherRunning"))
        assertTrue(Shell.commands.none { it.contains(" ringing ") })
        assertTrue(Shell.commands.any { it.contains(" cancel - ") })
    }

    @Test fun vehicleWritesFollowPreparationAndInitializedChildAcknowledgement() {
        BydOutputSettings.setCarPlayCalls(app, true)
        BydCarPlayCall.onFrame(ringing("ordered")); drain()
        val prepare = Shell.commands.indexOfFirst { it.contains(" prepare - ") }
        val launch = Shell.commands.indexOfFirst { it.startsWith("setsid nohup ") }
        val probe = Shell.commands.indexOfFirst { it.contains(" probe - ") }
        val show = Shell.commands.indexOfFirst { it.contains(" ringing ") }
        assertTrue(prepare >= 0 && prepare < launch && launch < probe && probe < show)
        val pid = android.os.Process.myPid().toString()
        assertTrue(Shell.commands[show].endsWith(" $pid"))
    }

    @Test fun failedWatcherHasOneRealWriterRetryEvenWhenTheCallFrameNeverChanges() {
        BydOutputSettings.setCarPlayCalls(app, true)
        Shell.rejectWatcher = true
        BydCarPlayCall.onFrame(ringing("unchanged")); drain()
        assertTrue(Shell.commands.none { it.contains(" ringing ") })
        Shell.rejectWatcher = false
        awaitRetry()
        assertEquals(1, Shell.commands.count { it.contains(" ringing ") })
        assertNotNull(ReflectionHelpers.getField<Any?>(BydCarPlayCall, "shown"))
    }

    @Test fun missingPristineCancelAcknowledgementRetainsItsTokenUntilConfirmedRetry() {
        BydOutputSettings.setCarPlayCalls(app, true)
        Shell.rejectWatcher = true
        Shell.omitCancelCompletion = true
        BydCarPlayCall.onFrame(ringing("cancel-pending")); drain()
        val original = ReflectionHelpers.getField<String?>(BydCarPlayCall, "watcherToken")!!
        assertTrue(ReflectionHelpers.getField<Boolean>(BydCarPlayCall, "prepared"))
        Shell.omitCancelCompletion = false
        Shell.rejectWatcher = false
        awaitRetry()
        val firstCancel = Shell.commands.indexOfFirst { it.endsWith(" cancel - $original") }
        val retryCancel = Shell.commands.indexOfLast { it.endsWith(" cancel - $original") }
        val newPrepare = Shell.commands.indexOfLast { it.contains(" prepare - ") }
        assertTrue(firstCancel >= 0 && retryCancel > firstCancel && newPrepare > retryCancel)
        assertTrue(Shell.commands.none { it.endsWith(" end - $original") })
        assertEquals(1, Shell.commands.count { it.contains(" ringing ") })
    }

    @Test fun disablingTheOptionCancelsTheUnchangedCallRetryWithoutHardwareWrites() {
        BydOutputSettings.setCarPlayCalls(app, true)
        Shell.rejectWatcher = true
        BydCarPlayCall.onFrame(ringing("disabled")); drain()
        BydOutputSettings.setCarPlayCalls(app, false)
        BydCarPlayCall.settingChanged(false); drain()
        Shell.rejectWatcher = false
        Thread.sleep(1200); drain()
        assertTrue(Shell.commands.none { it.contains(" ringing ") || it.contains(" end - ") })
    }

    @Test fun refusedEndGetsOnlyOneAutomaticRetryUntilAnExplicitNewCleanupEvent() {
        BydOutputSettings.setCarPlayCalls(app, true)
        BydCarPlayCall.onFrame(ringing("end-retry")); drain()
        val path = ReflectionHelpers.getField<String?>(BydCarPlayCall, "watcherToken")!!
        Shell.endFailures = 10
        BydCarPlayCall.end(); drain()
        awaitRetry()
        Thread.sleep(1200); drain()
        assertEquals(2, Shell.commands.count { it.endsWith(" end - $path") })
        Shell.endFailures = 0
        BydCarPlayCall.end(); drain()
        assertEquals(3, Shell.commands.count { it.endsWith(" end - $path") })
        assertNull(ReflectionHelpers.getField<String?>(BydCarPlayCall, "watcherToken"))
    }

    @Test fun sessionStartArmsTheWatcherSoTheFirstCallSkipsItsLaunch() {
        BydOutputSettings.setCarPlayCalls(app, true)
        BydCarPlayCall.sessionStarted(); drain()
        val armed = ReflectionHelpers.getField<String?>(BydCarPlayCall, "watcherToken")!!
        assertTrue(Shell.commands.any { it.contains(" watch $armed ${app.packageName} ") })
        assertTrue(Shell.commands.none { it.contains(" ringing ") || it.contains(" end - ") || it.contains(" cancel - ") })
        BydCarPlayCall.onFrame(ringing("armed")); drain()
        assertEquals(armed, tokenFrom(Shell.commands.single { it.contains(" ringing ") }))
        assertEquals(1, Shell.commands.count { it.startsWith("setsid nohup ") })
    }

    @Test fun endingASessionWithAnArmedWatcherOnlyCancelsIt() {
        BydOutputSettings.setCarPlayCalls(app, true)
        BydCarPlayCall.sessionStarted(); drain()
        val armed = ReflectionHelpers.getField<String?>(BydCarPlayCall, "watcherToken")!!
        BydCarPlayCall.end(); drain()
        assertTrue(Shell.commands.any { it.endsWith(" cancel - $armed") })
        assertTrue(Shell.commands.none { it.contains(" end - ") })
        assertNull(ReflectionHelpers.getField<String?>(BydCarPlayCall, "watcherToken"))
    }

    @Test fun turningTheOptionOffCancelsAnArmedWatcherAndOnArmsAgain() {
        BydOutputSettings.setCarPlayCalls(app, true)
        BydCarPlayCall.sessionStarted(); drain()
        val armed = ReflectionHelpers.getField<String?>(BydCarPlayCall, "watcherToken")!!
        BydOutputSettings.setCarPlayCalls(app, false)
        BydCarPlayCall.settingChanged(false); drain()
        assertTrue(Shell.commands.any { it.endsWith(" cancel - $armed") })
        assertNull(ReflectionHelpers.getField<String?>(BydCarPlayCall, "watcherToken"))
        BydOutputSettings.setCarPlayCalls(app, true)
        BydCarPlayCall.settingChanged(true); drain()
        assertNotEquals(armed, ReflectionHelpers.getField<String?>(BydCarPlayCall, "watcherToken"))
        assertTrue(Shell.commands.none { it.contains(" end - ") })
    }

    @Test fun anEndedCallRearmsWithAFreshToken() {
        BydOutputSettings.setCarPlayCalls(app, true)
        BydCarPlayCall.sessionStarted(); drain()
        BydCarPlayCall.onFrame(ringing("first")); drain()
        val first = tokenFrom(Shell.commands.single { it.contains(" ringing ") })
        BydCarPlayCall.onFrame(Iap2Messages.buildRaw(CarPlayCallState.CALL_STATE_UPDATE) {
            u8(2, 0); string(4, "first")
        }); drain()
        assertTrue(Shell.commands.any { it.endsWith(" end - $first") })
        val next = ReflectionHelpers.getField<String?>(BydCarPlayCall, "watcherToken")!!
        assertNotEquals(first, next)
        assertTrue(ReflectionHelpers.getField<Boolean>(BydCarPlayCall, "prepared"))
        assertTrue(Shell.commands.any { it.contains(" watch $next ${app.packageName} ") })
    }

    @Test fun noSessionMeansNoArmedWatcher() {
        BydOutputSettings.setCarPlayCalls(app, true)
        BydCarPlayCall.settingChanged(true); drain()
        assertTrue(Shell.commands.isEmpty())
    }

    private fun awaitRetry() {
        val future = ReflectionHelpers.getField<java.util.concurrent.ScheduledFuture<*>?>(BydCarPlayCall, "retry")
            ?: error("Expected a bounded retry")
        future.get(5, TimeUnit.SECONDS)
        drain()
    }

    private fun tokenFrom(command: String): String =
        Regex("/data/local/tmp/diplay-carplay-call-[A-Za-z0-9_.]+-[0-9a-f-]{36}")
            .find(command)!!.value

    @Implements(BydAdbShell::class, isInAndroidSdk = false)
    class Shell {
        @Implementation fun run(context: Context, command: String): String? {
            commands.add(command)
            if (command.startsWith("setsid nohup ") && rejectWatcher) return "nohup: watcher launch refused"
            if (command.startsWith("setsid nohup ") && emptyWatcherReply) return ""
            if (command.contains(" cancel - ") && omitCancelCompletion) return ""
            if (command.contains(" probe ")) return if (rejectWatcher || omitReadiness) "watch=ERR" else "watch=0"
            if (rejectPhase?.let { command.contains(" $it ") } == true) return "write=ERR call write refused"
            if (command.contains(" end - ") && endFailures > 0) {
                endFailures--
                return "write=ERR cleanup refused"
            }
            val phaseOrEnd = command.contains(" end - ") ||
                listOf("ringing", "dialing", "active").any { command.contains(" $it ") }
            return if (omitCompletion && phaseOrEnd) "state=0" else "state=0\nwrite=0"
        }
        companion object {
            val commands = CopyOnWriteArrayList<String>()
            @Volatile var rejectPhase: String? = null
            @Volatile var endFailures = 0
            @Volatile var omitCompletion = false
            @Volatile var rejectWatcher = false
            @Volatile var omitReadiness = false
            @Volatile var omitCancelCompletion = false
            @Volatile var emptyWatcherReply = false
        }
    }
}

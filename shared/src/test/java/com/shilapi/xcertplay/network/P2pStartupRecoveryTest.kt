package com.shilapi.xcertplay.network

import android.net.wifi.p2p.WifiP2pManager
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class P2pStartupRecoveryTest {
    @Test fun confirmedFrequencyGoesFirstWithoutBeingRetriedLater() {
        val preferred = P2pCreationRequest(P2pCreationMode.FIXED_2_GHZ, 2437)
        val plan = P2pStartupRecovery.plan(5180, preferred)
        assertEquals(preferred, plan.first())
        assertEquals(1, plan.count { it.frequencyMHz == 2437 })
        assertEquals(listOf(2437, 5180, 5745, 2412, 2462, null), plan.map { it.frequencyMHz })
    }

    @Test fun rejectedRememberedSystemConfigurationFallsBackToExplicitChannels() {
        val attempted = mutableListOf<Int?>()
        val result = P2pStartupRecovery.create(5180, {}, P2pCreationRequest(P2pCreationMode.SYSTEM_DEFAULT)) {
            attempted += it.frequencyMHz
            if (it.frequencyMHz == null) throw P2pCreateRejected(WifiP2pManager.ERROR, "rejected")
        }
        assertEquals(listOf(null, 5180), attempted)
        assertEquals(5180, result.frequencyMHz)
    }

    @Test fun unsafeRememberedFrequenciesDoNotBypassTheChannelPolicy() {
        for (frequency in listOf(0, 2472, 2484, 5500, 5955)) {
            assertEquals(P2pStartupRecovery.plan(null), P2pStartupRecovery.plan(null,
                P2pCreationRequest(P2pCreationMode.FIXED_5_GHZ, frequency)))
        }
    }

    @Test fun workingAlignedChannelNeverUsesFallback() {
        val attempts = mutableListOf<P2pCreationRequest>()
        val mode = P2pStartupRecovery.create(5180, { fail("Unexpected retry") }) { attempts += it }
        assertEquals(listOf(P2pCreationRequest(P2pCreationMode.ALIGNED_5_GHZ, 5180)), attempts)
        assertEquals(P2pCreationMode.ALIGNED_5_GHZ, mode.mode)
    }

    @Test fun rejectedChannelAndCustomGroupFallBackToSystemConfiguration() {
        val attempts = mutableListOf<P2pCreationRequest>()
        var retries = 0
        val mode = P2pStartupRecovery.create(5180, { retries++ }) {
            attempts += it
            if (it.mode != P2pCreationMode.SYSTEM_DEFAULT) throw P2pCreateRejected(WifiP2pManager.ERROR, "rejected")
        }
        assertEquals(listOf(5180, 5745, 2437, 2412, 2462, null), attempts.map { it.frequencyMHz })
        assertEquals(P2pCreationMode.SYSTEM_DEFAULT, mode.mode)
        assertEquals(5, retries)
    }

    @Test fun unconnectedStationStartsWithFiveGhzAndBusyRetriesEachChannelOnce() {
        val attempts = mutableListOf<P2pCreationRequest>()
        try {
            P2pStartupRecovery.create(null, {}) {
                attempts += it
                throw P2pCreateRejected(WifiP2pManager.BUSY, "busy")
            }
            fail("Expected busy")
        } catch (failure: P2pCreateRejected) { assertEquals(WifiP2pManager.BUSY, failure.reason) }
        assertEquals(P2pStartupRecovery.plan(null).flatMap { mode -> List(2) { mode } }, attempts)
    }

    @Test fun firmwareThatBusiesEveryFiveGhzRequestStillReachesTwoGhz() {
        val attempts = mutableListOf<Int?>()
        val result = P2pStartupRecovery.create(5200, {}) {
            attempts += it.frequencyMHz
            if ((it.frequencyMHz ?: 0) >= 5000) throw P2pCreateRejected(WifiP2pManager.BUSY, "busy")
        }
        assertEquals(P2pCreationMode.FIXED_2_GHZ, result.mode)
        assertEquals(2437, result.frequencyMHz)
        assertEquals(listOf(5200, 5200, 5180, 5180, 5745, 5745, 2437), attempts)
    }

    @Test fun everyConfigurationRejectedStopsAfterBoundedAttempts() {
        var calls = 0
        try {
            P2pStartupRecovery.create(null, {}) {
                calls++
                throw P2pCreateRejected(WifiP2pManager.ERROR, "rejected")
            }
            fail("Expected rejection")
        } catch (_: P2pCreateRejected) { assertEquals(6, calls) }
    }

    @Test fun twoGhzOnlyDriverWithoutChannelSelectionRecoversBeforeAnyAutomaticRequest() {
        val attempts = mutableListOf<P2pCreationRequest>()
        val result = P2pStartupRecovery.create(null, {}) {
            attempts += it
            if (it.frequencyMHz !in listOf(2412, 2437, 2462)) {
                throw P2pCreateRejected(WifiP2pManager.ERROR, "unsupported band or automatic channel")
            }
        }
        assertEquals(2437, result.frequencyMHz)
        assertTrue(attempts.all { it.frequencyMHz != null })
        assertEquals(listOf(5180, 5745, 2437), attempts.map { it.frequencyMHz })
    }

    @Test fun existingTwoGhzStationUsesItsExactChannelFirst() {
        val result = P2pStartupRecovery.create(2422, { fail("Unexpected fallback") }) {
            assertEquals(2422, it.frequencyMHz)
        }
        assertEquals(P2pCreationMode.ALIGNED_2_GHZ, result.mode)
    }

    @Test fun rejectedChannelSixTriesOtherExplicitTwoGhzChannels() {
        val attempted = mutableListOf<Int?>()
        val result = P2pStartupRecovery.create(null, {}) {
            attempted += it.frequencyMHz
            if (it.frequencyMHz != 2462) throw P2pCreateRejected(WifiP2pManager.ERROR, "rejected")
        }
        assertEquals(2462, result.frequencyMHz)
        assertEquals(listOf(5180, 5745, 2437, 2412, 2462), attempted)
    }

    @Test fun planNeverRequestsDfsSixGhzInvalidOrPhoneUnfriendlyStationFrequencies() {
        for (station in listOf(0, -1, 5191, 5260, 5500, 5955, 2472, 2484)) {
            val plan = P2pStartupRecovery.plan(station)
            assertEquals(listOf(5180, 5745, 2437, 2412, 2462, null), plan.map { it.frequencyMHz })
        }
        val plan = P2pStartupRecovery.plan(5200)
        assertEquals(5200, plan.first().frequencyMHz)
        assertEquals(plan.size, plan.distinctBy { it.frequencyMHz }.size)
        assertTrue(plan.size <= 7)
    }

    @Test fun permissionUnsupportedAndUncertainTimeoutNeverTriggerAnotherCreation() {
        for (failure in listOf(P2pCreateRejected(WifiP2pManager.NO_PERMISSION, "permission"),
            P2pCreateRejected(WifiP2pManager.P2P_UNSUPPORTED, "unsupported"), IOException("timeout"))) {
            var calls = 0
            try {
                P2pStartupRecovery.create(5180, { fail("Unsafe retry") }) { calls++; throw failure }
                fail("Expected error")
            } catch (actual: IOException) { assertSame(failure, actual) }
            assertEquals(1, calls)
        }
    }

    @Test fun groupAppearingDuringRecoveryPreventsASecondCreation() {
        var calls = 0
        try {
            P2pStartupRecovery.create(5180, { throw P2pResetRequiredException() }) {
                calls++
                throw P2pCreateRejected(WifiP2pManager.ERROR, "rejected")
            }
            fail("Expected occupied group")
        } catch (_: P2pResetRequiredException) { assertEquals(1, calls) }
    }
}

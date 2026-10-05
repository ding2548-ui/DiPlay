package com.shilapi.xcertplay.hud

import org.junit.Assert.*
import org.junit.Test

class DiLink3ClusterModeSessionTest {
    private val projection = BydDiLink3ClusterMode.Mode.PROJECTION
    private val simple = BydDiLink3ClusterMode.Mode.SIMPLE_NAVIGATION
    private val stock = BydDiLink3ClusterMode.Mode.STOCK
    private val ok = "Result: Parcel(00000000 00000000 '........')"
    private val refused = "Result: Parcel(ffffffec 00000000 '........')"
    private var pending = false
    private var writable = true
    private val commands = mutableListOf<String>()
    private var response: (String) -> String? = { ok }
    private fun session() = DiLink3ClusterModeSession(
        run = { command ->
            assertTrue("Recovery must be durable before an OEM write", pending)
            commands.add(command)
            response(command)
        },
        loadRecovery = { pending },
        saveRecovery = { next -> if (writable) { pending = next; true } else false },
    )
    private fun prepare(state: DiLink3ClusterModeSession, current: () -> BydDiLink3ClusterMode.Mode? = { null },
                        wanted: () -> Boolean = { true }, delay: () -> Unit = {}) =
        state.prepareDisplay({ false }, current, wanted, delay)

    @Test fun projectionCreationRestoresStockAfterSecondCommandTimesOut() {
        response = { if (it == BydDiLink3ClusterMode.CREATE_DISPLAY[1]) null else ok }
        assertFalse(prepare(session()))
        assertEquals(BydDiLink3ClusterMode.CREATE_DISPLAY, commands)
        assertFalse(pending)
    }

    @Test fun refusedSecondCommandAndThrownAdbErrorBothCompensateFullProjection() {
        for (throwError in listOf(false, true)) {
            pending = false
            commands.clear()
            response = {
                if (it == BydDiLink3ClusterMode.CREATE_DISPLAY[1]) {
                    if (throwError) throw java.io.IOException("ADB disconnected") else refused
                } else ok
            }
            assertFalse(prepare(session()))
            assertEquals(BydDiLink3ClusterMode.CREATE_DISPLAY, commands)
            assertFalse(pending)
        }
    }

    @Test fun unknownFirstCommandReplyStillAttemptsCompensation() {
        response = { if (it == BydDiLink3ClusterMode.CREATE_DISPLAY[0]) null else ok }
        assertFalse(prepare(session()))
        assertEquals(listOf(BydDiLink3ClusterMode.CREATE_DISPLAY[0], stock.command), commands)
    }

    @Test fun cancelledPreparationRestoresStockBeforeTheNextStep() {
        var wanted = true
        assertFalse(prepare(session(), wanted = { wanted }, delay = { wanted = false }))
        assertEquals(listOf(BydDiLink3ClusterMode.CREATE_DISPLAY[0], stock.command), commands)
        assertFalse(pending)
    }

    @Test fun retriesStockRestoreAfterTemporaryAdbFailure() {
        val state = session()
        assertTrue(state.apply(projection))
        response = { null }
        assertFalse(state.apply(null))
        assertTrue(pending)
        response = { ok }
        assertTrue(state.apply(null))
        assertEquals(listOf(projection.command, stock.command, stock.command), commands)
        assertFalse(pending)
    }

    @Test fun failedPreparationRestorationBlocksANewProjectionUntilRecovery() {
        val state = session()
        response = { if (it == BydDiLink3ClusterMode.CREATE_DISPLAY[0]) ok else null }
        assertFalse(prepare(state))
        assertTrue(pending)
        assertFalse(state.apply(projection))
        assertEquals(stock.command, commands.last())
        response = { ok }
        assertTrue(state.apply(projection))
        assertEquals(listOf(stock.command, projection.command), commands.takeLast(2))
    }

    @Test fun interruptedProcessRestoresBeforeAcceptingNewGuidance() {
        val old = session()
        assertTrue(old.apply(projection))
        val restarted = session() // In-memory ownership was lost, but the journal survived.
        response = { if (it == stock.command) null else ok }
        assertFalse(restarted.apply(simple))
        assertEquals(stock.command, commands.last())
        response = { ok }
        assertTrue(restarted.apply(simple))
        assertEquals(listOf(stock.command, simple.command), commands.takeLast(2))
    }

    @Test fun startupRecoveryWorksWithNoNewOutputAndNoDisplayCreation() {
        pending = true
        val restarted = session()
        assertTrue(restarted.recoverInterrupted())
        assertEquals(listOf(stock.command), commands)
        assertFalse(pending)
        assertTrue(restarted.recoverInterrupted())
        assertEquals(1, commands.size)
    }

    @Test fun failureToPersistRecoveryRefusesAllCarMutations() {
        writable = false
        val state = session()
        assertFalse(state.apply(projection))
        assertFalse(prepare(state))
        assertTrue(commands.isEmpty())
    }

    @Test fun failedJournalClearIsRetriedInsteadOfForgettingOwnership() {
        val state = session()
        assertTrue(state.apply(simple))
        writable = false
        assertFalse(state.apply(null))
        assertTrue(pending)
        writable = true
        assertTrue(state.apply(null))
        assertFalse(pending)
        assertEquals(listOf(simple.command, stock.command, stock.command), commands)
    }

    @Test fun reopeningWithinTheSameProcessKeepsItsActiveMode() {
        val state = session()
        assertTrue(state.apply(projection))
        assertTrue(state.recoverInterrupted())
        assertTrue(state.apply(projection))
        assertEquals(listOf(projection.command), commands)
    }

    @Test fun preparationUsesLatestDesiredModeAfterItsBlockingSteps() {
        val state = session()
        var desired: BydDiLink3ClusterMode.Mode? = simple
        prepare(state, current = { desired }, delay = { desired = projection })
        assertEquals(BydDiLink3ClusterMode.CREATE_DISPLAY.take(2) + projection.command, commands)
        assertTrue(pending)
        assertTrue(state.apply(null))
    }

    @Test fun aFailedFinalMapModeAlsoCompensatesPreparation() {
        response = { if (it == projection.command) null else ok }
        assertFalse(prepare(session(), current = { projection }))
        assertEquals(BydDiLink3ClusterMode.CREATE_DISPLAY.take(2) + listOf(projection.command, stock.command), commands)
        assertFalse(pending)
    }

    @Test fun unrelatedDisplayAndAnUnusedOutputNeverChangeClusterMode() {
        val state = session()
        assertTrue(state.apply(null))
        assertTrue(state.prepareDisplay({ true }, { projection }, { true }, {}))
        assertTrue(commands.isEmpty())
    }
}

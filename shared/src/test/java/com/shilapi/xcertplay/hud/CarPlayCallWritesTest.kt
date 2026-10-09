package com.shilapi.xcertplay.hud

import com.shilapi.xcertplay.hud.CarPlayCallWrites.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlayCallWritesTest {
    private val writes = listOf(
        CarPlayCallWrite("audio", 0, 1),
        CarPlayCallWrite("car", 1, 0),
        CarPlayCallWrite("bt", 2, 5),
        CarPlayCallWrite("state", 1, 2),
    )
    private val log = ArrayList<String>()

    private fun run(outcomes: Map<String, Result> = emptyMap(), name: Result = Result.DONE) =
        CarPlayCallWrites.apply(
            writes,
            write = { step, value, undo ->
                log += "${if (undo) "undo-" else ""}${step.label}=$value"
                if (undo) Result.DONE else outcomes[step.label] ?: Result.DONE
            },
            name = { log += "name"; name },
        )

    @Test fun aSetterThatMutatesBeforeReportingFailureIsAlsoCompensated() {
        val values = writes.associate { it.label to it.idle }.toMutableMap()
        assertFalse(CarPlayCallWrites.apply(writes, { step, value, undo ->
            values[step.label] = value
            if (!undo && step.label == "bt") Result.REFUSED else Result.DONE
        }, { Result.DONE }))
        assertEquals(writes.associate { it.label to it.idle }, values)
    }

    @Test fun aThrowingUndoDoesNotPreventCompensationOfTheRemainingWrites() {
        assertFalse(CarPlayCallWrites.apply(writes, { step, value, undo ->
            log += "${if (undo) "undo-" else ""}${step.label}=$value"
            if (undo && step.label == "car") throw IllegalStateException("setter failed after write")
            if (!undo && step.label == "bt") Result.REFUSED else Result.DONE
        }, { Result.DONE }))
        assertEquals(listOf("audio=0", "car=1", "bt=2", "undo-bt=5", "undo-car=0", "undo-audio=1"), log)
    }

    @Test fun allAcceptedWritesEveryFeatureAndTheName() {
        assertTrue(run())
        assertEquals(listOf("audio=0", "car=1", "bt=2", "state=1", "name"), log)
    }

    @Test fun refusedWriteStopsAndSetsAcceptedOnesBackToIdleNewestFirst() {
        assertFalse(run(mapOf("bt" to Result.REFUSED)))
        assertEquals(listOf("audio=0", "car=1", "bt=2", "undo-bt=5", "undo-car=0", "undo-audio=1"), log)
    }

    @Test fun missingFeatureIsSkippedAndNotUndone() {
        assertFalse(run(mapOf("car" to Result.MISSING, "state" to Result.REFUSED)))
        assertEquals(listOf("audio=0", "car=1", "bt=2", "state=1", "undo-state=2", "undo-bt=5", "undo-audio=1"), log)
    }

    @Test fun missingFeaturesAloneStillShowTheRest() {
        assertTrue(run(mapOf("audio" to Result.MISSING)))
        assertEquals(listOf("audio=0", "car=1", "bt=2", "state=1", "name"), log)
    }

    @Test fun refusedNameUndoesTheCallState() {
        assertFalse(run(name = Result.REFUSED))
        assertEquals(
            listOf("audio=0", "car=1", "bt=2", "state=1", "name", "undo-state=2", "undo-bt=5", "undo-car=0", "undo-audio=1"),
            log,
        )
    }

    @Test fun firstWriteRefusedAlsoCompensatesThatAttempt() {
        assertFalse(run(mapOf("audio" to Result.REFUSED)))
        assertEquals(listOf("audio=0", "undo-audio=1"), log)
    }
}

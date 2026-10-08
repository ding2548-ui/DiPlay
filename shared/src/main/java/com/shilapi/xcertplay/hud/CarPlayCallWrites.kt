package com.shilapi.xcertplay.hud

/** One of the car's call-state writes: the value sent for a call and the one BYD's CarPlay app sends when it ends. */
internal class CarPlayCallWrite(val label: String, val call: Int, val idle: Int)

/**
 * Applies a call's writes with best-effort compensation. Missing features are skipped. Any attempted
 * setter may mutate before reporting failure, so the failing setter is compensated too, newest first.
 * Compensation continues after failures; false leaves cleanup ownership pending until an explicit end
 * succeeds. The caller name is written last and is hidden by the ended instrument state.
 */
internal object CarPlayCallWrites {
    enum class Result { DONE, MISSING, REFUSED }

    /** Returns false after a failed write and compensation attempt; it does not prove restoration. */
    fun apply(
        writes: List<CarPlayCallWrite>,
        write: (step: CarPlayCallWrite, value: Int, undo: Boolean) -> Result,
        name: () -> Result,
    ): Boolean {
        val attempted = ArrayList<CarPlayCallWrite>()
        for (step in writes) {
            attempted += step
            when (runCatching { write(step, step.call, false) }.getOrDefault(Result.REFUSED)) {
                Result.DONE -> Unit
                Result.MISSING -> attempted.remove(step)
                Result.REFUSED -> return undo(attempted, write)
            }
        }
        if (runCatching(name).getOrDefault(Result.REFUSED) == Result.REFUSED) return undo(attempted, write)
        return true
    }

    private fun undo(accepted: List<CarPlayCallWrite>, write: (CarPlayCallWrite, Int, Boolean) -> Result): Boolean {
        accepted.asReversed().forEach { step -> runCatching { write(step, step.idle, true) } }
        return false
    }
}

package com.shilapi.xcertplay.media

/** Bounded failure metadata for exported reports; exception messages can contain private inputs. */
internal object MediaFailureSummary {
    fun describe(error: Throwable): String {
        val failures = mutableListOf<Throwable>()
        var current: Throwable? = error
        while (failures.size < 3) {
            val next = current ?: break
            if (failures.any { it === next }) break
            failures += next
            current = next.cause
        }
        val types = failures.joinToString("/") { identifier(it.javaClass.simpleName, 64) }
        // Keep one code location, never the complete stack, source filename or arbitrary caller.
        val frame = failures.asSequence().flatMap { it.stackTrace.asSequence() }.firstOrNull {
            it.className.startsWith("android.media.") ||
                it.className.startsWith("com.shilapi.xcertplay.media.")
        }
        val location = frame?.let {
            "${identifier(it.className, 128)}.${identifier(it.methodName, 64)}:${it.lineNumber}"
        } ?: "unavailable"
        return "error=${identifier(error.javaClass.simpleName, 64)} causes=$types at=$location"
    }

    private fun identifier(value: String, limit: Int): String =
        value.take(limit).filter { it.isLetterOrDigit() || it == '.' || it == '_' || it == '$' }
            .ifEmpty { "unknown" }
}

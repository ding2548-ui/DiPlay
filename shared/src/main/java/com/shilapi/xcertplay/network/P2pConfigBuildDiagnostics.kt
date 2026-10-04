package com.shilapi.xcertplay.network

/** Distinguishes config construction from group creation without reading credentials. */
internal object P2pConfigBuildDiagnostics {
    fun <T> build(
        sdkInt: Int,
        selection: P2pCreationRequest,
        diagnostic: (String) -> Unit,
        create: () -> T,
    ): T {
        emit(diagnostic) { context("begin", sdkInt, selection) }
        try {
            val config = create()
            emit(diagnostic) { context("complete", sdkInt, selection) }
            return config
        } catch (failure: Throwable) {
            emit(diagnostic) { context("failed", sdkInt, selection) + " " + describe(failure) }
            if (isKnownApi29BuilderFailure(sdkInt, failure)) {
                emit(diagnostic) {
                    context("compatibility", sdkInt, selection) + " reason=config_get_network_name fallback=guarded_system_default"
                }
                throw P2pConfigBuildCompatibilityFailure(failure as NoSuchMethodError)
            }
            // Logging failures never replace an unrecognized error or authorize a retry.
            throw failure
        }
    }

    /** Only the reported API 29 builder defect is safe to classify before createGroup. */
    internal fun isKnownApi29BuilderFailure(sdkInt: Int, error: Throwable): Boolean {
        if (sdkInt != 29 || error !is NoSuchMethodError) return false
        return runCatching {
            val message = error.message ?: return@runCatching false
            // Match the actual #15 ART error, including the return type and terminating
            // class descriptor. A similarly named method or class is a different failure.
            message.startsWith(
                "No virtual method getNetworkName()Ljava/lang/String; in class Landroid/net/wifi/p2p/WifiP2pConfig;",
            ) &&
                error.stackTrace.firstOrNull()?.className == "android.net.wifi.p2p.WifiP2pConfig\$Builder"
        }.getOrDefault(false)
    }

    private fun emit(diagnostic: (String) -> Unit, message: () -> String) {
        try {
            diagnostic(message())
        } catch (_: Throwable) {
            // Diagnostics are optional, including when firmware metadata access is broken.
        }
    }

    private fun context(stage: String, sdkInt: Int, selection: P2pCreationRequest): String =
        "P2P_CONFIG_DIAGNOSTIC stage=$stage api=$sdkInt mode=${selection.mode} " +
            "frequencyMHz=${selection.frequencyMHz ?: "unknown"}"

    private fun describe(error: Throwable): String {
        val failures = mutableListOf<Throwable>()
        var current: Throwable? = error
        while (failures.size < 3) {
            val next = current ?: break
            if (failures.any { it === next }) break
            failures += next
            current = next.cause
        }
        val classes = failures.joinToString("/") { identifier(it.javaClass.simpleName, 64) }
        // One relevant location is sufficient; never export a stack or source filename.
        val frame = failures.asSequence().flatMap { it.stackTrace.asSequence() }.firstOrNull {
            it.className.startsWith("android.net.wifi.p2p.") ||
                it.className.startsWith("com.shilapi.xcertplay.network.")
        }
        val location = frame?.let {
            "${identifier(it.className, 128)}.${identifier(it.methodName, 64)}:${it.lineNumber}"
        } ?: "unavailable"
        return "failureClass=${identifier(error.javaClass.simpleName, 64)} causes=$classes at=$location"
    }

    private fun identifier(value: String, limit: Int): String =
        value.take(limit).filter { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '.' || it == '_' || it == '$' }
            .ifEmpty { "unknown" }
}

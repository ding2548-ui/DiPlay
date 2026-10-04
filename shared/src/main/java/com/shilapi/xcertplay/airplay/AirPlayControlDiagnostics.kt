package com.shilapi.xcertplay.airplay

/** Only fixed protocol routes/methods and byte counts survive into the diagnostic report. */
internal object AirPlayControlDiagnostics {
    fun request(method: String, path: String, bytes: Int): String {
        val verb = method.takeIf { it in setOf("GET", "POST", "SETUP", "RECORD", "TEARDOWN", "OPTIONS", "GET_PARAMETER", "SET_PARAMETER") } ?: "OTHER"
        val route = path.substringBefore('?').substringBefore('#').substringAfterLast('/').lowercase()
            .takeIf { it in setOf("info", "pair-setup", "pair-verify", "auth-setup", "fp-setup") } ?: "other"
        return "airplay control request method=$verb route=$route contentBytes=$bytes"
    }
}

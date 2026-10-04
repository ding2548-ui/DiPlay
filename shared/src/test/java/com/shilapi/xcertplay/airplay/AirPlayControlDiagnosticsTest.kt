package com.shilapi.xcertplay.airplay

import org.junit.Assert.*
import org.junit.Test

class AirPlayControlDiagnosticsTest {
    @Test fun identifiesNegotiationWithoutRequestHeadersQueriesOrBodyContents() {
        assertEquals("airplay control request method=POST route=pair-verify contentBytes=128",
            AirPlayControlDiagnostics.request("POST", "rtsp://192.168.49.1/pair-verify?token=secret", 128))
        assertEquals("airplay control request method=SETUP route=other contentBytes=64",
            AirPlayControlDiagnostics.request("SETUP", "rtsp://192.168.49.1/private-session", 64))
        assertEquals("airplay control request method=OTHER route=other contentBytes=0",
            AirPlayControlDiagnostics.request("Jane's iPhone\npassword=secret", "/private-secret", 0))
    }
}

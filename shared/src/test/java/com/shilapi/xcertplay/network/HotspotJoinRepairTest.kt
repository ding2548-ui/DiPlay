package com.shilapi.xcertplay.network

import org.junit.Assert.*
import org.junit.Test

class HotspotJoinRepairTest {
    @Test fun protocolAcceptsOnlyOneCompleteSafeReplyAndCommandsContainNoConfiguration() {
        val token = "12345678-1234-1234-1234-123456789abc"
        val reply = "DIPLAY_HOTSPOT_JOIN_V1|APPLIED|$token|1"
        assertEquals(HotspotJoinRepair.Code.APPLIED, HotspotJoinRepair.parse(reply).code)
        assertEquals(token, HotspotJoinRepair.parse(reply).token)
        for (bad in listOf("APPLIED", "$reply\n$reply", "$reply\nextra", "DIPLAY_HOTSPOT_JOIN_V1|READY|password|0")) {
            assertEquals(HotspotJoinRepair.Code.UNKNOWN, HotspotJoinRepair.parse(bad).code)
        }
        val command = HotspotJoinRepair.command("/data/app/with'quote/base.apk", "com.shihab.diplay",
            "apply", token, token)
        assertTrue(command.contains("app_process"))
        assertTrue(command.contains("'\"'\"'"))
        assertFalse(command.contains("setSoftApConfiguration"))
        try { HotspotJoinRepair.command("a", "bad;id", "apply", token, token); fail() }
        catch (_: IllegalArgumentException) { }
    }
}

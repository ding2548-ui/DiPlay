package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

class AdbClusterRouterTest {
    private fun record(id: Int = 7, size: String = "1920 x 720", owner: String = "com.xdja.containerservice") =
        "mBaseDisplayInfo=DisplayInfo{\"fission_bg_xdjaVirtualSurface, displayId $id\", real $size, owner $owner (uid 1000)}"

    @Test fun usesCurrentLogicalDisplayIdRatherThanLayerStackOrAssumedOne() {
        val dump = "mCurrentLayerStack=1\nDisplay 7:\n" + record() + "\nmOverrideDisplayInfo=" + record()
        assertEquals(7, AdbClusterRouter.displayId(dump))
    }
    @Test fun rejectsMainPassengerWrongGeometryAndAmbiguousTargets() {
        assertNull(AdbClusterRouter.displayId(record(0)))
        assertNull(AdbClusterRouter.displayId(record(size = "1280 x 720")))
        assertNull(AdbClusterRouter.displayId(record(owner = "com.passenger")))
        assertNull(AdbClusterRouter.displayId(record() + "\n" + record(8)))
        assertNull(AdbClusterRouter.displayId(record().replace("mBaseDisplayInfo", "mOverrideDisplayInfo")))
    }
    @Test fun directLaunchUsesIndependentTaskAndRejectsMainDisplay() {
        val token = "01234567-89ab-cdef-0123-456789abcdef"
        val command = AdbClusterRouter.launchCommand("com.shihab.diplay.hudtest", 7, token)
        assertTrue(command.startsWith("am start-activity --display 7 -f 0x18000000 "))
        assertTrue(command.endsWith("--es cluster_launch_token $token"))
        assertTrue(runCatching { AdbClusterRouter.launchCommand("com.shihab.diplay", 0, token) }.isFailure)
        assertTrue(runCatching { AdbClusterRouter.launchCommand("bad;command", 7, token) }.isFailure)
        assertTrue(runCatching { AdbClusterRouter.launchCommand("com.shihab.diplay", 7, "bad") }.isFailure)
        assertFalse(AdbClusterRouter.accepted("Starting: Intent {}\nError: Permission Denial"))
        assertFalse(AdbClusterRouter.accepted(""))
        assertTrue(AdbClusterRouter.accepted("Starting: Intent {}"))
    }
    @Test fun verifiesOnlyExactTaskInsidePerDisplayHistory() {
        val pkg = "com.shihab.diplay.hudtest"
        val record = "    * Hist #0: ActivityRecord{abc u0 $pkg/com.shilapi.xcertplay.AdbClusterActivity t12}"
        val dump = "Display #7 (activities from top to bottom):\n$record\nResumedActivity: $record"
        assertEquals(7, AdbClusterRouter.activityDisplay(dump, pkg, 12))
        assertNull(AdbClusterRouter.activityDisplay(dump, pkg, 13))
        assertNull(AdbClusterRouter.activityDisplay(dump.replace("Display #7", "Display #0"), pkg, 12))
        assertNull(AdbClusterRouter.activityDisplay("ResumedActivity: $record", pkg, 12))
        assertNull(AdbClusterRouter.activityDisplay(dump.replace(pkg, "other.package"), pkg, 12))
    }
}

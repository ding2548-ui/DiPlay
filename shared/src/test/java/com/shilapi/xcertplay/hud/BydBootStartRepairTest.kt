package com.shilapi.xcertplay.hud

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BydBootStartRepairTest {
    @Test fun whitelistRequiresTheRequestedPackageEntry() {
        val pkg = "com.shilapi.xcertplay"
        assertTrue(BydBootStartRepair.isWhitelisted("user,$pkg,10234\n", pkg))
        assertTrue(BydBootStartRepair.isWhitelisted("system,$pkg,10234", pkg))
        assertFalse(BydBootStartRepair.isWhitelisted("user,$pkg.debug,10234", pkg))
        assertFalse(BydBootStartRepair.isWhitelisted("Permission denied for $pkg", pkg))
        assertFalse(BydBootStartRepair.isWhitelisted(null, pkg))
    }
}

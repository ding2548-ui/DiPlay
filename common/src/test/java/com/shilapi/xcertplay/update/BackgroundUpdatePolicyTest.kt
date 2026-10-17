package com.shilapi.xcertplay.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundUpdatePolicyTest {
    @Test fun anInactiveStoredSessionIsBusy() {
        assertTrue(BackgroundUpdatePolicy.connectionBusy(hasSession = true, active = false))
    }

    @Test fun aStableActiveSessionAllowsTheSmallMetadataCheck() {
        assertFalse(BackgroundUpdatePolicy.connectionBusy(hasSession = true, active = true))
    }

    @Test fun havingNoSessionDoesNotBlockTheCheck() {
        assertFalse(BackgroundUpdatePolicy.connectionBusy(hasSession = false, active = false))
    }
}

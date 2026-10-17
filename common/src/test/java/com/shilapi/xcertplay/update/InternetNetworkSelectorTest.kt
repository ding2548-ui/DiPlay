package com.shilapi.xcertplay.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class InternetNetworkSelectorTest {
    @Test fun requiresBothInternetAndValidation() {
        assertFalse(InternetNetworkSelector.isValidatedInternet(hasInternet = true, validated = false))
        assertTrue(InternetNetworkSelector.isValidatedInternet(hasInternet = true, validated = true))
    }
}

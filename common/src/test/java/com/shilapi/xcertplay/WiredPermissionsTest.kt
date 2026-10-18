package com.shilapi.xcertplay

import android.Manifest
import org.junit.Assert.assertEquals
import org.junit.Test

class WiredPermissionsTest {
    @Test fun wiredStartsRequestLocalNetworkOnlyOnAndroid17() {
        assertEquals(listOf(Manifest.permission.ACCESS_LOCAL_NETWORK), WiredPermissions.required(37))
        assertEquals(emptyList<String>(), WiredPermissions.required(36))
        assertEquals(emptyList<String>(), WiredPermissions.required(25))
    }
}

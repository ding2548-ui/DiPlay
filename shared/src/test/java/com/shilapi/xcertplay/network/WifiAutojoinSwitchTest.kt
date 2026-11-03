package com.shilapi.xcertplay.network

import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Fakes shaped like each release's IWifiManager proxy, called the way the shell helper calls them. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class WifiAutojoinSwitchTest {
    interface BooleanListener {
        fun asBinder(): IBinder
    }

    class Android10Wifi {
        val calls = mutableListOf<Boolean>()
        fun enableWifiConnectivityManager(enabled: Boolean) { calls += enabled }
    }

    class Android11Wifi {
        var allowed = true
        fun allowAutojoinGlobal(choice: Boolean) { allowed = choice }
    }

    class Android14Wifi(var allowed: Boolean = true) {
        var ignoreChanges = false
        var deny = false
        var answer = true
        val packages = mutableListOf<String>()
        var extras: Bundle? = null

        fun allowAutojoinGlobal(choice: Boolean, packageName: String, extras: Bundle) {
            if (deny) throw SecurityException("Uid 2000 is not allowed to set wifi global autojoin")
            packages += packageName
            this.extras = extras
            if (!ignoreChanges) allowed = choice
        }

        fun queryAutojoinGlobal(listener: BooleanListener) {
            if (deny) throw SecurityException("Uid 2000 is not allowed to get wifi global autojoin")
            if (!answer) return
            val data = Parcel.obtain()
            try {
                data.writeInterfaceToken(BooleanListener::class.java.name)
                data.writeInt(if (allowed) 1 else 0)
                data.setDataPosition(0)
                listener.asBinder().transact(IBinder.FIRST_CALL_TRANSACTION, data, null, IBinder.FLAG_ONEWAY)
            } finally {
                data.recycle()
            }
        }
    }

    private fun switch(wifi: Any) = WifiAutojoinSwitch(wifi, BooleanListener::class.java, queryTimeoutMillis = 200)

    @Test
    fun android10UsesTheConnectivityManagerSwitch() {
        val wifi = Android10Wifi()
        val switch = switch(wifi)
        assertEquals("enableWifiConnectivityManager", switch.method)
        assertEquals(WifiScanSwitchResult.DONE, switch.set(false))
        assertEquals(WifiScanSwitchResult.DONE, switch.set(true))
        assertEquals(listOf(false, true), wifi.calls)
    }

    @Test
    fun android11To13SwitchAutojoinWithoutAQueryBefore13() {
        val wifi = Android11Wifi()
        val switch = switch(wifi)
        assertEquals("allowAutojoinGlobal", switch.method)
        assertNull(switch.read())
        assertEquals(WifiScanSwitchResult.DONE, switch.set(false))
        assertEquals(false, wifi.allowed)
        assertEquals(WifiScanSwitchResult.DONE, switch.set(true))
        assertEquals(true, wifi.allowed)
    }

    @Test
    fun android14NamesTheShellAndReadsTheChangeBack() {
        val wifi = Android14Wifi()
        val switch = switch(wifi)
        assertEquals(true, switch.read())
        assertEquals(WifiScanSwitchResult.DONE, switch.set(false))
        assertEquals(false, wifi.allowed)
        assertEquals(listOf("com.android.shell"), wifi.packages)
        assertTrue(wifi.extras!!.isEmpty)
        assertEquals(WifiScanSwitchResult.DONE, switch.set(true))
        assertEquals(true, wifi.allowed)
    }

    @Test
    fun autojoinSomeoneElseTurnedOffIsLeftAlone() {
        val wifi = Android14Wifi(allowed = false)
        assertEquals(WifiScanSwitchResult.ALREADY_OFF, switch(wifi).set(false))
        assertTrue(wifi.packages.isEmpty())
    }

    @Test
    fun aChangeTheFrameworkIgnoredIsNotReportedAsDone() {
        val wifi = Android14Wifi().apply { ignoreChanges = true }
        assertEquals(WifiScanSwitchResult.UNKNOWN, switch(wifi).set(false))
    }

    @Test
    fun aRefusedCallerChangesNothing() {
        val wifi = Android14Wifi().apply { deny = true }
        assertEquals(WifiScanSwitchResult.DENIED, switch(wifi).set(false))
        assertEquals(true, wifi.allowed)
    }

    @Test
    fun anUnansweredQueryOnlySkipsTheCheck() {
        val wifi = Android14Wifi().apply { answer = false }
        assertEquals(WifiScanSwitchResult.DONE, switch(wifi).set(false))
        assertEquals(false, wifi.allowed)
    }

    @Test
    fun firmwareWithoutEitherMethodIsUnsupported() {
        val switch = switch(Any())
        assertNull(switch.method)
        assertEquals(WifiScanSwitchResult.UNSUPPORTED, switch.set(false))
    }
}

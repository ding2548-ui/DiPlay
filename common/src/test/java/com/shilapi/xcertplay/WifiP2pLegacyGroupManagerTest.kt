package com.shilapi.xcertplay

import android.Manifest
import android.content.Context
import android.net.wifi.SupplicantState
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import com.shilapi.xcertplay.network.WifiP2pGroupManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowWifiP2pManager
import org.robolectric.util.ReflectionHelpers
import java.net.InetAddress
import java.util.concurrent.ExecutionException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE,
    shadows = [WifiP2pLegacyGroupManagerTest.P2pRadio::class, WifiP2pLegacyGroupManagerTest.P2pChannel::class])
class WifiP2pLegacyGroupManagerTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val radio get() = shadowOf(context.getSystemService(WifiP2pManager::class.java)) as P2pRadio
    private val memory get() = context.getSharedPreferences("carplay_wifi_p2p_success", Context.MODE_PRIVATE)

    @Before fun setup() {
        shadowOf(context).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val wifi = context.getSystemService(WifiManager::class.java)
        wifi.isWifiEnabled = true
        shadowOf(wifi.connectionInfo).setFrequency(5180)
        shadowOf(wifi.connectionInfo).setSupplicantState(SupplicantState.COMPLETED)
    }

    @Test fun selectedChannelIsRequestedThroughTheHiddenApiAndReportedUnverified() {
        val logs = mutableListOf<String>()
        WifiP2pGroupManager(context, logs::add, preferredChannel = 149).use { manager ->
            val info = background { manager.start(5000) }
            assertEquals(6, radio.listeningChannel)
            assertEquals(149, radio.operatingChannel)
            assertEquals(1, radio.systemCreations)
            assertEquals("DIRECT-legacy-test", info.ssid)
            assertEquals(149, info.channel)
            assertEquals(5745, info.frequencyMHz)
            assertEquals("5 GHz", info.bandLabel)
            assertTrue(logs.any { it.contains("legacy channel applied channel=149 frequencyMHz=5745") })
            assertTrue(logs.any { it.contains("verified=false") })
            assertTrue(logs.any { it.contains("actualMHz=unavailable matched=unverified") })
            manager.onCarPlayConfirmed()
        }
        assertNull(memory.getString("confirmed", null))
    }

    @Test fun automaticStartPinsFiveGhzAndKeepsItsChannelUnknownWhenTheFrameworkRefuses() {
        radio.rejectChannels = true
        val logs = mutableListOf<String>()
        WifiP2pGroupManager(context, logs::add).use { manager ->
            val info = background { manager.start(8000) }
            assertEquals(1, radio.systemCreations)
            assertEquals(0, info.channel)
            assertNull(info.frequencyMHz)
            assertEquals("Auto", info.bandLabel)
            assertTrue(logs.any { it.contains("legacy channel rejected code=") })
        }
    }

    @Test fun rejectedSelectedChannelFailsInsteadOfFallingBackToTwoGhz() {
        radio.rejectChannels = true
        WifiP2pGroupManager(context, preferredChannel = 149).use { manager ->
            val error = failure { manager.start(5000) }
            assertTrue(error.message!!.contains("channel 149"))
        }
        assertEquals(0, radio.systemCreations)
        assertNull(memory.getString("confirmed", null))
    }

    @Test fun defaultRetryClearsTheSuccessfulPinFromRejectedCreations() {
        radio.rejectPinnedCreation = true
        WifiP2pGroupManager(context).use { manager ->
            assertEquals(0, background { manager.start(8000) }.channel)
            assertEquals(0, radio.operatingChannel)
            assertEquals(0, radio.channelRequests.last())
        }
        assertEquals(6, radio.systemCreations)
        assertEquals(1, radio.channelRequests.count { it == 0 })
    }

    @Test fun closingOurGroupReleasesOnlyOurRestriction() {
        val manager = WifiP2pGroupManager(context, preferredChannel = 149)
        background { manager.start(5000) }
        background { manager.close() }
        assertEquals(listOf(149, 0), radio.channelRequests)
        assertEquals(1, radio.removals)
    }

    @Test fun closingAfterAReplacementPreservesItsGroupAndChannelState() {
        val logs = mutableListOf<String>()
        val manager = WifiP2pGroupManager(context, logs::add, preferredChannel = 149)
        background { manager.start(5000) }
        val replacement = radio.makeGroup("DIRECT-another-app")
        radio.group = replacement
        background { manager.close() }
        assertSame(replacement, radio.group)
        assertEquals(0, radio.removals)
        assertEquals(listOf(149), radio.channelRequests)
        assertTrue(logs.any { it.contains("legacy channel cleanup skipped=another_app_owns_group") })
    }

    @Test fun failedClearStopsDefaultCreationAndDoesNotClaimRestoration() {
        radio.rejectPinnedCreation = true
        radio.rejectClear = true
        val logs = mutableListOf<String>()
        WifiP2pGroupManager(context, logs::add).use { manager ->
            assertTrue(failure { manager.start(8000) }.message!!.contains("clear its previous"))
        }
        assertEquals(5, radio.systemCreations)
        assertTrue(logs.any { it.contains("restriction cleared=false") })
        assertTrue(logs.none { it.contains("restriction cleared=true") })
    }

    @Test fun unansweredSelectionStopsWithoutRetryAndLateCallbackCannotRepin() {
        radio.deferSelection = true
        WifiP2pGroupManager(context, preferredChannel = 149).use { manager ->
            assertTrue(failure { manager.start(5000) }.message!!.contains("did not respond"))
            assertEquals(listOf(149, 0), radio.channelRequests)
            assertEquals(0, radio.systemCreations)
            radio.deferredListener!!.onSuccess()
            assertEquals(0, radio.operatingChannel)
            assertEquals(listOf(149, 0), radio.channelRequests)
        }
    }

    @Test fun closeDuringSelectionWaitsForCompensationBeforeClosingTheChannel() {
        radio.deferSelection = true
        val manager = WifiP2pGroupManager(context, preferredChannel = 149)
        val workers = Executors.newFixedThreadPool(2)
        try {
            val starting = workers.submit<Any> { manager.start(5000) }
            assertTrue(radio.selectionIssued.await(2, TimeUnit.SECONDS))
            val closing = workers.submit { manager.close() }
            val deadline = System.nanoTime() + 1_000_000_000L
            while (!ReflectionHelpers.getField<Boolean>(manager, "closed") && System.nanoTime() < deadline) {
                Thread.sleep(1)
            }
            assertTrue(ReflectionHelpers.getField(manager, "closed"))
            radio.deferredListener!!.onSuccess()
            closing.get(5, TimeUnit.SECONDS)
            try { starting.get(5, TimeUnit.SECONDS); fail("Closed startup must fail") }
            catch (_: ExecutionException) { }
            assertEquals(0, radio.systemCreations)
            assertEquals(listOf(149, 0), radio.channelRequests)
        } finally { workers.shutdownNow(); manager.close() }
    }

    @Test fun interruptedSelectionStillCompensatesAndPreservesTheInterrupt() {
        radio.deferSelection = true
        val manager = WifiP2pGroupManager(context, preferredChannel = 149)
        val interrupted = AtomicBoolean(false)
        val starting = Thread {
            try { manager.start(5000); fail("Interrupted startup must fail") }
            catch (_: java.io.IOException) { interrupted.set(Thread.currentThread().isInterrupted) }
        }
        try {
            starting.start()
            assertTrue(radio.selectionIssued.await(2, TimeUnit.SECONDS))
            starting.interrupt()
            starting.join(5000)
            assertTrue(!starting.isAlive)
            assertTrue(interrupted.get())
            assertEquals(listOf(149, 0), radio.channelRequests)
            assertEquals(0, radio.systemCreations)
        } finally { manager.close() }
    }

    @Test fun missingExactGroupInterfaceStopsAndRemovesOnlyOurGroup() {
        radio.missingInterface = true
        WifiP2pGroupManager(context, preferredChannel = 149).use { manager ->
            assertTrue(failure { manager.start(300) }.message!!.contains("usable Wi-Fi P2P group"))
        }
        assertEquals(1, radio.systemCreations)
        assertEquals(1, radio.removals)
        assertEquals(listOf(149, 0), radio.channelRequests)
    }

    @Test fun invokedSetterThrowingAfterMutationStopsAndCompensates() {
        radio.throwAfterSelection = true
        WifiP2pGroupManager(context, preferredChannel = 149).use { manager ->
            assertTrue(failure { manager.start(3000) }.message!!.contains("unknown outcome"))
        }
        assertEquals(0, radio.systemCreations)
        assertEquals(listOf(149, 0), radio.channelRequests)
        assertEquals(0, radio.operatingChannel)
    }

    private fun failure(block: () -> Any): Throwable {
        try { background(block); fail("Expected failure") }
        catch (failure: ExecutionException) { return failure.cause!! }
        error("unreachable")
    }

    private fun <T> background(block: () -> T): T {
        val executor = Executors.newSingleThreadExecutor()
        return try { executor.submit<T> { block() }.get(20, TimeUnit.SECONDS) }
        finally { executor.shutdownNow() }
    }

    @Implements(WifiP2pManager.Channel::class)
    class P2pChannel {
        @Implementation protected fun close() {}
    }

    @Implements(WifiP2pManager::class)
    class P2pRadio : ShadowWifiP2pManager() {
        var rejectChannels = false
        var systemCreations = 0
        var group: WifiP2pGroup? = null
        var removals = 0
        val channelRequests = mutableListOf<Int>()
        var rejectPinnedCreation = false
        var rejectClear = false
        var deferSelection = false
        var missingInterface = false
        var throwAfterSelection = false
        @Volatile var deferredListener: WifiP2pManager.ActionListener? = null
        val selectionIssued = CountDownLatch(1)

        @Implementation override fun setWifiP2pChannels(
            channel: WifiP2pManager.Channel,
            listenChannel: Int,
            operatingChannel: Int,
            listener: WifiP2pManager.ActionListener,
        ) {
            channelRequests.add(operatingChannel)
            if (rejectChannels || (rejectClear && operatingChannel == 0)) {
                listener.onFailure(WifiP2pManager.ERROR)
                return
            }
            super.setWifiP2pChannels(channel, listenChannel, operatingChannel, listener)
            if (throwAfterSelection && operatingChannel != 0) throw IllegalStateException("vendor failed after mutation")
            if (deferSelection && operatingChannel != 0) {
                deferredListener = listener
                selectionIssued.countDown()
            } else listener.onSuccess()
        }

        @Implementation override fun requestGroupInfo(
            channel: WifiP2pManager.Channel,
            listener: WifiP2pManager.GroupInfoListener,
        ) {
            listener.onGroupInfoAvailable(group)
        }

        @Implementation override fun requestConnectionInfo(
            channel: WifiP2pManager.Channel,
            listener: WifiP2pManager.ConnectionInfoListener,
        ) {
            listener.onConnectionInfoAvailable(WifiP2pInfo().apply {
                groupFormed = true
                isGroupOwner = true
                groupOwnerAddress = InetAddress.getByName("192.168.49.1")
            })
        }

        @Implementation override fun createGroup(
            channel: WifiP2pManager.Channel,
            listener: WifiP2pManager.ActionListener,
        ) {
            systemCreations++
            if (rejectPinnedCreation && operatingChannel != 0) {
                listener.onFailure(WifiP2pManager.ERROR)
                return
            }
            group = makeGroup()
            listener.onSuccess()
        }

        @Implementation override fun createGroup(
            channel: WifiP2pManager.Channel,
            config: WifiP2pConfig?,
            listener: WifiP2pManager.ActionListener,
        ) {
            group = makeGroup()
            listener.onSuccess()
        }

        @Implementation override fun removeGroup(
            channel: WifiP2pManager.Channel,
            listener: WifiP2pManager.ActionListener,
        ) {
            removals++
            group = null
            listener.onSuccess()
        }

        fun makeGroup(name: String = "DIRECT-legacy-test") = WifiP2pGroup().apply {
            shadowOf(this).setIsGroupOwner(true)
            shadowOf(this).setNetworkName(name)
            shadowOf(this).setPassphrase("legacy-passphrase")
            shadowOf(this).setInterface(if (missingInterface) "" else "p2p0")
        }
    }
}

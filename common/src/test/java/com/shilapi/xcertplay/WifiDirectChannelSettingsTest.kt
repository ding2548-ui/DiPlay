package com.shilapi.xcertplay

import android.app.AlertDialog
import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.network.WifiP2pChannels
import com.shilapi.xcertplay.orchestration.CarPlayRuntimeConfig
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], qualifiers = "en", manifest = Config.NONE)
class WifiDirectChannelSettingsTest {
    private lateinit var activity: DiPlayActivity
    private val prefs get() = RuntimeEnvironment.getApplication()
        .getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE)

    @Before fun setup() {
        prefs.edit().clear().commit()
        activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        AirPlayPersistence.saveWirelessHotspotMode(activity, WirelessHotspotMode.WIFI_P2P)
    }

    @Test fun defaultIsAutoAndCancelDoesNotSavePreviewedChannel() {
        assertEquals(WifiP2pChannels.AUTO, AirPlayPersistence.loadWifiP2pPreferredChannel(activity))
        val control = channelControl(controls())!!
        assertTrue(control.text.toString().endsWith("Auto"))
        control.performClick()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertEquals(0, dialog.listView.checkedItemPosition)
        select(dialog, 149)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(WifiP2pChannels.AUTO, AirPlayPersistence.loadWifiP2pPreferredChannel(activity))
        assertTrue(control.text.toString().endsWith("Auto"))
    }

    @Test fun savedChannelSurvivesModeChangesAndCanBeResetToAuto() {
        val control = channelControl(controls())!!
        control.performClick()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        select(dialog, 149)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(149, AirPlayPersistence.loadWifiP2pPreferredChannel(activity))
        assertTrue(control.text.toString().contains("149"))
        AirPlayPersistence.saveWirelessHotspotMode(activity, WirelessHotspotMode.MANUAL)
        assertNull(channelControl(controls()))
        assertEquals(149, AirPlayPersistence.loadWifiP2pPreferredChannel(activity))
        AirPlayPersistence.saveWirelessHotspotMode(activity, WirelessHotspotMode.WIFI_P2P)
        channelControl(controls())!!.performClick()
        val reopened = ShadowAlertDialog.getLatestAlertDialog()
        assertEquals(choices.indexOf(149), reopened.listView.checkedItemPosition)
        select(reopened, WifiP2pChannels.AUTO)
        reopened.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(WifiP2pChannels.AUTO, AirPlayPersistence.loadWifiP2pPreferredChannel(activity))
    }

    @Test fun invalidOrCorruptSavedChannelsUseAuto() {
        for (channel in listOf(-1, 12, 14, 52, 100, 196)) {
            prefs.edit().putInt("wifi_p2p_preferred_channel", channel).commit()
            assertEquals(WifiP2pChannels.AUTO, AirPlayPersistence.loadWifiP2pPreferredChannel(activity))
        }
        prefs.edit().putString("wifi_p2p_preferred_channel", "149").commit()
        assertEquals(WifiP2pChannels.AUTO, AirPlayPersistence.loadWifiP2pPreferredChannel(activity))
    }

    @Test fun hostRuntimeConfigReceivesSavedChannel() {
        AirPlayPersistence.saveWifiP2pPreferredChannel(activity, 149)
        val host = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        CarPlayHostActivity::class.java.getDeclaredField("airPlayIdentity").apply { isAccessible = true }
            .set(host, AirPlayPersistence.loadIdentity(host))
        val config = CarPlayHostActivity::class.java.getDeclaredMethod("createRuntimeConfig")
            .apply { isAccessible = true }.invoke(host) as CarPlayRuntimeConfig
        assertEquals(149, config.wifiP2pPreferredChannel)
    }

    private val choices get() = listOf(WifiP2pChannels.AUTO) + WifiP2pChannels.channels

    private fun select(dialog: AlertDialog, channel: Int) {
        val index = choices.indexOf(channel)
        dialog.listView.performItemClick(null, index, index.toLong())
    }

    private fun controls() = LinearLayout(activity).also {
        DiPlayActivity::class.java.getDeclaredMethod("wirelessLinkControls", LinearLayout::class.java)
            .apply { isAccessible = true }.invoke(activity, it)
    }

    private fun channelControl(view: View): Button? {
        if (view is Button && view.text.startsWith(activity.getString(R.string.wifi_direct_channel_summary, ""))) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            channelControl(view.getChildAt(index))?.let { return it }
        }
        return null
    }
}

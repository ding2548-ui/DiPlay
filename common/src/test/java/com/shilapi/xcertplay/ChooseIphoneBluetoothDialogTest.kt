package com.shilapi.xcertplay

import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowBluetoothAdapter
import org.robolectric.util.ReflectionHelpers

/** What "Choose iPhone" says when Android's Bluetooth is missing, off, or will not stay on. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en-w1000dp-h700dp-land")
class ChooseIphoneBluetoothDialogTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private var activity: DiPlayActivity? = null

    @After fun tearDown() {
        activity?.finish()
        ShadowBluetoothAdapter.setIsBluetoothSupported(true)
        AppAppearanceRuntime.resetForTest()
        context.getSharedPreferences("diplay", 0).edit().clear().commit()
        context.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
    }

    private fun open(): DiPlayActivity =
        Robolectric.buildActivity(DiPlayActivity::class.java).setup().get().also { activity = it }

    private fun choosePhone(screen: DiPlayActivity): AlertDialog {
        ReflectionHelpers.callInstanceMethod<Unit>(screen, "choosePhone")
        shadowOf(Looper.getMainLooper()).idle()
        return ShadowAlertDialog.getLatestAlertDialog()
    }

    private fun texts(dialog: AlertDialog): List<String> = descendants(dialog.window!!.decorView)
        .filterIsInstance<TextView>().map { it.text.toString() }.filter { it.isNotEmpty() }.toList()

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }

    // Issue 703's screen: the car's own Bluetooth is on, Android has none, and DiPlay asked to turn it on.
    @Test fun aHeadUnitWithoutAndroidBluetoothIsToldSoInsteadOfBeingSentToASwitch() {
        ShadowBluetoothAdapter.setIsBluetoothSupported(false)
        val screen = open()
        val dialog = choosePhone(screen)
        val shown = texts(dialog)
        assertTrue(shown.toString(), screen.getString(R.string.bluetooth_cannot_open_connections) in shown)
        assertFalse(shown.toString(), screen.getString(R.string.turn_on_bluetooth) in shown)
        assertFalse(shown.toString(), screen.getString(R.string.enable_the_car_s_bluetooth_and_pair_your_iphone_first) in shown)
        assertEquals(screen.getString(R.string.got_it), dialog.getButton(AlertDialog.BUTTON_POSITIVE).text.toString())
        assertEquals(View.GONE, dialog.getButton(AlertDialog.BUTTON_NEGATIVE).visibility)
    }

    @Test fun bluetoothThatIsOffIsAskedForAsBefore() {
        shadowOf(BluetoothAdapter.getDefaultAdapter()).setEnabled(false)
        val screen = open()
        val dialog = choosePhone(screen)
        val shown = texts(dialog)
        assertTrue(shown.toString(), screen.getString(R.string.turn_on_bluetooth) in shown)
        assertTrue(shown.toString(), screen.getString(R.string.enable_the_car_s_bluetooth_and_pair_your_iphone_first) in shown)
        assertEquals(screen.getString(R.string.open_bluetooth), dialog.getButton(AlertDialog.BUTTON_POSITIVE).text.toString())
    }

    // Issue 581's head unit: the switch in Android's settings turns itself off again.
    @Test fun bluetoothStillOffAfterAVisitToItsSettingsGetsTheHint() {
        shadowOf(BluetoothAdapter.getDefaultAdapter()).setEnabled(false)
        val screen = open()
        val hint = screen.getString(R.string.bluetooth_will_not_stay_on_hint)
        val first = choosePhone(screen)
        assertFalse(texts(first).toString(), texts(first).any { hint in it })
        first.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()

        val second = choosePhone(screen)
        val message = texts(second).single { screen.getString(R.string.enable_the_car_s_bluetooth_and_pair_your_iphone_first) in it }
        assertTrue(message, message.endsWith(hint))
        assertEquals(screen.getString(R.string.open_bluetooth), second.getButton(AlertDialog.BUTTON_POSITIVE).text.toString())
    }

    // Declining the dialog is not a visit to the settings: nothing suggests the switch failed.
    @Test fun choosingLaterDoesNotBringTheHint() {
        shadowOf(BluetoothAdapter.getDefaultAdapter()).setEnabled(false)
        val screen = open()
        choosePhone(screen).getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val again = texts(choosePhone(screen))
        assertFalse(again.toString(), again.any { screen.getString(R.string.bluetooth_will_not_stay_on_hint) in it })
    }

    @Test fun bluetoothThatIsOnGoesOnToPairing() {
        shadowOf(BluetoothAdapter.getDefaultAdapter()).setEnabled(true)
        val screen = open()
        val shown = texts(choosePhone(screen))
        assertTrue(shown.toString(), screen.getString(R.string.pair_your_iphone) in shown)
        assertFalse(shown.toString(), screen.getString(R.string.turn_on_bluetooth) in shown)
    }
}

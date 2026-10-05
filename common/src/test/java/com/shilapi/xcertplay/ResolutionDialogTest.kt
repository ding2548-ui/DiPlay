package com.shilapi.xcertplay
import android.app.AlertDialog
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.shilapi.xcertplay.airplay.CarPlayDisplayScale
import com.shilapi.xcertplay.host.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class ResolutionDialogTest {
    @Test @Config(qualifiers = "zh-rCN") fun chineseDialogs() = numericDialogsKeepNaturalHeightWhenResetting()
    @Test @Config(qualifiers = "ar") fun arabicDialogs() = numericDialogsKeepNaturalHeightWhenResetting()
    @Test @Config(qualifiers = "es") fun spanishDialogs() = numericDialogsKeepNaturalHeightWhenResetting()
    @Test @Config(qualifiers = "ru") fun russianDialogs() = numericDialogsKeepNaturalHeightWhenResetting()
    @Test @Config(qualifiers = "uk") fun ukrainianDialogs() = numericDialogsKeepNaturalHeightWhenResetting()
    @Test fun numericDialogsKeepNaturalHeightWhenResetting() {
        val a = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        val parent = LinearLayout(a)
        var saved = 56
        val integer = DiPlayActivity::class.java.declaredMethods.first { it.name == "resolutionSettingControl" }
        integer.isAccessible = true
        integer.invoke(a, parent, R.string.resolution, R.string.custom_resolution_hint,
            CarPlayDisplayScale.MIN_PERCENT..CarPlayDisplayScale.MAX_PERCENT, 100,
            R.string.custom_resolution_summary, { saved }, false, { value: Int -> saved = value })
        for (index in listOf(0)) {
            parent.getChildAt(index).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            val d = ShadowAlertDialog.getLatestAlertDialog()
            val decor = d.window!!.decorView
            fun measure(): Int {
                fun force(v: View) {
                    v.forceLayout()
                    if (v is ViewGroup) for (i in 0 until v.childCount) force(v.getChildAt(i))
                }
                repeat(3) {
                    force(decor)
                    decor.measure(View.MeasureSpec.makeMeasureSpec(620, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.AT_MOST))
                    decor.layout(0,0,620,decor.measuredHeight)
                }
                return decor.measuredHeight
            }
            fun findInput(v: View): EditText? {
                if (v is EditText) return v
                if (v is ViewGroup) for (i in 0 until v.childCount) {
                    findInput(v.getChildAt(i))?.let { return it }
                }
                return null
            }
            val input = findInput(decor)!!
            assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT,
                (input.parent as View).layoutParams.height)
            assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, d.window!!.attributes.height)
            val before = measure()
            d.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
            assertEquals("dialog $index reset must not change height",before,measure())
            assertEquals("100", input.text.toString())
            assertEquals(56, saved)
            input.setText("161")
            d.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            assertTrue(d.isShowing)
            assertNotNull(input.error)
            assertEquals(56, saved)
            input.setText("160")
            d.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            assertFalse(d.isShowing)
            assertEquals(160, saved)
            parent.getChildAt(index).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            val reset = ShadowAlertDialog.getLatestAlertDialog()
            assertEquals("160", findInput(reset.window!!.decorView)!!.text.toString())
            reset.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
            assertEquals(160, saved)
            reset.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            assertEquals(160, saved)
            parent.getChildAt(index).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            val applyDefault = ShadowAlertDialog.getLatestAlertDialog()
            applyDefault.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
            applyDefault.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            assertEquals(100, saved)
        }
    }
}

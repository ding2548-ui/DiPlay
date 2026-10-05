package com.shilapi.xcertplay
import android.app.AlertDialog
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.shilapi.xcertplay.host.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class NightModeDialogsTest {
    @Test @Config(qualifiers = "zh-rCN") fun chineseDialogs() = numericDialogsKeepNaturalHeightWhenResetting()
    @Test @Config(qualifiers = "ar") fun arabicDialogs() = numericDialogsKeepNaturalHeightWhenResetting()
    @Test @Config(qualifiers = "es") fun spanishDialogs() = numericDialogsKeepNaturalHeightWhenResetting()
    @Test @Config(qualifiers = "ru") fun russianDialogs() = numericDialogsKeepNaturalHeightWhenResetting()
    @Test @Config(qualifiers = "uk") fun ukrainianDialogs() = numericDialogsKeepNaturalHeightWhenResetting()
    @Test fun numericDialogsKeepNaturalHeightWhenResetting() {
        val a = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        val parent = LinearLayout(a)
        val integer = DiPlayActivity::class.java.declaredMethods.first { it.name == "nightDelaySettingControl" }
        integer.isAccessible = true
        val threshold = DiPlayActivity::class.java.getDeclaredMethod("ambientLightThresholdControl", LinearLayout::class.java)
        threshold.isAccessible = true
        threshold.invoke(a, parent)
        integer.invoke(a, parent, R.string.ambient_delay_title, R.string.ambient_delay_hint,
            0..60, 2, R.string.ambient_delay_summary, { 13 }, false, { _: Int -> })
        for (index in listOf(0,2)) {
            parent.getChildAt(index).performClick()
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
            d.dismiss()
        }
    }
}

package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.media.AmbientAlbumPalette
import kotlin.math.roundToInt

/**
 * The car's 31 ambient colors as tappable swatches, drawn with the stock settings' preview colors.
 * [single] picks one color; otherwise any number may be selected, but never none.
 */
internal class AmbientSwatchGrid(
    context: Context,
    private val accent: Int,
    initial: Set<Int>,
    var single: Boolean,
    private val onChanged: (Set<Int>) -> Unit,
    private val onLastDeselected: () -> Unit,
) : ViewGroup(context) {
    var selection: Set<Int> = initial
        private set
    private val swatches = HashMap<Int, FrameLayout>()
    private val density = context.resources.displayMetrics.density

    init {
        for (index in 1..COLORS) {
            val swatch = swatchView(context, index)
            swatches[index] = swatch
            addView(swatch)
        }
        refresh()
    }

    // Swatches wrap onto as many rows as the available width needs.
    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val available = MeasureSpec.getSize(widthSpec)
        var x = 0
        var y = 0
        var rowHeight = 0
        var widest = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            measureChildWithMargins(child, widthSpec, 0, heightSpec, 0)
            val lp = child.layoutParams as MarginLayoutParams
            val w = child.measuredWidth + lp.leftMargin + lp.rightMargin
            val h = child.measuredHeight + lp.topMargin + lp.bottomMargin
            if (x > 0 && x + w > available) {
                x = 0
                y += rowHeight
                rowHeight = 0
            }
            x += w
            widest = maxOf(widest, x)
            rowHeight = maxOf(rowHeight, h)
        }
        setMeasuredDimension(resolveSize(widest, widthSpec), resolveSize(y + rowHeight, heightSpec))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val available = right - left
        var x = 0
        var y = 0
        var rowHeight = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val lp = child.layoutParams as MarginLayoutParams
            val w = child.measuredWidth + lp.leftMargin + lp.rightMargin
            val h = child.measuredHeight + lp.topMargin + lp.bottomMargin
            if (x > 0 && x + w > available) {
                x = 0
                y += rowHeight
                rowHeight = 0
            }
            child.layout(x + lp.leftMargin, y + lp.topMargin, x + lp.leftMargin + child.measuredWidth, y + lp.topMargin + child.measuredHeight)
            x += w
            rowHeight = maxOf(rowHeight, h)
        }
    }

    override fun generateDefaultLayoutParams(): LayoutParams = MarginLayoutParams(-2, -2)
    override fun generateLayoutParams(attrs: android.util.AttributeSet?): LayoutParams = MarginLayoutParams(context, attrs)
    override fun generateLayoutParams(p: LayoutParams): LayoutParams = MarginLayoutParams(p)
    override fun checkLayoutParams(p: LayoutParams): Boolean = p is MarginLayoutParams

    fun setSelection(colors: Set<Int>) {
        selection = colors
        refresh()
    }

    fun setInteractive(enabled: Boolean) {
        isEnabled = enabled
        swatches.values.forEach { it.isEnabled = enabled }
        alpha = if (enabled) 1f else 0.4f
    }

    private fun swatchView(context: Context, index: Int): FrameLayout {
        val size = dp(SWATCH_DP)
        val check = TextView(context).apply {
            text = "✓"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(if (isLight(AmbientAlbumPalette.previewRgb(index))) Color.BLACK else Color.WHITE)
            tag = CHECK
        }
        return FrameLayout(context).apply {
            layoutParams = MarginLayoutParams(size, size).apply { setMargins(dp(MARGIN_DP), dp(MARGIN_DP), dp(MARGIN_DP), dp(MARGIN_DP)) }
            isClickable = true
            isFocusable = true
            addView(check, FrameLayout.LayoutParams(-1, -1))
            setOnClickListener { toggle(index) }
        }
    }

    private fun toggle(index: Int) {
        if (!isEnabled) return
        val next = when {
            single -> setOf(index)
            index in selection && selection.size == 1 -> { onLastDeselected(); return }
            index in selection -> selection - index
            else -> selection + index
        }
        if (next == selection) return
        selection = next
        refresh()
        onChanged(next)
    }

    private fun refresh() {
        for ((index, swatch) in swatches) {
            val selected = index in selection
            swatch.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(AmbientAlbumPalette.previewRgb(index))
                if (selected) setStroke(dp(3), accent) else setStroke(dp(1), UNSELECTED_RING)
            }
            swatch.findViewWithTag<View>(CHECK).visibility = if (selected) VISIBLE else INVISIBLE
            swatch.contentDescription = context.getString(
                if (selected) R.string.settings_ambient_swatch_selected else R.string.settings_ambient_swatch, index,
            )
        }
    }

    private fun isLight(rgb: Int): Boolean =
        (0.299 * Color.red(rgb) + 0.587 * Color.green(rgb) + 0.114 * Color.blue(rgb)) > 150

    private fun dp(value: Int) = (value * density).roundToInt()

    internal companion object {
        const val COLORS = 31
        private const val SWATCH_DP = 44
        private const val MARGIN_DP = 5
        private const val CHECK = "check"
        private const val UNSELECTED_RING = 0x55808080
    }
}

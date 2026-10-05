package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Color
import android.view.View
import android.widget.*
import com.shilapi.xcertplay.host.R

/** An in-activity overlay: no dimmed background, decoder restart, or stream negotiation. */
internal class CarPlayPicturePanel(context: Context, close: () -> Unit) : LinearLayout(context) {
    private val prefs = CarPlayPicture.preferences(context)
    private val controls = mutableMapOf<String, Pair<TextView, SeekBar>>()
    private val original = Switch(context)
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun text(value: String) = TextView(context).apply {
        text = value; textSize = 15f; setTextColor(Color.WHITE)
    }
    init {
        orientation = VERTICAL
        setPadding(dp(12), dp(8), dp(12), dp(8))
        setBackgroundColor(Color.argb(235, 12, 17, 27))
        isClickable = true
        addView(text(context.getString(R.string.picture_adjustments)))
        val rows = LinearLayout(context).apply { orientation = VERTICAL }
        val scroll = ScrollView(context).apply { addView(rows) }
        addView(scroll, LayoutParams(-1, 0, 1f))
        rows.addView(text(context.getString(R.string.picture_live_hint)))
        val labels = listOf(R.string.picture_brightness, R.string.picture_contrast,
            R.string.picture_saturation, R.string.picture_warmth)
        CarPlayPicture.keys.forEachIndexed { index, key ->
            val label = text("")
            val range = CarPlayPicture.range(key)
            val slider = SeekBar(context).apply {
                max = range.last - range.first
                progress = CarPlayPicture.value(prefs, key) - range.first
                contentDescription = context.getString(labels[index])
            }
            fun updateLabel(value: Int) { label.text = "${context.getString(labels[index])}: $value" }
            updateLabel(CarPlayPicture.value(prefs, key))
            slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seek: SeekBar, progress: Int, fromUser: Boolean) {
                    updateLabel(progress + range.first)
                    if (fromUser) prefs.edit().putInt(key, progress + range.first).apply()
                }
                override fun onStartTrackingTouch(seek: SeekBar) = Unit
                override fun onStopTrackingTouch(seek: SeekBar) = Unit
            })
            controls[key] = label to slider
            rows.addView(label)
            rows.addView(slider, LayoutParams(-1, dp(48)))
        }
        original.apply {
            text = context.getString(R.string.picture_show_original)
            setTextColor(Color.WHITE)
            setOnCheckedChangeListener { _, checked ->
                CarPlayPicture.showOriginal(checked)
                controls.values.forEach { it.second.isEnabled = !checked }
            }
        }
        addView(original, LayoutParams(-1, dp(48)))
        val actions = LinearLayout(context)
        actions.addView(Button(context).apply {
            text = context.getString(R.string.picture_reset); isAllCaps = false
            setOnClickListener {
                original.isChecked = false
                CarPlayPicture.reset(prefs)
                controls.forEach { (key, pair) ->
                    pair.second.progress = CarPlayPicture.defaultValue(key) - CarPlayPicture.range(key).first
                }
            }
        }, LayoutParams(0, dp(48), 1f))
        actions.addView(Button(context).apply {
            text = context.getString(R.string.picture_done); isAllCaps = false
            setOnClickListener { close() }
        }, LayoutParams(0, dp(48), 1f))
        addView(actions)
    }
}

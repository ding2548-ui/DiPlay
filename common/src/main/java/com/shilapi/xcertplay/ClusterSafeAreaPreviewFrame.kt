package com.shilapi.xcertplay

import android.content.Context
import android.view.View
import android.view.ViewGroup

/** Fits the whole 1920×720 editor inside the dialog's available preview space. */
internal class ClusterSafeAreaPreviewFrame(context: Context, val editor: SafeAreaEditorView) : ViewGroup(context) {
    init { addView(editor) }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = View.MeasureSpec.getSize(widthMeasureSpec)
        val height = View.MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(width, height)
        val fit = minOf(width / 1920f, height / 720f)
        editor.measure(View.MeasureSpec.makeMeasureSpec((1920 * fit).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((720 * fit).toInt(), View.MeasureSpec.EXACTLY))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val x = (width - editor.measuredWidth) / 2
        val y = (height - editor.measuredHeight) / 2
        editor.layout(x, y, x + editor.measuredWidth, y + editor.measuredHeight)
    }
}

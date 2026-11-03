package com.shilapi.xcertplay

import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout

/**
 * Shared region/crop math for every cluster-profile surface (centre card, dashboard activity).
 * Regions are normalized fractions of the surface canvas; the frame mapping is a TextureView
 * transform so FIT letterboxes and CROP fills regardless of buffer resolution.
 */
internal object ClusterProfileLayout {

    /** Positions [view] inside [layer] by the normalized [region]; skips no-op updates. */
    fun applyRegion(view: View?, region: ClusterScreenProfile.Region, layer: FrameLayout) {
        view ?: return
        if (layer.width <= 0 || layer.height <= 0) return
        val width = (layer.width * region.width).toInt().coerceIn(1, layer.width)
        val height = (layer.height * region.height).toInt().coerceIn(1, layer.height)
        val left = (layer.width * region.x).toInt().coerceIn(0, layer.width - width)
        val top = (layer.height * region.y).toInt().coerceIn(0, layer.height - height)
        val params = view.layoutParams
        if (params is FrameLayout.LayoutParams && params.width == width && params.height == height &&
            params.leftMargin == left && params.topMargin == top) return
        val next = FrameLayout.LayoutParams(width, height)
        next.leftMargin = left
        next.topMargin = top
        view.layoutParams = next
    }

    /** Removes region positioning: the view fills its layer again. */
    fun clearRegion(view: View?) {
        view ?: return
        val params = view.layoutParams
        if (params is FrameLayout.LayoutParams && params.width == ViewGroup.LayoutParams.MATCH_PARENT &&
            params.height == ViewGroup.LayoutParams.MATCH_PARENT && params.leftMargin == 0 &&
            params.topMargin == 0) return
        view.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    /** Maps the decoded frame into [view]: FIT letterboxes, CROP fills and clips.
     *  The TextureView stretches its buffer to the view by default, so the scale factors work
     *  purely in view space against the frame's aspect ratio. */
    fun applyTransform(view: TextureView?, frameAspect: Double, mode: ClusterScreenProfile.ScaleMode) {
        view ?: return
        if (view.width <= 0 || view.height <= 0) return
        if (!frameAspect.isFinite() || frameAspect <= 0) return
        val viewAspect = view.width.toDouble() / view.height.toDouble()
        var scaleX = 1f
        var scaleY = 1f
        when (mode) {
            ClusterScreenProfile.ScaleMode.FIT ->
                if (viewAspect > frameAspect) scaleX = (frameAspect / viewAspect).toFloat()
                else scaleY = (viewAspect / frameAspect).toFloat()
            ClusterScreenProfile.ScaleMode.CROP ->
                if (viewAspect > frameAspect) scaleY = (viewAspect / frameAspect).toFloat()
                else scaleX = (frameAspect / viewAspect).toFloat()
        }
        val matrix = android.graphics.Matrix()
        matrix.setScale(scaleX, scaleY, view.width / 2f, view.height / 2f)
        view.setTransform(matrix)
    }
}

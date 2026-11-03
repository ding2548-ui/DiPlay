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
     *  purely in view space against the frame's aspect ratio.
     *
     *  [crop] 限定源画面里保留的归一化区域：变换把该区域映射到视图上（FIT 按裁剪内容宽高比
     *  居中留黑边，CROP 铺满并裁掉溢出），被裁掉的部分不再参与显示——与副屏渲染器的
     *  「整幅放大 + 负 margin 只露裁剪区域」同一语义。null 或全幅时走原整幅映射。 */
    fun applyTransform(
        view: TextureView?,
        frameAspect: Double,
        mode: ClusterScreenProfile.ScaleMode,
        crop: ClusterScreenProfile.Region? = null,
        mask: CropMaskView? = null,
    ) {
        view ?: return
        if (view.width <= 0 || view.height <= 0) return
        val full = crop == null || (crop.x <= 0.001f && crop.y <= 0.001f &&
            crop.width >= 0.999f && crop.height >= 0.999f)
        if (full) {
            CropDiagLog.log(
                "applyTransform FULL view=${view.width}x${view.height} aspect=$frameAspect mode=$mode")
            applyWholeFrameTransform(view, frameAspect, mode)
            mask?.clear()
            return
        }
        val c = crop!!
        val zoom = when (mode) {
            ClusterScreenProfile.ScaleMode.FIT ->
                minOf(1f / c.width, 1f / c.height)
            ClusterScreenProfile.ScaleMode.CROP ->
                maxOf(1f / c.width, 1f / c.height)
        }
        val matrix = android.graphics.Matrix()
        matrix.setScale(zoom, zoom)
        matrix.postTranslate(
            (view.width - c.width * view.width * zoom) / 2f - c.x * view.width * zoom,
            (view.height - c.height * view.height * zoom) / 2f - c.y * view.height * zoom)
        view.setTransform(matrix)
        // 内容矩形：FIT 时小于 view，两侧是整幅画面内部的相邻内容——遮罩涂黑，而不是露出来。
        val contentW = c.width * view.width * zoom
        val contentH = c.height * view.height * zoom
        val contentL = (view.width - contentW) / 2f
        val contentT = (view.height - contentH) / 2f
        mask?.setGeometry(contentL, contentT, contentL + contentW, contentT + contentH)
        CropDiagLog.log(
            "applyTransform crop view=${view.width}x${view.height} mode=$mode crop=$crop zoom=$zoom " +
                "content=[$contentL,$contentT,${contentL + contentW},${contentT + contentH}]")
    }

    private fun applyWholeFrameTransform(
        view: TextureView,
        frameAspect: Double,
        mode: ClusterScreenProfile.ScaleMode,
    ) {
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

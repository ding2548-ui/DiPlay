package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

/**
 * 裁剪黑边遮罩：盖在视频画面之上、其余控件之下，只露出内容矩形，矩形外涂黑。
 *
 * 解决「适配（FIT）+ 裁剪≠全屏」时留边露出相邻画面的问题：SurfaceView/TextureView 装的是
 * 整幅解码画面，FIT 缩放后内容矩形居中，矩形两侧落在画面内部——显示的是裁剪框旁边的相邻
 * 内容而不是黑边（裁剪=全屏时两侧在画面之外，天然是黑底，所以此前没暴露）。三个输出端
 * （副屏渲染器 / 仪表盘 / 中控卡）与主屏编辑器预览共用。
 *
 * 坐标是遮罩自身（即内容矩形容器）的 view 坐标；[setGeometry] 传内容矩形，矩形外的四条
 * 边被涂黑，尺寸越界自动收敛；内容铺满或矩形无效时不画。
 */
class CropMaskView(context: Context) : View(context) {

    private val paint = Paint().apply { color = Color.BLACK }
    private var left = 0f
    private var top = 0f
    private var right = 0f
    private var bottom = 0f

    /** 内容矩形（view 坐标）；矩形外四条边涂黑。 */
    fun setGeometry(l: Float, t: Float, r: Float, b: Float) {
        left = l
        top = t
        right = r
        bottom = b
        invalidate()
    }

    /** 清除遮罩（不画任何东西）。 */
    fun clear() {
        left = 0f; top = 0f; right = 0f; bottom = 0f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val l = left.coerceIn(0f, w)
        val t = top.coerceIn(0f, h)
        val r = right.coerceIn(l, w)
        val b = bottom.coerceIn(t, h)
        if (l <= 0f && t <= 0f && r >= w && b >= h) return
        if (t > 0f) canvas.drawRect(0f, 0f, w, t, paint)
        if (b < h) canvas.drawRect(0f, b, w, h, paint)
        if (l > 0f) canvas.drawRect(0f, t, l, b, paint)
        if (r < w) canvas.drawRect(r, t, w, b, paint)
    }
}

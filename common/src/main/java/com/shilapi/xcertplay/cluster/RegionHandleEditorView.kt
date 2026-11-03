package com.shilapi.xcertplay.cluster

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

/**
 * `p/g61`：OgleaPlay 0.2.18 的「裁剪视频画面」编辑器画布。
 *
 * 与 [com.shilapi.xcertplay.SafeAreaEditorView]（四条互不相干的边界线、用于 CarPlay 安全区）
 * 的区别，正是这次要对齐的三点行为：
 *
 * 1. **八个手柄**：四角 + 四边中点各画一个白点；命中判定沿用上游的位掩码——以每条边为中心
 *    留 22 dp 的触摸带，同时命中两条边即为角（可两轴同时拖动），而不是四条贯穿全屏的线；
 * 2. **边裁边看**：本视图只负责画框与遮罩，实时画面由外部在同一 `FrameLayout` 里垫一层
 *    SurfaceView（见 [CropPreviewBridge]），框外 47 % 压暗即为裁剪结果预览；
 * 3. **闭合矩形**：框内拖动 = 平移；框外按下拖动 = 直接拉出一个新框（上游 `n` 分支），
 *    四条边永远不会交叉。
 *
 * 坐标全部用归一化 0..1，与 [ScreenRegion] 一致，因此同一份值在主屏编辑器、副屏画布与
 * 不同分辨率的面板之间通用。
 */
@SuppressLint("ViewConstructor")
internal class RegionHandleEditorView(context: Context) : View(context) {

    /** 当前框；外部写值即可重画（对话框打开、恢复默认时用）。 */
    var region: ScreenRegion = ScreenRegion.FULL
        set(value) {
            field = value
            invalidate()
        }

    /** 框外是否压暗。裁剪编辑器开，纯位置编辑器也开——用户要看到「留下什么」。 */
    var dimOutside = true

    /** 10 × 10 网格，与上游 `g61` 相同。 */
    var showGrid = true

    /** 每次拖动后回调，参数已是合法（clamped）区域。 */
    var onRegionChanged: ((ScreenRegion) -> Unit)? = null

    private val density = resources.displayMetrics.density

    /** 手柄半径 6 dp；触摸带 22 dp（上游 `g(0x16)`）。 */
    private val handleRadius = 6f * density
    private val touchBand = 22f * density

    /** 最小边长为画布的 4 %（上游 0.04f），再小就无法用 22 dp 的带抓住。 */
    private val minSize = 0.04f

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0x1c, 0x40, 0x49)
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }
    private val dimPaint = Paint().apply { color = Color.argb(0x77, 0, 0, 0) }
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 2f * density
        style = Paint.Style.STROKE
    }
    private val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val handleStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0x1c, 0x40, 0x49)
        strokeWidth = 1.5f * density
        style = Paint.Style.STROKE
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 14f * density * resources.configuration.fontScale
    }

    // 拖动状态：位掩码与上游一致（1 左边 / 2 右边 / 4 上边 / 8 下边）。
    private var mask = 0
    private var movingInside = false
    private var drawingNew = false
    private var downX = 0f
    private var downY = 0f
    private var startRegion = ScreenRegion.FULL

    init {
        isClickable = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        val w = width.toFloat()
        val h = height.toFloat()

        if (showGrid) {
            for (step in 1 until GRID_CELLS) {
                val x = w * step / GRID_CELLS
                val y = h * step / GRID_CELLS
                canvas.drawLine(x, 0f, x, h, gridPaint)
                canvas.drawLine(0f, y, w, y, gridPaint)
            }
        }

        val rect = RectF(
            region.x * w,
            region.y * h,
            region.right * w,
            region.bottom * h,
        )
        if (dimOutside) {
            canvas.drawRect(0f, 0f, w, rect.top, dimPaint)
            canvas.drawRect(0f, rect.bottom, w, h, dimPaint)
            canvas.drawRect(0f, rect.top, rect.left, rect.bottom, dimPaint)
            canvas.drawRect(rect.right, rect.top, w, rect.bottom, dimPaint)
        }
        canvas.drawRect(rect, framePaint)

        // 八个手柄：四角 + 四边中点（顺序与上游一致：左上、上中、右上、左中、右中、左下、下中、右下）。
        val cx = rect.centerX()
        val cy = rect.centerY()
        val handles = floatArrayOf(
            rect.left, rect.top, cx, rect.top, rect.right, rect.top,
            rect.left, cy, rect.right, cy,
            rect.left, rect.bottom, cx, rect.bottom, rect.right, rect.bottom,
        )
        var index = 0
        while (index < handles.size) {
            canvas.drawCircle(handles[index], handles[index + 1], handleRadius, handleFill)
            canvas.drawCircle(handles[index], handles[index + 1], handleRadius, handleStroke)
            index += 2
        }

        canvas.drawText(
            "保留画面 · 拖动 / 裁剪",
            (rect.left + 12f * density).coerceAtMost((w - 12f * density).coerceAtLeast(0f)),
            (rect.top + 24f * density).coerceAtMost(h),
            labelPaint,
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (width <= 0 || height <= 0) return false
        val fx = (event.x / width).coerceIn(0f, 1f)
        val fy = (event.y / height).coerceIn(0f, 1f)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = fx
                downY = fy
                startRegion = region
                mask = hitMask(fx, fy, startRegion)
                movingInside = mask == 0 && contains(fx, fy, startRegion)
                // 上游语义：既没摸到边、也不在框内时，按下即开始拉一个新框。
                drawingNew = mask == 0 && !movingInside
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val next = when {
                    drawingNew -> newRegion(downX, downY, fx, fy)
                    movingInside -> moveRegion(startRegion, fx - downX, fy - downY)
                    mask != 0 -> resizeRegion(startRegion, mask, fx - downX, fy - downY)
                    else -> return true
                }
                if (next != region) {
                    region = next
                    onRegionChanged?.invoke(next)
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                mask = 0
                movingInside = false
                drawingNew = false
                parent?.requestDisallowInterceptTouchEvent(false)
                if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun hitMask(fx: Float, fy: Float, target: ScreenRegion): Int {
        val bandX = (touchBand / width).coerceAtLeast(0.0005f)
        val bandY = (touchBand / height).coerceAtLeast(0.0005f)
        var hit = 0
        if (kotlin.math.abs(fx - target.x) <= bandX) {
            hit = hit or MASK_LEFT
        } else if (kotlin.math.abs(fx - target.right) <= bandX) {
            hit = hit or MASK_RIGHT
        }
        if (kotlin.math.abs(fy - target.y) <= bandY) {
            hit = hit or MASK_TOP
        } else if (kotlin.math.abs(fy - target.bottom) <= bandY) {
            hit = hit or MASK_BOTTOM
        }
        return hit
    }

    private fun contains(fx: Float, fy: Float, target: ScreenRegion): Boolean =
        fx >= target.x && fx <= target.right && fy >= target.y && fy <= target.bottom

    private fun moveRegion(start: ScreenRegion, dx: Float, dy: Float): ScreenRegion {
        val left = (start.x + dx).coerceIn(0f, 1f - start.width)
        val top = (start.y + dy).coerceIn(0f, 1f - start.height)
        return ScreenRegion(left, top, start.width, start.height)
    }

    private fun resizeRegion(start: ScreenRegion, hit: Int, dx: Float, dy: Float): ScreenRegion {
        var left = start.x
        var top = start.y
        var right = start.right
        var bottom = start.bottom
        if (hit and MASK_LEFT != 0) left = (start.x + dx).coerceIn(0f, right - minSize)
        if (hit and MASK_RIGHT != 0) right = (start.right + dx).coerceIn(left + minSize, 1f)
        if (hit and MASK_TOP != 0) top = (start.y + dy).coerceIn(0f, bottom - minSize)
        if (hit and MASK_BOTTOM != 0) bottom = (start.bottom + dy).coerceIn(top + minSize, 1f)
        return ScreenRegion(left, top, right - left, bottom - top).clamped()
    }

    private fun newRegion(x0: Float, y0: Float, x1: Float, y1: Float): ScreenRegion {
        val left = minOf(x0, x1).coerceIn(0f, 1f - minSize)
        val top = minOf(y0, y1).coerceIn(0f, 1f - minSize)
        val right = maxOf(x0, x1).coerceAtLeast(left + minSize).coerceAtMost(1f)
        val bottom = maxOf(y0, y1).coerceAtLeast(top + minSize).coerceAtMost(1f)
        return ScreenRegion(left, top, right - left, bottom - top).clamped()
    }

    private companion object {
        const val GRID_CELLS = 10
        const val MASK_LEFT = 1
        const val MASK_RIGHT = 2
        const val MASK_TOP = 4
        const val MASK_BOTTOM = 8
    }
}

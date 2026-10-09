package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import com.shilapi.xcertplay.airplay.AirPlaySafeArea
import com.shilapi.xcertplay.airplay.SafeAreaRect

/** Full-screen editor for the two horizontal and two vertical safe-area boundaries. */
internal class SafeAreaEditorView(
    context: Context,
    palette: DiPlayPalette = DiPlayPalette.DARK,
) : View(context) {
    private enum class Edge {
        LEFT,
        TOP,
        RIGHT,
        BOTTOM,
    }

    private val density = resources.displayMetrics.density
    private val touchRadius = 40f * density
    private val dimPaint = Paint().apply { color = Color.argb(118, 0, 0, 0) }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = palette.overlayAccent
        strokeWidth = 3f * density
        style = Paint.Style.STROKE
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = palette.overlayPrimaryText
        alpha = 210
        strokeWidth = 1f * density
        style = Paint.Style.STROKE
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = palette.overlayPrimaryText
        textSize = 15f * density * resources.configuration.fontScale
        typeface = android.graphics.Typeface.MONOSPACE
    }

    private var rect: SafeAreaRect? = null
    private var sourceRect: SafeAreaRect? = null
    private var sourceWidth = 0
    private var sourceHeight = 0
    private var activeEdge: Edge? = null
    var interactive = true
    var dimOutside = true
    var onRectChanged: ((SafeAreaRect) -> Unit)? = null

    init {
        isClickable = true
    }

    fun applyPalette(palette: DiPlayPalette) {
        linePaint.color = palette.overlayAccent
        borderPaint.color = palette.overlayPrimaryText
        borderPaint.alpha = 210
        textPaint.color = palette.overlayPrimaryText
        invalidate()
    }

    fun setRect(value: SafeAreaRect, sourceWidthPixels: Int, sourceHeightPixels: Int) {
        require(sourceWidthPixels > 0 && sourceHeightPixels > 0) {
            "Source dimensions must be positive"
        }
        sourceWidth = sourceWidthPixels
        sourceHeight = sourceHeightPixels
        sourceRect = value
        if (width > 0 && height > 0) {
            rect = AirPlaySafeArea.scaleRect(
                value,
                sourceWidth,
                sourceHeight,
                width,
                height,
            )
            invalidate()
        }
    }

    fun currentRectForSource(): SafeAreaRect? {
        sourceRect?.let { return it }
        val current = rect ?: return null
        if (sourceWidth <= 0 || sourceHeight <= 0 || width <= 0 || height <= 0) return null
        return AirPlaySafeArea.scaleRect(current, width, height, sourceWidth, sourceHeight)
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        if (width <= 0 || height <= 0) return
        val source = sourceRect ?: AirPlaySafeArea.default(width, height)
        rect = if (sourceWidth > 0 && sourceHeight > 0) {
            AirPlaySafeArea.scaleRect(source, sourceWidth, sourceHeight, width, height)
        } else {
            source.clampTo(width, height)
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val safe = rect ?: return
        val width = width.toFloat()
        val height = height.toFloat()
        val left = safe.left.toFloat()
        val top = safe.top.toFloat()
        val right = safe.right.toFloat()
        val bottom = safe.bottom.toFloat()

        if (dimOutside) {
        canvas.drawRect(0f, 0f, width, top, dimPaint)
        canvas.drawRect(0f, bottom, width, height, dimPaint)
        canvas.drawRect(0f, top, left, bottom, dimPaint)
        canvas.drawRect(right, top, width, bottom, dimPaint)
        }

        canvas.drawRect(left, top, right, bottom, borderPaint)
        // Keep strokes visible even when a boundary is at the canvas edge.
        val inset = minOf(linePaint.strokeWidth / 2f, width / 2f, height / 2f)
        canvas.drawLine(left.coerceIn(inset, width - inset), 0f, left.coerceIn(inset, width - inset), height, linePaint)
        canvas.drawLine(right.coerceIn(inset, width - inset), 0f, right.coerceIn(inset, width - inset), height, linePaint)
        canvas.drawLine(0f, top.coerceIn(inset, height - inset), width, top.coerceIn(inset, height - inset), linePaint)
        canvas.drawLine(0f, bottom.coerceIn(inset, height - inset), width, bottom.coerceIn(inset, height - inset), linePaint)

        val source = currentRectForSource() ?: safe
        drawLabel(canvas, "x=${source.left}", left + 8f * density, top + 20f * density)
        drawLabel(canvas, "x=${source.right}", right + 8f * density, bottom - 8f * density)
        drawLabel(canvas, "y=${source.top}", left + 8f * density, top - 8f * density)
        drawLabel(canvas, "y=${source.bottom}", right - 88f * density, bottom + 20f * density)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!interactive) return false
        val safe = rect ?: return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activeEdge = nearestEdge(event.x, event.y, safe)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val edge = activeEdge ?: return true
                val moved = moveEdge(safe, edge, event.x, event.y)
                rect = moved
                if (sourceWidth > 0 && sourceHeight > 0 && width > 0 && height > 0) {
                    sourceRect = AirPlaySafeArea.scaleRect(
                        moved,
                        width,
                        height,
                        sourceWidth,
                        sourceHeight,
                    )
                }
                currentRectForSource()?.let { onRectChanged?.invoke(it) }
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                activeEdge = null
                performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun nearestEdge(x: Float, y: Float, safe: SafeAreaRect): Edge? {
        val candidates = listOf(
            Edge.LEFT to kotlin.math.abs(x - safe.left),
            Edge.RIGHT to kotlin.math.abs(x - safe.right),
            Edge.TOP to kotlin.math.abs(y - safe.top),
            Edge.BOTTOM to kotlin.math.abs(y - safe.bottom),
        ).filter { it.second <= touchRadius }
        return candidates.minByOrNull { it.second }?.first
    }

    private fun moveEdge(safe: SafeAreaRect, edge: Edge, x: Float, y: Float): SafeAreaRect {
        val pointX = x.toInt().coerceIn(0, width)
        val pointY = y.toInt().coerceIn(0, height)
        return when (edge) {
            Edge.LEFT -> safe.copy(left = pointX.coerceIn(0, safe.right - 1))
            Edge.TOP -> safe.copy(top = pointY.coerceIn(0, safe.bottom - 1))
            Edge.RIGHT -> safe.copy(right = pointX.coerceIn(safe.left + 1, width))
            Edge.BOTTOM -> safe.copy(bottom = pointY.coerceIn(safe.top + 1, height))
        }
    }

    private fun drawLabel(canvas: Canvas, value: String, x: Float, y: Float) {
        val horizontalMin = 4f * density
        val horizontalMax = (width - textPaint.measureText(value) - 4f * density)
            .coerceAtLeast(horizontalMin)
        val verticalMin = textPaint.textSize + 4f * density
        val verticalMax = (height - 4f * density).coerceAtLeast(verticalMin)
        val safeX = x.coerceIn(horizontalMin, horizontalMax)
        val safeY = y.coerceIn(verticalMin, verticalMax)
        canvas.drawText(value, safeX, safeY, textPaint)
    }
}

package com.shilapi.xcertplay.cluster

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView

/**
 * `p/ly2`: the secondary-screen renderer, ported 1:1.
 *
 * Two independent geometries stack here, and both are needed to reproduce the original:
 *
 * 1. **Region** — [videoBox] is positioned inside the canvas by the active source's normalized
 *    rectangle, converted straight to `FrameLayout.LayoutParams`.
 * 2. **Crop** — the decoded frame is never re-encoded: the SurfaceView is scaled to the crop rect
 *    and then pulled by **negative margins** so exactly that sub-rectangle fills the box. That is
 *    the trick that makes "show this part of the source in this part of the panel" work without
 *    touching the decoder.
 *
 * The original also hosted a music card and a CarPlay touch forwarder; the card here is a plain
 * placeholder (the song card lives in the host's own views) and single-finger touch forwarding is
 * not ported — see the report in RELEASE_NOTES.
 */
@SuppressLint("ViewConstructor")
internal class SecondaryScreenRenderer(
    context: Context,
    private val onSurface: (Surface?) -> Unit,
    private val onProfileEdited: (ScreenProfile) -> Unit,
) : FrameLayout(context) {

    private val videoBox = FrameLayout(context).apply { clipChildren = true }
    private val surfaceView = SurfaceView(context).apply { setZOrderMediaOverlay(true) }
    private val status = TextView(context).apply {
        setBackgroundColor(Color.BLACK)
        setTextColor(Color.WHITE)
        textSize = 22f
        gravity = Gravity.CENTER
        setPadding(24, 24, 24, 24)
        visibility = GONE
    }
    private val musicCard = TextView(context).apply {
        setBackgroundColor(0xCC101418.toInt())
        setTextColor(Color.WHITE)
        textSize = 26f
        gravity = Gravity.CENTER
        text = "独立音乐"
        visibility = GONE
    }
    private val grid = CalibrationGrid(context)

    private var profile = ScreenProfile()
    private var calibrating = false
    private var surface: Surface? = null
    private var sourceWidth = 1920
    private var sourceHeight = 720

    // Calibration drag state.
    private var dragging = false
    private var fiveFinger = false
    private var dragMode = DragMode.NONE
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var dragStartRegion = ScreenRegion.FULL

    private enum class DragMode { NONE, MOVE, LEFT, RIGHT, TOP, BOTTOM, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

    init {
        clipChildren = true
        videoBox.addView(surfaceView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        videoBox.addView(status, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(videoBox, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(musicCard, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(grid, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                surface = holder.surface
                onSurface(holder.surface)
                applyProfile(profile)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                surface = null
                onSurface(null)
            }
        })
        addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyProfile(profile) }
    }

    fun currentSurface(): Surface? = surface

    /** [width]/[height] describe the stream being rendered, so the crop maths uses source pixels. */
    fun setSourceSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        if (sourceWidth == width && sourceHeight == height) return
        sourceWidth = width
        sourceHeight = height
        applyCrop()
    }

    fun setCalibrating(calibrating: Boolean) {
        this.calibrating = calibrating
        grid.visible = calibrating
        status.visibility = if (!calibrating && shouldShowStatus) VISIBLE else GONE
        grid.invalidate()
        requestLayout()
    }

    fun applyProfile(next: ScreenProfile) {
        profile = next
        val music = next.source == ContentSource.MUSIC
        videoBox.visibility = if (music) GONE else VISIBLE
        musicCard.visibility = if (music) VISIBLE else GONE
        if (music) {
            applyRegion(musicCard, next.musicVideo)
        } else {
            applyRegion(videoBox, next.activeVideo)
            applyCrop()
        }
        grid.profile = next
        grid.invalidate()
        updateStatus()
    }

    private var shouldShowStatus = false

    private fun updateStatus() {
        val waiting = when (profile.source) {
            ContentSource.NAVIGATION -> "等待独立导航画面；首次启用请保存并重连 CarPlay"
            ContentSource.MAIN -> "等待视频关键帧"
            ContentSource.MUSIC -> ""
        }
        shouldShowStatus = waiting.isNotEmpty()
        status.text = waiting
        status.visibility = if (shouldShowStatus && !calibrating) VISIBLE else GONE
    }

    /** `f()`: normalized region -> LayoutParams. */
    private fun applyRegion(view: View, region: ScreenRegion) {
        if (width <= 0 || height <= 0) return
        val w = (region.width * width).toInt().coerceIn(1, width)
        val h = (region.height * height).toInt().coerceIn(1, height)
        val left = (region.x * width).toInt().coerceIn(0, width - w)
        val top = (region.y * height).toInt().coerceIn(0, height - h)
        val params = view.layoutParams
        if (params is LayoutParams && params.width == w && params.height == h &&
            params.leftMargin == left && params.topMargin == top) return
        view.layoutParams = LayoutParams(w, h).apply {
            leftMargin = left
            topMargin = top
        }
    }

    /**
     * `a()`: the crop geometry, exactly as the original computed it.
     *
     * The box is [videoBox]'s size; the frame is scaled by FIT (min) or CROP (max) against the crop
     * rectangle, then offset so the crop rect's own origin lands on the box origin — which is what
     * the negative margins do.
     */
    private fun applyCrop() {
        if (width <= 0 || height <= 0) return
        val boxW = videoBox.width.takeIf { it > 0 } ?: return
        val boxH = videoBox.height.takeIf { it > 0 } ?: return
        val crop = profile.activeCrop
        val cropW = sourceWidth * crop.width
        val cropH = sourceHeight * crop.height
        if (cropW <= 0f || cropH <= 0f) return
        val scale = when (profile.mode) {
            FitMode.FIT -> minOf(boxW / cropW, boxH / cropH)
            FitMode.CROP -> maxOf(boxW / cropW, boxH / cropH)
        }
        val surfaceW = (cropW * scale).toInt().coerceAtLeast(1)
        val surfaceH = (cropH * scale).toInt().coerceAtLeast(1)
        val left = ((boxW - surfaceW) / 2f - crop.x * surfaceW).toInt()
        val top = ((boxH - surfaceH) / 2f - crop.y * surfaceH).toInt()
        val params = surfaceView.layoutParams
        if (params is LayoutParams && params.width == surfaceW && params.height == surfaceH &&
            params.leftMargin == left && params.topMargin == top) return
        surfaceView.layoutParams = LayoutParams(surfaceW, surfaceH).apply {
            leftMargin = left
            topMargin = top
        }
    }

    // --- input -----------------------------------------------------------------------------

    /**
     * Calibration owns every touch while it is on: one finger drags the active region (body moves,
     * edges resize), five fingers switch the source. Outside calibration nothing is consumed, so a
     * normal CarPlay session never loses a gesture to this view.
     */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!calibrating) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                fiveFinger = false
                dragStartX = event.x
                dragStartY = event.y
                dragStartRegion = profile.activeVideo
                dragMode = hitTest(event.x, event.y, dragStartRegion)
                dragging = dragMode != DragMode.NONE
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount >= 5) {
                    fiveFinger = true
                    dragging = false
                    dragMode = DragMode.NONE
                    dragStartX = event.x
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (fiveFinger) {
                    val dx = event.x - dragStartX
                    if (kotlin.math.abs(dx) >= width * SWIPE_FRACTION) {
                        dragStartX = event.x
                        cycleSource(if (dx > 0) 1 else -1)
                    }
                } else if (dragging && width > 0 && height > 0) {
                    val next = resize(
                        dragStartRegion, dragMode,
                        (event.x - dragStartX) / width,
                        (event.y - dragStartY) / height,
                    )
                    profile = profile.withVideo(next)
                    applyRegion(videoBox, next)
                    applyCrop()
                    grid.profile = profile
                    grid.invalidate()
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) onProfileEdited(profile)
                dragging = false
                fiveFinger = false
                dragMode = DragMode.NONE
            }
        }
        return true
    }

    private fun cycleSource(direction: Int) {
        val next = if (direction > 0) profile.source.next() else when (profile.source) {
            ContentSource.MAIN -> ContentSource.MUSIC
            ContentSource.NAVIGATION -> ContentSource.MAIN
            ContentSource.MUSIC -> ContentSource.NAVIGATION
        }
        profile = profile.copy(source = next)
        applyProfile(profile)
        onProfileEdited(profile)
    }

    /** Which part of [region] the touch grabbed; the outer 15 % is a resize handle. */
    private fun hitTest(x: Float, y: Float, region: ScreenRegion): DragMode {
        if (width <= 0 || height <= 0) return DragMode.NONE
        val fx = x / width
        val fy = y / height
        if (fx < region.x || fx > region.right || fy < region.y || fy > region.bottom) return DragMode.NONE
        val edgeX = region.width * HANDLE_FRACTION
        val edgeY = region.height * HANDLE_FRACTION
        val nearLeft = fx - region.x <= edgeX
        val nearRight = region.right - fx <= edgeX
        val nearTop = fy - region.y <= edgeY
        val nearBottom = region.bottom - fy <= edgeY
        return when {
            nearLeft && nearTop -> DragMode.TOP_LEFT
            nearRight && nearTop -> DragMode.TOP_RIGHT
            nearLeft && nearBottom -> DragMode.BOTTOM_LEFT
            nearRight && nearBottom -> DragMode.BOTTOM_RIGHT
            nearLeft -> DragMode.LEFT
            nearRight -> DragMode.RIGHT
            nearTop -> DragMode.TOP
            nearBottom -> DragMode.BOTTOM
            else -> DragMode.MOVE
        }
    }

    private fun resize(start: ScreenRegion, mode: DragMode, dx: Float, dy: Float): ScreenRegion {
        var left = start.x
        var top = start.y
        var right = start.right
        var bottom = start.bottom
        when (mode) {
            DragMode.MOVE -> {
                left = (start.x + dx).coerceIn(0f, 1f - start.width)
                top = (start.y + dy).coerceIn(0f, 1f - start.height)
                right = left + start.width
                bottom = top + start.height
            }
            DragMode.LEFT, DragMode.TOP_LEFT, DragMode.BOTTOM_LEFT ->
                left = (start.x + dx).coerceIn(0f, right - MIN_SIZE)
            DragMode.RIGHT, DragMode.TOP_RIGHT, DragMode.BOTTOM_RIGHT ->
                right = (start.right + dx).coerceIn(left + MIN_SIZE, 1f)
            else -> Unit
        }
        when (mode) {
            DragMode.TOP, DragMode.TOP_LEFT, DragMode.TOP_RIGHT ->
                top = (start.y + dy).coerceIn(0f, bottom - MIN_SIZE)
            DragMode.BOTTOM, DragMode.BOTTOM_LEFT, DragMode.BOTTOM_RIGHT ->
                bottom = (start.bottom + dy).coerceIn(top + MIN_SIZE, 1f)
            else -> Unit
        }
        return ScreenRegion(left, top, right - left, bottom - top).clamped()
    }

    private companion object {
        /** The original's calibration threshold: 8 % of the panel width. */
        const val SWIPE_FRACTION = 0.08f
        const val HANDLE_FRACTION = 0.15f
        const val MIN_SIZE = 0.05f
    }
}

/**
 * `p/ky2`: the calibration grid. Ten by ten cells, a blue hairline grid, and the active region
 * outlined in orange with its label — same parameters as the original.
 */
@SuppressLint("ViewConstructor")
internal class CalibrationGrid(context: Context) : View(context) {
    var profile: ScreenProfile = ScreenProfile()
    var visible = false

    private val line = Paint().apply {
        color = Color.rgb(0x48, 0x9a, 0xff)
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }
    private val active = Paint().apply {
        color = Color.rgb(0xff, 0x8c, 0x00)
        strokeWidth = 5f
        style = Paint.Style.STROKE
    }
    private val label = Paint().apply {
        color = Color.rgb(0xff, 0x8c, 0x00)
        textSize = 22f
        style = Paint.Style.FILL
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!visible || width <= 0 || height <= 0) return
        for (step in 1 until CELLS) {
            val x = width * step / CELLS.toFloat()
            val y = height * step / CELLS.toFloat()
            canvas.drawLine(x, 0f, x, height.toFloat(), line)
            canvas.drawLine(0f, y, width.toFloat(), y, line)
        }
        val region = profile.activeVideo
        val left = region.x * width
        val top = region.y * height
        val right = region.right * width
        val bottom = region.bottom * height
        canvas.drawRect(left, top, right, bottom, active)
        canvas.drawText("视频显示区域", left + 10f, top + 30f, label)
    }

    private companion object {
        const val CELLS = 10
    }
}

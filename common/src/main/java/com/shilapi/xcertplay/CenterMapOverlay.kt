package com.shilapi.xcertplay

import android.app.ActivityManager
import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The dashboard map (CarPlay stream 111) as a floating card on the centre screen while DiPlay is in
 * the background; with Usage Access only over a home screen (see [HomeScreenMonitor]). A second decoder draws the
 * stream here, so the dashboard keeps its map. Needs "display over other apps"
 * (SYSTEM_ALERT_WINDOW). A tap opens CarPlay, dragging moves the card and pinching resizes it.
 */
internal object CenterMapOverlay {
    const val TAG = "DiPlay-CenterMap"
    private const val SHOW_DELAY_MILLIS = 600L
    private const val RELEASE_DELAY_MILLIS = 1_000L
    private const val WIDTH_FRACTION = 0.36
    private const val MIN_WIDTH_FRACTION = 0.25
    private const val MIN_PINCH_REFERENCE_DP = 64f

    private val main = Handler(Looper.getMainLooper())
    private var root: View? = null

    /** The CarPlay screen, asked to show the card once no DiPlay screen is in front. */
    var requestShow: (() -> Unit)? = null

    /** Diagnostic sink, so the deferral decision reaches the exported report too. */
    var logger: ((String) -> Unit)? = null
    private val showIfBackground = Runnable {
        if (!diPlayInFront()) requestShow?.invoke()
        else logger?.invoke("Centre map: card deferred, DiPlay is still in front")
    }

    fun permitted(context: Context): Boolean = Settings.canDrawOverlays(context)

    /** Shows the card shortly, unless a DiPlay screen is in front by then. */
    fun scheduleShow() {
        main.removeCallbacks(showIfBackground)
        main.postDelayed(showIfBackground, SHOW_DELAY_MILLIS)
    }

    /** A DiPlay screen is in front: the card goes. */
    fun onDiPlayScreenShown() {
        main.removeCallbacks(showIfBackground)
        hide()
    }

    val shown: Boolean get() = root != null

    /**
     * Adds the card. [onSurface] gets its surface, and null when the card goes, before the surface is
     * released. Main thread.
     */
    fun show(
        context: Context,
        aspect: Double,
        onSurface: (Surface?) -> Unit,
        onTap: () -> Unit,
    ): Boolean {
        if (root != null) return true
        if (!aspect.isFinite() || aspect <= 0) return false
        if (!permitted(context)) return false
        val windows = context.getSystemService(WindowManager::class.java) ?: return false
        val metrics = context.resources.displayMetrics
        val screenWidth = metrics.widthPixels
        val screenHeight = metrics.heightPixels
        // The widest card that still fits the screen height; the stream itself is 1600x600.
        if (screenWidth <= 0 || screenHeight <= 0) return false
        val maxWidth = minOf(screenWidth, (screenHeight * aspect).toInt()).coerceAtLeast(1)
        val minWidth = (screenWidth * MIN_WIDTH_FRACTION).toInt().coerceIn(1, maxWidth)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val hasSavedAspect = prefs.contains(KEY_ASPECT)
        val savedAspect = if (hasSavedAspect) java.lang.Double.longBitsToDouble(prefs.getLong(KEY_ASPECT, 0L)) else aspect
        val initialWidth = if (hasSavedAspect && kotlin.math.abs(savedAspect - aspect) > 0.1) {
            (screenWidth * WIDTH_FRACTION).toInt().coerceIn(minWidth, maxWidth)
        } else {
            prefs.getInt(KEY_WIDTH, (screenWidth * WIDTH_FRACTION).toInt()).coerceIn(minWidth, maxWidth)
        }
        val width = initialWidth
        prefs.edit().putInt(KEY_WIDTH, width)
            .putLong(KEY_ASPECT, java.lang.Double.doubleToRawLongBits(aspect)).apply()
        val height = (width / aspect).toInt()
        val radius = 24f * metrics.density / 2
        // TYPE_APPLICATION_OVERLAY only exists since API 26; older firmware (this Android 7
        // head unit among them) rejects type 2038 with a BadTokenException. TYPE_PHONE is the
        // pre-O overlay type and works on Android 7 with the same SYSTEM_ALERT_WINDOW grant.
        val windowType = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            width,
            height,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED, // the TextureView needs it
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt(KEY_X, screenWidth - width - (32 * metrics.density).toInt())
                .coerceIn(0, (screenWidth - width).coerceAtLeast(0))
            y = prefs.getInt(KEY_Y, (96 * metrics.density).toInt())
                .coerceIn(0, (screenHeight - height).coerceAtLeast(0))
            title = "DiPlay centre map"
        }
        var surface: Surface? = null
        val video = TextureView(context).apply {
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(texture: SurfaceTexture, w: Int, h: Int) {
                    surface = Surface(texture).also(onSurface)
                    Log.i(TAG, "card surface ${w}x$h")
                }

                override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, w: Int, h: Int) = Unit
                override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit

                override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                    // Stop the mirror first; its decoder stops on its own thread, so the old
                    // surface stays valid a little longer.
                    onSurface(null)
                    val old = surface
                    surface = null
                    main.postDelayed({ old?.release(); texture.release() }, RELEASE_DELAY_MILLIS)
                    return false
                }
            }
        }
        val card = FrameLayout(context).apply {
            setBackgroundColor(Color.BLACK)
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) =
                    outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
            clipToOutline = true
            addView(video, FrameLayout.LayoutParams(-1, -1))
        }
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        var pinched = false // a second finger came down: no tap or drag until all fingers are up
        var firstPointer = -1
        var secondPointer = -1
        var initialSpan = 0f
        var previousSpan = 0f
        var pinchWidth = 0f
        var centerX = 0
        var centerY = 0
        var scaling = false
        // The car's ScaleGestureDetector minimum span is 32 mm, too large for a small
        // card. Use touch slop and the two fingers' distance instead, keeping its centre
        // and aspect ratio. A denominator floor prevents near-touching fingers from
        // making small movements resize the whole card. It does not gate recognition.
        val minPinchReference = MIN_PINCH_REFERENCE_DP * metrics.density
        fun beginPinch(event: MotionEvent, liftedIndex: Int = -1) {
            val indices = (0 until event.pointerCount).filter { it != liftedIndex }
            firstPointer = -1
            secondPointer = -1
            scaling = false
            if (indices.size < 2) return
            val first = indices[0]
            val second = indices[1]
            firstPointer = event.getPointerId(first)
            secondPointer = event.getPointerId(second)
            // Wait for the first MOVE: initial pointer-down coordinates can still be
            // settling. Merely putting a second finger down must not change the size.
            initialSpan = 0f
            previousSpan = 0f
            pinchWidth = params.width.toFloat()
            centerX = params.x + params.width / 2
            centerY = params.y + params.height / 2
        }
        card.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY; startX = params.x; startY = params.y
                    dragging = false
                    pinched = false
                    firstPointer = -1
                    secondPointer = -1
                    scaling = false
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    pinched = true
                    if (firstPointer == -1) beginPinch(event)
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    val lifted = event.getPointerId(event.actionIndex)
                    if (lifted == firstPointer || lifted == secondPointer) beginPinch(event, event.actionIndex)
                }
                MotionEvent.ACTION_MOVE -> if (pinched) {
                    val first = event.findPointerIndex(firstPointer)
                    val second = event.findPointerIndex(secondPointer)
                    if (first >= 0 && second >= 0) {
                        val span = hypot(event.getX(first) - event.getX(second), event.getY(first) - event.getY(second))
                        if (initialSpan == 0f) {
                            initialSpan = span
                            previousSpan = span
                        } else if (scaling || abs(span - initialSpan) > slop) {
                            scaling = true
                            val factor = 1f + (span - previousSpan) / maxOf(previousSpan, minPinchReference)
                            // Rebase at every sample, including at a size limit. Reversing
                            // direction then responds immediately, without a dead zone.
                            pinchWidth = (pinchWidth * factor).coerceIn(minWidth.toFloat(), maxWidth.toFloat())
                            previousSpan = span
                            params.width = pinchWidth.toInt()
                            params.height = (params.width / aspect).toInt()
                            params.x = (centerX - params.width / 2).coerceIn(0, (screenWidth - params.width).coerceAtLeast(0))
                            params.y = (centerY - params.height / 2).coerceIn(0, (screenHeight - params.height).coerceAtLeast(0))
                            runCatching { windows.updateViewLayout(view, params) }
                        }
                    }
                } else {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (dragging || abs(dx) > slop || abs(dy) > slop) {
                        dragging = true
                        params.x = (startX + dx).toInt().coerceIn(0, (screenWidth - params.width).coerceAtLeast(0))
                        params.y = (startY + dy).toInt().coerceIn(0, (screenHeight - params.height).coerceAtLeast(0))
                        runCatching { windows.updateViewLayout(view, params) }
                    }
                }
                MotionEvent.ACTION_UP -> when {
                    pinched || dragging -> {
                        prefs.edit().putInt(KEY_X, params.x).putInt(KEY_Y, params.y).putInt(KEY_WIDTH, params.width)
                            .putLong(KEY_ASPECT, java.lang.Double.doubleToRawLongBits(aspect)).apply()
                        if (pinched) Log.i(TAG, "card resized ${params.width}x${params.height}")
                    }
                    else -> onTap()
                }
            }
            true
        }
        return try {
            windows.addView(card, params)
            root = card
            Log.i(TAG, "card shown ${width}x$height at ${params.x},${params.y}")
            true
        } catch (error: RuntimeException) {
            Log.w(TAG, "card failed", error)
            false
        }
    }

    /** Removes the card; its surface goes back through onSurface(null). Main thread. */
    fun hide() {
        val view = root ?: return
        root = null
        runCatching { view.context.getSystemService(WindowManager::class.java)?.removeViewImmediate(view) }
        Log.i(TAG, "card hidden")
    }

    // A DiPlay activity in front makes the process foreground; the session service alone does not.
    fun diPlayInFront(): Boolean {
        val state = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(state)
        return state.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    private const val PREFS = "diplay_center_map"
    private const val KEY_X = "x"
    private const val KEY_Y = "y"
    private const val KEY_WIDTH = "width"
    private const val KEY_ASPECT = "aspect"
}

package com.shilapi.xcertplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout

/**
 * The live CarPlay screen (the main stream, 110) floating over the launcher, driven by the
 * dashboard launcher's popup-map protocol: the launcher broadcasts
 * [ACTION_SHOW] with x/y/w/h when the desktop should show the window and [ACTION_CLOSE]
 * when it should go — the same broadcasts the stock AMap head-unit app answers. The window
 * is created here (TYPE_PHONE below API 26, TYPE_APPLICATION_OVERLAY above, like the centre
 * card); the launcher only picks the geometry. Off by default: when the switch is on, any
 * app on the head unit may ask for the window.
 */
internal object ShowmapOverlay {
    const val ACTION_SHOW = "com.autonavi.plus.showmap"
    const val ACTION_CLOSE = "com.autonavi.plus.closemap"
    const val KEY = "showmap"
    const val TAG = "DiPlay-Showmap"

    /** The main CarPlay stream is always 16:9, unlike the dashboard stream's switchable shape. */
    private const val STREAM_ASPECT = 16.0 / 9

    /** Mirrors the main CarPlay stream; the host wires this to its media sink. */
    var sink: ((Surface?) -> Unit)? = null

    /** The settings switch; off means every broadcast is ignored. */
    var enabled: Boolean = false

    private val main = Handler(Looper.getMainLooper())
    private var root: View? = null
    private var surface: Surface? = null
    private var params: WindowManager.LayoutParams? = null

    val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_SHOW -> {
                    val x = intent.getIntExtra("x", Int.MIN_VALUE)
                    val y = intent.getIntExtra("y", Int.MIN_VALUE)
                    val w = intent.getIntExtra("w", 0)
                    val h = intent.getIntExtra("h", 0)
                    Log.i(TAG, "show x=$x y=$y w=$w h=$h enabled=$enabled")
                    if (!enabled) return
                    main.post { show(context.applicationContext, x, y, w, h) }
                }
                ACTION_CLOSE -> {
                    Log.i(TAG, "close enabled=$enabled")
                    if (!enabled) return
                    main.post { hide() }
                }
            }
        }
    }

    fun permitted(context: Context): Boolean = Settings.canDrawOverlays(context)

    /** Re-hands the mirror surface to the media sink after a reconnect. */
    fun reattach() {
        val live = surface ?: return
        sink?.invoke(live)
    }

    private fun show(context: Context, x: Int, y: Int, w: Int, h: Int) {
        if (!permitted(context)) {
            Log.w(TAG, "no overlay permission")
            return
        }
        val windows = context.getSystemService(WindowManager::class.java) ?: return
        val metrics = context.resources.displayMetrics
        val screenWidth = metrics.widthPixels
        val screenHeight = metrics.heightPixels
        if (screenWidth <= 0 || screenHeight <= 0) return
        val width = if (w in 1 until screenWidth) w else (screenWidth / 3.0).toInt().coerceAtLeast(1)
        val height = if (h in 1 until screenHeight) h else (width / STREAM_ASPECT).toInt().coerceAtLeast(1)
        if (root != null) {
            // Already up: the launcher only moved or resized it.
            val existing = params ?: return
            existing.x = x.coerceIn(0, (screenWidth - width).coerceAtLeast(0))
            existing.y = y.coerceIn(0, (screenHeight - height).coerceAtLeast(0))
            existing.width = width
            existing.height = height
            runCatching { windows.updateViewLayout(root, existing) }
            return
        }
        val windowType = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val layout = WindowManager.LayoutParams(
            width,
            height,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = if (x == Int.MIN_VALUE) screenWidth - width else x
            this.y = if (y == Int.MIN_VALUE) (screenHeight - height) / 2 else y
            this.x = this.x.coerceIn(0, (screenWidth - width).coerceAtLeast(0))
            this.y = this.y.coerceIn(0, (screenHeight - height).coerceAtLeast(0))
            title = "DiPlay showmap"
        }
        params = layout
        val video = TextureView(context).apply {
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(texture: SurfaceTexture, vw: Int, vh: Int) {
                    if (root === null) return
                    cropToFill(this@apply, vw, vh)
                    surface = Surface(texture).also { sink?.invoke(it) }
                    Log.i(TAG, "surface $vw x $vh")
                }

                override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, vw: Int, vh: Int) =
                    cropToFill(this@apply, vw, vh)

                override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit

                override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                    sink?.invoke(null)
                    val old = surface
                    surface = null
                    main.postDelayed({ old?.release(); texture.release() }, 1_000L)
                    return false
                }
            }
        }
        val frame = FrameLayout(context).apply {
            setBackgroundColor(Color.BLACK)
            addView(video, FrameLayout.LayoutParams(-1, -1))
            // A tap opens CarPlay, like a tap on the centre card.
            setOnClickListener {
                context.startActivity(
                    Intent(context, CarPlayHostActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
        try {
            windows.addView(frame, layout)
            root = frame
            Log.i(TAG, "shown ${width}x$height at ${layout.x},${layout.y}")
        } catch (error: RuntimeException) {
            Log.w(TAG, "window failed", error)
            params = null
        }
    }

    private fun hide() {
        val view = root ?: return
        root = null
        params = null
        runCatching {
            view.context.getSystemService(WindowManager::class.java)?.removeViewImmediate(view)
        }
        // onSurfaceTextureDestroyed hands the mirror back through sink(null) and releases.
        Log.i(TAG, "hidden")
    }

    /** Keeps the 16:9 stream filling the window, cropping the edges that do not fit. */
    private fun cropToFill(view: TextureView, width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val viewAspect = width.toFloat() / height
        val scaleX = if (viewAspect < STREAM_ASPECT) (STREAM_ASPECT / viewAspect).toFloat() else 1f
        val scaleY = if (viewAspect > STREAM_ASPECT) (viewAspect / STREAM_ASPECT).toFloat() else 1f
        view.setTransform(Matrix().apply { setScale(scaleX, scaleY, width / 2f, height / 2f) })
    }
}

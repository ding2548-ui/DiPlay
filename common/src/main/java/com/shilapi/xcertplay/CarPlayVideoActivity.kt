package com.shilapi.xcertplay
import android.annotation.SuppressLint

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsManifest
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.shilapi.xcertplay.airplay.VideoInCar
import com.shilapi.xcertplay.host.R
import java.util.Locale
import androidx.media3.ui.R as Media3R
import kotlin.math.roundToInt

/**
 * The car's own player for iOS 27 video in car (see [CarPlayVideo]). Full screen over CarPlay, which
 * stays connected underneath; a tap shows Back to CarPlay, play/pause, 10 s back and forward and the
 * time bar for a few seconds.
 *
 * Media3 ExoPlayer parses media in the app: the head unit's own MP4 parser aborted on progressive
 * Safari video on a DiLink 5.0 Tang. URLs the car cannot load (an app's own scheme, app-served AES-128
 * keys) go to the iPhone (IphoneResolvingDataSource). The HLS encryption is logged, without URLs, so a
 * protected item that cannot play here is easy to tell apart.
 */
@OptIn(UnstableApi::class)
class CarPlayVideoActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var player: ExoPlayer
    private lateinit var controls: View
    private lateinit var playPause: ImageView
    private lateinit var position: TextView
    private lateinit var length: TextView
    private lateinit var timeBar: SeekBar
    private var scrubbing = false
    private var loadedUrl: String? = null
    private var prepared = false
    private var encryptionLogged = false
    private val hideControls = Runnable {
        controls.visibility = View.GONE
        main.removeCallbacks(tick)
    }
    private val tick = object : Runnable {
        override fun run() {
            updateTime()
            main.postDelayed(this, TICK_MILLIS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        val http = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
        val sources = IphoneResolvingDataSource.Factory(DefaultDataSource.Factory(this, http))
        player = ExoPlayer.Builder(this).setMediaSourceFactory(DefaultMediaSourceFactory(sources)).build()
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY && !prepared) {
                    prepared = true
                    Log.i(TAG, "prepared duration=${player.duration} ms size=${player.videoSize.width}x${player.videoSize.height}")
                }
            }

            override fun onTimelineChanged(timeline: Timeline, reason: Int) = logEncryption()

            override fun onPlayerError(error: PlaybackException) {
                Log.w(TAG, "player error ${error.errorCodeName}: ${causes(error)}")
                CarPlayVideo.onPlayerFailed(
                    when (error.errorCode) {
                        in 2000..2999 -> VideoInCar.ERROR_NETWORK // IO
                        in 4000..4999 -> VideoInCar.ERROR_DECODER // decoding
                        else -> VideoInCar.ERROR_INCOMPATIBLE_ASSET // parsing, DRM, unsupported
                    },
                )
            }
        })
        val view = PlayerView(this).apply {
            useController = false
            setShutterBackgroundColor(Color.BLACK)
            this.player = this@CarPlayVideoActivity.player
        }
        controls = controlBar()
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
            addView(controls, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        })
        view.setOnClickListener { showControls() }
        CarPlayVideo.activity = this
        load()
        showControls()
    }

    private fun controlBar(): View {
        val back = TextView(this).apply {
            text = "‹  " + getString(R.string.video_back_to_carplay)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            gravity = Gravity.CENTER
            minHeight = dp(64)
            minWidth = dp(64)
            setPadding(dp(24), 0, dp(24), 0)
            background = GradientDrawable().apply { cornerRadius = dp(32).toFloat(); setColor(0xB3000000.toInt()) }
            setOnClickListener { finish() }
        }
        fun round(icon: Int, label: String, onClick: () -> Unit) = ImageView(this).apply {
            setImageResource(icon)
            setColorFilter(Color.WHITE)
            contentDescription = label
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x80000000.toInt()) }
            setOnClickListener { onClick(); showControls() }
        }
        playPause = round(Media3R.drawable.exo_icon_play, "") { CarPlayVideo.setPlaying(!CarPlayVideo.playing) }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(round(Media3R.drawable.exo_icon_rewind, getString(Media3R.string.exo_controls_rewind_description)) { skip(-CarPlayVideo.SKIP_MILLIS) },
                LinearLayout.LayoutParams(dp(80), dp(80)))
            addView(playPause, LinearLayout.LayoutParams(dp(96), dp(96)).apply { marginStart = dp(48); marginEnd = dp(48) })
            addView(round(Media3R.drawable.exo_icon_fastforward, getString(Media3R.string.exo_controls_fastforward_description)) { skip(CarPlayVideo.SKIP_MILLIS) },
                LinearLayout.LayoutParams(dp(80), dp(80)))
        }
        fun timeText() = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            fontFeatureSettings = "tnum"
        }
        position = timeText()
        length = timeText()
        timeBar = SeekBar(this).apply {
            progressTintList = ColorStateList.valueOf(Color.WHITE)
            thumbTintList = ColorStateList.valueOf(Color.WHITE)
            secondaryProgressTintList = ColorStateList.valueOf(0x80FFFFFF.toInt())
            progressBackgroundTintList = ColorStateList.valueOf(0x4DFFFFFF)
            minimumHeight = dp(48)
            setPadding(dp(20), dp(16), dp(20), dp(16))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) position.text = time(progress.toLong())
                }

                override fun onStartTrackingTouch(bar: SeekBar) {
                    scrubbing = true
                    main.removeCallbacks(hideControls)
                }

                override fun onStopTrackingTouch(bar: SeekBar) {
                    scrubbing = false
                    if (prepared) player.seekTo(bar.progress.toLong())
                    showControls()
                }
            })
        }
        val timeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(position)
            addView(timeBar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(length)
        }
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(32), dp(48), dp(32), dp(24))
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xCC000000.toInt(), 0))
            addView(buttons)
            addView(timeRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(16) })
        }
        return FrameLayout(this).apply {
            addView(back, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START)
                .apply { setMargins(dp(24), dp(24), dp(24), dp(24)) })
            addView(bottom, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
            // A tap on the picture while the controls show hides them again.
            setOnClickListener { hideControls.run() }
        }
    }

    private fun showControls() {
        updatePlayPause()
        updateTime()
        controls.visibility = View.VISIBLE
        main.removeCallbacks(tick)
        main.postDelayed(tick, TICK_MILLIS)
        main.removeCallbacks(hideControls)
        if (!scrubbing) main.postDelayed(hideControls, CONTROLS_MILLIS)
    }

    private fun updatePlayPause() {
        val playing = CarPlayVideo.playing
        playPause.setImageResource(if (playing) Media3R.drawable.exo_icon_pause else Media3R.drawable.exo_icon_play)
        playPause.contentDescription = getString(if (playing) R.string.video_pause else R.string.video_play)
    }

    /** The time bar: position, buffered part and length; no bar while the length is unknown (live). */
    private fun updateTime() {
        val total = player.duration.takeIf { prepared && it != C.TIME_UNSET && it > 0 }
        timeBar.isEnabled = total != null
        timeBar.visibility = if (total != null) View.VISIBLE else View.INVISIBLE
        length.text = total?.let(::time) ?: ""
        if (total == null) {
            position.text = if (prepared) time(player.currentPosition) else ""
            return
        }
        timeBar.max = total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        timeBar.secondaryProgress = player.bufferedPosition.coerceIn(0, total).toInt()
        if (!scrubbing) {
            timeBar.progress = player.currentPosition.coerceIn(0, total).toInt()
            position.text = time(player.currentPosition)
        }
    }

    private fun time(millis: Long): String {
        val seconds = (millis / 1000).coerceAtLeast(0)
        return if (seconds >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
        else String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
    }

    fun state(): VideoInCar.PlayerState {
        val duration = player.duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0) ?: 0
        return VideoInCar.PlayerState(
            prepared = prepared,
            playing = player.isPlaying,
            positionSeconds = player.currentPosition / 1000.0,
            durationSeconds = duration / 1000.0,
            bufferedSeconds = player.bufferedPosition / 1000.0,
        )
    }

    fun load() {
        val url = CarPlayVideo.url ?: return
        if (url == loadedUrl) return
        loadedUrl = url
        prepared = false
        encryptionLogged = false
        Log.i(TAG, "loading video scheme=${Uri.parse(url).scheme.orEmpty()} ${playbackNetworkSummary()}")
        val item = MediaItem.Builder().setUri(url)
            .apply { if (CarPlayVideo.streaming) setMimeType(MimeTypes.APPLICATION_M3U8) }
            .build()
        player.setMediaItem(item, (CarPlayVideo.pendingSeekMillis ?: CarPlayVideo.startMillis).toLong())
        CarPlayVideo.pendingSeekMillis = null
        player.prepare()
        applyRate()
    }

    fun applyRate() {
        player.playWhenReady = CarPlayVideo.playing
        if (controls.visibility == View.VISIBLE) updatePlayPause()
    }

    /** Moves the playback position by [deltaMillis], within the video. */
    fun skip(deltaMillis: Int) {
        if (!prepared) return
        val end = player.duration.takeIf { it != C.TIME_UNSET } ?: Long.MAX_VALUE
        player.seekTo((player.currentPosition + deltaMillis).coerceIn(0, end))
    }

    fun applySeek() {
        val target = CarPlayVideo.pendingSeekMillis ?: return
        player.seekTo(target.toLong()) // ExoPlayer seeks to the exact position
        CarPlayVideo.pendingSeekMillis = null
    }

    // Which encryption the HLS stream uses, without URLs (they carry tokens).
    private fun logEncryption() {
        if (encryptionLogged) return
        val manifest = player.currentManifest as? HlsManifest ?: return
        encryptionLogged = true
        val media = manifest.mediaPlaylist
        val keyScheme = media.segments.firstNotNullOfOrNull { it.fullSegmentEncryptionKeyUri }
            ?.let { android.net.Uri.parse(it).scheme ?: "relative" }
        val drm = media.protectionSchemes?.let { init ->
            (0 until init.schemeDataCount).map { init.get(it).uuid }
        }
        val sessionKeys = manifest.multivariantPlaylist.sessionKeyDrmInitData.map { it.schemeType }
        Log.i(TAG, "HLS encryption aes128KeyScheme=$keyScheme sampleAesSchemeType=${media.protectionSchemes?.schemeType} " +
            "drmUuids=$drm sessionKeys=$sessionKeys segments=${media.segments.size}")
    }

    private fun causes(error: Throwable): String = generateSequence(error) { it.cause }.take(4)
        .joinToString(" <- ") { "${it.javaClass.simpleName}(${it.message?.replace(Regex("\\w+://\\S+"), "<url>")})" }

    // The network summary is diagnostics only; a missing ACCESS_NETWORK_STATE permission just
    // means the report shows less, and its constant does not exist before API 23 anyway.
    @SuppressLint("MissingPermission")
    private fun playbackNetworkSummary(): String {
        val manager = getSystemService(ConnectivityManager::class.java)
        val network = manager?.activeNetwork
        val capabilities = network?.let(manager::getNetworkCapabilities)
        if (network == null || capabilities == null) return "network=none"
        val transports = buildList {
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("wifi")
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("cellular")
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add("ethernet")
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add("vpn")
        }
        return "network=${transports.ifEmpty { listOf("other") }.joinToString("+")} " +
            "internet=${capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)} " +
            "validated=${capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)}"
    }

    override fun onDestroy() {
        main.removeCallbacks(hideControls)
        main.removeCallbacks(tick)
        if (CarPlayVideo.activity === this) {
            CarPlayVideo.activity = null
            CarPlayVideo.onPlayerClosed(if (prepared) player.currentPosition.toInt() else null)
        }
        player.release()
        super.onDestroy()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

    private companion object {
        const val TAG = "DiPlay-Video"
        const val CONTROLS_MILLIS = 5_000L
        const val TICK_MILLIS = 500L
    }
}

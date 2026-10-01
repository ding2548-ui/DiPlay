package com.shilapi.xcertplay

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
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
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.VideoView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.shilapi.xcertplay.airplay.VideoInCar
import com.shilapi.xcertplay.host.R

/**
 * The car's own player for iOS 27 video in car (see [CarPlayVideo]). Full screen over CarPlay, which
 * stays connected underneath; a tap shows Back to CarPlay and play/pause for a few seconds.
 */
class CarPlayVideoActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var video: VideoView
    private lateinit var controls: View
    private lateinit var playPause: TextView
    private var loadedUrl: String? = null
    private var prepared = false
    private var player: MediaPlayer? = null
    private val hideControls = Runnable { controls.visibility = View.GONE }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        video = VideoView(this)
        controls = controlBar()
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(video, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
            addView(controls, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START)
                .apply { setMargins(dp(24), dp(24), dp(24), dp(24)) })
            setOnClickListener { showControls() }
        })
        video.setOnPreparedListener { prepared ->
            player = prepared
            this.prepared = true
            CarPlayVideo.pendingSeekMillis?.let { seekExactly(it); CarPlayVideo.pendingSeekMillis = null }
            applyRate()
        }
        video.setOnErrorListener { _, what, extra ->
            Log.w(TAG, "player error what=$what extra=$extra")
            CarPlayVideo.onPlayerFailed(
                when (extra) {
                    MediaPlayer.MEDIA_ERROR_IO, MediaPlayer.MEDIA_ERROR_TIMED_OUT -> VideoInCar.ERROR_NETWORK
                    MediaPlayer.MEDIA_ERROR_MALFORMED, MediaPlayer.MEDIA_ERROR_UNSUPPORTED -> VideoInCar.ERROR_INCOMPATIBLE_ASSET
                    else -> VideoInCar.ERROR_DECODER
                },
            )
            true
        }
        CarPlayVideo.activity = this
        load()
        showControls()
    }

    private fun controlBar(): View {
        fun pill(text: String, onClick: () -> Unit) = TextView(this).apply {
            this.text = text
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            gravity = Gravity.CENTER
            minHeight = dp(64)
            minWidth = dp(64)
            setPadding(dp(24), 0, dp(24), 0)
            background = GradientDrawable().apply { cornerRadius = dp(32).toFloat(); setColor(0xB3000000.toInt()) }
            setOnClickListener { onClick(); showControls() }
        }
        playPause = pill("") { CarPlayVideo.setPlaying(!CarPlayVideo.playing) }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(pill("‹  " + getString(R.string.video_back_to_carplay)) { finish() })
            addView(playPause, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(16) })
        }
    }

    private fun showControls() {
        updatePlayPause()
        controls.visibility = View.VISIBLE
        main.removeCallbacks(hideControls)
        main.postDelayed(hideControls, CONTROLS_MILLIS)
    }

    private fun updatePlayPause() {
        val playing = CarPlayVideo.playing
        playPause.text = if (playing) "❚❚" else "▶"
        playPause.contentDescription = getString(if (playing) R.string.video_pause else R.string.video_play)
    }

    fun state() = VideoInCar.PlayerState(
        prepared = prepared,
        playing = video.isPlaying,
        positionSeconds = video.currentPosition / 1000.0,
        durationSeconds = if (prepared) video.duration.coerceAtLeast(0) / 1000.0 else 0.0,
        bufferedSeconds = if (prepared) video.duration.coerceAtLeast(0) / 1000.0 * video.bufferPercentage / 100.0 else 0.0,
    )

    fun load() {
        val url = CarPlayVideo.url ?: return
        if (url == loadedUrl) return
        loadedUrl = url
        prepared = false
        player = null
        video.setVideoURI(Uri.parse(url))
        if (CarPlayVideo.startMillis > 0) CarPlayVideo.pendingSeekMillis = CarPlayVideo.startMillis
    }

    fun applyRate() {
        if (CarPlayVideo.playing) video.start() else video.pause()
        if (controls.visibility == View.VISIBLE) updatePlayPause()
    }

    /** Moves the playback position by [deltaMillis], within the video. */
    fun skip(deltaMillis: Int) {
        if (!prepared) return
        seekExactly((video.currentPosition + deltaMillis).coerceIn(0, video.duration.coerceAtLeast(0)))
    }

    fun applySeek() {
        val target = CarPlayVideo.pendingSeekMillis ?: return
        if (!prepared) return // applied once the player is prepared
        seekExactly(target)
        CarPlayVideo.pendingSeekMillis = null
    }

    // The exact position, not the previous key frame, so a 10 s skip really moves 10 s.
    private fun seekExactly(millis: Int) {
        player?.seekTo(millis.toLong(), MediaPlayer.SEEK_CLOSEST) ?: video.seekTo(millis)
    }

    override fun onDestroy() {
        main.removeCallbacks(hideControls)
        if (CarPlayVideo.activity === this) {
            CarPlayVideo.activity = null
            CarPlayVideo.onPlayerClosed(if (prepared) video.currentPosition else null)
        }
        video.stopPlayback()
        super.onDestroy()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val TAG = "DiPlay-Video"
        const val CONTROLS_MILLIS = 4_000L
    }
}

package com.shilapi.xcertplay

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.shilapi.xcertplay.airplay.CarPlayButton
import com.shilapi.xcertplay.airplay.VideoInCar
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.orchestration.CarPlayVideoListener
import java.util.concurrent.Executors

/**
 * iOS 27 video in car (see [VideoInCar]). The iPhone hands the car a media URL (insertPlayQueueItem)
 * and drives it (setRate, seek, stop); the car plays it in [CarPlayVideoActivity], which opens when the
 * iPhone sends requestUI "videoplayback:" and only while the car is in P.
 *
 * The parked state comes from [LeapmotorGearMonitor] (the Leapmotor CAN broadcast), replacing the
 * upstream BYD ADB source.
 */
internal object CarPlayVideo : CarPlayVideoListener {
    private const val TAG = "DiPlay-Video"
    private const val SKIP_MILLIS = 10_000

    private val main = Handler(Looper.getMainLooper())
    private val sender = Executors.newSingleThreadExecutor { Thread(it, "diplay-video-reply").apply { isDaemon = true } }
    @Volatile private var appContext: Context? = null
    @Volatile private var controller: CarPlayController? = null

    // Main-thread state; CarPlayVideoActivity applies it.
    private var streamId: Long? = null
    private var itemUuid: Any? = null
    var url: String? = null
        private set
    var startMillis = 0
        private set
    var playing = false
        private set
    var pendingSeekMillis: Int? = null
    var activity: CarPlayVideoActivity? = null

    fun attach(context: Context, next: CarPlayController) {
        appContext = context.applicationContext
        controller = next
        next.videoListener = this
    }

    fun detach(expected: CarPlayController?) {
        if (expected != null && controller !== expected) return
        controller = null
        appContext = null
        activity?.finish()
        stop()
    }

    override fun readParked(): Boolean? = LeapmotorGearMonitor.parked()

    override fun onVideoAllowedChanged(allowed: Boolean) {
        if (!allowed) main.post { closePlayer("the car left P") }
    }

    override fun onVideoSessionEnded() {
        main.post {
            stop()
            streamId = null
        }
    }

    override fun onVideoUiRequested() {
        main.post { show() }
    }

    override fun onVideoMessage(streamId: Long, message: Map<String, Any?>) {
        main.post { handle(streamId, message) }
    }

    /**
     * A steering-wheel media key (CarPlayButton index) while the player is on screen: play and pause
     * toggle it, next and previous skip 10 s. The iPhone is not asked: a CarPlay play/pause makes it end
     * the video session. Returns false when no player is open. Main thread not required.
     */
    fun onMediaKey(index: Int): Boolean {
        val player = activity ?: return false
        when (index) {
            CarPlayButton.NEXT -> player.skip(SKIP_MILLIS)
            CarPlayButton.PREVIOUS -> player.skip(-SKIP_MILLIS)
            else -> setPlaying(!playing)
        }
        return true
    }

    /** Play or pause from the car (wheel or on-screen button); tells the iPhone at once. Main thread. */
    fun setPlaying(next: Boolean) {
        playing = next
        activity?.applyRate()
        streamId?.let { reply(it, VideoInCar.playbackStateNotification(next, itemUuid)) }
    }

    /**
     * The car's player cannot play the item, for example protected video: tell the iPhone as Apple's
     * receiver does, say so on the car screen and go back to CarPlay instead of showing black. Main thread.
     */
    fun onPlayerFailed(code: Int) {
        Log.w(TAG, "item cannot play here code=$code")
        streamId?.let { reply(it, VideoInCar.errorNotification(itemUuid, code)) }
        appContext?.let { Toast.makeText(it, R.string.video_cannot_play, Toast.LENGTH_LONG).show() }
        stop()
    }

    /** The player closed on the car (Back, or the car left P): pause, so the iPhone shows it paused. */
    fun onPlayerClosed(positionMillis: Int?) {
        positionMillis?.let { startMillis = it }
        if (playing) setPlaying(false)
    }

    private fun handle(streamId: Long, message: Map<String, Any?>) {
        this.streamId = streamId
        val request = message["kind"] == "request"
        when (val type = message["type"] as? String) {
            "insertPlayQueueItem" -> {
                val item = VideoInCar.parseItem(message)
                if (item == null) {
                    Log.w(TAG, "queue item this player cannot play")
                    return
                }
                Log.i(TAG, "queue item app=${(message["item"] as? Map<*, *>)?.get("clientBundleID")}")
                url = item.url
                itemUuid = item.uuid
                startMillis = item.startMillis
                activity?.load()
            }
            "setRate" -> {
                playing = ((message["rate"] as? Number)?.toDouble() ?: 0.0) > 0.0
                activity?.applyRate()
            }
            "seek" -> {
                VideoInCar.seekMillis(message)?.let {
                    pendingSeekMillis = it
                    activity?.applySeek()
                }
                if (request) reply(streamId, VideoInCar.seekResponse(message["messageID"]))
            }
            "playbackInfo" -> if (request) {
                reply(streamId, VideoInCar.playbackInfoResponse(message["messageID"], itemUuid, playerState()))
            }
            "property" -> if (request) {
                reply(streamId, VideoInCar.propertyResponse(message["messageID"], message["property"], playerState()))
            }
            "stop", "removePlayQueueItem" -> stop()
            "setProperty" -> Unit
            else -> Log.i(TAG, "video message $type")
        }
    }

    // While the player is closed the item stays paused where it was, so the iPhone keeps its position.
    private fun playerState(): VideoInCar.PlayerState? =
        activity?.state() ?: url?.let { VideoInCar.PlayerState(false, false, startMillis / 1000.0, 0.0, 0.0) }

    private fun reply(streamId: Long, message: Map<String, Any?>) {
        val target = controller ?: return
        sender.execute {
            // A reply that cannot be sent must never take CarPlay down with it.
            val sent = runCatching { target.sendVideoMessage(streamId, message) }
                .onFailure { Log.w(TAG, "reply ${message["type"]} failed: ${it.javaClass.simpleName}") }
                .getOrDefault(false)
            if (!sent) Log.w(TAG, "reply ${message["type"]} not sent")
        }
    }

    private fun show() {
        val context = appContext ?: return
        when {
            url == null -> Log.w(TAG, "video player requested without a playable item")
            !VideoInCar.allowed -> Log.w(TAG, "video player requested while not parked")
            activity != null -> Unit
            else -> context.startActivity(
                Intent(context, CarPlayVideoActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    private fun closePlayer(reason: String) {
        val player = activity ?: return
        Log.i(TAG, "closing the video player: $reason")
        player.finish()
    }

    private fun stop() {
        url = null
        itemUuid = null
        playing = false
        pendingSeekMillis = null
        activity?.finish()
    }
}

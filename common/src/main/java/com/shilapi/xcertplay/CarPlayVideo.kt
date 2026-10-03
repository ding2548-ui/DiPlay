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
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * iOS 27 video in car (see [VideoInCar]). The iPhone hands the car a media URL (insertPlayQueueItem)
 * and drives it (setRate, seek, stop); the car plays it in [CarPlayVideoActivity], which opens as soon
 * as the iPhone starts the item (or sends requestUI "videoplayback:") and only while the car is in N
 * (Leapmotor has no P).
 *
 * The video-allowed state comes from [LeapmotorGearMonitor] (the Leapmotor CAN broadcast, N 挡),
 * replacing the upstream BYD parked source; Leapmotor gates on N instead of P.
 */
internal object CarPlayVideo : CarPlayVideoListener {
    private const val TAG = "DiPlay-Video"
    const val SKIP_MILLIS = 10_000
    private const val URL_TIMEOUT_SECONDS = 10L

    private val main = Handler(Looper.getMainLooper())
    private val sender = Executors.newSingleThreadExecutor { Thread(it, "diplay-video-reply").apply { isDaemon = true } }
    @Volatile private var appContext: Context? = null
    @Volatile private var controller: CarPlayController? = null

    // Main-thread state; CarPlayVideoActivity applies it. The stream and item are also read by loader threads.
    @Volatile private var streamId: Long? = null
    @Volatile private var itemUuid: Any? = null
    var url: String? = null
        private set
    /** The iPhone called the item streaming (HLS) rather than a file. */
    var streaming = false
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

    override fun readVideoAllowed(): Boolean? = LeapmotorGearMonitor.videoAllowed()

    override fun onVideoAllowedChanged(allowed: Boolean) {
        if (!allowed) main.post { closePlayer("the car left N") }
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

    /** The player closed on the car (Back, or the car left N): pause, so the iPhone shows it paused. */
    fun onPlayerClosed(positionMillis: Int?) {
        positionMillis?.let { startMillis = it }
        if (playing) setPlaying(false)
    }

    private fun handle(streamId: Long, message: Map<String, Any?>) {
        this.streamId = streamId
        val request = message["kind"] == "request"
        when (val type = message["type"] as? String) {
            "insertPlayQueueItem" -> {
                // An app's own scheme is loaded through the iPhone (IphoneResolvingDataSource).
                val item = VideoInCar.parseItem(message, iphoneLoadsAppSchemes = true)
                if (item == null) {
                    Log.w(TAG, "queue item this player cannot play")
                    return
                }
                Log.i(TAG, "queue item app=${(message["item"] as? Map<*, *>)?.get("clientBundleID")}")
                url = item.url
                itemUuid = item.uuid
                val queued = message["item"] as? Map<*, *>
                streaming = queued?.get("mediaType") == "streaming"
                Log.i(TAG, "queue item host=${android.net.Uri.parse(item.url).host} mediaType=${queued?.get("mediaType")}")
                startMillis = item.startMillis
                activity?.load()
            }
            "setRate" -> {
                playing = ((message["rate"] as? Number)?.toDouble() ?: 0.0) > 0.0
                // The iPhone starts the item at once, as Apple's receiver plays it straight
                // away, so open the player now instead of waiting for Now Playing's video button.
                if (playing && activity == null) show()
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
            "unhandledURL" -> onUrlLoaded(message)
            "stop", "removePlayQueueItem" -> stop()
            "setProperty" -> Unit
            else -> Log.i(TAG, "video message $type keys=${message.keys}")
        }
    }

    /** What the iPhone answered to [resolveOnIphone]. */
    class LoadedUrl(val status: Int?, val data: ByteArray?, val location: String?)

    private val pendingUrls = ConcurrentHashMap<Long, CompletableFuture<Map<*, *>>>()
    private val nextUrlRequest = AtomicLong(1)

    /**
     * Asks the iPhone to load a URL the car cannot (an app's own scheme), as Apple's receiver
     * does: unhandledURL request with FCUP_Response_URL / _IsContentKeyRequest / _RequestID, answered
     * with FCUP_Response_Data, _StatusCode and _Headers (Location). Blocking; a loader thread.
     */
    fun resolveOnIphone(url: String): LoadedUrl? {
        val stream = streamId ?: return null
        val id = nextUrlRequest.getAndIncrement()
        val answer = CompletableFuture<Map<*, *>>()
        pendingUrls[id] = answer
        reply(stream, linkedMapOf(
            "type" to "unhandledURL",
            "kind" to "request",
            "request" to linkedMapOf(
                "FCUP_Response_URL" to url,
                "FCUP_Response_IsContentKeyRequest" to false,
                "FCUP_Response_RequestID" to id,
            ),
            "item" to linkedMapOf("uuid" to itemUuid),
        ))
        Log.i(TAG, "asked the iPhone to load a ${android.net.Uri.parse(url).scheme} URL request=$id")
        val response = try {
            answer.get(URL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (_: Exception) {
            Log.w(TAG, "no iPhone answer for request=$id")
            null
        } finally {
            pendingUrls.remove(id)
        }
        response ?: return null
        val headers = response["FCUP_Response_Headers"] as? Map<*, *>
        return LoadedUrl(
            status = (response["FCUP_Response_StatusCode"] as? Number)?.toInt(),
            data = response["FCUP_Response_Data"] as? ByteArray,
            location = (headers?.get("Location") ?: headers?.get("location")) as? String,
        )
    }

    private fun onUrlLoaded(message: Map<String, Any?>) {
        val response = message["response"] as? Map<*, *>
        val id = (response?.get("FCUP_Response_RequestID") as? Number)?.toLong()
        Log.i(TAG, "iPhone unhandledURL answer request=$id keys=${message.keys} responseKeys=${response?.keys}")
        if (id != null) pendingUrls[id]?.complete(response)
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
            !VideoInCar.allowed -> Log.w(TAG, "video player requested while not in N")
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

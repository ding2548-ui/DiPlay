package com.shilapi.xcertplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.shilapi.xcertplay.airplay.CarPlayButton
import com.shilapi.xcertplay.orchestration.CarPlayController
import org.json.JSONObject

/**
 * The Leapmotor head unit's car-control broadcasts, decoded.
 *
 * Protocol (captured on the real car, see the car-fangkong-inject skill): every key press arrives
 * as a parallel broadcast whose `receiver` extra is a byte[]; the first two bytes are the length
 * `(b0 * 0x64) + b1` and a UTF-8 JSON body follows from offset 2, e.g.
 * `{"data":{"action":"nextOne","type":-1},"type":"music"}`. `data.type` is -1 on the real car, so
 * nothing here may gate on a "media source" value.
 */
internal object LeapmotorMediaProtocol {
    /** Primary channel first; the rest are the fallbacks the car also emits. */
    val ACTIONS = listOf(
        "car.meter.music.BROADCAST",
        "car.hmi.music.BROADCAST",
        "com.leapmotor.command.music",
        "com.leapmotor.command.multimedia",
        "com.leapmotor.customkey.music.pauseplay",
        "com.leapmotor.ICU2MMICtrl",
    )

    /** The byte[] payload → `data.action`, or null when it is not a usable key press. */
    fun actionFromPayload(payload: ByteArray?): String? {
        val bytes = payload ?: return null
        if (bytes.size <= 2) return null
        val declared = (bytes[0].toInt() and 0xff) * 0x64 + (bytes[1].toInt() and 0xff)
        val end = minOf(bytes.size, 2 + declared)
        if (end <= 2) return null
        return actionFromJson(String(bytes, 2, end - 2, Charsets.UTF_8))
    }

    /** The JSON body → `data.action`; a non-music `type` is rejected, a blank one is tolerated. */
    fun actionFromJson(text: String?): String? {
        val body = text?.trim().orEmpty()
        if (body.isEmpty() || !body.startsWith("{")) return null
        return runCatching {
            val root = JSONObject(body)
            // Our own "pause the stock player" command travels on the same car actions; it carries
            // a self marker so it never comes back to us as a key press.
            if (
                root.optString(CarPlayAudioOwnership.SELF_MARKER_KEY) ==
                CarPlayAudioOwnership.SELF_MARKER_VALUE
            ) {
                return@runCatching null
            }
            val type = root.optString("type")
            if (type.isNotEmpty() && !type.equals("music", ignoreCase = true)) return@runCatching null
            root.optJSONObject("data")?.optString("action")?.trim()?.takeIf { it.isNotEmpty() }
        }.getOrNull()
    }
}

/**
 * Steering-wheel media keys of the Leapmotor head unit, forwarded to CarPlay as HID presses.
 *
 * The car broadcasts the key to every registered receiver (non-ordered broadcast), so DiPlay can
 * listen without displacing the stock player. Keys are only meaningful while a CarPlay session is
 * up; [controller] is set on attach and cleared on detach, and presses outside a session are
 * logged and dropped.
 */
internal object LeapmotorMediaKeys {
    private const val TAG = "DiPlay-MediaKeys"

    private val mainHandler = Handler(Looper.getMainLooper())
    private var receiver: BroadcastReceiver? = null
    private var appContext: Context? = null

    @Volatile
    private var controller: CarPlayController? = null

    /** Both the logcat line and the app's diagnostic report receive these. */
    @Volatile
    var onDiagnostic: ((String) -> Unit)? = null

    private var lastKeyUptimeMillis = 0L
    private var lastKeyLabel = ""

    @Synchronized
    fun attach(context: Context, next: CarPlayController) {
        val application = context.applicationContext
        appContext = application
        controller = next
        LearnedWheelKeys.attach(application, next)
        if (receiver != null) return
        val filter = IntentFilter().apply { LeapmotorMediaProtocol.ACTIONS.forEach(::addAction) }
        val created = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                // Parsing and the HID dispatch are cheap; no work runs on the main thread here.
                handle(intent)
            }
        }
        runCatching { application.registerReceiver(created, filter) }
            .onSuccess {
                receiver = created
                report("media keys listening on ${LeapmotorMediaProtocol.ACTIONS.size} car actions")
            }
            .onFailure { report("media key receiver not registered: ${it.message}") }
    }

    @Synchronized
    fun detach(expected: CarPlayController?) {
        if (expected != null && controller !== expected) return
        controller = null
        LearnedWheelKeys.detach(expected)
        receiver?.let { active ->
            runCatching { appContext?.unregisterReceiver(active) }
            receiver = null
            report("media keys detached")
        }
    }

    /** Exposed for the in-app self test and unit tests. */
    fun dispatch(action: String?, source: String): Boolean {
        val active = controller
        if (action.isNullOrBlank()) return false
        // The learning dialog consumes the next press as its capture input.
        if (LearnedWheelKeys.isCapturing() &&
            LearnedWheelKeys.onKey(WheelBinding.CAR_PREFIX + action.trim())
        ) {
            return true
        }
        // User-learned bindings win over the built-in per-car table.
        val learned = LearnedWheelKeys.learnedCarButton(action)
        if (learned != null) {
            if (active == null) {
                report("learned key ignored (no CarPlay session) action=$action")
                return false
            }
            if (CarPlayVideo.onMediaKey(learned)) {
                report("media key source=$source action=$action -> learned video player $learned")
                return true
            }
            val learnedSent = active.sendMediaButton(learned)
            report("media key source=$source action=$action -> learned $learned sent=$learnedSent")
            return learnedSent
        }
        val mapped = CarPlayButton.forLeapmotorAction(action)
        if (!CarPlayButton.isWheelAction(action)) {
            // The car's own commands (its `pause` when another app takes focus, its echoes of what
            // we sent) share this bus. Forwarding them paused CarPlay itself; drop them and keep the
            // evidence in the report.
            report("media key ignored: car-internal command source=$source action=$action")
            return false
        }
        if (mapped == null) {
            // Unknown button: keep the evidence, a voice/Siri key is expected to show up here.
            report("media key unmapped source=$source action=$action")
            return false
        }
        if (active == null) {
            report("media key ignored (no CarPlay session) action=$action")
            return false
        }
        // While the iOS 27 video player is on screen the keys drive it (skip / pause), not CarPlay.
        if (CarPlayVideo.onMediaKey(mapped)) {
            report("media key source=$source action=$action -> video player $mapped")
            return true
        }
        // A duplicate DOWN/UP pair from the car would otherwise jump two tracks.
        val now = SystemClock.uptimeMillis()
        val label = "$action=$mapped"
        if (label == lastKeyLabel && now - lastKeyUptimeMillis < DUPLICATE_WINDOW_MILLIS) {
            report("media key duplicate suppressed action=$action")
            return false
        }
        lastKeyLabel = label
        lastKeyUptimeMillis = now
        val sent = active.sendMediaButton(mapped)
        report("media key source=$source action=$action -> CarPlay $mapped sent=$sent")
        return sent
    }

    private fun handle(intent: Intent?) {
        val actionName = intent?.action ?: return
        val payload = runCatching {
            @Suppress("DEPRECATION")
            intent.getByteArrayExtra("receiver")
        }.getOrNull()
        val action = LeapmotorMediaProtocol.actionFromPayload(payload)
            ?: intent.getStringExtra("action")?.takeIf { it.isNotBlank() }
            ?: run {
                // A self-marked payload (our stock-player pause) lands here by design.
                val marked = runCatching {
                    val text = payload?.let { String(it, 2, (it.size - 2).coerceAtLeast(0), Charsets.UTF_8) }
                        ?: ""
                    text.contains("\"${CarPlayAudioOwnership.SELF_MARKER_KEY}\":\"${CarPlayAudioOwnership.SELF_MARKER_VALUE}\"")
                }.getOrDefault(false)
                report(
                    if (marked) "media key ignored: self-marked command action=$actionName"
                    else "media key payload unusable action=$actionName bytes=${payload?.size ?: 0}",
                )
                return
            }
        dispatch(action, actionName)
    }

    private fun report(message: String) {
        Log.i(TAG, message)
        runCatching { onDiagnostic?.invoke(message) }
    }

    private const val DUPLICATE_WINDOW_MILLIS = 250L
}

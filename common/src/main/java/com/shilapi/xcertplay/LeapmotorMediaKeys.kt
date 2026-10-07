package com.shilapi.xcertplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
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
    /**
     * Channel A, the C-series / S01 wheel: one JSON `data.action` per press.
     * The first entry is the channel actually observed on the real S01 unit.
     */
    private val CHANNEL_A = listOf(
        "car.meter.music.BROADCAST",
        "car.hmi.music.BROADCAST",
        "com.leapmotor.command.music",
        "com.leapmotor.command.multimedia",
    )

    /**
     * The rest of the stock player's own ten-action filter (`KGMusicBrowserService.onCreate`).
     * They carry nothing we forward — the car's housekeeping, source switching and the echo of
     * what we sent — but listening to them keeps the evidence in the report, which is how a
     * wheel channel we have not identified yet gets found on a unit we cannot reach.
     */
    private val STOCK_HOUSEKEEPING = listOf(
        "com.leapmotor.command.netRadio",
        "com.leapmotor.command.fmradio",
        "com.leapmotor.music.netradio.search",
        "car.meter.query.BROADCAST",
        "com.leapmotor.pohone.toxmly.calling",
    )

    /** Channel B (T03) and the old ICU alias — integer extras, decoded by [WheelLearning]. */
    val EXTRA_CHANNELS = listOf(
        "com.leapmotor.customkey.music.pauseplay",
        "com.leapmotor.ICU2MMICtrl",
    )

    /**
     * Everything the app registers on, channel A first. The two extra channels are also
     * registered by [LearnedWheelKeys.ensureCarBusReceiver]; a car that emits both would deliver
     * the press twice, which the shared de-duplication window absorbs.
     */
    val ACTIONS = CHANNEL_A + STOCK_HOUSEKEEPING + EXTRA_CHANNELS

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
                root.optString(SELF_MARKER_KEY) ==
                SELF_MARKER_VALUE
            ) {
                return@runCatching null
            }
            val type = root.optString("type")
            if (type.isNotEmpty() && !type.equals("music", ignoreCase = true)) return@runCatching null
            root.optJSONObject("data")?.optString("action")?.trim()?.takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    // ---- Channel B: the T03 wheel, which rides integer extras instead of a JSON body ----
    // Reverse-engineered from the stock APK's own receiver (see
    // `com.leapmotor.multimedia-AppMain-方控指令与切歌暂停实现.md`, §五): one action carries every
    // wheel key and the identity is the extra value, not the action name. Verified on the
    // emulator — every command really changed the song / toggled playback through the kugou
    // tvsdk. The stock receiver bails out when both extras are zero, and so do we.

    /** Extra names as the stock receiver reads them, camelCase aliases included. */
    private val MEDIA_KEY_EXTRA = arrayOf("ICU_MediaKey", "mediaKey")
    private val MEDIA_SWITCH_EXTRA = arrayOf("ICU_MediaSwitch", "mediaSwitch")

    /** True when this action is one that carries the wheel in integer extras. */
    fun isExtraChannel(action: String?): Boolean = action != null && action in EXTRA_CHANNELS

    /** Reads the first present extra among [keys]; 0 when none of them is set. */
    private fun intExtra(intent: Intent, vararg keys: String): Int {
        for (key in keys) {
            val value = runCatching { intent.getIntExtra(key, Int.MIN_VALUE) }.getOrDefault(Int.MIN_VALUE)
            if (value != Int.MIN_VALUE) return value
        }
        return 0
    }

    /**
     * The channel-B wheel press as `data.action` vocabulary, or null when this broadcast is not a
     * key (an echo, or an extra set we do not know). `ICU_MediaSwitch` 2 = next, 1 = previous;
     * `ICU_MediaKey` 1 = play/pause.
     */
    fun extraChannelAction(intent: Intent?): String? {
        val source = intent ?: return null
        val switch = intExtra(source, *MEDIA_SWITCH_EXTRA)
        val key = intExtra(source, *MEDIA_KEY_EXTRA)
        return when {
            key == 1 -> "playpause"
            switch == 2 -> "nextOne"
            switch == 1 -> "preOne"
            else -> null
        }
    }

    /** A readable dump of an extra-channel broadcast, so an unknown unit tells us what it sends. */
    fun extraChannelDetail(intent: Intent?): String {
        val source = intent ?: return "none"
        val extras = source.extras ?: return "no extras"
        return extras.keySet().sorted().joinToString(" ") { name ->
            val value = runCatching { extras.get(name) }.getOrNull()
            when (value) {
                null -> "$name=null"
                is ByteArray -> "$name=byte[${value.size}]"
                else -> "$name=$value"
            }
        }
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
/** Marks our own "pause the stock player" command so it never loops back as a key press. */
private const val SELF_MARKER_KEY = "src"
private const val SELF_MARKER_VALUE = "diplay"

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
    private var lastKeyMapped = 0

    @Synchronized
    fun attach(context: Context, next: CarPlayController) {
        val application = context.applicationContext
        appContext = application
        controller = next
        LearnedWheelKeys.attach(application, next)
        LearnedWheelKeys.refreshBroadcastReceivers(application, WheelLearningStore.load(application))
        if (LearnedWheelKeys.isBroadcastLogEnabled(application)) {
            LearnedWheelKeys.setBroadcastLogEnabled(application, true)
        }
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
        // A BROADCAST: binding owns the whole bus action; the per-key payload dispatch stays out.
        if (LearnedWheelKeys.broadcastBindingConsumes(source)) {
            report("media key ignored: bus bound by broadcast binding source=$source")
            return true
        }
        // User-learned bindings win over the built-in per-car table.
        val learned = LearnedWheelKeys.learnedCarButton(action)
        if (learned != null) {
            if (active == null) {
                report("learned key ignored (no CarPlay session) action=$action")
                return false
            }
            if (!claimDispatchWindow(learned)) {
                report("media key duplicate suppressed action=$action")
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
        val mapped = CarPlayMediaButton.forLeapmotorAction(action)
        if (!CarPlayMediaButton.isWheelAction(action)) {
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
        if (!claimDispatchWindow(mapped)) {
            report("media key duplicate suppressed action=$action")
            return false
        }
        // While the iOS 27 video player is on screen the keys drive it (skip / pause), not CarPlay.
        if (CarPlayVideo.onMediaKey(mapped)) {
            report("media key source=$source action=$action -> video player $mapped")
            return true
        }
        val sent = active.sendMediaButton(mapped)
        report("media key source=$source action=$action -> CarPlay $mapped sent=$sent")
        return sent
    }

    /**
     * True when this press is the first of its button within the de-duplication window. One
     * physical press can reach us twice under two different names — the car's broadcast says
     * "nextOne" while the media session says "next" — so the window is keyed on the CarPlay button
     * rather than on the raw action, and every path that dispatches shares it.
     */
    private fun claimDispatchWindow(button: Int): Boolean {
        val now = SystemClock.uptimeMillis()
        if (button == lastKeyMapped && now - lastKeyUptimeMillis < DUPLICATE_WINDOW_MILLIS) return false
        lastKeyMapped = button
        lastKeyUptimeMillis = now
        return true
    }

    private fun handle(intent: Intent?) {
        val actionName = intent?.action ?: return
        // Channel B (T03 / ICU): the wheel rides integer extras and there is no `receiver` body at
        // all, so it must be decoded before the byte[] path — otherwise every press falls through
        // to "payload unusable" and the wheel looks dead.
        if (LeapmotorMediaProtocol.isExtraChannel(actionName)) {
            val fromExtras = LeapmotorMediaProtocol.extraChannelAction(intent)
            if (fromExtras != null) {
                dispatch(fromExtras, actionName)
                return
            }
            // Not a key press we recognise. Dump the extras: an unreachable unit (T03) is
            // identified from these lines alone.
            report(
                "media key extra-channel unmatched action=$actionName " +
                    "extras=[${LeapmotorMediaProtocol.extraChannelDetail(intent)}]",
            )
            return
        }
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
                    text.contains("\"${SELF_MARKER_KEY}\":\"${SELF_MARKER_VALUE}\"")
                }.getOrDefault(false)
                if (!marked) {
                    // No JSON body and no `action` string. On an unidentified unit that is itself
                    // the finding, so dump whatever did arrive instead of a bare byte count.
                    report(
                        "media key payload unusable action=$actionName bytes=${payload?.size ?: 0} " +
                            "extras=[${LeapmotorMediaProtocol.extraChannelDetail(intent)}]",
                    )
                    return
                }
                report("media key ignored: self-marked command action=$actionName")
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

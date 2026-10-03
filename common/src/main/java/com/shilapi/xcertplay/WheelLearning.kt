// SPDX-License-Identifier: GPL-3.0-only
// User-learned steering wheel controls, ported from EasyPlay's legacy wheel stack
// (LegacyWheelMappings / LegacyWheelLearningDialog / LegacyWheelDispatcher).
//
// Instead of hard-coding per-OEM key tables (the BYD/Leapmotor split), the user presses
// a physical button once and it is bound to an action. Only learned keys are forwarded,
// which also removes the "car-internal echo" class of bugs by construction. Two input
// sources feed the learner: generic ACTION_MEDIA_BUTTON key events (any car) and the
// Leapmotor car-control broadcasts (identified by their action string).
package com.shilapi.xcertplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import com.shilapi.xcertplay.airplay.CarPlayButton
import com.shilapi.xcertplay.orchestration.CarPlayController
import org.json.JSONArray
import org.json.JSONObject

/** What a learned wheel key does inside CarPlay. */
enum class WheelAction(val label: String) {
    NEXT("下一首"),
    PREVIOUS("上一首"),
    PLAY("播放"),
    PAUSE("暂停"),
    TOGGLE("播放 / 暂停"),
}

/** One learned key: a stable identifier plus the action it triggers. */
data class WheelBinding(val id: String, val action: WheelAction, val longPress: Boolean = false) {
    /** Human readable key name, e.g. "KEY:24" or "CAR:nextOne". */
    fun label(): String = when {
        id.startsWith(KEY_PREFIX) -> keyLabel(id.removePrefix(KEY_PREFIX).toIntOrNull() ?: 0)
        id.startsWith(CAR_PREFIX) -> "车机键 ${id.removePrefix(CAR_PREFIX)}"
        else -> id
    }

    companion object {
        const val KEY_PREFIX = "KEY:"
        const val CAR_PREFIX = "CAR:"
    }
}

/** SharedPreferences backed JSON store, same shape as EasyPlay's bindings-v1. */
internal object WheelLearningStore {
    const val PREFERENCES = "wheel-learning"
    const val BINDINGS = "bindings-v1"
    private const val MAX_STORED_CHARACTERS = 0x1000
    private val storageLock = Any()

    fun load(context: Context): List<WheelBinding> = synchronized(storageLock) {
        val text = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(BINDINGS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(text)
            (0 until array.length()).mapNotNull { index ->
                val entry = array.optJSONObject(index) ?: return@mapNotNull null
                val id = entry.optString("id")
                val action = entry.optString("action")
                if (id.isEmpty()) return@mapNotNull null
                WheelBinding(id, WheelAction.entries.firstOrNull { it.name == action } ?: return@mapNotNull null,
                    entry.optBoolean("longPress"))
            }
        }.getOrElse { emptyList() }
    }

    fun save(context: Context, bindings: List<WheelBinding>): Boolean = synchronized(storageLock) {
        val array = JSONArray()
        bindings.forEach { binding ->
            array.put(JSONObject().apply {
                put("id", binding.id)
                put("action", binding.action.name)
                put("longPress", binding.longPress)
            })
        }
        val text = array.toString()
        if (text.length > MAX_STORED_CHARACTERS) return false
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit().putString(BINDINGS, text).apply()
        return true
    }

    fun clear(context: Context) = synchronized(storageLock) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().remove(BINDINGS).apply()
    }
}

/**
 * Runtime dispatcher for learned wheel keys. Attaches alongside
 * [LeapmotorMediaKeys]; presses are only forwarded while a CarPlay session is up.
 */
internal object LearnedWheelKeys {
    private const val TAG = "DiPlay-WheelLearning"
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var controller: CarPlayController? = null

    /** Set while the learning dialog waits for a press; receives the captured key id. */
    @Volatile
    private var captureCallback: ((String) -> Unit)? = null

    private var receiver: BroadcastReceiver? = null
    private var appContext: Context? = null
    private var lastKeyUptimeMillis = 0L
    private var lastKeyLabel = ""

    @Volatile
    var onDiagnostic: ((String) -> Unit)? = null

    fun attach(context: Context, next: CarPlayController) {
        controller = next
        ensureReceiver(context)
    }

    /** Registers the MEDIA_BUTTON receiver; needed for learning even before any session. */
    @Synchronized
    private fun ensureReceiver(context: Context) {
        val application = context.applicationContext
        appContext = application
        if (receiver != null) return
        val filter = IntentFilter(Intent.ACTION_MEDIA_BUTTON)
        val created = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                handleMediaButton(intent)
            }
        }
        runCatching { application.registerReceiver(created, filter) }
            .onSuccess {
                receiver = created
                report("learned wheel listening on media buttons")
            }
            .onFailure { report("learned wheel receiver not registered: ${it.message}") }
    }

    fun detach(expected: CarPlayController?) {
        if (expected != null && controller !== expected) return
        controller = null
    }

    /** Learning dialog entry: the next captured key resolves through [onCaptured]. */
    fun beginCapture(context: Context, onCaptured: (String) -> Unit) {
        ensureReceiver(context)
        captureCallback = onCaptured
        report("wheel learning started")
    }

    fun cancelCapture() {
        if (captureCallback != null) {
            captureCallback = null
            report("wheel learning cancelled")
        }
    }

    /** True while a learning dialog is open; suppresses normal dispatching of the press. */
    fun isCapturing(): Boolean = captureCallback != null

    /**
     * Feeds a press through the learner. Returns true when the press was consumed as a
     * capture input or matched a learned binding, false when nothing learned it.
     */
    fun onKey(id: String): Boolean {
        captureCallback?.let { callback ->
            captureCallback = null
            mainHandler.post { callback(id) }
            report("wheel learning captured $id")
            return true
        }
        val binding = WheelLearningStore.load(appContext ?: return false)
            .firstOrNull { it.id == id } ?: return false
        return perform(binding)
    }

    /** Leapmotor car actions consult the learned "CAR:<action>" bindings first. */
    fun learnedCarButton(action: String?): Int? {
        if (action.isNullOrBlank()) return null
        val binding = WheelLearningStore.load(appContext ?: return null)
            .firstOrNull { it.id == WheelBinding.CAR_PREFIX + action.trim() } ?: return null
        return when (binding.action) {
            WheelAction.NEXT -> CarPlayButton.NEXT
            WheelAction.PREVIOUS -> CarPlayButton.PREVIOUS
            WheelAction.PLAY -> CarPlayButton.PLAY
            WheelAction.PAUSE -> CarPlayButton.PAUSE
            WheelAction.TOGGLE -> CarPlayButton.PLAY_PAUSE
        }
    }

    private fun handleMediaButton(intent: Intent?) {
        val event = intent?.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return
        val id = WheelBinding.KEY_PREFIX + event.keyCode
        // One trigger per physical press: act on the UP event only.
        if (event.action != KeyEvent.ACTION_UP) {
            if (isCapturing()) onKey(id)
            return
        }
        onKey(id)
    }

    private fun perform(binding: WheelBinding): Boolean {
        val active = controller
        if (active == null) {
            report("learned wheel ignored (no CarPlay session) ${binding.id} -> ${binding.action}")
            return false
        }
        val button = when (binding.action) {
            WheelAction.NEXT -> CarPlayButton.NEXT
            WheelAction.PREVIOUS -> CarPlayButton.PREVIOUS
            WheelAction.PLAY -> CarPlayButton.PLAY
            WheelAction.PAUSE -> CarPlayButton.PAUSE
            WheelAction.TOGGLE -> CarPlayButton.PLAY_PAUSE
        }
        // While the iOS 27 video player is on screen the keys drive it, not CarPlay.
        if (CarPlayVideo.onMediaKey(button)) {
            report("learned wheel ${binding.id} -> video player $button")
            return true
        }
        val now = SystemClock.uptimeMillis()
        if (binding.id == lastKeyLabel && now - lastKeyUptimeMillis < DUPLICATE_WINDOW_MILLIS) {
            report("learned wheel duplicate suppressed ${binding.id}")
            return false
        }
        lastKeyLabel = binding.id
        lastKeyUptimeMillis = now
        val sent = active.sendMediaButton(button)
        report("learned wheel ${binding.id} -> CarPlay $button sent=$sent")
        return sent
    }

    private fun report(message: String) {
        Log.i(TAG, message)
        runCatching { onDiagnostic?.invoke(message) }
    }

    /** Rough KeyEvent code -> readable name for the learning UI; unknown codes stay numeric. */
    fun keyLabel(code: Int): String = when (code) {
        KeyEvent.KEYCODE_VOLUME_UP -> "音量加"
        KeyEvent.KEYCODE_VOLUME_DOWN -> "音量减"
        KeyEvent.KEYCODE_MEDIA_PLAY -> "媒体播放"
        KeyEvent.KEYCODE_MEDIA_PAUSE -> "媒体暂停"
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> "媒体播放暂停"
        KeyEvent.KEYCODE_MEDIA_NEXT -> "媒体下一首"
        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> "媒体上一首"
        KeyEvent.KEYCODE_MEDIA_STOP -> "媒体停止"
        KeyEvent.KEYCODE_HEADSETHOOK -> "耳机按键"
        KeyEvent.KEYCODE_BACK -> "返回"
        KeyEvent.KEYCODE_DPAD_UP -> "方向上"
        KeyEvent.KEYCODE_DPAD_DOWN -> "方向下"
        KeyEvent.KEYCODE_DPAD_LEFT -> "方向左"
        KeyEvent.KEYCODE_DPAD_RIGHT -> "方向右"
        KeyEvent.KEYCODE_ENTER -> "确认"
        else -> "键码 $code"
    }

    private const val DUPLICATE_WINDOW_MILLIS = 250L
}

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

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
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
        id.startsWith(KEY_PREFIX) -> LearnedWheelKeys.keyLabel(id.removePrefix(KEY_PREFIX).toIntOrNull() ?: 0)
        id.startsWith(CAR_PREFIX) -> "车机键 ${id.removePrefix(CAR_PREFIX)}"
        id.startsWith(BROADCAST_PREFIX) -> "广播 ${id.removePrefix(BROADCAST_PREFIX)}"
        else -> id
    }

    companion object {
        const val KEY_PREFIX = "KEY:"
        const val CAR_PREFIX = "CAR:"
        const val BROADCAST_PREFIX = "BROADCAST:"
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
        // The car-bus receiver (channel B integer extras + the BROADCAST: bindings) used to be
        // created only by setBroadcastLogEnabled(), which is off by default — so on a T03 the
        // wheel channel had no receiver at all and every press vanished silently. It is part of
        // the wheel path, not of the diagnostics, so it is registered unconditionally here.
        ensureCarBusReceiver(context)
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
        // Media button broadcasts come from the system, so the receiver is exported. The flag
        // overload only exists from Android 13 and passing it on API 25 would itself raise
        // NoSuchMethodError, hence the plain call below that version.
        @SuppressLint("UnspecifiedRegisterReceiverFlag")
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                application.registerReceiver(created, filter, Context.RECEIVER_EXPORTED)
            } else {
                application.registerReceiver(created, filter)
            }
        }
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
            WheelAction.NEXT -> CarPlayMediaButton.NEXT
            WheelAction.PREVIOUS -> CarPlayMediaButton.PREVIOUS
            WheelAction.PLAY -> CarPlayMediaButton.PLAY
            WheelAction.PAUSE -> CarPlayMediaButton.PAUSE
            WheelAction.TOGGLE -> CarPlayMediaButton.PLAY_PAUSE
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
            WheelAction.NEXT -> CarPlayMediaButton.NEXT
            WheelAction.PREVIOUS -> CarPlayMediaButton.PREVIOUS
            WheelAction.PLAY -> CarPlayMediaButton.PLAY
            WheelAction.PAUSE -> CarPlayMediaButton.PAUSE
            WheelAction.TOGGLE -> CarPlayMediaButton.PLAY_PAUSE
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

    // ---- Broadcast monitoring: learn wheel keys by their broadcast ACTION ----
    data class BroadcastLogEntry(
        val action: String,
        val detail: String,
        val timeMillis: Long,
        /** The WheelBinding id this entry binds to (extras-signature level for ICU2MMI). */
        val bindingId: String,
    )

    /**
     * Leapmotor T03 steering-wheel bus: one action for every key, the key identity rides in
     * integer extras (volumeCtrl / muteCtrl / voiceAssistant / customKey / mediaKey /
     * mediaSwitch). Real-device captures: mediaSwitch=1 -> previous, mediaSwitch=2 -> next,
     * mediaKey=1 -> play/pause. Because one action carries many keys, bindings are stored at
     * extras-signature level (`BROADCAST:<action>|mediaSwitch=1`), not action level.
     */
    const val ICU2MMI_ACTION = "com.leapmotor.ICU2MMICtrl"

    /**
     * The T03 stock-player wheel channel, reverse-engineered from its own APK
     * (`com.leapmotor.multimedia-AppMain-方控指令与切歌暂停实现.md`): the stock receiver reads
     * the integer extras `ICU_MediaKey` / `ICU_MediaSwitch` (1=previous, 2=next, key 1=play
     * pause) — verified on the emulator: every command really changed the song / toggled
     * playback through the kugou tvsdk.
     */
    const val CUSTOMKEY_ACTION = "com.leapmotor.customkey.music.pauseplay"

    /** Non-zero extras as a stable `key=value` signature, or null when nothing is pressed. */
    private fun icu2MmiSignature(intent: Intent?): String? {
        val source = intent ?: return null
        fun readExtra(vararg keys: String): Int {
            for (key in keys) {
                val value = runCatching { source.getIntExtra(key, Int.MIN_VALUE) }.getOrDefault(Int.MIN_VALUE)
                if (value != Int.MIN_VALUE) return value
            }
            return 0
        }
        val mediaKey = readExtra("ICU_MediaKey", "mediaKey")
        val mediaSwitch = readExtra("ICU_MediaSwitch", "mediaSwitch")
        val parts = mutableListOf<String>()
        val volumeCtrl = readExtra("volumeCtrl")
        if (volumeCtrl != 0) parts += "volumeCtrl=$volumeCtrl"
        val muteCtrl = readExtra("muteCtrl")
        if (muteCtrl != 0) parts += "muteCtrl=$muteCtrl"
        val voiceAssistant = readExtra("voiceAssistant")
        if (voiceAssistant != 0) parts += "voiceAssistant=$voiceAssistant"
        val customKey = readExtra("customKey")
        if (customKey != 0) parts += "customKey=$customKey"
        if (mediaKey != 0) parts += "ICU_MediaKey=$mediaKey"
        if (mediaSwitch != 0) parts += "ICU_MediaSwitch=$mediaSwitch"
        return parts.takeIf { it.isNotEmpty() }?.joinToString(",")
    }

    /** Built-in media-key mapping; null for keys CarPlay has no media action for. */
    private fun icu2MmiBuiltinAction(signature: String): String? = when (signature) {
        "ICU_MediaSwitch=1", "mediaSwitch=1" -> "previous"
        "ICU_MediaSwitch=2", "mediaSwitch=2" -> "next"
        "ICU_MediaKey=1", "mediaKey=1" -> "play_pause"
        else -> null
    }

    private fun handleIcu2Mmi(intent: Intent?) {
        val signature = icu2MmiSignature(intent)
        // The T03 radio may either use the six integer extras or the C-series `receiver`
        // byte payload (the stock FangKongReceiver checks for that extra first). Cover both.
        val payload = runCatching { intent?.getByteArrayExtra("receiver") }.getOrNull()
        val payloadAction = LeapmotorMediaProtocol.actionFromPayload(payload)
        val bindingId: String
        val detail: String
        if (signature != null) {
            bindingId = "${WheelBinding.BROADCAST_PREFIX}$ICU2MMI_ACTION|$signature"
            detail = signature
        } else if (payloadAction != null) {
            bindingId = "${WheelBinding.BROADCAST_PREFIX}$ICU2MMI_ACTION"
            detail = "data.action=$payloadAction"
        } else {
            // Probe build: dump every extra so the report shows what the head unit actually
            // sends — byte arrays print their size, everything else its value.
            val dump = intent?.extras?.keySet()?.sorted()?.joinToString(",") { key ->
                val value = runCatching { intent.extras?.get(key) }.getOrNull()
                when (value) {
                    null -> "$key=null"
                    is ByteArray -> "$key=byte[${value.size}]"
                    else -> "$key=${value.javaClass.simpleName}=$value"
                }
            } ?: "none"
            bindingId = "${WheelBinding.BROADCAST_PREFIX}$ICU2MMI_ACTION"
            detail = "extras($dump)"
        }
        if (broadcastLogEnabled) {
            synchronized(broadcastLog) {
                broadcastLog.addLast(
                    BroadcastLogEntry(ICU2MMI_ACTION, detail, System.currentTimeMillis(), bindingId),
                )
                while (broadcastLog.size > MAX_LOG_ENTRIES) broadcastLog.removeFirst()
            }
        }
        if (captureCallback != null) return
        val context = appContext
        if (context != null && signature != null) {
            val binding = WheelLearningStore.load(context)
                .firstOrNull { it.id == "${WheelBinding.BROADCAST_PREFIX}$ICU2MMI_ACTION|$signature" }
            if (binding != null) {
                perform(binding)
                return
            }
        }
        // No learned binding for this key: fall back to the built-ins — the integer extras
        // table (mediaSwitch=1/2, mediaKey=1) or the C-series JSON payload action.
        val builtin = icu2MmiBuiltinAction(signature ?: "")
        if (builtin != null) {
            LeapmotorMediaKeys.dispatch(builtin, ICU2MMI_ACTION)
            return
        }
        if (payloadAction != null) {
            LeapmotorMediaKeys.dispatch(payloadAction, ICU2MMI_ACTION)
        }
    }

    private fun handleBusBroadcast(intent: Intent?) {
        val action = intent?.action ?: return
        val bindingId = WheelBinding.BROADCAST_PREFIX + action
        if (broadcastLogEnabled) recordBroadcast(action, intent, bindingId)
        if (captureCallback != null) return
        val binding = WheelLearningStore.load(appContext ?: return)
            .firstOrNull { it.id == bindingId } ?: return
        perform(binding)
    }

    private val broadcastLog = ArrayDeque<BroadcastLogEntry>()
    private val broadcastLock = Any()
    private val broadcastReceivers = mutableMapOf<String, BroadcastReceiver>()
    private var carBusReceiver: BroadcastReceiver? = null

    @Volatile
    private var broadcastLogEnabled = false

    fun isBroadcastLogEnabled(context: Context): Boolean =
        context.getSharedPreferences(WheelLearningStore.PREFERENCES, Context.MODE_PRIVATE)
            .getBoolean("broadcast-log", false)

    fun setBroadcastLogEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(WheelLearningStore.PREFERENCES, Context.MODE_PRIVATE)
            .edit().putBoolean("broadcast-log", enabled).apply()
        broadcastLogEnabled = enabled
        if (enabled) {
            ensureCarBusReceiver(context)
            report("broadcast log enabled on ${LeapmotorMediaProtocol.ACTIONS.size} car actions")
        } else {
            synchronized(broadcastLog) { broadcastLog.clear() }
            report("broadcast log disabled")
        }
    }

    fun broadcastLogEntries(): List<BroadcastLogEntry> = synchronized(broadcastLog) { broadcastLog.toList() }

    /**
     * Keeps receivers for every BROADCAST: binding alive so a learned broadcast action
     * dispatches its bound CarPlay action even without the learning screen open.
     */
    @Synchronized
    fun refreshBroadcastReceivers(context: Context, bindings: List<WheelBinding>) {
        val application = context.applicationContext
        val wanted = bindings
            .filter { it.id.startsWith(WheelBinding.BROADCAST_PREFIX) }
            // ICU2MMI signature bindings carry `action|signature` ids; only the action part
            // is registrable, and that action is already covered by the always-on car-bus
            // receiver — registering it again would deliver every press twice.
            .map { it.id.removePrefix(WheelBinding.BROADCAST_PREFIX).substringBefore('|') }
            .filter { it != ICU2MMI_ACTION }
            .toSet()
        wanted.forEach { action -> ensureBroadcastReceiver(application, action) }
        synchronized(broadcastReceivers) {
            broadcastReceivers.keys.filter { it !in wanted }.forEach { action ->
                broadcastReceivers.remove(action)?.let { receiver ->
                    runCatching { application.unregisterReceiver(receiver) }
                    report("broadcast binding receiver removed: $action")
                }
            }
        }
    }

    /** True when the whole bus is bound by a BROADCAST: action; the payload dispatch then stays out. */
    fun broadcastBindingConsumes(broadcastAction: String?): Boolean {
        if (broadcastAction.isNullOrBlank()) return false
        val context = appContext ?: return false
        val id = WheelBinding.BROADCAST_PREFIX + broadcastAction
        return WheelLearningStore.load(context).any { it.id == id }
    }

    @Synchronized
    private fun ensureBroadcastReceiver(context: Context, action: String) {
        if (broadcastReceivers.containsKey(action)) return
        val created = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (broadcastLogEnabled) recordBroadcast(action, intent, WheelBinding.BROADCAST_PREFIX + action)
                handleBusBroadcast(intent)
            }
        }
        runCatching { context.registerReceiver(created, IntentFilter(action)) }
            .onSuccess {
                broadcastReceivers[action] = created
                report("broadcast binding listening: $action")
            }
            .onFailure { report("broadcast receiver not registered: $action ${it.message}") }
    }

    @Synchronized
    private fun ensureCarBusReceiver(context: Context) {
        if (carBusReceiver != null) return
        val application = context.applicationContext
        val filter = IntentFilter().apply {
            LeapmotorMediaProtocol.ACTIONS.forEach(::addAction)
            addAction(ICU2MMI_ACTION)
            // The stock T03 wheel channel (extras ICU_MediaKey / ICU_MediaSwitch) —
            // reverse-engineered and verified: every command toggles/changes the song.
            addAction(CUSTOMKEY_ACTION)
        }
        val created = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == ICU2MMI_ACTION || intent?.action == CUSTOMKEY_ACTION) {
                    handleIcu2Mmi(intent)
                } else {
                    handleBusBroadcast(intent)
                }
            }
        }
        // Car bus broadcasts arrive from another process, so the receiver is exported; the flag
        // overload is Android 13+, see the media button receiver above.
        @SuppressLint("UnspecifiedRegisterReceiverFlag")
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                application.registerReceiver(created, filter, Context.RECEIVER_EXPORTED)
            } else {
                application.registerReceiver(created, filter)
            }
        }
            .onSuccess {
                carBusReceiver = created
                report("car bus listening on ${filter.countActions()} actions (wheel channels included)")
            }
            .onFailure { report("broadcast monitor not registered: ${it.message}") }
    }

    private fun recordBroadcast(action: String, intent: Intent?, bindingId: String) {
        val payload = runCatching {
            @Suppress("DEPRECATION")
            intent?.getByteArrayExtra("receiver")
        }.getOrNull()
        val parsed = LeapmotorMediaProtocol.actionFromPayload(payload)
        val entry = BroadcastLogEntry(
            action,
            parsed?.let { "data.action=$it" } ?: "payload=${payload?.size ?: 0}B",
            System.currentTimeMillis(),
            bindingId,
        )
        synchronized(broadcastLog) {
            broadcastLog.addLast(entry)
            while (broadcastLog.size > MAX_LOG_ENTRIES) broadcastLog.removeFirst()
        }
    }

    private const val DUPLICATE_WINDOW_MILLIS = 250L
    private const val MAX_LOG_ENTRIES = 12
}

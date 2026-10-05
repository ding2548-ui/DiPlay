package com.shilapi.xcertplay

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import com.shilapi.xcertplay.adb.AdbKeys
import com.shilapi.xcertplay.adb.LocalAdb
import com.shilapi.xcertplay.airplay.AirPlayKnobState
import com.shilapi.xcertplay.glance.CarPlayGlance
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.hud.BydNavigationOutputs

/**
 * Optional: steering-wheel keys zoom CarPlay's dashboard map and work as a CarPlay joystick. BYD's window
 * manager takes the wheel's keys (volume 291/292, the custom key 305, the media key 289) before any app
 * sees them and acts on them itself. An accessibility service that filters key events gets them earlier,
 * in the input filter, and may keep them. With the zoom setting on and the dashboard map streaming, the
 * mode key (BYD's custom key by default) switches the zoom keys (the volume keys by default) from volume
 * to map zoom until it is pressed again, or, in the timed behaviour, until a few seconds after the last
 * zoom. With the joystick setting on, the joystick key (BYD's media key by default) turns the joystick on
 * and off; while it is on the keys drive CarPlay's main screen as a car's rotary knob would (see
 * [WheelJoystick]). A call always keeps the keys for the call. Every other key passes on unchanged. On
 * the Tang the console's volume sends the same codes as the wheel's, so it zooms and moves too.
 */
class WheelKeyService : AccessibilityService() {
    private val keys = WheelZoomKeys()
    private val joystick = WheelJoystick()
    private val handler = Handler(Looper.getMainLooper())
    private var learning: WheelZoomSettings.Role? = null
    private var learnt: ((WheelZoomSettings.Role, WheelKey) -> Unit)? = null
    private var learningCancelled: (() -> Unit)? = null
    private val endLearning = Runnable { clearLearning() }
    private val endTimedMode = Runnable {
        refreshEligibility()
        if (keys.timeOut() && eligibleRoute() != null) announce(zoomOn = false)
    }
    // This read is nonblocking: no ADB, input-device query or network lock in the periodic poll.
    internal var mapRoute: () -> Any? = { CarPlayBackgroundSession.snapshot()?.controller?.dashboardMapRoute() }
    internal var session: () -> Any? = { CarPlayBackgroundSession.snapshot()?.controller?.activeAirPlaySessionToken() }
    internal var knob: (AirPlayKnobState) -> Boolean = { CarPlayBackgroundSession.snapshot()?.controller?.sendKnob(it) == true }
    internal var routeActive: () -> Boolean = { CarPlayGlance.snapshot().maneuverType != null }

    // With the auto-off setting the joystick ends a while after the last press and when a route starts,
    // so the keys go back to music and volume without a thought.
    private val joystickIdle = Runnable { if (joystick.end()) joystickEnded() }
    private var routeSeen = false
    private val watchRoute = object : Runnable {
        override fun run() {
            if (!joystick.on) return
            val route = routeActive()
            if (route && !routeSeen) {
                if (joystick.end()) joystickEnded()
                return
            }
            routeSeen = route
            handler.postDelayed(this, ROUTE_CHECK_MILLIS)
        }
    }
    private val pollEligibility = object : Runnable {
        override fun run() {
            if (running !== this@WheelKeyService) return
            refreshEligibility()
            handler.postDelayed(this, ELIGIBILITY_POLL_MILLIS)
        }
    }

    override fun onServiceConnected() {
        running = this
        refreshEligibility()
        handler.removeCallbacks(pollEligibility)
        handler.postDelayed(pollEligibility, ELIGIBILITY_POLL_MILLIS)
        Log.i(TAG, "wheel key service connected")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (running === this) running = null
        clearLearning()
        keys.updateEligibility(null)
        joystick.end()
        stopJoystickTimers()
        handler.removeCallbacks(pollEligibility)
        handler.removeCallbacks(endTimedMode)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (running === this) running = null
        clearLearning()
        keys.timeOut()
        joystick.end()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() { settingsChanged() }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP) return false
        val key = WheelKey(event.keyCode, event.scanCode, deviceName(event.deviceId))
        val down = event.action == KeyEvent.ACTION_DOWN
        refreshEligibility()
        val calling = inCall()
        if (calling) clearLearning()
        val enabled = WheelZoomSettings.enabled(this)
        val role = if (enabled || WheelZoomSettings.joystick(this)) WheelZoomSettings.roleOf(this, key) else null
        val controller = CarPlayBackgroundSession.snapshot()?.controller
        val action = keys.onKey(
            role = role.takeIf { enabled },
            down = down,
            firstPress = event.repeatCount == 0,
            mapShown = eligibleRoute() != null,
            inCall = calling,
            physicalKey = PhysicalWheelKey(event.deviceId, event.keyCode, event.scanCode),
            onFirstPress = {
                learning?.let { role ->
                    val done = learnt
                    clearLearning(notify = false)
                    WheelZoomSettings.assign(this, role, key)
                    Log.i(TAG, "$role key is now $key")
                    done?.invoke(role, key)
                    WheelZoomKeys.Action.CONSUME
                } ?: joystickPress(role, calling)
            },
        )
        when (action) {
            WheelZoomKeys.Action.PASS -> return false
            WheelZoomKeys.Action.CONSUME -> Unit
            WheelZoomKeys.Action.MODE_ON -> announce(zoomOn = true)
            WheelZoomKeys.Action.MODE_OFF -> announce(zoomOn = false)
            WheelZoomKeys.Action.ZOOM_IN, WheelZoomKeys.Action.ZOOM_OUT ->
                controller?.zoomDashboardMap(zoomIn = action == WheelZoomKeys.Action.ZOOM_IN)
        }
        rearmTimedMode()
        return true
    }

    private fun eligibleRoute(): Any? = if (WheelZoomSettings.enabled(this)) mapRoute() else null

    private fun refreshEligibility() {
        val joystickAllowed = WheelZoomSettings.joystick(this)
        if (!WheelZoomSettings.enabled(this) && !joystickAllowed) clearLearning()
        if (keys.updateEligibility(eligibleRoute())) handler.removeCallbacks(endTimedMode)
        // A new CarPlay session, or the setting turned off, ends the joystick quietly.
        if (joystick.updateSession(session()) or (!joystickAllowed && joystick.end())) stopJoystickTimers()
    }

    // The joystick's part of a first press; null leaves the key to the zoom, or to the car.
    private fun joystickPress(role: WheelZoomSettings.Role?, calling: Boolean): WheelZoomKeys.Action? {
        if (!WheelZoomSettings.joystick(this)) return null
        when (val move = joystick.onKey(role, session = session() != null, inCall = calling)) {
            WheelJoystick.Action.PASS -> return null
            WheelJoystick.Action.ON -> {
                if (keys.timeOut()) handler.removeCallbacks(endTimedMode) // the joystick takes the volume keys
                announceJoystick(on = true)
                armJoystickTimers()
            }
            WheelJoystick.Action.OFF -> joystickEnded()
            else -> {
                knob(knobState(move))
                if (WheelZoomSettings.joystickAutoOff(this)) armJoystickIdle()
            }
        }
        return WheelZoomKeys.Action.CONSUME
    }

    // CarPlay moves its focus only when the knob turns; x/y nudges pan a focused map instead.
    private fun knobState(move: WheelJoystick.Action): AirPlayKnobState = when (move) {
        WheelJoystick.Action.PREVIOUS -> AirPlayKnobState(wheel = -1)
        WheelJoystick.Action.NEXT -> AirPlayKnobState(wheel = 1)
        WheelJoystick.Action.SELECT -> AirPlayKnobState(select = true)
        else -> AirPlayKnobState(back = true)
    }

    private fun joystickEnded() {
        stopJoystickTimers()
        announceJoystick(on = false)
    }

    private fun armJoystickTimers() {
        stopJoystickTimers()
        if (!joystick.on || !WheelZoomSettings.joystickAutoOff(this)) return
        armJoystickIdle()
        routeSeen = routeActive()
        handler.postDelayed(watchRoute, ROUTE_CHECK_MILLIS)
    }

    private fun armJoystickIdle() {
        handler.removeCallbacks(joystickIdle)
        handler.postDelayed(joystickIdle, WheelZoomSettings.JOYSTICK_IDLE_MILLIS)
    }

    private fun stopJoystickTimers() {
        handler.removeCallbacks(joystickIdle)
        handler.removeCallbacks(watchRoute)
    }

    // A toast with the keys at a glance on the centre screen; on the dashboard only the state (its font
    // is limited).
    private fun announceJoystick(on: Boolean) {
        Log.i(TAG, "joystick ${if (on) "on" else "off"}")
        val text = getString(if (on) R.string.wheel_joystick_on else R.string.wheel_joystick_off)
        handler.post {
            if (on) Toast.makeText(this, "$text\n${getString(R.string.wheel_joystick_hint)}", Toast.LENGTH_LONG).show()
            else Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
        }
        BydNavigationOutputs.dashboardNote(text)
    }

    private fun clearLearning(notify: Boolean = true) {
        val cancelled = learningCancelled.takeIf { learning != null && notify }
        learning = null
        learnt = null
        learningCancelled = null
        handler.removeCallbacks(endLearning)
        cancelled?.invoke()
    }

    private fun settingsChanged() {
        clearLearning()
        keys.timeOut()
        handler.removeCallbacks(endTimedMode)
        refreshEligibility()
        armJoystickTimers() // the auto-off setting may have changed
    }

    // In the timed behaviour zoom mode ends a few seconds after the last press.
    private fun rearmTimedMode() {
        handler.removeCallbacks(endTimedMode)
        if (keys.zoomMode && WheelZoomSettings.behaviour(this) == WheelZoomSettings.Behaviour.TIMED) {
            handler.postDelayed(endTimedMode, WheelZoomSettings.TIMED_MODE_MILLIS)
        }
    }

    // Android is in a call or communication audio mode during CarPlay and Bluetooth calls (and ringing).
    private fun inCall(): Boolean =
        (getSystemService(Context.AUDIO_SERVICE) as? AudioManager)?.mode.let { it != null && it != AudioManager.MODE_NORMAL }

    // On the centre screen, and where the song shows on the dashboard: zoom with BYD's Bluetooth-music
    // icon (source 6), volume with the song's usual icon.
    private fun announce(zoomOn: Boolean) {
        Log.i(TAG, "zoom mode ${if (zoomOn) "on" else "off"}")
        handler.post {
            Toast.makeText(this, if (zoomOn) R.string.wheel_zoom_mode_on else R.string.wheel_zoom_mode_off, Toast.LENGTH_SHORT).show()
        }
        if (zoomOn) {
            BydNavigationOutputs.dashboardNote("🔍 ${getString(R.string.wheel_zoom_note_zoom)}", ZOOM_NOTE_SOURCE)
        } else {
            BydNavigationOutputs.dashboardNote("🔊 ${getString(R.string.wheel_zoom_note_volume)}")
        }
    }

    private fun deviceName(id: Int): String = runCatching { InputDevice.getDevice(id)?.name }.getOrNull() ?: "?"

    companion object {
        private const val TAG = "DiPlay-WheelKeys"
        private const val ZOOM_NOTE_SOURCE = 6
        private const val ELIGIBILITY_POLL_MILLIS = 250L
        private const val ROUTE_CHECK_MILLIS = 1_000L
        internal const val LEARNING_TIMEOUT_MILLIS = 10_000L
        @Volatile private var running: WheelKeyService? = null

        fun connected(): Boolean = running != null

        /** The next key pressed is assigned to [role]; [done] runs on the service's thread. */
        fun learn(role: WheelZoomSettings.Role, cancelled: () -> Unit = {}, done: (WheelZoomSettings.Role, WheelKey) -> Unit): Boolean {
            val service = running ?: return false
            if (!WheelZoomSettings.enabled(service) && !WheelZoomSettings.joystick(service)) return false
            service.clearLearning()
            service.learnt = done
            service.learningCancelled = cancelled
            service.learning = role
            service.handler.postDelayed(service.endLearning, LEARNING_TIMEOUT_MILLIS)
            return true
        }

        fun cancelLearning() { running?.onMain { clearLearning() } }

        fun settingsChanged() { running?.onMain { settingsChanged() } }

        fun enabledInSettings(context: Context): Boolean {
            val list = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            return component(context).flattenToString() in list.orEmpty().split(':')
        }

        /**
         * BYD's settings have no accessibility page, so the user can turn the service on through the car's
         * own adb (allowed once on the car screen). Services already in the list stay there.
         */
        fun enableOverAdb(context: Context): LocalAdb.Access = LocalAdb(AdbKeys.load(context)).use { adb ->
            val access = adb.connect(mayAsk = true)
            if (access == LocalAdb.Access.READY) {
                val ours = component(context).flattenToString()
                val current = adb.shell("settings get secure enabled_accessibility_services")?.trim()
                    ?.takeUnless { it.isEmpty() || it == "null" }
                val list = (current?.split(':').orEmpty() + ours).filter { it.isNotBlank() }.distinct().joinToString(":")
                adb.shell("settings put secure enabled_accessibility_services '$list'")
                adb.shell("settings put secure accessibility_enabled 1")
                Log.i(TAG, "wheel key service allowed over adb")
            }
            access
        }

        private fun component(context: Context) = ComponentName(context, WheelKeyService::class.java)
    }

    private fun onMain(action: WheelKeyService.() -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else handler.post { action() }
    }
}

/** Use the input-device ID for a held press; saved assignments still use the stable device name. */
private data class PhysicalWheelKey(val device: Int, val code: Int, val scan: Int)

/** Keep the system's key stream well-formed even if settings/calls/routes change mid-press. */
internal class WheelKeyPresses {
    private val consumed = mutableMapOf<Any, Boolean>()

    fun filter(key: Any?, down: Boolean, firstPress: Boolean, decide: () -> WheelZoomKeys.Action): WheelZoomKeys.Action {
        key ?: return WheelZoomKeys.Action.PASS
        if (!down) return disposition(consumed.remove(key) ?: false)
        consumed[key]?.let { return disposition(it) }
        // A repeat whose DOWN was not seen is passed without starting a new action or capture.
        if (!firstPress) return WheelZoomKeys.Action.PASS
        return decide().also { consumed[key] = it != WheelZoomKeys.Action.PASS }
    }

    private fun disposition(consume: Boolean) = if (consume) WheelZoomKeys.Action.CONSUME else WheelZoomKeys.Action.PASS
}

/** A key as the head unit reports it; code, scan code and device name tell keys apart. */
data class WheelKey(val code: Int, val scan: Int, val device: String) {
    override fun toString(): String = "$code/$scan"

    fun encode(): String = "$code|$scan|$device"

    companion object {
        fun decode(text: String?): WheelKey? = text?.split('|', limit = 3)?.takeIf { it.size == 3 }?.let {
            WheelKey(it[0].toIntOrNull() ?: return null, it[1].toIntOrNull() ?: return null, it[2])
        }
    }
}

/** What a key press does for the dashboard map zoom; no Android types, so it is unit-tested. */
class WheelZoomKeys {
    enum class Action { PASS, CONSUME, MODE_ON, MODE_OFF, ZOOM_IN, ZOOM_OUT }

    var zoomMode = false
        private set
    private var route: Any? = null
    private val presses = WheelKeyPresses()

    /** A new phone/stream or a lost map never inherits the old session's zoom mode. */
    fun updateEligibility(route: Any?): Boolean {
        if (this.route == route) return false
        this.route = route
        return timeOut()
    }

    /** [role] is null for keys that have no role; [firstPress] is false for auto-repeats. */
    fun onKey(role: WheelZoomSettings.Role?, down: Boolean, firstPress: Boolean, mapShown: Boolean, inCall: Boolean,
        physicalKey: Any? = role, onFirstPress: (() -> Action?)? = null): Action {
        if (!mapShown) timeOut()
        return presses.filter(physicalKey, down, firstPress) {
            onFirstPress?.invoke() ?: decide(role, mapShown, inCall)
        }
    }

    private fun decide(role: WheelZoomSettings.Role?, mapShown: Boolean, inCall: Boolean): Action {
        if (role == null || !mapShown || inCall) return Action.PASS
        if (role == WheelZoomSettings.Role.MODE) {
            zoomMode = !zoomMode
            return if (zoomMode) Action.MODE_ON else Action.MODE_OFF
        }
        if (!zoomMode || (role != WheelZoomSettings.Role.ZOOM_IN && role != WheelZoomSettings.Role.ZOOM_OUT)) return Action.PASS
        return if (role == WheelZoomSettings.Role.ZOOM_IN) Action.ZOOM_IN else Action.ZOOM_OUT
    }

    /** The timed behaviour's few seconds ran out, or the joystick took the keys; true if that ended zoom mode. */
    fun timeOut(): Boolean {
        if (!zoomMode) return false
        zoomMode = false
        return true
    }
}

/**
 * What a key's first press does for the CarPlay joystick (repeats and releases follow it, see
 * [WheelKeyPresses]); no Android types, so it is unit-tested. CarPlay moves its focus only with a knob's
 * turn, so previous/next and the zoom keys (the volume roller) both turn it; play/pause selects and the
 * mode key goes back. Without a CarPlay session the joystick key keeps the car's action and the joystick
 * ends; a call keeps every key for the call.
 */
class WheelJoystick {
    enum class Action { PASS, ON, OFF, PREVIOUS, NEXT, SELECT, BACK }

    var on = false
        private set
    private var session: Any? = null

    /** A new CarPlay session (or none) never inherits the old one's joystick; true if that ended it. */
    fun updateSession(session: Any?): Boolean {
        if (this.session === session) return false
        this.session = session
        return end()
    }

    /** [role] is null for keys that have no role. */
    fun onKey(role: WheelZoomSettings.Role?, session: Boolean, inCall: Boolean): Action {
        if (role == null || inCall) return Action.PASS
        if (!session) {
            on = false
            return Action.PASS
        }
        if (role == WheelZoomSettings.Role.JOYSTICK) {
            on = !on
            return if (on) Action.ON else Action.OFF
        }
        if (!on) return Action.PASS
        return when (role) {
            WheelZoomSettings.Role.PREVIOUS, WheelZoomSettings.Role.ZOOM_IN -> Action.PREVIOUS
            WheelZoomSettings.Role.NEXT, WheelZoomSettings.Role.ZOOM_OUT -> Action.NEXT
            WheelZoomSettings.Role.SELECT -> Action.SELECT
            WheelZoomSettings.Role.MODE -> Action.BACK
            WheelZoomSettings.Role.JOYSTICK -> Action.PASS
        }
    }

    /** The joystick ends by itself (auto-off, the setting, the session); true if it was on. */
    fun end(): Boolean {
        if (!on) return false
        on = false
        return true
    }
}

/** Settings for the wheel's dashboard map zoom and CarPlay joystick. */
object WheelZoomSettings {
    private const val PREFS = "diplay_wheel_map_zoom"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_BEHAVIOUR = "behaviour"
    private const val KEY_JOYSTICK = "joystick"
    private const val KEY_JOYSTICK_AUTO_OFF = "joystick_auto_off"
    private const val BYD_KEYS = "simulate-keys"
    const val TIMED_MODE_MILLIS = 5_000L
    const val JOYSTICK_IDLE_MILLIS = 15_000L

    /**
     * Defaults are a BYD Tang's wheel: the custom key, volume up and down (the roller), the media key,
     * previous, next and play/pause.
     */
    enum class Role(val defaultKey: WheelKey) {
        MODE(WheelKey(305, 300, BYD_KEYS)),
        ZOOM_IN(WheelKey(291, 115, BYD_KEYS)),
        ZOOM_OUT(WheelKey(292, 114, BYD_KEYS)),
        JOYSTICK(WheelKey(289, 89, BYD_KEYS)),
        PREVIOUS(WheelKey(88, 268, BYD_KEYS)),
        NEXT(WheelKey(87, 270, BYD_KEYS)),
        SELECT(WheelKey(353, 505, BYD_KEYS)),
    }

    /** The mode key switches zoom mode until pressed again, or turns it on for a few seconds. */
    enum class Behaviour { TOGGLE, TIMED }

    fun enabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        WheelKeyService.settingsChanged()
    }

    fun behaviour(context: Context): Behaviour =
        Behaviour.entries.firstOrNull { it.name == prefs(context).getString(KEY_BEHAVIOUR, null) } ?: Behaviour.TOGGLE

    fun setBehaviour(context: Context, behaviour: Behaviour) {
        prefs(context).edit().putString(KEY_BEHAVIOUR, behaviour.name).apply()
        WheelKeyService.settingsChanged()
    }

    fun joystick(context: Context): Boolean = prefs(context).getBoolean(KEY_JOYSTICK, false)

    fun setJoystick(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_JOYSTICK, enabled).apply()
        WheelKeyService.settingsChanged()
    }

    fun joystickAutoOff(context: Context): Boolean = prefs(context).getBoolean(KEY_JOYSTICK_AUTO_OFF, true)

    fun setJoystickAutoOff(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_JOYSTICK_AUTO_OFF, enabled).apply()
        WheelKeyService.settingsChanged()
    }

    fun key(context: Context, role: Role): WheelKey =
        WheelKey.decode(prefs(context).getString("key_${role.name}", null)) ?: role.defaultKey

    fun assign(context: Context, role: Role, key: WheelKey) {
        prefs(context).edit().putString("key_${role.name}", key.encode()).apply()
        WheelKeyService.settingsChanged()
    }

    fun roleOf(context: Context, key: WheelKey): Role? = Role.entries.firstOrNull { key(context, it) == key }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

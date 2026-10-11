package com.shilapi.xcertplay.hud

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import java.util.concurrent.Executors

/**
 * The CallPlay call card on a Leapmotor head unit.
 *
 * The BYD path ([BydCarPlayCall]) reaches the instrument cluster through an `app_process` helper
 * under the head unit's ADB shell. A Leapmotor unit has no `adbd`, so that path can never run there.
 * What it has instead is a documented broadcast ingress into the meter:
 *
 * ```
 * action  car.hmi.sender.BROADCAST
 * extra   sender : ByteArray = [u16 length BE][UTF-8 JSON][optional raw tail]
 * ```
 *
 * The JSON envelope is `{"type":"<kind>", "data":{...}}`; the meter already receives `music`,
 * `lyric`, `navi`, `xlui`, `carinfo` and `drivemode` this way (verified against the third-party
 * `小零助手` app and `LauncherThree`, both of which speak it). The call kind is `"call"`:
 *
 * ```
 * {"type":"call","data":{"state":"ring|answer|end","name":"…"}}
 * ```
 *
 * Separately, the car's own Bluetooth phone state arrives on
 * `com.leapmotor.phonecall` (int extra `MMI_PhoneSts`: 1 ringing, 2 in call, 0 idle). We watch it so
 * that a car-side call is never mistaken for "no call", and so the steering-wheel keys can act on
 * whichever side owns the call.
 *
 * Nothing here writes to the car except the two documented broadcast actions; there is no ADB, no
 * reflection and no root dependency.
 */
object LeapmotorCarPlayCallOutput {
    private const val TAG = "DiPlay-Lm-Call"
    private const val SENDER_ACTION = "car.hmi.sender.BROADCAST"
    private const val SENDER_EXTRA = "sender"
    private const val PHONE_STATE_ACTION = "com.leapmotor.phonecall"
    private const val PHONE_STATE_EXTRA = "MMI_PhoneSts"

    /** `MMI_PhoneSts` values, confirmed against `PhoneApp` and the third-party `小零助手`. */
    const val PHONE_IDLE = 0
    const val PHONE_RINGING = 1
    const val PHONE_IN_CALL = 2

    private val writer = Executors.newSingleThreadExecutor { Thread(it, "diplay-lm-call").apply { isDaemon = true } }
    private val state = CarPlayCallState()

    @Volatile private var context: Context? = null
    @Volatile private var receiver: BroadcastReceiver? = null
    @Volatile private var carPhoneState = -1

    /** The last card the meter was sent, so an unchanged card is not re-sent. Writer thread only. */
    private var shown: CarPlayCallCard? = null

    /** Both the logcat line and the app's diagnostic report receive these (`appendLog` on the car). */
    @Volatile var onDiagnostic: ((String) -> Unit)? = null

    fun attach(appContext: Context) {
        context = appContext.applicationContext
    }

    /** Whether the meter broadcast ingress is usable on this unit (the same car probe as the lamp). */
    fun available(): Boolean = BydOutputSettings.leapmotorHeadUnit()

    /**
     * Registers the one receiver for the car's own phone state. Idempotent; safe to call on every
     * session start. Does nothing on a non-Leapmotor unit.
     */
    fun start(context: Context) {
        if (!available()) return
        if (receiver != null) return
        synchronized(this) {
            if (receiver != null) return
            val app = context.applicationContext
            this.context = app
            val created = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action != PHONE_STATE_ACTION) return
                    @Suppress("DEPRECATION")
                    val next = runCatching { intent.getIntExtra(PHONE_STATE_EXTRA, -1) }.getOrDefault(-1)
                    if (next < 0) return
                    carPhoneState = next
                    report("car phone state=$next")
                    // The car owns its own call UI. Only reset our own card while the iPhone has no
                    // call, so a native Bluetooth call keeps its stock presentation.
                    if (state.current() == null) writer.execute { publish(null) }
                }
            }
            @Suppress("UnspecifiedRegisterReceiverFlag")
            val registered = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    app.registerReceiver(created, IntentFilter(PHONE_STATE_ACTION), Context.RECEIVER_EXPORTED)
                } else {
                    app.registerReceiver(created, IntentFilter(PHONE_STATE_ACTION))
                }
            }.onFailure { Log.w(TAG, "phone-state receiver not registered", it) }
            if (registered.isSuccess) receiver = created
            report("call output listening")
        }
    }

    /** A CarPlay session started; nothing to arm, the card is driven by [onFrame]. */
    fun sessionStarted(context: Context) = start(context)

    /** The latest CarPlay call, or null when there is none. */
    fun current(): CarPlayCallCard? = synchronized(state) { state.current() }

    /** Called for every iAP2 frame; only CallStateUpdate matters. */
    fun onFrame(frame: Iap2Frame) {
        val card = synchronized(state) {
            if (!state.accept(frame)) return
            state.current()
        }
        report("CarPlay call ${card?.phase ?: "ended"}")
        val app = context ?: return
        if (!BydOutputSettings.carPlayCalls(app)) return
        writer.execute { publish(card) }
    }

    /** The setting changed: show the current call now, or end the one DiPlay showed. */
    fun settingChanged(enabled: Boolean) {
        val app = context ?: return
        val card = if (enabled) current() else null
        writer.execute { publish(card) }
    }

    /** The session ended: forget the calls and clear the card. */
    fun end() {
        synchronized(state) { state.clear() }
        carPhoneState = -1
        // `shown` is left alone: publish(null) must still see the stale card to know it has to be
        // cleared from the meter. Clearing it here would make the clear a no-op.
        if (context == null) shown = null else writer.execute { publish(null) }
    }

    /** Emits the card (or its absence) to the meter. Runs on the writer thread. */
    private fun publish(card: CarPlayCallCard?) {
        val app = context ?: return
        // Nothing was shown and nothing is wanted: an initial `end` would only reset the meter's own
        // native call card, so it is skipped. Every later transition is sent, including the clear.
        if (card == null && shown == null) return
        if (card == shown) return
        if (send(app, callJson(card))) shown = card
    }

    /**
     * The `{"type":"call", ...}` envelope. A null card is an explicit `end`, never "omit the key":
     * the meter keeps the last state it saw. Built by hand rather than with `org.json`, whose Android
     * implementation is not available to JVM tests; `internal` so the format has JVM coverage.
     */
    internal fun callJson(card: CarPlayCallCard?): String {
        val state = when (card?.phase) {
            null -> "end"
            CarPlayCallCard.Phase.RINGING -> "ring"
            CarPlayCallCard.Phase.DIALING -> "answer"
            CarPlayCallCard.Phase.ACTIVE -> "answer"
        }
        val data = buildString {
            append("{\"state\":\"").append(state).append('"')
            if (card != null && card.name.isNotEmpty()) {
                append(",\"name\":\"").append(escape(card.name)).append('"')
            }
            append('}')
        }
        return "{\"type\":\"call\",\"data\":$data}"
    }

    /** Minimal JSON string escaping (RFC 8259): quote, backslash and control characters. */
    private fun escape(value: String): String = buildString(value.length + 8) {
        for (ch in value) {
            when {
                ch == '"' -> append("\\\"")
                ch == '\\' -> append("\\\\")
                ch == '\n' -> append("\\n")
                ch == '\r' -> append("\\r")
                ch == '\t' -> append("\\t")
                ch < ' ' -> append("\\u").append(String.format("%04x", ch.code))
                else -> append(ch)
            }
        }
    }

    /**
     * The `sender` extra: `[u16 length BE][UTF-8 json]`, the wire format the meter's own ingress uses
     * (`MeterWire.pack`). `internal` for JVM coverage.
     */
    internal fun wire(json: String): ByteArray {
        val payload = json.toByteArray(Charsets.UTF_8)
        require(payload.size <= 0xffff) { "call json too long (${payload.size})" }
        val bytes = ByteArray(payload.size + 2)
        bytes[0] = ((payload.size shr 8) and 0xff).toByte()
        bytes[1] = (payload.size and 0xff).toByte()
        System.arraycopy(payload, 0, bytes, 2, payload.size)
        return bytes
    }

    private fun send(app: Context, json: String): Boolean {
        val bytes = runCatching { wire(json) }.onFailure { Log.w(TAG, it.message ?: "call wire failed") }
            .getOrNull() ?: return false
        return runCatching {
            app.sendBroadcast(Intent(SENDER_ACTION).putExtra(SENDER_EXTRA, bytes))
            true
        }.onFailure { Log.w(TAG, "call broadcast failed", it) }.getOrDefault(false)
    }

    private fun report(message: String) {
        Log.i(TAG, message)
        runCatching { onDiagnostic?.invoke(message) }
    }
}

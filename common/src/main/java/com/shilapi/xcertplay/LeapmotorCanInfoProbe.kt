package com.shilapi.xcertplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import org.json.JSONObject

/**
 * Forensic listener for the head unit's own vehicle-data bus.
 *
 * Reverse engineering the LP-IVI010-AA V1.01.80 ROM showed the ICU data bus as
 * `car.meter.caninfo.BROADCAST` / `car.meter.carinfo.BROADCAST`, extra "receiver" = byte[]
 * (2-byte big-endian length prefix + UTF-8 JSON `{"type":..,"data":{..}}`, same framing as the
 * steering-wheel bus). The static strings only reveal speed/mileage fields; whether a gear /
 * reverse field exists can only be confirmed on the real car, so this probe logs the first
 * unique payloads into the diagnostic report — one drive that engages reverse answers it.
 *
 * Removal is a one-line change in [CarPlayHostActivity] once the payload is mapped.
 */
internal object LeapmotorCanInfoProbe {
    private const val MAX_REPORTED = 30
    private const val ACTION_CANINFO = "car.meter.caninfo.BROADCAST"
    private const val ACTION_CARINFO = "car.meter.carinfo.BROADCAST"

    private val seen = HashSet<String>()
    private var reported = 0
    private var registered = false

    @Volatile
    var onDiagnostic: ((String) -> Unit)? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (reported >= MAX_REPORTED) return
            val action = intent.action ?: return
            val payload = intent.getByteArrayExtra("receiver")
            if (payload == null) {
                report("$action receiver extra missing")
                return
            }
            if (payload.size <= 2) {
                report("$action payload too short bytes=${payload.size}")
                return
            }
            // 2-byte big-endian length prefix + UTF-8 JSON (same framing as car.meter.music).
            val declared = ((payload[0].toInt() and 0xff) shl 8) or (payload[1].toInt() and 0xff)
            val end = minOf(payload.size, 2 + declared)
            val body = String(payload, 2, end - 2, Charsets.UTF_8)
            val type = runCatching { JSONObject(body).optString("type") }.getOrDefault("")
            val key = "$action|$body"
            if (key in seen) return
            seen.add(key)
            report("$action type=$type json=$body")
        }
    }

    /** Registers for the whole app lifetime; safe to call from [android.app.Activity.onCreate]. */
    fun attach(context: Context) {
        if (registered) return
        registered = true
        val filter = IntentFilter().apply {
            addAction(ACTION_CANINFO)
            addAction(ACTION_CARINFO)
        }
        runCatching { context.applicationContext.registerReceiver(receiver, filter) }
            .onFailure { report("register failed: ${it.message}") }
    }

    fun detach(context: Context) {
        if (!registered) return
        registered = false
        runCatching { context.applicationContext.unregisterReceiver(receiver) }
    }

    private fun report(message: String) {
        if (reported >= MAX_REPORTED) return
        reported += 1
        onDiagnostic?.invoke(message)
    }
}

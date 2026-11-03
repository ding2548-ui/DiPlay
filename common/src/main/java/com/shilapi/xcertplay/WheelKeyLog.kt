package com.shilapi.xcertplay

import android.view.KeyEvent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What the wheel key service saw and did with it, for the diagnostic report. The service wrote only to
 * logcat, so a head unit without adb could not show whether a wheel key reaches Android at all (#662).
 * The media keys that reach DiPlay's media session, and its audio focus changes, are noted here too:
 * without them a report could not tell a key the head unit kept from one the iPhone ignored (#610).
 *
 * Kept in memory since the app process started: storage on a key press would delay the head unit's own
 * key handling. A press is one entry; a held key's repeats and its release add nothing.
 *
 * The service is handed every hardware key, a connected keyboard's too. A key that writes text is counted
 * and never listed, and no input device name is kept.
 */
internal object WheelKeyLog {
    internal const val ENTRIES = 40

    private class Entry(val atMillis: Long, val text: String, var times: Int = 1, var lastMillis: Long = atMillis) {
        fun copy() = Entry(atMillis, text, times, lastMillis)
    }

    private val entries = ArrayDeque<Entry>()
    private var boundAtMillis: Long? = null
    private var presses = 0
    private var kept = 0
    private var textKeys = 0

    @Synchronized
    fun bound(nowMillis: Long = System.currentTimeMillis()) {
        boundAtMillis = nowMillis
    }

    /** A line the service also writes to logcat. */
    @Synchronized
    fun note(message: String, nowMillis: Long = System.currentTimeMillis()) = add(message, nowMillis)

    /** [text] is a key that writes text; [detail] says which feature kept the key or why one did not. */
    @Synchronized
    fun press(code: Int, scan: Int, device: Int, kept: Boolean, detail: String?, text: Boolean,
        nowMillis: Long = System.currentTimeMillis()) {
        if (text) {
            textKeys++
            return
        }
        presses++
        if (kept) this.kept++
        add("key $code/$scan device $device ${if (kept) "kept" else "left to the head unit"}" +
            (detail?.let { ": $it" } ?: ""), nowMillis)
    }

    // Some head units send a held key as a new press every 100 ms; one entry counts them.
    private fun add(text: String, nowMillis: Long) {
        entries.lastOrNull()?.takeIf { it.text == text }?.let {
            it.times++
            it.lastMillis = nowMillis
            return
        }
        if (entries.size == ENTRIES) entries.removeFirst()
        entries.addLast(Entry(nowMillis, text))
    }

    /** [bound] is whether the service is running now. A key press waits only for the copy, not for the text. */
    fun report(bound: Boolean, nowMillis: Long = System.currentTimeMillis()): List<String> {
        val summary: String
        val seen: List<Entry>
        synchronized(this) {
            val boundAt = boundAtMillis
            if (boundAt == null && entries.isEmpty()) {
                return listOf("Wheel keys: the service has not run since DiPlay started")
            }
            summary = "Wheel keys since DiPlay started: presses=$presses kept=$kept " +
                "leftToHeadUnit=${presses - kept} textKeysNotListed=$textKeys " +
                (if (bound && boundAt != null) "boundForMs=${nowMillis - boundAt}" else "bound=false") +
                if (bound && presses + textKeys == 0) "; no key has reached the service" else ""
            seen = entries.map { it.copy() }
        }
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        return (listOf(summary) + seen.map { entry ->
            "Wheel keys: ${time.format(Date(entry.atMillis))}  ${entry.text}" +
                if (entry.times > 1) " x${entry.times}, last ${time.format(Date(entry.lastMillis))}" else ""
        }).mapNotNull(DiagnosticRedactor::redact)
    }

    /** A keyboard's typing area: characters, and the keys that edit or modify them. */
    fun writesText(code: Int): Boolean =
        code in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_POUND ||
            code in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_SYM ||
            code in KeyEvent.KEYCODE_ENTER..KeyEvent.KEYCODE_NUM ||
            code == KeyEvent.KEYCODE_PLUS ||
            code in KeyEvent.KEYCODE_PAGE_UP..KeyEvent.KEYCODE_SWITCH_CHARSET ||
            code in KeyEvent.KEYCODE_ESCAPE..KeyEvent.KEYCODE_INSERT ||
            code in KeyEvent.KEYCODE_NUM_LOCK..KeyEvent.KEYCODE_NUMPAD_RIGHT_PAREN ||
            code in KeyEvent.KEYCODE_ZENKAKU_HANKAKU..KeyEvent.KEYCODE_KANA

    @Synchronized
    internal fun reset() {
        entries.clear()
        boundAtMillis = null
        presses = 0
        kept = 0
        textKeys = 0
    }
}

package com.shilapi.xcertplay

import android.app.Activity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.network.HotspotJoinRepair
import java.util.concurrent.atomic.AtomicBoolean

/** Opt-in controls for the saved car hotspot. Rendering/resuming never runs privileged work. */
internal class HotspotJoinControls(private val activity: Activity, private val sessionActive: () -> Boolean,
    private val backend: Backend = AndroidBackend(activity), private val beforeAction: () -> Unit = {},
    private val labelFactory: (String) -> TextView = { TextView(activity).apply { text = it; textSize = 15f } },
    private val buttonFactory: (String, () -> Unit) -> Button = { title, click ->
        Button(activity).apply { text = title; setOnClickListener { click() } } }) {
    interface Backend {
        fun run(action: HotspotJoinRepair.Action, token: String?): HotspotJoinRepair.Result
        fun execute(block: () -> Unit)
        fun cancel()
    }
    private class AndroidBackend(activity: Activity) : Backend {
        private val app = activity.applicationContext
        @Volatile private var repair: HotspotJoinRepair? = null
        private val cancelled = AtomicBoolean()
        override fun run(action: HotspotJoinRepair.Action, token: String?): HotspotJoinRepair.Result {
            val operation = HotspotJoinRepair().also { repair = it }
            if (cancelled.get()) operation.cancel()
            return try { operation.run(app, action, token) } finally { repair = null }
        }
        override fun execute(block: () -> Unit) {
            cancelled.set(false)
            Thread(block, "diplay-hotspot-join").start()
        }
        override fun cancel() { cancelled.set(true); repair?.cancel() }
    }
    private var root: LinearLayout? = null
    private var result: HotspotJoinRepair.Result? = null
    private var busy = false
    @Volatile private var closed = false

    fun build(): LinearLayout = LinearLayout(activity).also {
        it.orientation = LinearLayout.VERTICAL; root = it; display()
    }

    private fun display() {
        val view = root ?: return
        view.removeAllViews()
        fun text(id: Int) { view.addView(labelFactory(activity.getString(id)).apply {
            if (id == R.string.hotspot_join_title) textSize = 22f
            setPadding(0, dp(12), 0, dp(12))
        }) }
        fun button(id: Int, action: () -> Unit) { view.addView(buttonFactory(activity.getString(id), action)
            .apply { isEnabled = !busy }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }) }
        text(R.string.hotspot_join_title)
        text(R.string.hotspot_join_description)
        if (busy) text(R.string.hotspot_join_working) else result?.let { text(message(it.code)) }
        button(R.string.hotspot_join_check) { run(HotspotJoinRepair.Action.CHECK) }
        if (result?.code == HotspotJoinRepair.Code.READY)
            button(R.string.hotspot_join_apply) { confirm(HotspotJoinRepair.Action.APPLY) }
        if (result?.rollback == true && result?.token != null)
            button(R.string.hotspot_join_restore) { confirm(HotspotJoinRepair.Action.RESTORE) }
        if (busy) view.addView(buttonFactory(activity.getString(R.string.cancel)) { backend.cancel() },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
    }

    private fun confirm(action: HotspotJoinRepair.Action) {
        if (busy || closed) return
        if (sessionActive()) {
            activity.appDialogBuilder().setMessage(R.string.hotspot_join_disconnect)
                .setPositiveButton(android.R.string.ok, null).show()
            return
        }
        activity.appDialogBuilder().setTitle(if (action == HotspotJoinRepair.Action.APPLY)
            R.string.hotspot_join_apply else R.string.hotspot_join_restore)
            .setMessage(R.string.hotspot_join_confirm)
            .setPositiveButton(android.R.string.ok) { _, _ -> run(action) }
            .setNegativeButton(R.string.cancel, null).show()
    }

    private fun run(action: HotspotJoinRepair.Action) {
        if (busy || closed) return
        if (action != HotspotJoinRepair.Action.CHECK && sessionActive()) return
        beforeAction()
        val token = result?.token
        busy = true; display()
        backend.execute {
            if (closed) return@execute
            val outcome = try { backend.run(action, token) }
                catch (_: Exception) { HotspotJoinRepair.Result(HotspotJoinRepair.Code.UNKNOWN) }
            activity.runOnUiThread {
                if (!closed) { busy = false; result = outcome; display() }
            }
        }
    }

    fun close() { closed = true; backend.cancel(); root = null }
    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()

    private fun message(code: HotspotJoinRepair.Code): Int = when (code) {
        HotspotJoinRepair.Code.READY -> R.string.hotspot_join_ready
        HotspotJoinRepair.Code.ALREADY_PRESENT -> R.string.hotspot_join_present
        HotspotJoinRepair.Code.APPLIED -> R.string.hotspot_join_applied
        HotspotJoinRepair.Code.RESTORED -> R.string.hotspot_join_restored
        HotspotJoinRepair.Code.UNSUPPORTED -> R.string.hotspot_join_unsupported
        HotspotJoinRepair.Code.HOTSPOT_ON, HotspotJoinRepair.Code.UNKNOWN_STATE -> R.string.hotspot_join_off_required
        HotspotJoinRepair.Code.DRIFT -> R.string.hotspot_join_drift
        HotspotJoinRepair.Code.FIRMWARE_CHANGED -> R.string.hotspot_join_firmware_changed
        HotspotJoinRepair.Code.CANCELLED -> R.string.hotspot_join_cancelled
        HotspotJoinRepair.Code.FAILED_ROLLED_BACK -> R.string.hotspot_join_failed_restored
        HotspotJoinRepair.Code.BUSY -> R.string.hotspot_join_busy
        HotspotJoinRepair.Code.ADB_OFF, HotspotJoinRepair.Code.NOT_APPROVED,
        HotspotJoinRepair.Code.PAIRING_ONLY -> R.string.hotspot_join_adb_required
        else -> R.string.hotspot_join_recovery
    }
}

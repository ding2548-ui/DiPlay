package com.shilapi.xcertplay

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Replays the stock guidance broadcast frames a navigation app sends to a dashboard
 * that consumes turn-by-turn semantics instead of a mirrored display. Purely a test
 * signal for this head unit: it fires the same actions and extras the stock AMap
 * navigation emits, so the cluster's left navigation area can be probed without any
 * CarPlay session. Nothing here touches the CarPlay presentation path.
 */
object MeterNaviBroadcastTest {
    private const val TAG = "DiPlay-MeterTest"
    private const val ACTION = "svautomotive.navigation.3partymanager.MESSAGE"
    private const val CODE_START = 0x19c
    private const val CODE_FULL = 0x1a1
    private const val CODE_STOP = 0x19e
    private const val MAX_REPEATS = 30
    private const val REPEAT_INTERVAL_MS = 1000L

    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var turnLeft = false
    private var repeats = 0

    fun isRunning(): Boolean = running

    /** Starts the repeating guidance frames, or stops them and sends the end frame. */
    fun toggle(context: Context): Boolean {
        if (running) {
            stop(context.applicationContext)
        } else {
            start(context.applicationContext)
        }
        return running
    }

    private fun start(app: Context) {
        running = true
        repeats = 0
        turnLeft = false
        send(app, CODE_START)
        writeLog(app, "guidance start frame sent")
        handler.postDelayed({ tick(app) }, REPEAT_INTERVAL_MS)
    }

    private fun stop(app: Context) {
        running = false
        handler.removeCallbacksAndMessages(null)
        send(app, CODE_STOP)
        writeLog(app, "guidance stop frame sent; repeating stopped")
    }

    private fun tick(app: Context) {
        if (!running) return
        sendFull(app)
        repeats += 1
        if (repeats < MAX_REPEATS) {
            handler.postDelayed({ tick(app) }, REPEAT_INTERVAL_MS)
        } else {
            stop(app)
        }
    }

    private fun sendFull(app: Context) {
        turnLeft = !turnLeft
        // Turn icon ids per the stock mapping carried in the navigation adapter:
        // left turn maps to 1, straight ahead to 0.
        val direct = if (turnLeft) 1 else 0
        val distance = if (turnLeft) 500f else 800f
        send(app, CODE_FULL) {
            putExtra("FLOAT_DISTANCE", distance)
            putExtra("INT_REMAIN_TIME", 600)
            putExtra("FLOAT_REMAIN_LENGTH", 12000f)
            putExtra("FLOAT_CUR_SPEED", 60f)
            putExtra("STR_CUR_ROAD_NAME", "G330")
            putExtra("STR_NEXT_ROAD_NAME", if (turnLeft) "S215" else "G330")
            putExtra("INT_DRIVING_DIRECT", direct)
        }
        writeLog(app, "guidance frame sent direct=$direct distance=${distance.toInt()} repeat=$repeats")
    }

    private fun send(app: Context, code: Int, extras: Intent.() -> Unit = {}) {
        val intent = Intent(ACTION).putExtra("CODE", code)
        intent.extras()
        app.sendBroadcast(intent)
    }

    private fun writeLog(app: Context, message: String) {
        val line = "${SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())}  Meter navi test: $message"
        Log.i(TAG, line)
        runCatching {
            val file = File(File(app.filesDir, "logs"), "diplay.log")
            file.parentFile?.mkdirs()
            FileWriter(file, true).use { it.write("$line\n") }
        }
    }
}

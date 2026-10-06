package com.shilapi.xcertplay

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.orchestration.CarPlayController

/**
 * The Leapmotor head unit's gear signal (P/R/N/D), read from the CAN server's broadcast API.
 *
 * Protocol (see `com.leapmotor.carcanserver-档位识别-P-R-N-D.md`): sending the broadcast
 * `leapmotor.apptocan.update` with extra `upgradeAsk="upgrade"` makes `com.leapmotor.carcanserver`
 * answer with `leapmotor.cantoapp.update`, whose extras are CAN signal codes in decimal. The gear
 * is `OPCODE_VCU_GEAR_LEVEL_POS_STS = 0xf69b9` → extra key `"1010105"`:
 * `1`=R, `2`=N, `3`=D, anything else (including 0) = P.
 *
 * Consumers:
 *  - 倒车（R）→ 暂停 CarPlay 音乐；退出 R → 恢复（仅当是本次倒车自己暂停的）。
 *  - 视频门控（iOS 27 video in car）：**零跑没有 P 挡读数，改为 N 挡（raw==2）允许视频**。
 *    收到任何档位数据之前一律返回 null（禁止视频），避免把"无数据"误当可用。
 */
internal object LeapmotorGearMonitor {
    private const val TAG = "DiPlay-Gear"
    private const val REQUEST_ACTION = "leapmotor.apptocan.update"
    private const val RESPONSE_ACTION = "leapmotor.cantoapp.update"
    private const val REQUEST_EXTRA = "upgradeAsk"
    private const val REQUEST_VALUE = "upgrade"
    private const val GEAR_EXTRA = "1010105" // 0xf69b9, VCU 档位位置状态
    private const val CARCAN_PACKAGE = "com.leapmotor.carcanserver"
    private const val POLL_MILLIS = 1_000L

    private const val GEAR_R = 1
    private const val GEAR_N = 2
    private const val GEAR_D = 3

    private var receiver: BroadcastReceiver? = null
    private var pollThread: Thread? = null
    private var appContext: Context? = null

    @Volatile private var controller: CarPlayController? = null

    /** Both the logcat line and the app's diagnostic report receive these. */
    @Volatile var onDiagnostic: ((String) -> Unit)? = null

    /** 最新一次收到的档位原始值；-1 表示从未收到。 */
    @Volatile private var rawGear = -1

    private var pausedByReverse = false

    @Synchronized
    fun attach(context: Context, next: CarPlayController) {
        val application = context.applicationContext
        appContext = application
        controller = next
        if (receiver != null) return
        val created = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                handle(intent)
            }
        }
        val filter = IntentFilter(RESPONSE_ACTION)
        // The CAN server broadcasts this action, so the receiver is exported. The flag overload of
        // registerReceiver only exists from Android 13; on this API 25 head unit passing the flag
        // would be a NoSuchMethodError of its own, so the two-argument call stays for older
        // platforms and lint is silenced for the whole block rather than for the branch.
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
                report("gear monitor listening on $RESPONSE_ACTION")
            }
            .onFailure { report("gear monitor receiver not registered: ${it.message}") }
        if (pollThread == null) {
            pollThread = Thread(::runPoll, "diplay-gear-poll").apply {
                isDaemon = true
                start()
            }
        }
    }

    @Synchronized
    fun detach(expected: CarPlayController?) {
        if (expected != null && controller !== expected) return
        controller = null
        receiver?.let { active ->
            runCatching { appContext?.unregisterReceiver(active) }
            receiver = null
            report("gear monitor detached")
        }
        pollThread?.interrupt()
        pollThread = null
        rawGear = -1
        pausedByReverse = false
    }

    /** Whether video may play now (N 挡，零跑没有 P 挡读数); null when no gear data ever arrived. */
    fun videoAllowed(): Boolean? {
        if (rawGear == -1) return null
        return rawGear == GEAR_N
    }

    /** Current gear as R/N/D（零跑无 P 档读数，0 视为 N 以外的"未知"）, or null without data. */
    fun gearName(): String? = when (rawGear) {
        -1 -> null
        GEAR_R -> "R"
        GEAR_N -> "N"
        GEAR_D -> "D"
        else -> "?"
    }

    private fun runPoll() {
        while (!Thread.currentThread().isInterrupted) {
            val context = appContext
            if (controller != null && context != null) {
                runCatching {
                    context.sendBroadcast(
                        Intent(REQUEST_ACTION)
                            .setPackage(CARCAN_PACKAGE)
                            .putExtra(REQUEST_EXTRA, REQUEST_VALUE),
                    )
                }
            }
            try {
                Thread.sleep(POLL_MILLIS)
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    private fun handle(intent: Intent?) {
        if (intent?.action != RESPONSE_ACTION) return
        @Suppress("DEPRECATION")
        val raw = runCatching { intent.getIntExtra(GEAR_EXTRA, -1) }.getOrDefault(-1)
        if (raw == -1) return
        val previous = rawGear
        rawGear = raw
        if (previous != raw) {
            report("gear raw=$previous -> $raw (${gearName() ?: "?"})")
            updateReverse(controller, reverse = raw == GEAR_R)
        }
    }

    /** 倒车 → 暂停 CarPlay；退出倒车 → 恢复（仅当是本次倒车暂停的）。 */
    private fun updateReverse(active: CarPlayController?, reverse: Boolean) {
        if (reverse) {
            if (pausedByReverse) return
            val sent = active?.sendMediaButton(CarPlayMediaButton.PAUSE) ?: false
            // Only remember our own successful pause, so we never "resume" a pause the user chose.
            pausedByReverse = sent
            report("reverse: pause CarPlay sent=$sent")
        } else {
            if (!pausedByReverse) return
            pausedByReverse = false
            val sent = active?.sendMediaButton(CarPlayMediaButton.PLAY) ?: false
            report("reverse ended: resume CarPlay sent=$sent")
        }
    }

    private fun report(message: String) {
        Log.i(TAG, message)
        runCatching { onDiagnostic?.invoke(message) }
    }
}

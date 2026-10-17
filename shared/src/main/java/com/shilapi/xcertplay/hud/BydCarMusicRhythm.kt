package com.shilapi.xcertplay.hud

import android.content.Context
import android.os.Binder
import android.os.IBinder
import android.util.Log
import com.shilapi.xcertplay.adb.AdbKeys
import com.shilapi.xcertplay.adb.LocalAdb
import java.lang.reflect.Method
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

/**
 * The car's own ambient-light music rhythm, running while CarPlay music plays.
 *
 * On a 2024 Tang (DiLink 5, Android 12) the rhythm lives in system_server
 * (`com.android.server.audio.visualizer`). A music app calls BYD's `IAudioService.startAudioOutput(package, token)`,
 * and system_server starts a Visualizer on the output mix and sends colours to the lamp ECU, following whatever
 * plays, until `stopAudioOutput` or the token's process dies. It starts only for BYD's own music apps (its media
 * centre, Kuwo, Kugou, NetEase, QQ Music), so DiPlay names the media centre. The car's ambient-light menu
 * keeps the colours and effects, and its music rhythm switch must be on.
 *
 * DiPlay first makes that call from its own process ([BydCarMusicRhythmDirect]). Where that fails,
 * [BydCarMusicRhythmTool] makes it instead, as the shell user over DiPlay's existing network ADB access, which this
 * never asks for. A rhythm is stopped the way it was started: on the Tang the head unit keeps each session under
 * its token.
 */
object BydCarMusicRhythm {
    private const val TAG = "DiPlay-CarRhythm"
    // A failed start is tried again, a little later each time, while the music still plays.
    private const val RETRY_MILLIS = 2_000L
    private const val MAX_RETRIES = 3
    private val executor = Executors.newSingleThreadScheduledExecutor { Thread(it, "diplay-car-rhythm").apply { isDaemon = true } }
    /** Tried first; tests replace it. */
    internal var direct: BydCarMusicRhythmCall = BydCarMusicRhythmDirect
    /** Used only when [direct] cannot; tests replace it. */
    internal var helper: BydCarMusicRhythmCall = BydCarMusicRhythmHelper
    @Volatile private var wanted = false
    // The way that started the running rhythm, and so the one that stops it; null while it is off.
    private var startedBy: BydCarMusicRhythmCall? = null
    private var failures = 0

    /** Starts or stops the car's rhythm; repeated calls with the same state do nothing. */
    fun setPlaying(context: Context, value: Boolean) {
        if (wanted == value) return
        wanted = value
        val app = context.applicationContext
        executor.execute {
            failures = 0
            apply(app)
        }
    }

    /**
     * Whether this head unit's audio service has the call, reachable from DiPlay itself or else over DiPlay's ADB
     * access; nothing is started.
     */
    fun checkSupport(context: Context): CompletableFuture<Boolean> {
        val result = CompletableFuture<Boolean>()
        val app = context.applicationContext
        executor.execute {
            val supported = runCatching { direct.supported(app) || helper.supported(app) }.getOrDefault(false)
            Log.i(TAG, "check supported=$supported")
            result.complete(supported)
        }
        return result
    }

    private fun apply(context: Context) {
        val running = startedBy
        if (wanted == (running != null)) return
        if (running != null) {
            running.stop(context)
            startedBy = null
        } else {
            startedBy = listOf(direct, helper).firstOrNull { it.start(context) }
            if (startedBy == null) retry(context) else failures = 0
        }
        val state = when (startedBy) {
            null -> "off"
            direct -> "on (direct)"
            else -> "on (adb helper)"
        }
        Log.i(TAG, "car music rhythm $state")
    }

    private fun retry(context: Context) {
        if (!wanted || failures >= MAX_RETRIES) return
        failures++
        Log.i(TAG, "start again in ${RETRY_MILLIS * failures} ms (attempt $failures)")
        executor.schedule({ apply(context) }, RETRY_MILLIS * failures, java.util.concurrent.TimeUnit.MILLISECONDS)
    }
}

/** One way to make BYD's start/stop call; [BydCarMusicRhythm] calls it only on its own thread. */
internal interface BydCarMusicRhythmCall {
    /** Whether this way can reach the call; nothing is started. */
    fun supported(context: Context): Boolean
    /** Starts the car's rhythm; false when this way could not. */
    fun start(context: Context): Boolean
    /** Stops the rhythm this way started. */
    fun stop(context: Context)
}

/**
 * Makes the call from DiPlay's own process, by reflection on the hidden `IAudioService`. On a 2024 Tang (DiLink 5,
 * Android 12L) ART logs the access as `blocked, reflection, denied` and still lets it through. A head unit that
 * enforces the block hides the method, so [supported] and [start] return false there.
 */
internal object BydCarMusicRhythmDirect : BydCarMusicRhythmCall {
    private const val TAG = "DiPlay-CarRhythm"
    // One token for the whole process, so a stop ends the session its start opened; the head unit also drops the
    // session when DiPlay's process dies.
    private val token = Binder()

    override fun supported(context: Context): Boolean = runCatching { BydCarMusicRhythmTool.audio() }
        .onFailure { Log.i(TAG, "no direct call: ${describe(it)}") }.isSuccess

    override fun start(context: Context): Boolean = call(start = true)

    override fun stop(context: Context) { call(start = false) }

    private fun call(start: Boolean): Boolean = runCatching {
        val audio = BydCarMusicRhythmTool.audio()
        (if (start) audio.start else audio.stop).invoke(audio.service, BydCarMusicRhythmTool.MEDIA_CENTRE, token)
    }.onFailure { Log.w(TAG, "direct ${if (start) "start" else "stop"} failed: ${describe(it)}") }.isSuccess

    private fun describe(error: Throwable): String = (error.cause ?: error).let { "${it.javaClass.simpleName}: ${it.message}" }
}

/** [BydCarMusicRhythmTool] kept open over DiPlay's network ADB access, one helper at a time. */
internal object BydCarMusicRhythmHelper : BydCarMusicRhythmCall {
    private const val TAG = "DiPlay-CarRhythm"
    private var adb: LocalAdb? = null
    private var shell: LocalAdb.InteractiveShell? = null

    override fun supported(context: Context): Boolean = runCatching {
        LocalAdb(AdbKeys.load(context)).use { client ->
            client.connect(mayAsk = false) == LocalAdb.Access.READY &&
                BydAmbientLightWorkerProtocol.reply(client.shell(command(context, "--probe"))) == "supported"
        }
    }.getOrDefault(false)

    override fun start(context: Context): Boolean {
        val open = shell ?: open(context) ?: return false
        val reply = BydAmbientLightWorkerProtocol.reply(open.exchangeBounded("start"))
        if (reply == "started") return true
        Log.w(TAG, "helper replied $reply")
        close()
        return false
    }

    override fun stop(context: Context) {
        // A helper already gone has stopped it: its stdin closed, and the head unit drops a dead token.
        val reply = shell?.let { BydAmbientLightWorkerProtocol.reply(it.exchangeBounded("stop")) } ?: return
        if (reply != "stopped") {
            Log.w(TAG, "helper replied $reply")
            close()
        }
    }

    private fun open(context: Context): LocalAdb.InteractiveShell? {
        close()
        val client = LocalAdb(AdbKeys.load(context)).also { adb = it }
        if (client.connect(mayAsk = false) != LocalAdb.Access.READY) {
            Log.w(TAG, "no adb access")
            close()
            return null
        }
        val opened = client.openShell(command(context, "--stdin")) ?: run { close(); return null }
        if (BydAmbientLightWorkerProtocol.reply(opened.exchangeBounded("ping", 10_000)) != "ready") {
            Log.w(TAG, "helper not ready")
            opened.close()
            close()
            return null
        }
        shell = opened
        return opened
    }

    private fun close() {
        runCatching { shell?.close() }
        shell = null
        runCatching { adb?.close() }
        adb = null
    }

    private fun command(context: Context, mode: String): String {
        val apk = "'" + context.applicationInfo.sourceDir.replace("'", "'\\''") + "'"
        return "CLASSPATH=$apk app_process /system/bin ${BydCarMusicRhythmTool::class.java.name} $mode"
    }
}

/**
 * Runs under the head unit's adb shell through app_process, not in DiPlay. `--probe` prints `supported` when the
 * audio service has BYD's start/stop calls. `--stdin` serves one request per line (`ping`, `start`, `stop`), each
 * answered with one line and `done`; stdin closing stops the rhythm.
 */
object BydCarMusicRhythmTool {
    /** BYD's media centre: the head unit starts its music rhythm only for its own music apps. */
    const val MEDIA_CENTRE = "com.byd.mediacenter"

    internal class Audio(val service: Any, val start: Method, val stop: Method)

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            when (args.firstOrNull()) {
                "--probe" -> println(if (runCatching { audio() }.isSuccess) "supported" else "unsupported")
                "--stdin" -> serve()
            }
        } catch (error: Throwable) {
            println("error ${(error.cause ?: error).javaClass.simpleName}")
            println("done")
        } finally {
            System.out.flush()
            System.exit(0)
        }
    }

    /** BYD's start/stop calls on the audio service; throws where the service lacks them or they are hidden. */
    internal fun audio(): Audio {
        val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
            .invoke(null, "audio") as IBinder
        val service = checkNotNull(Class.forName("android.media.IAudioService\$Stub")
            .getMethod("asInterface", IBinder::class.java).invoke(null, binder))
        return Audio(service,
            service.javaClass.getMethod("startAudioOutput", String::class.java, IBinder::class.java),
            service.javaClass.getMethod("stopAudioOutput", String::class.java, IBinder::class.java))
    }

    private fun serve() {
        val audio = audio()
        val token = Binder()
        var started = false
        val input = System.`in`.bufferedReader()
        while (true) {
            val reply = when ((input.readLine() ?: break).trim()) {
                "ping" -> "ready"
                "start" -> {
                    if (!started) audio.start.invoke(audio.service, MEDIA_CENTRE, token)
                    started = true
                    "started"
                }
                "stop" -> {
                    if (started) audio.stop.invoke(audio.service, MEDIA_CENTRE, token)
                    started = false
                    "stopped"
                }
                else -> "error request"
            }
            println(reply)
            println("done")
            System.out.flush()
        }
        if (started) audio.stop.invoke(audio.service, MEDIA_CENTRE, token)
    }
}

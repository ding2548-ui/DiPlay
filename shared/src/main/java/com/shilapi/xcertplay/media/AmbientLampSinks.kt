package com.shilapi.xcertplay.media

import android.content.Context
import android.util.Log
import com.shilapi.xcertplay.LmAmbientLightClient
import com.shilapi.xcertplay.LmAmbientLightProtocol
import com.shilapi.xcertplay.LmAmbientMode
import com.shilapi.xcertplay.hud.BydAmbientLightClient
import com.shilapi.xcertplay.hud.BydAmbientLightPolicy

/**
 * BYD transport: the pre-existing ADB worker wrapped in the pre-existing serialized session.
 * Unchanged behaviour, only moved behind the [AmbientLampSink] seam so the Leapmotor build can
 * swap it out.
 */
internal class BydAdbLampSink(context: Context, private val executor: java.util.concurrent.Executor,
    private val applied: (Triple<Int, Int, Int>) -> Unit) : AmbientLampSink {
    private val lampSession = AmbientLampSession(
        factory = {
            val real = BydAmbientLightClient(context)
            object : AmbientLampSession.Client {
                override fun unhealthy(): Boolean {
                    val phase = real.status().phase
                    return phase == BydAmbientLightClient.Phase.FAILED || phase == BydAmbientLightClient.Phase.CLOSED
                }
                override fun begin(seed: BydAmbientLightPolicy.Snapshot?) = real.begin(seed?.let {
                    BydAmbientLightClient.State(it.frontColor, it.backColor, it.frontBrightness, it.backBrightness, it.area)
                }).thenApply { BydAmbientLightPolicy.Snapshot(it.frontColor, it.backColor, it.frontBrightness, it.backBrightness, it.area) }
                override fun apply(desired: Triple<Int, Int, Int>) = real.apply(desired.first, desired.second, desired.third).thenApply { Unit }
                override fun stop() = real.stop().thenApply { Unit }
                override fun close() = real.close()
            }
        },
        dispatch = { executor.execute(it) },
        now = { android.os.SystemClock.uptimeMillis() },
        log = { Log.i("DiPlay-Ambient", it) },
        applied = applied,
    )

    override fun render(frame: AmbientLampFrame) {
        lampSession.setEnabled(true)
        lampSession.update(Triple(frame.area, frame.colorIndex, frame.brightness))
    }

    override fun restore() {
        lampSession.setEnabled(false)
    }

    override fun isReady(): Boolean = lampSession.isReady()

    override fun recover() = lampSession.recover()
}

/**
 * Leapmotor transport: one `leapmotor.carapp.CARCANCONTROL` broadcast per frame, routed with
 * `appCAN="Atmos_ctrl"`. The head unit never replies, so there is nothing to keep in sync and
 * nothing that can fail asynchronously — the only real constraint is the send rate, which the
 * controller's 200ms cadence already holds well under [LmAmbientLightProtocol.MAX_SENDS_PER_SECOND].
 */
internal class LeapmotorLampSink(context: Context) : AmbientLampSink {
    private val client = LmAmbientLightClient(context)
    private var last: LmAmbientLightProtocol.Params? = null
    private var sends = 0L
    private var lastLogMillis = 0L

    override fun render(frame: AmbientLampFrame) {
        val lamp = frame.leapmotor ?: return
        val params = LmAmbientLightProtocol.switchParams(
            if (lamp.mode == LmAmbientMode.OFF) LmAmbientMode.AUTO else lamp.mode, lamp.deg, lamp.rgb)
        if (params == last) return
        last = params
        client.send(params)
        sends++
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastLogMillis >= 5_000) {
            lastLogMillis = now
            Log.i("DiPlay-Ambient", "leapmotor applied=$sends sw=${params.sw} auto=${params.auto} deg=${params.deg} r=${params.r} g=${params.g} b=${params.b}")
        }
    }

    override fun restore() {
        // The feature is off: hand the lamp back to the car's own automatic mode at the default
        // angle rather than leaving it on our last colour.
        val current = last
        val params = LmAmbientLightProtocol.switchParams(LmAmbientMode.AUTO,
            LmAmbientLightProtocol.DEG_DEFAULT, current?.let { (it.r shl 16) or (it.g shl 8) or it.b }
                ?: DEFAULT_RGB)
        if (params == last) return
        last = params
        client.send(params)
        Log.i("DiPlay-Ambient", "leapmotor restored to auto")
    }

    private companion object {
        const val DEFAULT_RGB = (LmAmbientLightProtocol.DEFAULT_R shl 16) or
            (LmAmbientLightProtocol.DEFAULT_G shl 8) or LmAmbientLightProtocol.DEFAULT_B
    }
}

/** The single place that decides which transport this build uses. */
internal object AmbientLampSinks {
    fun create(context: Context, channel: AmbientLampChannel, executor: java.util.concurrent.Executor,
        applied: (Triple<Int, Int, Int>) -> Unit): AmbientLampSink = when (channel) {
        AmbientLampChannel.LEAPMOTOR_BROADCAST -> LeapmotorLampSink(context.applicationContext)
        AmbientLampChannel.BYD_ADB -> BydAdbLampSink(context.applicationContext, executor, applied)
    }
}

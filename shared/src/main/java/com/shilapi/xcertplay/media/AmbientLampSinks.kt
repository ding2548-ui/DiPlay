package com.shilapi.xcertplay.media

import android.content.Context
import com.shilapi.xcertplay.AmbientDiagnostics
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
        log = { AmbientDiagnostics.log(it) },
        applied = applied,
    )

    override fun render(frame: AmbientLampFrame) {
        lampSession.setEnabled(true)
        lampSession.update(Triple(frame.area, frame.colorIndex, frame.brightness))
    }

    /** BYD's brightness lives in the 1..6 `brightness` field, so the Leapmotor level is unused. */
    override fun restore(lampDeg: Int) {
        lampSession.setEnabled(false)
    }

    override fun isReady(): Boolean = lampSession.isReady()

    override fun recover() = lampSession.recover()
}

/**
 * Leapmotor transport: one `leapmotor.carapp.CARCANCONTROL` broadcast per changed frame, routed with
 * `appCAN="Atmos_ctrl"`. The head unit never replies, so there is nothing to keep in sync and
 * nothing that can fail asynchronously; the only real constraint is the send rate, which the
 * controller's 50 ms cadence keeps at 20/s and the "unchanged state is not re-sent" check below
 * cuts further.
 */
internal class LeapmotorLampSink(context: Context) : AmbientLampSink {
    private val client = LmAmbientLightClient(context)
    private var last: LmAmbientLightProtocol.Params? = null
    private var sends = 0L
    private var lastLogMillis = 0L

    override fun render(frame: AmbientLampFrame) {
        val lamp = frame.leapmotor ?: return
        val params = LmAmbientLightProtocol.switchParams(lamp.mode, lamp.deg, lamp.rgb)
        if (params == last) return
        // Only remember a state the platform accepted: a refused broadcast has to be retried on the
        // next tick instead of being deduplicated away.
        if (!client.send(params)) return
        last = params
        sends++
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastLogMillis >= 5_000) {
            lastLogMillis = now
            AmbientDiagnostics.log("leapmotor applied=$sends sw=${params.sw} auto=${params.auto} deg=${params.deg} r=${params.r} g=${params.g} b=${params.b}")
        }
    }

    /**
     * Hands the switch back to the car's own automatic mode, keeping the colour we last sent and the
     * brightness the user configured. Forcing [LmAmbientLightProtocol.DEG_DEFAULT] here (the old
     * behaviour) silently reset the car's own `fendeng` to 5 every time the feature was turned off.
     */
    override fun restore(lampDeg: Int) {
        val rgb = last?.let { (it.r shl 16) or (it.g shl 8) or it.b } ?: DEFAULT_RGB
        val params = LmAmbientLightProtocol.switchParams(
            LmAmbientMode.AUTO,
            lampDeg.coerceIn(LmAmbientLightProtocol.DEG_MIN, LmAmbientLightProtocol.DEG_MAX),
            rgb)
        val alreadyCurrent = params == last
        if (!alreadyCurrent) {
            if (!client.send(params)) return
            last = params
        }
        AmbientDiagnostics.log("leapmotor restored to auto deg=${params.deg} sent=${!alreadyCurrent}")
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

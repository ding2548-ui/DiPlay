package com.shilapi.xcertplay.media

import android.content.Context
import com.shilapi.xcertplay.AmbientDiagnostics
import com.shilapi.xcertplay.LmAmbientLightProtocol
import com.shilapi.xcertplay.LmAmbientMode
import com.shilapi.xcertplay.LmCarProbe
import com.shilapi.xcertplay.hud.BydAmbientLightClient
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** The sole scheduler owns lamp I/O; audio writes only append small envelope metadata. */
object AmbientMusicController {
    private val executor = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "carplay-ambient-music").apply { isDaemon = true }
    }
    private val sequence = AtomicLong()
    private val activeSink = AtomicLong()
    private val activeRenderer = AtomicLong()
    private val phonePlayback = AmbientPhonePlaybackState()
    @Volatile private var artworkOwner: Any? = null
    private var albumColor: Int? = null
    @Volatile private var settings = AmbientMusicSettings.Values()
    private var context: Context? = null
    private var envelope: AmbientMusicEnvelope? = null
    private var playback: (() -> Pair<Int, Boolean>)? = null
    private var allowed = false
    private var colors = AmbientMusicColorDetector()
    private var brightnessEnvelope = AmbientMusicBrightness(settings.speed)
    private val flow = LmAmbientMusicFlow()
    private var appliedCount = 0L
    private var lastLogMillis = 0L

    /**
     * The transport this unit uses, decided at runtime by [LmCarProbe] rather than compiled in.
     *
     * A Leapmotor head unit carries `/data/leapmotor/CarType` and therefore gets the fire-and-forget
     * CAN broadcast; every other unit (BYD and anything unknown) keeps the ADB transport it always
     * had. The probe is a single cheap file check, so the BYD path is untouched — in particular the
     * ADB worker is never started on a Leapmotor car and vice versa.
     */
    internal val lampChannel: AmbientLampChannel by lazy {
        if (LmCarProbe.isLeapmotor()) AmbientLampChannel.LEAPMOTOR_BROADCAST else AmbientLampChannel.BYD_ADB
    }

    /** True when the runtime probe selected the Leapmotor channel. Used by the settings UI copy. */
    val runsOnLeapmotor: Boolean get() = lampChannel == AmbientLampChannel.LEAPMOTOR_BROADCAST

    @Volatile private var sink: AmbientLampSink? = null

    // The 20 Hz lamp update, scheduled only while the feature is on; turning it off restores the lamps by itself.
    private var ticking: ScheduledFuture<*>? = null

    /** On the executor, after [settings] changed. */
    private fun updateTicking() {
        if (!settings.enabled) {
            ticking?.cancel(false)
            ticking = null
        } else if (ticking == null) {
            ticking = executor.scheduleWithFixedDelay({ runCatching { tick() }.onFailure {
                AmbientDiagnostics.warn("lamp update failed", it)
                sink?.recover()
            } }, 50, 50, TimeUnit.MILLISECONDS)
        }
    }

    private fun currentSink(): AmbientLampSink? = sink ?: context?.let { appContext ->
        AmbientLampSinks.create(appContext, lampChannel, executor) { desired ->
            appliedCount++
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastLogMillis >= 5_000) {
                lastLogMillis = now
                AmbientDiagnostics.log("applied=$appliedCount area=${desired.first} color=${desired.second} brightness=${desired.third} colorMode=${settings.colorMode} estimatedBpm=${colors.estimatedBpm?.toInt() ?: 0}")
            }
        }.also { sink = it }
    }

    @Synchronized fun openSink(context: Context?): Long {
        if (context == null) return 0L
        this.context = context.applicationContext
        val token = sequence.incrementAndGet()
        activeSink.set(token)
        activeRenderer.set(0L)
        executor.execute {
            if (activeSink.get() != token) return@execute
            settings = AmbientMusicSettings.load(context)
            resetColors()
            flow.reset()
            brightnessEnvelope = AmbientMusicBrightness(settings.speed)
            allowed = settings.enabled; envelope = null; playback = null
            if (!settings.enabled) restoreOriginal()
            updateTicking()
        }
        return token
    }

    @Synchronized internal fun openRenderer(sink: Long, envelope: AmbientMusicEnvelope,
        playback: () -> Pair<Int, Boolean>): Long {
        if (sink == 0L || sink != activeSink.get()) return 0L
        val token = sequence.incrementAndGet()
        activeRenderer.set(token)
        executor.execute {
            if (activeSink.get() != sink || activeRenderer.get() != token) return@execute
            allowed = true
            this.envelope = envelope
            this.playback = playback
        }
        return token
    }

    internal fun wantsPcm(token: Long): Boolean = token != 0L && token == activeRenderer.get() && settings.enabled && settings.music

    /** Binds phone playback callbacks to the current CarPlay media owner. */
    @Synchronized fun claimPlaybackOwner(owner: Any) {
        phonePlayback.claim(owner)
        if (artworkOwner === owner) return
        artworkOwner = owner
        albumColorChanged(owner, null)
    }

    /** Already decoded artwork is sampled on the existing artwork worker, never on PCM writes. */
    fun albumColorChanged(owner: Any, color: Int?) {
        executor.execute {
            if (artworkOwner !== owner) return@execute
            val valid = color?.takeIf { it in 1..31 }
            if (albumColor == valid) return@execute
            albumColor = valid
            if (settings.colorSource == AmbientColorSource.ALBUM) resetColors(preserveTiming = true)
        }
    }

    /** Stale callbacks from a replaced media owner are ignored. */
    fun phonePlaybackChanged(owner: Any, playing: Boolean): Boolean = phonePlayback.update(owner, playing)

    @Synchronized fun releasePlaybackOwner(owner: Any): Boolean {
        if (artworkOwner === owner) {
            artworkOwner = null
            executor.execute {
                if (artworkOwner != null) return@execute
                albumColor = null
                if (settings.colorSource == AmbientColorSource.ALBUM) resetColors(preserveTiming = true)
            }
        }
        return phonePlayback.release(owner)
    }

    private fun resetColors(preserveTiming: Boolean = false) {
        val palette = ambientColorPalette(settings.colorSource, settings.selectedColors, albumColor)
        val initial = if (settings.colorSource == AmbientColorSource.ALBUM) albumColor ?: palette.first()
            else settings.color
        // 不再注入随机取色：颜色只能来自用户色板（或专辑解析出的颜色），否则就是「随机变色」。
        if (preserveTiming) colors.updatePalette(initial, palette)
        else colors = AmbientMusicColorDetector(initial, palette, AmbientColorSpeed.STANDARD)
    }

    /**
     * Checks existing authorization and lamp readback only; never asks for ADB or writes a lamp.
     *
     * On a Leapmotor unit the transport is a fire-and-forget broadcast: there is nothing to authorize
     * and nothing to read back, so the answer is always yes without ever constructing the ADB-backed
     * sink. This is also why the settings dialog must not gate on the reader below.
     */
    fun checkSupport(context: Context): java.util.concurrent.CompletableFuture<Boolean> {
        if (LmCarProbe.isLeapmotor()) {
            return java.util.concurrent.CompletableFuture.completedFuture(true)
        }
        val result = java.util.concurrent.CompletableFuture<Boolean>()
        executor.execute {
            val existing = sink as? BydAdbLampSink
            if (existing?.isReady() == true) {
                result.complete(true)
                return@execute
            }
            val client = BydAmbientLightClient(context.applicationContext)
            client.read().handle { _, error -> error == null }.thenCompose { supported ->
                // Release the read-only worker before an enabled session can acquire its process lock.
                client.stop().handle { _, error ->
                    client.close()
                    supported && error == null
                }
            }.whenComplete { supported, error -> result.complete(error == null && supported == true) }
        }
        return result
    }

    fun settingsChanged(context: Context) {
        executor.execute {
            this.context = context.applicationContext
            settings = AmbientMusicSettings.load(context)
            resetColors()
            flow.reset()
            brightnessEnvelope = AmbientMusicBrightness(settings.speed)
            allowed = settings.enabled
            if (!settings.enabled) restoreOriginal()
            updateTicking()
        }
    }

    @Synchronized internal fun closeRenderer(token: Long) {
        if (token == 0L || !activeRenderer.compareAndSet(token, 0L)) return
        executor.execute {
            if (activeRenderer.get() != 0L) return@execute
            allowed = settings.enabled
            envelope = null; playback = null
            if (!settings.enabled) restoreOriginal()
        }
    }

    @Synchronized fun closeSink(token: Long) {
        if (token == 0L || !activeSink.compareAndSet(token, 0L)) return
        activeRenderer.set(0L)
        executor.execute {
            if (activeSink.get() != 0L) return@execute
            allowed = settings.enabled
            envelope = null; playback = null
            if (!settings.enabled) restoreOriginal()
        }
    }

    private fun tick() {
        val values = settings
        if (!allowed) return
        if (!values.enabled) { restoreOriginal(); return }
        if (context == null) return
        val lamp = currentSink() ?: return
        val rendererActive = activeRenderer.get() != 0L && playback != null
        // Playback is only consulted while the renderer is live. The old code also required a nonzero
        // "brightness" here, which is a BYD-only ceiling that Leapmotor has no equivalent of.
        val playbackState = if (rendererActive) playback?.invoke() else null
        val leapmotor = lampChannel == AmbientLampChannel.LEAPMOTOR_BROADCAST
        val target = AmbientMusicActivityPolicy.target(
            enabled = values.enabled,
            music = values.music,
            rendererActive = rendererActive,
            playing = playbackState?.second == true && phonePlayback.current() != false
        )
        if (target == AmbientMusicTarget.RESTORE_OEM) { restoreOriginal(); return }
        val energy = if (target == AmbientMusicTarget.FOLLOW_PLAYBACK) {
            val (head, playing) = playbackState ?: return
            envelope?.playedWindow(head, playing) ?: AmbientMusicEnvelope.Energy(0.0, 0.0)
        } else AmbientMusicEnvelope.Energy(0.0, 0.0)
        val rms = energy.rms
        val nowMillis = android.os.SystemClock.uptimeMillis()
        val brightness = when (target) {
            AmbientMusicTarget.LOWEST -> 1
            AmbientMusicTarget.FOLLOW_PLAYBACK -> brightnessEnvelope.level(rms, nowMillis, values.brightness)
            else -> values.brightness
        }
        val area = if (target == AmbientMusicTarget.LOWEST) 3 else values.area
        val color = if (target == AmbientMusicTarget.FOLLOW_PLAYBACK && values.colorCycle)
            colors.color(values.colorMode, nowMillis, rms, energy.bassRms) else if (values.colorSource == AmbientColorSource.ALBUM) albumColor ?: 1
            else values.selectedColors.firstOrNull() ?: values.color
        val lm = if (!leapmotor) null else leapmotorFrame(target, values, energy, nowMillis, color)
        lamp.render(AmbientLampFrame(area, color, brightness, lm))
    }

    /**
     * 零跑灯带帧。音乐跟随中：SINGLE 固定为用户选定色、亮度随低频；MULTI 显示色板推进色
     * （由「颜色变化方式」在已选色板内切换）、亮度为用户档位。非跟随帧显示用户色板色。
     * 两种模式都不发频谱 RGB——那是用户看到的「随机变色」来源。
     *
     * The switch state is [LmAmbientMode.ON] on every frame. The OEM music engine echoes whatever
     * switch state the driver had stored (`GetSW()`/`GetAuto()`), which this app cannot read: the
     * OEM keeps it in its own private `ACache`, not on the CAN bus. `ON` is the only choice that
     * guarantees the strip is lit while the feature runs — `AUTO` leaves the decision to the car's
     * ambient-light sensor, so a daytime drive would show nothing. Handing control back is
     * [AmbientLampSink.restore]'s job, which sends `AUTO`.
     */
    private fun leapmotorFrame(target: AmbientMusicTarget, values: AmbientMusicSettings.Values,
        energy: AmbientMusicEnvelope.Energy, nowMillis: Long, colorIndex: Int): LeapmotorLamp {
        // SINGLE 固定显示用户选定的颜色（`values.color`，如 26#B8224C），绝不随音乐跳变；
        // MULTI 显示色板推进到的当前色（由颜色变化方式在已选色板内切换）。
        val heldIndex = (if (values.colorSource == AmbientColorSource.ALBUM) albumColor else null)
            ?: values.color
        val shownIndex = if (values.flowMode == AmbientFlowMode.SINGLE) heldIndex else colorIndex
        val selectedRgb = AmbientAlbumPalette.previewRgb(shownIndex.coerceIn(1, 31))
        val deg = values.flowBrightness
            .coerceIn(LmAmbientLightProtocol.DEG_MIN, LmAmbientLightProtocol.DEG_MAX)
        return if (target == AmbientMusicTarget.FOLLOW_PLAYBACK && values.music) {
            val params = flow.params(energy, nowMillis, selectedRgb, values.flowMode, deg)
            LeapmotorLamp(LmAmbientMode.ON, params.deg, (params.r shl 16) or (params.g shl 8) or params.b)
        } else {
            // A fixed colour honours the user's brightness instead of the protocol default, so the
            // row the BYD path calls "maximum brightness" is meaningful on a Leapmotor car too.
            LeapmotorLamp(LmAmbientMode.ON, deg, selectedRgb)
        }
    }

    private fun restoreOriginal() {
        sink?.restore(settings.flowBrightness
            .coerceIn(LmAmbientLightProtocol.DEG_MIN, LmAmbientLightProtocol.DEG_MAX))
    }
}

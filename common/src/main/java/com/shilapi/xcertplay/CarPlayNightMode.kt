package com.shilapi.xcertplay

/** Stable preference values; never persist enum ordinals. */
enum class CarPlayNightMode(val key: String) {
    SYSTEM("system"),
    AMBIENT("ambient"),
    DAY("day"),
    NIGHT("night");

    companion object {
        fun fromKey(key: String?): CarPlayNightMode = entries.firstOrNull { it.key == key } ?: SYSTEM
    }
}

/** Main-thread controller. Only its output is sent to CarPlay; Android theme is never changed. */
internal class CarPlayNightModeController(
    private val light: LightSource,
    private val scheduler: Scheduler,
    initialNight: Boolean,
    private val onNightChanged: (Boolean) -> Unit,
) {
    interface LightSource {
        val available: Boolean
        fun start(onLux: (Float) -> Unit): Boolean
        fun stop()
    }

    interface Scheduler {
        fun postDelayed(task: Runnable, delayMillis: Long)
        fun remove(task: Runnable)
    }

    var night: Boolean = initialNight
        private set
    private var mode = CarPlayNightMode.SYSTEM
    private var systemNight = initialNight
    private var threshold = AmbientLightThreshold()
    private var delaySeconds = 2
    private var resumed = false
    private var listening = false
    private var pending: Boolean? = null
    private val transition = Runnable {
        val target = pending
        pending = null
        if (resumed && listening && mode == CarPlayNightMode.AMBIENT && target != null) {
            applyNight(target)
        }
    }

    fun configure(
        mode: CarPlayNightMode,
        systemNight: Boolean,
        threshold: AmbientLightThreshold = AmbientLightThreshold(),
        delaySeconds: Int = 2,
    ) {
        stopListening()
        this.mode = mode
        this.systemNight = systemNight
        this.threshold = threshold
        this.delaySeconds = delaySeconds.coerceIn(0, 60)
        applyMode()
    }

    fun resume(systemNight: Boolean) {
        this.systemNight = systemNight
        resumed = true
        applyMode()
    }

    fun pause() {
        resumed = false
        stopListening()
    }

    fun systemChanged(night: Boolean) {
        systemNight = night
        val ambientFallback = mode == CarPlayNightMode.AMBIENT &&
            (!light.available || (resumed && !listening))
        if (mode == CarPlayNightMode.SYSTEM || ambientFallback) applyNight(night)
    }

    private fun applyMode() {
        when (mode) {
            CarPlayNightMode.SYSTEM -> applyNight(systemNight)
            CarPlayNightMode.DAY -> applyNight(false)
            CarPlayNightMode.NIGHT -> applyNight(true)
            CarPlayNightMode.AMBIENT -> {
                if (!light.available) {
                    applyNight(systemNight)
                } else if (resumed && !listening) {
                    listening = light.start(::onLux)
                    if (!listening) {
                        light.stop()
                        applyNight(systemNight)
                    }
                }
            }
        }
    }

    private fun onLux(lux: Float) {
        if (!resumed || !listening || mode != CarPlayNightMode.AMBIENT) return
        val target = when {
            !lux.isFinite() || lux < 0f -> null
            !night && lux < threshold.lux.toFloat() -> true
            night && lux >= threshold.lux.toFloat() -> false
            else -> null
        }
        if (target == pending) return
        cancelPending()
        pending = target
        // TYPE_LIGHT is commonly on-change: a stable reading need not emit again.
        if (target != null) {
            if (delaySeconds == 0) transition.run()
            else scheduler.postDelayed(transition, delaySeconds * 1_000L)
        }
    }

    private fun stopListening() {
        if (listening) light.stop()
        listening = false
        cancelPending()
    }

    private fun cancelPending() {
        scheduler.remove(transition)
        pending = null
    }

    private fun applyNight(value: Boolean) {
        if (value == night) return
        night = value
        onNightChanged(value)
    }
}

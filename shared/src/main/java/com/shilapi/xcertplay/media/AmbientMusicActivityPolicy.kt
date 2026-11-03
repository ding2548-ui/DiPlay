package com.shilapi.xcertplay.media

/** Decides whether enabled ambient control follows playback, holds its lowest level, or restores OEM state. */
internal enum class AmbientMusicTarget { RESTORE_OEM, LOWEST, STATIC, FOLLOW_PLAYBACK }

internal object AmbientMusicActivityPolicy {
    /** Guidance routed onto the automotive media bus must not replace the music lamp owner. */
    fun acceptsAudio(audioType: String, payloadType: Int): Boolean =
        AudioChannelMapper.map(audioType, payloadType, AudioChannelMappingMode.MOBILE_COMPATIBLE).channel == AudioChannel.MEDIA

    /**
     * The switch alone turns the feature on; playback decides *what* it does, never whether it runs.
     *
     * The old signature took a "brightness" ceiling and mapped `brightness <= 0` onto [LOWEST]. That
     * was a BYD-only concept leaking into the decision: the two channels use their brightness field
     * for different things (BYD 1..6 "maximum brightness", Leapmotor a 0..10 `lampDeg` level), so on
     * neither may it double as an off switch. Both channels now light as soon as the feature is
     * enabled, which is why [target] no longer takes brightness.
     */
    fun target(enabled: Boolean, music: Boolean, rendererActive: Boolean, playing: Boolean): AmbientMusicTarget = when {
        !enabled -> AmbientMusicTarget.RESTORE_OEM
        !rendererActive || !playing -> AmbientMusicTarget.LOWEST
        !music -> AmbientMusicTarget.STATIC
        else -> AmbientMusicTarget.FOLLOW_PLAYBACK
    }
}

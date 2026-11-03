package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.media.AmbientIdleBehavior
import com.shilapi.xcertplay.media.AmbientMusicSettings

/**
 * Plain-language lines for what the lamps do under the given settings, mirroring AmbientMusicActivityPolicy:
 * brightness 0 holds the lowest level; while paused, stopped or disconnected the lamps follow the idle choice
 * (steady glow, the car's own lighting after a pause, or the lowest level); only the chosen area follows the music.
 */
internal object AmbientBehaviorText {
    fun lines(context: Context, values: AmbientMusicSettings.Values): List<String> {
        if (!values.enabled) return listOf(context.getString(R.string.settings_ambient_behavior_off))
        val area = context.getString(
            when (values.area) {
                1 -> R.string.settings_ambient_front
                2 -> R.string.settings_ambient_rear
                else -> R.string.settings_ambient_all
            },
        )
        return buildList {
            if (values.brightness <= 0) {
                add(context.getString(R.string.settings_ambient_behavior_lowest))
                if (values.idle == AmbientIdleBehavior.CAR) add(context.getString(R.string.settings_ambient_behavior_idle_car))
            } else {
                add(
                    context.getString(
                        if (values.music) R.string.settings_ambient_behavior_follow else R.string.settings_ambient_behavior_fixed,
                        area,
                        values.brightness,
                    ),
                )
                add(
                    when (values.idle) {
                        AmbientIdleBehavior.STEADY -> context.getString(R.string.settings_ambient_behavior_idle_steady, area, values.brightness)
                        AmbientIdleBehavior.CAR -> context.getString(R.string.settings_ambient_behavior_idle_car)
                        AmbientIdleBehavior.OFF -> context.getString(R.string.settings_ambient_behavior_idle)
                    },
                )
                if (values.area != ALL_ZONES) add(context.getString(R.string.settings_ambient_behavior_other_zone))
            }
            add(context.getString(R.string.settings_ambient_behavior_restore))
        }
    }

    private const val ALL_ZONES = 3
}

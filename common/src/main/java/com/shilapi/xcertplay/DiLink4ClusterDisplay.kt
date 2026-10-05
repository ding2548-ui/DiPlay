package com.shilapi.xcertplay

import com.shilapi.xcertplay.airplay.CarPlayClusterDisplay
import com.shilapi.xcertplay.airplay.AirPlaySafeArea
import com.shilapi.xcertplay.airplay.SafeAreaRect

/** 2022 Seal / DiLink 4.0: 1920x720 logical display, 1920x624 observed activity area. */
internal object DiLink4ClusterDisplay {
    const val NAME = "fission_bg_xdjaVirtualSurface"

    // Exact name and geometry only. Do not select arbitrary virtual or passenger displays.
    fun matches(name: String, width: Int, height: Int): Boolean =
        name == NAME && width == 1920 && height == 720

    const val STREAM_WIDTH = 1920
    const val STREAM_HEIGHT = 720

    // Reuse DiLink 5 marker-safe margins as a calibration starting point.
    // Draw outside remains enabled so the map background still fills the activity.
    fun streamConfig(content: CarPlayClusterDisplay.Content, horizontalStep: Int = 0, verticalStep: Int = 0,
        safeAreaRect: SafeAreaRect? = null): com.shilapi.xcertplay.airplay.AirPlayDisplayConfig {
        val config = CarPlayClusterDisplay.config(STREAM_WIDTH, STREAM_HEIGHT, scalePercent = 100,
            horizontalStep = horizontalStep, verticalStep = verticalStep, content = content)
        return if (safeAreaRect == null) config else config.copy(safeArea = AirPlaySafeArea.toInsets(
            safeAreaRect, STREAM_WIDTH, STREAM_HEIGHT, STREAM_WIDTH, STREAM_HEIGHT))
    }

    fun defaultSafeAreaRect(horizontalStep: Int = 0, verticalStep: Int = 0): SafeAreaRect {
        val insets = streamConfig(CarPlayClusterDisplay.Content.MAP, horizontalStep, verticalStep).safeArea!!
        return SafeAreaRect(insets.left, insets.top, STREAM_WIDTH - insets.right, STREAM_HEIGHT - insets.bottom)
    }
}

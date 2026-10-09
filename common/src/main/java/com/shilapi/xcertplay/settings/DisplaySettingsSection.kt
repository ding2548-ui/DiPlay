package com.shilapi.xcertplay.settings

import android.content.Context
import com.shilapi.xcertplay.host.R

object DisplaySettingsSection {

    fun createResolutionSlider(
        context: Context,
        initialPercent: Int,
        theme: SettingsTheme = SettingsTheme.OVERLAY,
        onPercentChanged: (Int) -> Unit,
    ): SettingsWidgets.ResolutionSliderResult =
        SettingsWidgets.createResolutionSlider(context, initialPercent, theme, onPercentChanged)

    fun createHevcRow(
        context: Context,
        checked: Boolean,
        theme: SettingsTheme = SettingsTheme.OVERLAY,
        onChanged: (Boolean) -> Unit,
    ): SettingsWidgets.SwitchRowResult = SettingsWidgets.createSwitchRow(
        context = context,
        label = if (theme.isOverlay) "HEVC (H.265)" else context.getString(R.string.efficient_video),
        description = if (theme.isOverlay) context.getString(R.string.hevc_h_265_video_transport)
        else context.getString(R.string.use_hevc_leave_off_for_the_widest_head_unit_compatibility),
        checked = checked,
        theme = theme,
        contentDescription = if (theme.isOverlay) context.getString(R.string.hevc_h_265_video_transport)
        else context.getString(R.string.efficient_video),
        labelSizeSp = if (theme.isOverlay) 20f else 18f,
        onChanged = onChanged,
    )

    fun createSoftwareHevcRow(
        context: Context,
        checked: Boolean,
        theme: SettingsTheme = SettingsTheme.OVERLAY,
        onChanged: (Boolean) -> Unit,
    ): SettingsWidgets.SwitchRowResult = SettingsWidgets.createSwitchRow(
        context = context,
        label = context.getString(R.string.hevc_software_decoder),
        description = context.getString(R.string.use_software_hevc_decoder),
        checked = checked,
        theme = theme,
        contentDescription = context.getString(R.string.use_software_hevc_decoder),
        labelSizeSp = if (theme.isOverlay) 20f else 18f,
        onChanged = onChanged,
    )

    fun createRightHandDriveRow(
        context: Context,
        checked: Boolean,
        theme: SettingsTheme = SettingsTheme.OVERLAY,
        onChanged: (Boolean) -> Unit,
    ): SettingsWidgets.SwitchRowResult = SettingsWidgets.createSwitchRow(
        context = context,
        label = context.getString(R.string.right_hand_drive),
        description = context.getString(R.string.place_carplay_s_controls_closer_to_the_driver),
        checked = checked,
        theme = theme,
        contentDescription = context.getString(R.string.right_hand_drive),
        onChanged = onChanged,
    )
}

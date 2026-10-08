package com.shilapi.xcertplay

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.view.ContextThemeWrapper
import com.shilapi.xcertplay.host.R

internal interface AppAppearanceOwner {
    val currentAppNight: Boolean
}

internal fun Context.appDialogTheme(): Int = if (
    (this as? AppAppearanceOwner)?.currentAppNight ?: resolveAppNightNow()
) {
    R.style.Theme_Xcertplay_Dialog_Dark
} else {
    R.style.Theme_Xcertplay_Dialog_Light
}

internal fun Context.appDialogContext(): Context = ContextThemeWrapper(this, appDialogTheme())

internal fun Context.appDialogBuilder(): AlertDialog.Builder = AlertDialog.Builder(this, appDialogTheme())

internal fun Context.appDialog(): Dialog = Dialog(this, appDialogTheme())

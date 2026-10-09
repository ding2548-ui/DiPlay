package com.shilapi.xcertplay

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Prefer file locations; older head units may only provide a content picker. Head units without a
 * system document picker may still have an app that answers every intent, so there the content
 * picker goes first.
 */
internal fun launchCarButtonImagePicker(
    openDocument: () -> Unit,
    getContent: () -> Unit,
    documentPickerIsSystem: Boolean = true,
): Result<Unit> {
    val (first, second) = if (documentPickerIsSystem) openDocument to getContent else getContent to openDocument
    return runCatching {
        try {
            first()
        } catch (_: ActivityNotFoundException) {
            second()
        }
    }
}

/** False only when apps answer the image document intent and none of them is part of the system image. */
internal fun Context.documentPickerIsSystem(): Boolean {
    val handlers = packageManager.queryIntentActivities(
        ActivityResultContracts.OpenDocument().createIntent(this, arrayOf("image/*")),
        PackageManager.MATCH_DEFAULT_ONLY,
    )
    return handlers.isEmpty() ||
        handlers.any { it.activityInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0 }
}

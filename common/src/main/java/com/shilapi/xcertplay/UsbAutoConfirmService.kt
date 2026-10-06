package com.shilapi.xcertplay

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * An accessibility service to automatically check "Always allow / Use by default"
 * and click "OK / Confirm" when the Android system USB permission dialog appears for DiPlay.
 *
 * This is the fallback path. The primary one is `android.permission.MANAGE_USB` (declared in the
 * manifest, granted to the platform-signed build): the platform's USB permission check returns true
 * outright for a caller holding it, so the dialog is never shown in the first place. The service
 * matters when that permission did not land, and it has to cope with a vendor ROM whose prompt is
 * not the AOSP one — see [isUsbPromptCandidate].
 */
class UsbAutoConfirmService : AccessibilityService() {

    private var lastClickTime = -DEBOUNCE_MILLIS
    private var usbWindowId: Int? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            // Every new window is a candidate. The prompt's package and activity name differ
            // between ROMs, so the decision is made from the window's content below; recording the
            // id here just keeps the content scan off unrelated windows.
            usbWindowId = event.windowId
        }
        if (usbWindowId != event.windowId) return
        val now = SystemClock.uptimeMillis()
        if (now - lastClickTime < DEBOUNCE_MILLIS) return
        val root = runCatching { rootInActiveWindow }.getOrNull() ?: return
        try {
            if (root.windowId != usbWindowId) return
            if (!isUsbPromptCandidate(root.packageName?.toString())) return
            val texts = mutableListOf<String>()
            visit(root) { node ->
                node.text?.toString()?.let(texts::add)
                node.contentDescription?.toString()?.let(texts::add)
                false
            }
            val joined = texts.joinToString(" ")
            val appLabel = applicationInfo.loadLabel(packageManager).toString()
            // Forensics: without this the service is silent about the prompts it declines to touch,
            // and "the dialog still appears" cannot be told apart from "the service never saw it".
            if (joined.contains("USB", ignoreCase = true)) {
                report(
                    "usb auto-confirm: window pkg=${root.packageName} texts=${joined.take(160)}",
                )
            }
            if (!isTargetPrompt(joined, appLabel)) return
            // Only the system USB dialog's optional default checkbox may be changed.
            visit(root) { node ->
                node.isCheckable && !node.isChecked && node.isEnabled &&
                    (node.viewIdResourceName == ALWAYS_USE_ID || mentionsDefault(node)) &&
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            val confirmed = visit(root) { node ->
                if (!node.isEnabled || !node.isClickable) return@visit false
                val label = node.text?.toString()?.trim().orEmpty()
                val matches = node.viewIdResourceName == CONFIRM_BUTTON_ID ||
                    (label.isNotEmpty() && CONFIRM_LABELS.any { label.contains(it, ignoreCase = true) })
                matches && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            if (confirmed) {
                lastClickTime = now
                report("usb auto-confirm: confirmed pkg=${root.packageName}")
                Log.i(TAG, "Successfully auto-confirmed DiPlay USB permission dialog")
            }
        } finally {
            @Suppress("DEPRECATION")
            root.recycle()
        }
    }

    override fun onInterrupt() { usbWindowId = null }

    private fun mentionsDefault(node: AccessibilityNodeInfo): Boolean {
        val text = listOfNotNull(node.text?.toString(), node.contentDescription?.toString())
            .joinToString(" ")
        return DEFAULT_LABELS.any { text.contains(it, ignoreCase = true) }
    }

    /**
     * True when [pkg] is a package that could be showing the system's USB prompt.
     *
     * This replaces an exact allow-list of "com.android.systemui" plus the two AOSP activity names.
     * That matched a stock ROM only: on this head unit the prompt comes from a different component,
     * and an exact class-name comparison rejected the window before a single node was read, so the
     * service stayed silent no matter how the permission had been granted. The window's content is
     * the gate now — it has to name this app and mention USB ([isTargetPrompt]) — and the only
     * package rule left is that the service never acts on its own windows.
     */
    private fun isUsbPromptCandidate(pkg: String?): Boolean {
        val name = pkg ?: return false
        return name != packageName
    }

    private fun report(message: String) {
        Log.i(TAG, message)
        runCatching { onDiagnostic?.invoke(message) }
    }

    private fun visit(node: AccessibilityNodeInfo, depth: Int = 0, action: (AccessibilityNodeInfo) -> Boolean): Boolean {
        if (depth > 32) return false
        if (action(node)) return true
        for (i in 0 until node.childCount) {
            val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
            try {
                if (visit(child, depth + 1, action)) return true
            } finally {
                @Suppress("DEPRECATION")
                child.recycle()
            }
        }
        return false
    }

    companion object {
        private const val TAG = "UsbAutoConfirm"
        private const val DEBOUNCE_MILLIS = 800L
        private const val ALWAYS_USE_ID = "android:id/alwaysUse"
        private const val CONFIRM_BUTTON_ID = "android:id/button1"

        /**
         * Where diagnostics go: both the logcat line and the app's exported report. The system
         * creates the service instance, so the host activity sets this on the companion rather than
         * on an instance it cannot reach.
         */
        @Volatile
        var onDiagnostic: ((String) -> Unit)? = null

        /** Substring matches: vendor prompts label the button "允许访问" / "确认" and so on. */
        private val CONFIRM_LABELS = listOf(
            "确定", "确认", "允许", "同意", "OK", "Allow", "Confirm", "Agree", "Accept", "Yes",
        )

        /** Substring matches for the optional "always allow for this device" checkbox. */
        private val DEFAULT_LABELS = listOf("默认", "始终", "always", "default")

        internal fun isTargetPrompt(text: String, appLabel: String): Boolean =
            appLabel.isNotBlank() &&
                Regex("(?<![\\p{L}\\p{N}_])${Regex.escape(appLabel)}(?![\\p{L}\\p{N}_])", RegexOption.IGNORE_CASE)
                    .containsMatchIn(text) && text.contains("USB", ignoreCase = true)


        fun isEnabled(context: Context): Boolean {
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            val colonSplitter = TextUtils.SimpleStringSplitter(':')
            colonSplitter.setString(enabledServices)
            val myService = ComponentName(context, UsbAutoConfirmService::class.java).flattenToString()
            val myShortService = ComponentName(context, UsbAutoConfirmService::class.java).flattenToShortString()
            while (colonSplitter.hasNext()) {
                val componentName = colonSplitter.next()
                if (componentName.equals(myService, ignoreCase = true) ||
                    componentName.equals(myShortService, ignoreCase = true)
                ) {
                    return true
                }
            }
            return false
        }

        fun openSettings(context: Context): Boolean = runCatching {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }
}

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
import com.shilapi.xcertplay.transport.UsbPermissionArming

/**
 * An accessibility service to automatically check "Always allow / Use by default"
 * and click "OK / Confirm" when the Android system USB permission dialog appears for DiPlay.
 *
 * This is the fallback path. The primary one is `android.permission.MANAGE_USB` (declared in the
 * manifest, granted to the platform-signed build): the platform's USB permission check returns true
 * outright for a caller holding it, so the dialog is never shown in the first place. The service
 * matters when that permission did not land, and it has to cope with a vendor ROM whose prompt is
 * not the AOSP one — see [isTargetPrompt].
 *
 * It acts only while [UsbPermissionArming] is armed by an actual `UsbManager.requestPermission()`
 * call, and then only on a system-owned AlertDialog that names this app and a USB device and has a
 * confirm/cancel pair. The service stays enabled from boot, so anything looser clicks unrelated
 * "OK" buttons: a settings page or another player's dialog that happens to mention DiPlay and USB.
 */
class UsbAutoConfirmService : AccessibilityService() {

    private var lastClickTime = -DEBOUNCE_MILLIS
    private var usbWindowId: Int? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        // Nothing may be clicked outside a USB permission request.
        if (!UsbPermissionArming.isArmed()) return
        val stateChange = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        if (stateChange) {
            // Every new window is a candidate. The prompt's package and activity name differ
            // between ROMs, so the decision is made from the window's content below; recording the
            // id here just keeps the content scan off unrelated windows.
            usbWindowId = event.windowId
        }
        val windowId = event.windowId
        if (usbWindowId != windowId) return
        val now = SystemClock.uptimeMillis()
        // The wired flow legitimately raises the prompt twice (the re-enumerated device asks
        // again), and the second window can be handed the first one's id, so a new window is
        // always examined. Only redraws of the same prompt are throttled.
        if (!stateChange && now - lastClickTime < DEBOUNCE_MILLIS) return
        val root = runCatching { rootInActiveWindow }.getOrNull() ?: return
        try {
            if (root.windowId != windowId) return
            // A window id can be reused, and only the system may own a USB permission prompt.
            if (!isSystemPromptWindow(root)) {
                decline(root, "window")
                return
            }
            val appLabel = applicationInfo.loadLabel(packageManager).toString()
            // Prefer the dialog's own title/message, but keep the whole-window fallback for vendor
            // prompts that do not use the AOSP ids.
            val target = isTargetPrompt(promptText(root), appLabel) ||
                isTargetPrompt(allText(root), appLabel)
            if (!target) {
                decline(root, "prompt")
                return
            }
            if (!hasConfirmAndCancel(root)) {
                decline(root, "buttons")
                return
            }
            // Only the system USB dialog's optional default checkbox may be changed.
            visit(root) { node ->
                node.isCheckable && !node.isChecked && node.isEnabled &&
                    (node.viewIdResourceName == ALWAYS_USE_ID || mentionsDefault(node)) &&
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            val confirmed = visit(root) { node ->
                isConfirmButton(node) && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
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

    /** Only the system can own the USB permission prompt. */
    private fun isSystemPromptWindow(root: AccessibilityNodeInfo): Boolean {
        val pkg = root.packageName?.toString() ?: return false
        return pkg in PROMPT_PACKAGES
    }

    /** The prompt is an AlertDialog: without its cancel button this is some other window. */
    private fun hasConfirmAndCancel(root: AccessibilityNodeInfo): Boolean {
        var confirm = false
        var cancel = false
        visit(root) { node ->
            if (isConfirmButton(node)) confirm = true
            if (isCancelButton(node)) cancel = true
            confirm && cancel
        }
        return confirm && cancel
    }

    private fun isConfirmButton(node: AccessibilityNodeInfo): Boolean =
        node.isEnabled && node.isClickable && !node.isCheckable &&
            (node.viewIdResourceName == CONFIRM_BUTTON_ID || labeledWith(node, CONFIRM_LABELS))

    private fun isCancelButton(node: AccessibilityNodeInfo): Boolean =
        node.isEnabled && node.isClickable && !node.isCheckable &&
            (node.viewIdResourceName == CANCEL_BUTTON_ID || labeledWith(node, CANCEL_LABELS))

    private fun labeledWith(node: AccessibilityNodeInfo, labels: List<String>): Boolean {
        val text = node.text?.toString()?.trim().orEmpty()
        return text.isNotEmpty() && labels.any { text.contains(it, ignoreCase = true) }
    }

    private fun promptText(root: AccessibilityNodeInfo): String {
        val parts = mutableListOf<String>()
        visit(root) { node ->
            if (node.viewIdResourceName == MESSAGE_ID || node.viewIdResourceName == TITLE_ID) {
                node.text?.toString()?.let(parts::add)
                node.contentDescription?.toString()?.let(parts::add)
            }
            false
        }
        return parts.joinToString(" ")
    }

    private fun allText(root: AccessibilityNodeInfo): String {
        val texts = mutableListOf<String>()
        visit(root) { node ->
            node.text?.toString()?.let(texts::add)
            node.contentDescription?.toString()?.let(texts::add)
            false
        }
        return texts.joinToString(" ")
    }

    private fun mentionsDefault(node: AccessibilityNodeInfo): Boolean {
        val text = listOfNotNull(node.text?.toString(), node.contentDescription?.toString())
            .joinToString(" ")
        return DEFAULT_LABELS.any { text.contains(it, ignoreCase = true) }
    }

    /**
     * Keeps the report useful without logging every screen: a declined window is only reported when
     * it actually contains a word that made the service look at it.
     */
    private fun decline(root: AccessibilityNodeInfo, stage: String) {
        val text = allText(root)
        val appLabel = applicationInfo.loadLabel(packageManager).toString()
        val interesting = USB_DEVICE_WORDS.containsMatchIn(text) ||
            (appLabel.isNotBlank() && text.contains(appLabel, ignoreCase = true))
        if (!interesting) return
        report("usb auto-confirm: declined stage=$stage pkg=${root.packageName} text=${text.take(160)}")
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
        private const val CANCEL_BUTTON_ID = "android:id/button2"
        private const val MESSAGE_ID = "android:id/message"
        private const val TITLE_ID = "android:id/alertTitle"

        /** Packages that can own the system USB permission prompt on the supported head units. */
        internal val PROMPT_PACKAGES = setOf("com.android.systemui", "android")

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

        /** The prompt always offers a way out; without it the window is not the prompt. */
        private val CANCEL_LABELS = listOf("取消", "拒绝", "否", "Cancel", "Deny", "No")

        /** Substring matches for the optional "always allow for this device" checkbox. */
        private val DEFAULT_LABELS = listOf("默认", "始终", "always", "default")
        private val USB_DEVICE_WORDS = Regex("USB|iPhone|iPad", RegexOption.IGNORE_CASE)

        /**
         * The prompt must name this app and a USB device. Android 10+ words it "Allow DiPlay to
         * access iPhone?" with no "USB", Android 7.1 names "the USB device" instead of the phone,
         * and Chinese puts the name between letters ("要允许DiPlay访问iPhone吗？"), so only ASCII
         * word characters count as part of a longer name such as "FakeDiPlay".
         */
        internal fun isTargetPrompt(text: String, appLabel: String): Boolean =
            appLabel.isNotBlank() &&
                Regex("(?<![A-Za-z0-9_])${Regex.escape(appLabel)}(?![A-Za-z0-9_])", RegexOption.IGNORE_CASE)
                    .containsMatchIn(text) &&
                USB_DEVICE_WORDS.containsMatchIn(text)


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

        /** BYD's settings app does not answer the standard action but has its own screen (#409). */
        private val ACCESSIBILITY_SCREENS = listOf(
            ComponentName("com.byd.systemsettings", "com.byd.systemsettings.accessibility.AccessibilityMainActivity"),
            ComponentName("com.android.settings", "com.android.settings.Settings\$AccessibilitySettingsActivity"),
        )

        fun openSettings(context: Context): Boolean =
            (listOf(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) + ACCESSIBILITY_SCREENS.map { Intent().setComponent(it) })
                .any { intent ->
                    runCatching {
                        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        true
                    }.getOrDefault(false)
                }
    }
}

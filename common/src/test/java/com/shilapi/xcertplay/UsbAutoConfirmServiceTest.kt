package com.shilapi.xcertplay

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.shilapi.xcertplay.transport.UsbPermissionArming
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class UsbAutoConfirmServiceTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        UsbPermissionArming.disarm()
    }

    @After
    fun tearDown() {
        UsbPermissionArming.disarm()
    }

    @Test
    fun isEnabledReturnsFalseByDefault() {
        Settings.Secure.putString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            null,
        )
        assertFalse(UsbAutoConfirmService.isEnabled(context))
    }

    @Test
    fun isEnabledReturnsTrueWhenConfiguredInSecureSettings() {
        val serviceName = "${context.packageName}/${UsbAutoConfirmService::class.java.name}"
        Settings.Secure.putString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            "other.package/other.Service:$serviceName",
        )
        assertTrue(UsbAutoConfirmService.isEnabled(context))
    }
    @Test fun missingAccessibilitySettingsDoesNotCrash() {
        val unavailable = org.mockito.Mockito.mock(Context::class.java)
        org.mockito.Mockito.doThrow(android.content.ActivityNotFoundException())
            .`when`(unavailable).startActivity(org.mockito.ArgumentMatchers.any(android.content.Intent::class.java))
        assertFalse(UsbAutoConfirmService.openSettings(unavailable))
    }

    // The window content is what decides the prompt; these cases pin down the wording.
    @Test fun promptContentIsWhatDecides() {
        assertTrue(UsbAutoConfirmService.isTargetPrompt("允许 DiPlay 访问 USB 设备？", "DiPlay"))
        assertFalse(UsbAutoConfirmService.isTargetPrompt("允许 其他应用 访问 USB 设备？", "DiPlay"))
    }

    @Test fun otherSystemDialogsNamingDiPlayAreLeftAlone() {
        assertFalse(UsbAutoConfirmService.isTargetPrompt("Start recording or casting with DiPlay?", "DiPlay"))
        assertFalse(UsbAutoConfirmService.isTargetPrompt(
            "DiPlay will have access to all of the information that is visible on your screen", "DiPlay",
        ))
        assertFalse(UsbAutoConfirmService.isTargetPrompt("要开始使用DiPlay录制或投放内容吗？", "DiPlay"))
    }

    @Test fun promptMustNameDiPlayInEveryAndroidWording() {
        // Android 7.1 and 10+ wording, in English and Chinese (#409, #521).
        assertTrue(UsbAutoConfirmService.isTargetPrompt("Allow the app DiPlay to access the USB device?", "DiPlay"))
        assertTrue(UsbAutoConfirmService.isTargetPrompt("允许应用“DiPlay”访问该USB设备吗？", "DiPlay"))
        assertTrue(UsbAutoConfirmService.isTargetPrompt("Allow DiPlay to access iPhone?", "DiPlay"))
        assertTrue(UsbAutoConfirmService.isTargetPrompt("要允许DiPlay访问iPhone吗？", "DiPlay"))
        assertTrue(UsbAutoConfirmService.isTargetPrompt("Always open DiPlay when iPhone is connected", "DiPlay"))
        assertFalse(UsbAutoConfirmService.isTargetPrompt("Allow CarPlay access to iPhone?", "DiPlay"))
        assertFalse(UsbAutoConfirmService.isTargetPrompt("Allow FakeDiPlay to access iPhone?", "DiPlay"))
        assertFalse(UsbAutoConfirmService.isTargetPrompt("要允许DiPlayer访问iPhone吗？", "DiPlay"))
        assertFalse(UsbAutoConfirmService.isTargetPrompt("Allow USB access?", ""))
    }

    @Test fun bydAccessibilityScreenIsUsedWhenTheStandardActionIsMissing() {
        val context = org.mockito.Mockito.mock(Context::class.java)
        val opened = mutableListOf<android.content.Intent>()
        org.mockito.Mockito.doAnswer { call ->
            val intent = call.getArgument<android.content.Intent>(0)
            if (intent.action == Settings.ACTION_ACCESSIBILITY_SETTINGS) throw android.content.ActivityNotFoundException()
            opened += intent
            null
        }.`when`(context).startActivity(org.mockito.ArgumentMatchers.any(android.content.Intent::class.java))
        assertTrue(UsbAutoConfirmService.openSettings(context))
        assertEquals(
            listOf("com.byd.systemsettings/com.byd.systemsettings.accessibility.AccessibilityMainActivity"),
            opened.map { it.component!!.flattenToString() },
        )
    }

    @Test fun armingExpiresOnItsOwn() {
        UsbPermissionArming.arm("test")
        val now = SystemClock.uptimeMillis()
        assertTrue(UsbPermissionArming.isArmed(now))
        assertFalse(UsbPermissionArming.isArmed(now + 46_000))
    }

    @Test fun nothingIsClickedWithoutAUsbPermissionRequest() {
        val service = service()
        val prompt = prompt(service)
        org.mockito.Mockito.doReturn(prompt.root).`when`(service).rootInActiveWindow
        service.onAccessibilityEvent(stateChanged())
        org.mockito.Mockito.verify(prompt.confirm, org.mockito.Mockito.never())
            .performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    @Test fun armedServiceIgnoresWindowsOwnedByOtherApps() {
        val service = service()
        val prompt = prompt(service, pkg = "com.example.music")
        org.mockito.Mockito.doReturn(prompt.root).`when`(service).rootInActiveWindow
        UsbPermissionArming.arm("test")
        service.onAccessibilityEvent(stateChanged())
        org.mockito.Mockito.verify(prompt.confirm, org.mockito.Mockito.never())
            .performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    @Test fun armedServiceRequiresTheDialogButtonPair() {
        val service = service()
        val prompt = prompt(service, withCancel = false)
        org.mockito.Mockito.doReturn(prompt.root).`when`(service).rootInActiveWindow
        UsbPermissionArming.arm("test")
        service.onAccessibilityEvent(stateChanged())
        org.mockito.Mockito.verify(prompt.confirm, org.mockito.Mockito.never())
            .performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    @Test fun everyPromptWindowIsConfirmedEvenWhenItReusesTheId() {
        val service = service()
        val prompt = prompt(service)
        org.mockito.Mockito.doReturn(prompt.root).`when`(service).rootInActiveWindow
        UsbPermissionArming.arm("test")
        // The wired flow raises the prompt twice, and the second window can get the same id.
        service.onAccessibilityEvent(stateChanged())
        service.onAccessibilityEvent(stateChanged())
        org.mockito.Mockito.verify(prompt.confirm, org.mockito.Mockito.times(2))
            .performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    @Test fun systemUsbPromptClicksOnlyWhenTheNamedAppMatches() {
        val service = service()
        val label = service.applicationInfo.loadLabel(service.packageManager)
        val prompt = prompt(service)
        org.mockito.Mockito.doReturn(prompt.root).`when`(service).rootInActiveWindow
        UsbPermissionArming.arm("test")
        try {
            org.mockito.Mockito.`when`(prompt.message.text)
                .thenReturn("Allow OtherApp to access this USB device?")
            service.onAccessibilityEvent(stateChanged())
            org.mockito.Mockito.verify(prompt.confirm, org.mockito.Mockito.never())
                .performAction(AccessibilityNodeInfo.ACTION_CLICK)
            org.mockito.Mockito.`when`(prompt.message.text)
                .thenReturn("Allow $label to access this USB device?")
            service.onAccessibilityEvent(stateChanged())
            org.mockito.Mockito.verify(prompt.confirm)
                .performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } finally {
            UsbPermissionArming.disarm()
        }
    }

    private fun service(): UsbAutoConfirmService =
        org.mockito.Mockito.spy(
            org.robolectric.Robolectric.buildService(UsbAutoConfirmService::class.java).create().get(),
        )

    private class Dialog(
        val root: AccessibilityNodeInfo,
        val message: AccessibilityNodeInfo,
        val confirm: AccessibilityNodeInfo,
    )

    /** Message plus a confirm/cancel row, shaped like the system's AlertDialog. */
    private fun prompt(
        service: UsbAutoConfirmService,
        pkg: String = "android",
        withCancel: Boolean = true,
    ): Dialog {
        val label = service.applicationInfo.loadLabel(service.packageManager)
        val message = leaf(id = "android:id/message", text = "Allow $label to access this USB device?")
        val confirm = leaf(id = "android:id/button1", text = "OK", clickable = true)
        val cancel = leaf(id = "android:id/button2", text = "Cancel", clickable = true)
        val children = if (withCancel) listOf(message, confirm, cancel) else listOf(message, confirm)
        val root = org.mockito.Mockito.mock(AccessibilityNodeInfo::class.java).also { node ->
            org.mockito.Mockito.`when`(node.packageName).thenReturn(pkg)
            org.mockito.Mockito.`when`(node.windowId).thenReturn(WINDOW_ID)
            org.mockito.Mockito.`when`(node.childCount).thenReturn(children.size)
            children.forEachIndexed { index, child ->
                org.mockito.Mockito.`when`(node.getChild(index)).thenReturn(child)
            }
        }
        return Dialog(root, message, confirm)
    }

    private fun leaf(id: String? = null, text: String? = null, clickable: Boolean = false): AccessibilityNodeInfo =
        org.mockito.Mockito.mock(AccessibilityNodeInfo::class.java).also { node ->
            org.mockito.Mockito.`when`(node.viewIdResourceName).thenReturn(id)
            org.mockito.Mockito.`when`(node.text).thenReturn(text)
            org.mockito.Mockito.`when`(node.isClickable).thenReturn(clickable)
            org.mockito.Mockito.`when`(node.isEnabled).thenReturn(true)
            org.mockito.Mockito.`when`(node.isCheckable).thenReturn(false)
            org.mockito.Mockito.`when`(node.performAction(AccessibilityNodeInfo.ACTION_CLICK)).thenReturn(true)
        }

    private fun stateChanged(): AccessibilityEvent =
        org.mockito.Mockito.mock(AccessibilityEvent::class.java).also { event ->
            org.mockito.Mockito.`when`(event.eventType)
                .thenReturn(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
            org.mockito.Mockito.`when`(event.windowId).thenReturn(WINDOW_ID)
        }

    private companion object {
        const val WINDOW_ID = 42
    }
}

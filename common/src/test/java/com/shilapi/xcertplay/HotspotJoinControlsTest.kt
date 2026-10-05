package com.shilapi.xcertplay

import android.app.AlertDialog
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.network.HotspotJoinRepair
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import android.os.Looper
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "en", manifest = Config.NONE)
class HotspotJoinControlsTest {
    private class Backend : HotspotJoinControls.Backend {
        val actions = mutableListOf<HotspotJoinRepair.Action>()
        var session = false
        var queued: (() -> Unit)? = null
        var deferred = false
        var cancelled = false
        override fun run(action: HotspotJoinRepair.Action, token: String?): HotspotJoinRepair.Result {
            actions += action
            return HotspotJoinRepair.Result(when (action) {
                HotspotJoinRepair.Action.CHECK -> HotspotJoinRepair.Code.READY
                HotspotJoinRepair.Action.APPLY -> HotspotJoinRepair.Code.APPLIED
                HotspotJoinRepair.Action.RESTORE -> HotspotJoinRepair.Code.RESTORED
            }, "12345678-1234-1234-1234-123456789abc", action == HotspotJoinRepair.Action.APPLY)
        }
        override fun execute(block: () -> Unit) { if (deferred) queued = block else block() }
        override fun cancel() { cancelled = true }
    }
    @Test fun renderingIsInertAndApplyRequiresASeparateUserConfirmation() {
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        val backend = Backend()
        val controls = HotspotJoinControls(activity, { false }, backend)
        val root = controls.build()
        activity.setContentView(root)
        assertTrue(backend.actions.isEmpty())
        fun button(id: Int) = views(root).filterIsInstance<Button>().single { it.text == activity.getString(id) }
        button(R.string.hotspot_join_check).performClick()
        assertEquals(listOf(HotspotJoinRepair.Action.CHECK), backend.actions)
        button(R.string.hotspot_join_apply).performClick()
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, backend.actions.size)
        button(R.string.hotspot_join_apply).performClick()
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(HotspotJoinRepair.Action.CHECK, HotspotJoinRepair.Action.APPLY), backend.actions)
        assertTrue(views(root).filterIsInstance<Button>().any { it.text == activity.getString(R.string.hotspot_join_restore) })
        controls.close()
    }
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()

    @Test fun closingBeforeTheWorkerStartsCancelsAndDoesNotRunPrivilegedWork() {
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        val backend = Backend().apply { deferred = true }
        val controls = HotspotJoinControls(activity, { false }, backend)
        val root = controls.build()
        views(root).filterIsInstance<Button>().first().performClick()
        controls.close()
        backend.queued!!.invoke()
        assertTrue(backend.cancelled)
        assertTrue(backend.actions.isEmpty())
    }

    @Test fun aSessionStartingWhileConfirmationIsOpenPreventsTheWrite() {
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        val backend = Backend()
        val controls = HotspotJoinControls(activity, { backend.session }, backend)
        val root = controls.build()
        fun button(id: Int) = views(root).filterIsInstance<Button>().single { it.text == activity.getString(id) }
        button(R.string.hotspot_join_check).performClick()
        button(R.string.hotspot_join_apply).performClick()
        backend.session = true
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(HotspotJoinRepair.Action.CHECK), backend.actions)
        controls.close()
    }

    @Test fun rollbackAlsoRequiresConfirmationAndBusyOperationsCannotOverlap() {
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        val backend = Backend().apply { deferred = true }
        val controls = HotspotJoinControls(activity, { false }, backend)
        val root = controls.build()
        fun button(id: Int) = views(root).filterIsInstance<Button>().single { it.text == activity.getString(id) }
        button(R.string.hotspot_join_check).performClick()
        assertFalse(button(R.string.hotspot_join_check).isEnabled)
        val worker = backend.queued
        button(R.string.hotspot_join_check).performClick()
        assertSame(worker, backend.queued)
        backend.deferred = false; worker!!.invoke()
        button(R.string.hotspot_join_apply).performClick()
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        button(R.string.hotspot_join_restore).performClick()
        assertEquals(2, backend.actions.size)
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(HotspotJoinRepair.Action.RESTORE, backend.actions.last())
        assertFalse(views(root).filterIsInstance<Button>().any { it.text == activity.getString(R.string.hotspot_join_restore) })
        controls.close()
    }
}

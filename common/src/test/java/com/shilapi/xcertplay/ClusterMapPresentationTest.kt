package com.shilapi.xcertplay

import android.hardware.display.DisplayManager
import android.view.Display
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDisplayManager
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class ClusterMapPresentationTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val manager get() = context.getSystemService(DisplayManager::class.java)

    private fun display(name: String, spec: String = "w960dp-h360dp"): Int {
        // Display.TYPE_VIRTUAL (5) is hidden from the public Android SDK.
        val id = ShadowDisplayManager.addDisplay(spec, 5)
        shadowOf(manager.getDisplay(id)).apply {
            setName(name)
            setFlags(Display.FLAG_PRESENTATION)
        }
        return id
    }

    @Test fun legacyFirmwareKeepsOriginalBaseDisplayPreference() {
        val base = display("fission_bg_XDJAScreenProjection")
        val shared = display("shared_fission_bg_XDJAScreenProjection_0")
        try {
            assertEquals(base, ClusterMapPresentation.findDisplay(context)?.displayId)
        } finally {
            ShadowDisplayManager.removeDisplay(shared)
            ShadowDisplayManager.removeDisplay(base)
        }
    }

    @Test fun baseDisplayStillWorksWhenNoSharedLayerExists() {
        val base = display("fission_bg_XDJAScreenProjection")
        try {
            assertEquals(base, ClusterMapPresentation.findDisplay(context)?.displayId)
        } finally {
            ShadowDisplayManager.removeDisplay(base)
        }
    }

    @Test fun unrelatedPresentationDisplayIsNotUsedForTheCluster() {
        val other = display("Passenger display")
        try {
            assertNull(ClusterMapPresentation.findDisplay(context))
        } finally {
            ShadowDisplayManager.removeDisplay(other)
        }
    }

    @Test
    @Config(sdk = [25])
    fun solePresentationDisplayIsProbedOnAndroid7Firmware() {
        val id = display("leapmotor cluster projection", "w1280dp-h480dp-mdpi")
        try {
            assertEquals(id, ClusterMapPresentation.findDisplay(context)?.displayId)
        } finally {
            ShadowDisplayManager.removeDisplay(id)
        }
    }

    @Test
    @Config(sdk = [25])
    fun android7ProbePrefersClusterNamedDisplayOverUnrelatedName() {
        val overlay = display("Overlay display")
        val cluster = display("cluster_projection", "w1920dp-h720dp-mdpi")
        try {
            assertEquals(cluster, ClusterMapPresentation.findDisplay(context)?.displayId)
        } finally {
            ShadowDisplayManager.removeDisplay(overlay)
            ShadowDisplayManager.removeDisplay(cluster)
        }
    }

    @Test
    @Config(sdk = [25])
    fun android7ProbeSkipsBydMarkerDisplays() {
        // The DiLink 4 name with the wrong geometry falls through its measured path; the probe
        // must not pick it up either, so the unknown non-BYD display is the only candidate.
        val stale = display(DiLink4ClusterDisplay.NAME)
        val unknown = display("leapmotor_virtual")
        try {
            assertEquals(unknown, ClusterMapPresentation.findDisplay(context)?.displayId)
        } finally {
            ShadowDisplayManager.removeDisplay(stale)
            ShadowDisplayManager.removeDisplay(unknown)
        }
    }

    @Test fun dilink4MeasuredProjectionIsSelected() {
        val id = display(DiLink4ClusterDisplay.NAME, "w1920dp-h720dp-mdpi")
        try {
            assertEquals(id, ClusterMapPresentation.findDisplay(context)?.displayId)
        } finally {
            ShadowDisplayManager.removeDisplay(id)
        }
    }

    @Test fun dilink4WrongGeometryIsRejected() {
        val id = display(DiLink4ClusterDisplay.NAME, "w1280dp-h720dp-mdpi")
        try {
            assertNull(ClusterMapPresentation.findDisplay(context))
        } finally {
            ShadowDisplayManager.removeDisplay(id)
        }
    }

    @Test fun dilink4RequiresPresentationFlag() {
        val id = display(DiLink4ClusterDisplay.NAME, "w1920dp-h720dp-mdpi")
        shadowOf(manager.getDisplay(id)).setFlags(0)
        try {
            assertNull(ClusterMapPresentation.findDisplay(context))
        } finally {
            ShadowDisplayManager.removeDisplay(id)
        }
    }

    @Test fun existingDilink5DisplayKeepsPriority() {
        val legacy = display(DiLink4ClusterDisplay.NAME, "w1920dp-h720dp-mdpi")
        val current = display(DiLink51ClusterLayout.BASE)
        try {
            assertEquals(current, ClusterMapPresentation.findDisplay(context)?.displayId)
        } finally {
            ShadowDisplayManager.removeDisplay(current)
            ShadowDisplayManager.removeDisplay(legacy)
        }
    }

    @Test fun disabledClusterStillReportsDisplayWithoutNavigationReceiver() {
        val enabled = AirPlayPersistence.loadClusterMapEnabled(context)
        AirPlayPersistence.saveClusterMapEnabled(context, false)
        val id = display(DiLink4ClusterDisplay.NAME, "w1920dp-h720dp-mdpi")
        try {
            assertFalse(com.shilapi.xcertplay.hud.BydOutputSettings.navigationAvailable(context))
            val report = ClusterMapPresentation.diagnosticReport(context)
            assertTrue(report.contains("clusterEnabled=false"))
            assertTrue(report.contains("navigationReceiverAvailable=false"))
            assertTrue(report.contains("1920x720"))
            assertTrue(report.contains("selectedCluster=$id:${DiLink4ClusterDisplay.NAME}"))
        } finally {
            ShadowDisplayManager.removeDisplay(id)
            AirPlayPersistence.saveClusterMapEnabled(context, enabled)
        }
    }

    @Test fun diagnosticsDistinguishVisibleDisplayFromEligiblePresentation() {
        val id = display(DiLink4ClusterDisplay.NAME, "w1920dp-h720dp-mdpi")
        shadowOf(manager.getDisplay(id)).setFlags(0)
        try {
            val report = ClusterMapPresentation.diagnosticReport(context)
            assertTrue(report.contains("$id:${DiLink4ClusterDisplay.NAME}"))
            assertTrue(report.contains("selectedCluster=none"))
        } finally {
            ShadowDisplayManager.removeDisplay(id)
        }
    }

    @Test fun legacyHolderSurfaceIsReportedAsPhysicalOutputWithoutTakingItsOwnership() {
        val id = display("fission_bg_XDJAScreenProjection")
        val reported = mutableListOf<android.view.Surface?>()
        val presentation = ClusterMapPresentation(context, manager.getDisplay(id)) { reported += it }
        val surface = org.mockito.Mockito.mock(android.view.Surface::class.java)
        val holder = org.mockito.Mockito.mock(android.view.SurfaceHolder::class.java)
        org.mockito.Mockito.`when`(holder.surface).thenReturn(surface)
        fun find(view: android.view.View): android.view.SurfaceView? {
            if (view is android.view.SurfaceView) return view
            if (view is android.view.ViewGroup) for (child in 0 until view.childCount) {
                find(view.getChildAt(child))?.let { return it }
            }
            return null
        }
        try {
            presentation.create()
            val view = find(presentation.window!!.decorView) ?: error("Legacy route must use SurfaceView")
            val callbacks = (view.holder as org.robolectric.shadows.ShadowSurfaceView.FakeSurfaceHolder).callbacks
            callbacks.forEach { it.surfaceCreated(holder) }
            assertSame(surface, presentation.outputSurface)
            assertSame(surface, reported.last())
            presentation.setMapVisible(false)
            assertFalse(presentation.mapVisible)
            presentation.setMapVisible(true)
            assertTrue(presentation.mapVisible)
            callbacks.forEach { it.surfaceDestroyed(holder) }
            assertNull(presentation.outputSurface)
            assertNull(reported.last())
            org.mockito.Mockito.verify(surface, org.mockito.Mockito.never()).release()
        } finally {
            presentation.dismiss()
            ShadowDisplayManager.removeDisplay(id)
        }
    }

}

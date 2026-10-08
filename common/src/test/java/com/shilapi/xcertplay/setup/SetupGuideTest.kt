package com.shilapi.xcertplay.setup

import com.shilapi.xcertplay.setup.DiLinkGeneration.Source
import com.shilapi.xcertplay.setup.SetupGuide.Feature
import com.shilapi.xcertplay.setup.SetupGuide.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupGuideTest {
    @Test fun gccHanDiLink3IsDetectedFromItsProduct() {
        val detection = DiLinkGeneration.detect("DiLink3.0",
            "BYD-AUTO/DiLink3.0/DiLink3.0:10/QKQ1.210910.001/eng.build.20260204.025317:user/release-keys", null, 29)
        assertEquals(DiLinkGeneration.DILINK_3, detection.generation)
        assertEquals(Source.SYSTEM_VERSION, detection.source)
        assertEquals("DiLink3.0", detection.evidence)
    }

    @Test fun diLink4And5AreDetectedFromTheFingerprint() {
        assertEquals(DiLinkGeneration.DILINK_4,
            DiLinkGeneration.detect("msmnile", "BYD-AUTO/DiLink4.0/DiLink4.0:11/x:user/release-keys", null, 30).generation)
        assertEquals(DiLinkGeneration.DILINK_5,
            DiLinkGeneration.detect(null, "BYD-AUTO/DiLink5.0/DiLink5.0:12/x:user/release-keys", null, 31).generation)
    }

    @Test fun genericIviBuildOnAndroid13IsOnlyALikelyDiLink5() {
        val detection = DiLinkGeneration.detect("IVI", "BYD-AUTO/IVI/IVI:13/TP1A.220624.014/x:user/release-keys", null, 33)
        assertEquals(DiLinkGeneration.DILINK_5, detection.generation)
        assertEquals(Source.LIKELY, detection.source)
    }

    @Test fun otherHeadUnitsAreUnknown() {
        val detection = DiLinkGeneration.detect("sdk_car_x86", "google/sdk_car_x86/generic:10/x:user/release-keys", "", 29)
        assertEquals(DiLinkGeneration.UNKNOWN, detection.generation)
        assertEquals(Source.NONE, detection.source)
        assertNull(DiLinkGeneration.detect(null, null, null, 28).evidence)
    }

    @Test fun diLink4ClusterRouteIsOnlyOfferedOnDiLink4() {
        fun offered(generation: DiLinkGeneration) =
            SetupGuide.features(generation).any { it.feature == Feature.DILINK4_ADB_CLUSTER }
        assertTrue(offered(DiLinkGeneration.DILINK_4))
        assertFalse(offered(DiLinkGeneration.DILINK_3))
        assertFalse(offered(DiLinkGeneration.DILINK_5))
        assertFalse(offered(DiLinkGeneration.UNKNOWN))
    }

    @Test fun diLink3CallFeaturesAreExperimentalAndNeedAdb() {
        val features = SetupGuide.features(DiLinkGeneration.DILINK_3).associateBy { it.feature }
        assertEquals(Status.TESTED, features.getValue(Feature.CLUSTER_MAP).status)
        listOf(Feature.CALL_KEYS, Feature.CALLS_ON_DASHBOARD).forEach {
            assertEquals(Status.EXPERIMENTAL, features.getValue(it).status)
            assertTrue(features.getValue(it).needsAdb)
        }
        assertFalse(SetupGuide.features(DiLinkGeneration.DILINK_5).any { it.feature == Feature.CALL_KEYS })
    }

    @Test fun onlyDiLink3WithTheDiLink4RouteIsAConflict() {
        assertTrue(SetupGuide.hasConflictingClusterRoute(DiLinkGeneration.DILINK_3, true))
        assertFalse(SetupGuide.hasConflictingClusterRoute(DiLinkGeneration.DILINK_3, false))
        assertFalse(SetupGuide.hasConflictingClusterRoute(DiLinkGeneration.DILINK_4, true))
    }

    @Test fun guideOpensByItselfOnlyOnAFreshSetup() {
        assertTrue(SetupGuide.shouldOpenOnLaunch(seen = false, phoneChosen = false))
        assertFalse(SetupGuide.shouldOpenOnLaunch(seen = false, phoneChosen = true))
        assertFalse(SetupGuide.shouldOpenOnLaunch(seen = true, phoneChosen = false))
    }
}

package com.shilapi.xcertplay

import com.shilapi.xcertplay.airplay.SafeAreaRect
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class ClusterSafeAreaPersistenceTest {
    @Test fun clusterBoxDoesNotOverwriteTheMainMappingAtTheSameSize() {
        val context = RuntimeEnvironment.getApplication()
        val main = SafeAreaRect(20, 30, 1900, 700)
        val cluster = SafeAreaRect(300, 100, 1500, 620)
        AirPlayPersistence.saveSafeAreaRect(context, 1920, 720, main)
        AirPlayPersistence.saveClusterSafeAreaRect(context, cluster)
        assertEquals(main, AirPlayPersistence.loadSafeAreaRect(context, 1920, 720))
        assertEquals(cluster, AirPlayPersistence.loadClusterSafeAreaRect(context))
        AirPlayPersistence.clearClusterSafeAreaRect(context)
        assertNull(AirPlayPersistence.loadClusterSafeAreaRect(context))
        assertEquals(main, AirPlayPersistence.loadSafeAreaRect(context, 1920, 720))
    }

    @Test fun mapWithBuiltInTurnCardDefaultsButSavedChoicesRemain() {
        val context = RuntimeEnvironment.getApplication()
        AirPlayPersistence.saveAdbClusterEnabled(context, true)
        assertEquals(com.shilapi.xcertplay.airplay.CarPlayClusterDisplay.Content.INSTRUMENTS,
            AirPlayPersistence.loadClusterContent(context))
        AirPlayPersistence.saveClusterContent(context, com.shilapi.xcertplay.airplay.CarPlayClusterDisplay.Content.MAP)
        assertEquals(com.shilapi.xcertplay.airplay.CarPlayClusterDisplay.Content.MAP,
            AirPlayPersistence.loadClusterContent(context))
    }

}

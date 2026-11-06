package com.shilapi.xcertplay

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.graphics.SurfaceTexture
import android.os.*
import android.view.Surface
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import com.shilapi.xcertplay.media.CarPlayNowPlaying

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class MapSurfaceServiceTest {
    @Test fun surfaceMirrorWorksBelowApi30AndConsentRevokesIt() {
        val controller = Robolectric.buildService(MapSurfaceService::class.java).create()
        val service = controller.get()
        shadowOf(service.packageManager).installPackage(PackageInfo().apply {
            packageName = "local.t03.xiaoling"
            applicationInfo = ApplicationInfo().apply { uid = Process.myUid(); packageName = "local.t03.xiaoling" }
        })
        val outputs = linkedMapOf<String, Surface>()
        MapMirrors.sink = { key, surface -> if (surface == null) outputs.remove(key) else outputs[key] = surface }
        val replies = mutableListOf<Message>()
        val client = Messenger(Handler(Looper.getMainLooper()) { replies += Message.obtain(it); true })
        val endpoint = Messenger(service.onBind(Intent(MapSurfaceEndpoint.ACTION)))
        val texture = SurfaceTexture(0)

        fun attach(version: Int = 1) {
            endpoint.send(Message.obtain(null, MapSurfaceEndpoint.ATTACH).apply {
                replyTo = client
                data = Bundle().apply { putInt("version", version); putParcelable("surface", Surface(texture)) }
            })
            shadowOf(Looper.getMainLooper()).idle()
        }
        try {
            AirPlayPersistence.saveLauncherMapSharing(service, false)
            attach()
            assertEquals("disabled", replies.last().data.getString("error"))
            assertTrue(outputs.isEmpty())
            AirPlayPersistence.saveLauncherMapSharing(service, true)
            attach(2)
            assertEquals("version", replies.last().data.getString("error"))
            attach()
            assertEquals(MapSurfaceEndpoint.ATTACHED, replies.last().what)
            assertEquals(1, outputs.size)
            MapMirrors.setStreamActive(true)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(replies.last().data.getBoolean("active"))
            AirPlayPersistence.saveLauncherMapSharing(service, false)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(outputs.isEmpty())
            assertEquals("disabled", replies.last().data.getString("error"))
            AirPlayPersistence.saveLauncherMapSharing(service, true)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue("Consent alone does not reattach revoked clients", outputs.isEmpty())
            attach()
            assertEquals(1, outputs.size)
            endpoint.send(Message.obtain(null, MapSurfaceEndpoint.DETACH).apply { replyTo = client })
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(outputs.isEmpty())
        } finally {
            controller.destroy()
            MapMirrors.sink = null
            MapMirrors.setStreamActive(false)
            texture.release()
        }
    }

    @Test fun followsSourceAndExposesMusic() {
        val controller = Robolectric.buildService(MapSurfaceService::class.java).create()
        val service = controller.get()
        shadowOf(service.packageManager).installPackage(PackageInfo().apply {
            packageName = "local.t03.xiaoling"
            applicationInfo = ApplicationInfo().apply { uid = Process.myUid(); packageName = "local.t03.xiaoling" }
        })
        val outputs = linkedMapOf<String, Surface>()
        MapMirrors.sink = { key, surface -> if (surface == null) outputs.remove(key) else outputs[key] = surface }
        val replies = mutableListOf<Message>()
        val client = Messenger(Handler(Looper.getMainLooper()) { replies += Message.obtain(it); true })
        val endpoint = Messenger(service.onBind(Intent(MapSurfaceEndpoint.ACTION)))
        val texture = SurfaceTexture(0)
        fun attach(version: Int = 1) {
            endpoint.send(Message.obtain(null, MapSurfaceEndpoint.ATTACH).apply {
                replyTo = client
                data = Bundle().apply { putInt("version", version); putParcelable("surface", Surface(texture)) }
            })
            shadowOf(Looper.getMainLooper()).idle()
        }
        try {
            AirPlayPersistence.saveLauncherMapSharing(service, true)
            attach()
            assertEquals(1, outputs.size)
            assertTrue("Xiaoling mirror key must be source-following",
                outputs.keys.single().startsWith(MapMirrors.XIAOLING_PREFIX))

            // MUSIC carries no video stream: even when the main stream is reported active, active must
            // stay false so the client does not wait for a frame it will never receive.
            ClusterScreenProfile.setSource(service, ClusterScreenProfile.Source.MUSIC)
            shadowOf(Looper.getMainLooper()).idle()
            MapMirrors.setStreamActive(true)
            shadowOf(Looper.getMainLooper()).idle()
            val state = replies.last()
            assertEquals(MapSurfaceEndpoint.STATE, state.what)
            assertFalse("no video for MUSIC source", state.data.getBoolean("active"))
            assertTrue("music flag set when source is MUSIC", state.data.getBoolean("music"))

            // A published track fills the music metadata even with no video present.
            ClusterMusicState.publish(CarPlayNowPlaying(title = "Song", artist = "Artist"), null)
            shadowOf(Looper.getMainLooper()).idle()
            val music = replies.last()
            assertEquals("Song", music.data.getString("title"))
            assertEquals("Artist", music.data.getString("artist"))
        } finally {
            controller.destroy()
            MapMirrors.sink = null
            MapMirrors.setStreamActive(false)
            ClusterScreenProfile.setSource(service, ClusterScreenProfile.Source.NAVIGATION)
            texture.release()
        }
    }
}

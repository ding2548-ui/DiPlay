package com.shilapi.xcertplay

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
@OptIn(UnstableApi::class)
class IphoneResolvingDataSourceTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val forbidden = listOf("file:///sdcard/v.mp4", "content://media/1", "asset:///v.mp4",
        "rawresource:///123", "android.resource://app/raw/video", "skd://key", "/sdcard/v.mp4",
        "relative.mp4", "https", "FILE:///sdcard/v.mp4")

    @Test fun rejectsLocalAndSchemeLessPlaylistSourcesBeforeLoading() {
        val upstream = RecordingSource()
        val source = IphoneResolvingDataSource(upstream) { fail("Must not ask the iPhone for local resources"); null }
        for (url in forbidden) {
            assertThrows(url, IOException::class.java) { source.open(spec(url)) }
            source.close()
        }
        assertTrue(upstream.opened.isEmpty())
    }

    @Test fun remoteMediaCannotOpenAnAppPrivateFile() {
        val file = java.io.File(context.filesDir, "synthetic-private-media")
        file.writeText("synthetic private data")
        val source = IphoneResolvingDataSource(DefaultDataSource.Factory(context).createDataSource())
        try {
            assertThrows(IOException::class.java) { source.open(DataSpec(Uri.fromFile(file))) }
        } finally { source.close(); file.delete() }
    }

    @Test fun validatesEveryIphoneRedirectBeforeDelegating() {
        for (url in forbidden) {
            val upstream = RecordingSource()
            val source = IphoneResolvingDataSource(upstream) {
                CarPlayVideo.LoadedUrl(302, null, url)
            }
            assertThrows(url, IOException::class.java) { source.open(spec("appvideo://playlist")) }
            source.close()
            assertTrue(url, upstream.opened.isEmpty())
        }
    }

    @Test fun appRedirectsStayOnTheIphoneAndHttpRedirectsPreserveTheRange() {
        val requested = mutableListOf<String>()
        val upstream = RecordingSource()
        val source = IphoneResolvingDataSource(upstream) { url ->
            requested += url
            CarPlayVideo.LoadedUrl(302, null,
                if (url == "appvideo://first") "appvideo://second" else "https://example.com/video")
        }
        val range = DataSpec.Builder().setUri("appvideo://first").setPosition(7).setLength(12).build()
        source.open(range)
        assertEquals(listOf("appvideo://first", "appvideo://second"), requested)
        assertEquals("https://example.com/video", upstream.opened.single().uri.toString())
        assertEquals(7L, upstream.opened.single().position)
        assertEquals(12L, upstream.opened.single().length)
        source.close()
        assertEquals(1, upstream.closes)
    }

    @Test fun redirectLoopsAreBounded() {
        var requests = 0
        val source = IphoneResolvingDataSource(RecordingSource()) {
            requests++
            CarPlayVideo.LoadedUrl(302, null, "appvideo://loop")
        }
        assertThrows(IOException::class.java) { source.open(spec("appvideo://loop")) }
        assertEquals(6, requests)
    }

    @Test fun iphoneDataWithStatusZeroCanBeReadAndSeeked() {
        val source = IphoneResolvingDataSource(RecordingSource()) {
            CarPlayVideo.LoadedUrl(0, byteArrayOf(1, 2, 3, 4), null)
        }
        assertEquals(2L, source.open(DataSpec.Builder().setUri("appvideo://segment").setPosition(1).setLength(2).build()))
        val bytes = ByteArray(2)
        assertEquals(2, source.read(bytes, 0, 2))
        assertArrayEquals(byteArrayOf(2, 3), bytes)
        source.close()
    }

    @Test fun failedHttpOpenClosesTheSelectedDelegateAndAllowsRetry() {
        val upstream = RecordingSource().apply { failNextOpen = true }
        val delegate = DefaultDataSource.Factory(context, DataSource.Factory { upstream }).createDataSource()
        val source = IphoneResolvingDataSource(delegate)
        val request = spec("https://example.com/video")
        assertThrows(IOException::class.java) { source.open(request) }
        assertEquals(1, upstream.closes)
        source.close() // ExoPlayer also closes after a failed open.
        assertEquals(1L, source.open(request))
        source.close()
        assertEquals(2, upstream.closes)
    }

    @Test fun failedRedirectedHttpOpenAlsoAllowsRetry() {
        val upstream = RecordingSource().apply { failNextOpen = true }
        val delegate = DefaultDataSource.Factory(context, DataSource.Factory { upstream }).createDataSource()
        val source = IphoneResolvingDataSource(delegate) {
            CarPlayVideo.LoadedUrl(302, null, "https://example.com/video")
        }
        val request = spec("appvideo://video")
        assertThrows(IOException::class.java) { source.open(request) }
        assertEquals(1, upstream.closes)
        assertEquals(1L, source.open(request))
        source.close()
    }

    private fun spec(url: String) = DataSpec(Uri.parse(url))

    private class RecordingSource : DataSource {
        val opened = mutableListOf<DataSpec>()
        var closes = 0
        var failNextOpen = false
        override fun addTransferListener(listener: TransferListener) = Unit
        override fun open(dataSpec: DataSpec): Long {
            opened += dataSpec
            if (failNextOpen) { failNextOpen = false; throw IOException("Transient opening failure") }
            return 1L
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int) = -1
        override fun getUri(): Uri? = opened.lastOrNull()?.uri
        override fun close() { closes++ }
    }
}

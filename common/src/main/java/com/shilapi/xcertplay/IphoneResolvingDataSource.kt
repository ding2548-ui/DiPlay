package com.shilapi.xcertplay

import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.shilapi.xcertplay.airplay.VideoInCar
import java.io.IOException

/**
 * Loads what the car can load itself (http, https, data) directly and asks the iPhone for
 * anything else, as Apple's receiver does (unhandledURL): apps can serve custom-scheme playlists and
 * AES-128 keys through their resource loader on the iPhone. FairPlay keys (skd://) are not requested:
 * they need a licensed FairPlay receiver.
 */
@OptIn(UnstableApi::class)
internal class IphoneResolvingDataSource(
    private val upstream: DataSource,
    private val resolveOnIphone: (String) -> CarPlayVideo.LoadedUrl? = CarPlayVideo::resolveOnIphone,
) : DataSource {
    private var current: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        check(current == null) { "already open" }
        return open(dataSpec, redirects = 0)
    }

    private fun open(dataSpec: DataSpec, redirects: Int): Long {
        val url = dataSpec.uri.toString()
        val scheme = dataSpec.uri.scheme?.lowercase()
        if (!VideoInCar.isPlayableUrl(url, iphoneLoadsAppSchemes = true)) {
            throw IOException("Unsupported video URL scheme")
        }
        if (scheme in DIRECT_SCHEMES) return openSource(upstream, dataSpec)
        val resolved = resolveOnIphone(url) ?: throw IOException("iPhone did not load the $scheme URL")
        Log.i(TAG, "iPhone loaded $scheme URL status=${resolved.status} bytes=${resolved.data?.size} redirect=${resolved.location != null}")
        resolved.location?.let { location ->
            if (redirects >= MAX_REDIRECTS) throw IOException("Too many iPhone URL redirects")
            // Reapply the policy: a resource loader's Location must not select an Android file,
            // content provider or APK asset. Custom-scheme redirects still go through the iPhone.
            return open(dataSpec.withUri(Uri.parse(location)), redirects + 1)
        }
        // An app's resource loader answers with data only: status 0 means no HTTP status, not a failure.
        if (resolved.status != null && resolved.status != 0 && resolved.status !in 200..299) throw IOException("iPhone status ${resolved.status}")
        return openSource(ByteArrayDataSource(resolved.data ?: throw IOException("iPhone sent no data")), dataSpec)
    }

    private fun openSource(source: DataSource, dataSpec: DataSpec): Long {
        current = source // Own it before open(), which can leave a selected delegate after failing.
        try {
            return source.open(dataSpec)
        } catch (error: Exception) {
            try {
                close()
            } catch (cleanup: Exception) {
                error.addSuppressed(cleanup)
            }
            throw error
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        current?.read(buffer, offset, length) ?: throw IOException("not open")

    override fun getUri(): Uri? = current?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = current?.responseHeaders ?: emptyMap()

    override fun close() {
        try {
            current?.close()
        } finally {
            current = null
        }
    }

    class Factory(private val upstream: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource = IphoneResolvingDataSource(upstream.createDataSource())
    }

    private companion object {
        const val TAG = "DiPlay-Video"
        const val MAX_REDIRECTS = 5
        val DIRECT_SCHEMES = setOf("http", "https", "data")
    }
}

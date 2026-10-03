// SPDX-License-Identifier: GPL-3.0-only
// In-app updater: checks GitHub releases for a newer CI build, downloads the APK
// (directly or through a gh-proxy style mirror), then installs it via the
// PackageInstaller session API (same mechanism as the platform's own store apps).
package com.shilapi.xcertplay

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object AppUpdater {
    private const val REPO = "ding2548-ui/DiPlay"
    private const val PREFS = "diplay"
    private const val SOURCE_KEY = "update_source"
    private const val ASSET_PREFIX = "DiPlay-2.10-beta-"
    private const val ASSET_SUFFIX = ".apk"
    private const val TAG_PATTERN = "v2.10-beta-"
    private const val CONNECT_TIMEOUT = 10_000
    private const val READ_TIMEOUT = 20_000

    /** Empty string means the default GitHub connection (no mirror). */
    const val DIRECT = ""

    /** gh-proxy style mirrors: the full github.com path is appended after the host. */
    val MIRRORS = listOf("seep.eu.org", "jsnzkpg4.pages.dev", "down.nigx.cn")

    fun sources(): List<String> = listOf(DIRECT) + MIRRORS

    fun source(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(SOURCE_KEY, DIRECT) ?: DIRECT

    fun saveSource(context: Context, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(SOURCE_KEY, value).apply()
    }

    fun sourceLabel(source: String): String = when (source) {
        DIRECT -> "直连 GitHub"
        else -> "代理 $source"
    }

    /** The CI stamps the build number into the version name, e.g. 2.10（90）. */
    fun currentBuild(context: Context): Int? {
        val name = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: return null
        return Regex("（(\\d+)）").find(name)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("\\((\\d+)\\)").find(name)?.groupValues?.get(1)?.toIntOrNull()
    }

    /**
     * Resolves the newest CI build number. GitHub answers /releases/latest with a
     * redirect to the tagged page; mirrors may either pass the redirect through or
     * stream the page themselves, so both a Location header and the page body are
     * accepted. Tries the selected source first, then every other source.
     */
    fun latestBuild(preferred: String): Int? {
        var lastError: Exception? = null
        var reachable = false
        // GitHub's /releases/latest ignores prereleases, which every beta build is, so the
        // releases list API (tried directly; mirrors rarely forward api.github.com) is the
        // primary source. The redirect probe stays as a fallback for the same variant tags.
        runCatching { fetchLatestBuildViaApi() }.fold(
            onSuccess = { reachable = true; if (it != null) return it },
            onFailure = { lastError = it },
        )
        val order = listOf(preferred) + sources().filter { it != preferred }
        for (source in order) {
            try {
                val build = fetchLatestBuild(source)
                reachable = true
                if (build != null) return build
            } catch (failure: Exception) {
                lastError = failure
            }
        }
        if (reachable) return null
        throw lastError ?: error("无法获取最新构建")
    }

    /** Newest CI build number among the beta-tagged releases, via the GitHub API. */
    private fun fetchLatestBuildViaApi(): Int? {
        val connection = java.net.URL("https://api.github.com/repos/$REPO/releases?per_page=20")
            .openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT
        connection.readTimeout = READ_TIMEOUT
        connection.setRequestProperty("User-Agent", "DiPlay-Update")
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            if (connection.responseCode != 200) return null
            val body = connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            val array = org.json.JSONArray(body)
            var best: Int? = null
            for (index in 0 until array.length()) {
                val tag = array.optJSONObject(index)?.optString("tag_name") ?: continue
                val build = Regex("${TAG_PATTERN}(\\d+)").find(tag)?.groupValues?.get(1)?.toIntOrNull() ?: continue
                if (best == null || build > best) best = build
            }
            return best
        } finally {
            connection.disconnect()
        }
    }

    private fun fetchLatestBuild(source: String): Int? {
        val connection = open(source, "github.com/$REPO/releases/latest", redirectless = true)
        try {
            val code = connection.responseCode
            if (code in 300..399) {
                val target = connection.getHeaderField("Location") ?: return null
                return Regex("${TAG_PATTERN}(\\d+)").find(target)?.groupValues?.get(1)?.toIntOrNull()
            }
            if (code != 200) error("HTTP $code")
            val body = connection.inputStream.use { stream ->
                val buffer = ByteArray(64 * 1024)
                val collected = StringBuilder()
                while (collected.length < 256 * 1024) {
                    val read = stream.read(buffer)
                    if (read <= 0) break
                    collected.append(String(buffer, 0, read, Charsets.UTF_8))
                }
                collected.toString()
            }
            return Regex("${TAG_PATTERN}(\\d+)").find(body)?.groupValues?.get(1)?.toIntOrNull()
        } finally {
            connection.disconnect()
        }
    }

    /** The release job names assets after the tag: v2.0-90 -> DiPlay-2.0-90-leapmotor.apk. */
    fun assetName(build: Int) = "$ASSET_PREFIX$build$ASSET_SUFFIX"

    fun downloadApk(context: Context, build: Int, source: String, onProgress: (Int, Int) -> Unit): File {
        val path = "github.com/$REPO/releases/download/v2.10-beta-$build/${assetName(build)}"
        val connection = open(source, path, redirectless = false)
        val directory = context.getExternalFilesDir(null) ?: context.filesDir
        val temporary = File(directory, "${assetName(build)}.part")
        val target = File(directory, assetName(build))
        try {
            val code = connection.responseCode
            if (code != 200) error("HTTP $code")
            val total = connection.contentLength
            temporary.outputStream().use { output ->
                connection.inputStream.use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        done += read
                        if (total > 0) onProgress(done.toInt(), total)
                    }
                }
            }
            if (target.exists()) target.delete()
            if (!temporary.renameTo(target)) error("无法保存下载文件")
            return target
        } catch (failure: Exception) {
            temporary.delete()
            throw failure
        } finally {
            connection.disconnect()
        }
    }

    /** The newest completed download, if any; used to decide whether to relaunch after an update. */
    fun pendingApk(context: Context): File? {
        val directory = context.getExternalFilesDir(null) ?: context.filesDir
        return directory.listFiles()
            ?.filter { it.name.startsWith(ASSET_PREFIX) && it.name.endsWith(ASSET_SUFFIX) }
            ?.maxByOrNull { it.lastModified() }
    }

    /** Removes every downloaded update APK; called after a successful install. */
    fun cleanup(context: Context) {
        val directory = context.getExternalFilesDir(null) ?: context.filesDir
        directory.listFiles()?.forEach {
            if (it.name.startsWith(ASSET_PREFIX) && it.name.endsWith(ASSET_SUFFIX)) it.delete()
        }
    }

    /**
     * Queues the APK for installation through a PackageInstaller session. The
     * platform signature grants INSTALL_PACKAGES, so the replace happens without a
     * dialog; if the platform ever withholds it, UpdateInstallReceiver opens the
     * system confirmation instead. On success the process is killed and
     * UpdateInstalledReceiver reopens DiPlay and deletes the downloaded APK.
     */
    fun installApk(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(context.packageName)
        val sessionId = installer.createSession(params)
        val session = installer.openSession(sessionId)
        try {
            session.openWrite("DiPlayUpdate.apk", 0, apk.length()).use { output ->
                apk.inputStream().use { input -> input.copyTo(output, 64 * 1024) }
                session.fsync(output)
            }
            val callback = Intent(context, UpdateInstallReceiver::class.java)
            val sender = PendingIntent.getBroadcast(context, sessionId, callback, PendingIntent.FLAG_IMMUTABLE)
            session.commit(sender.intentSender)
        } catch (failure: Exception) {
            runCatching { session.abandon() }
            throw failure
        } finally {
            session.close()
        }
    }

    private fun open(source: String, path: String, redirectless: Boolean): HttpURLConnection {
        val url = if (source.isEmpty()) "https://$path" else "https://$source/$path"
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = redirectless.not()
        connection.connectTimeout = CONNECT_TIMEOUT
        connection.readTimeout = READ_TIMEOUT
        connection.setRequestProperty("User-Agent", "DiPlay-Update")
        return connection
    }
}

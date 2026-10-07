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

    // Both lines publish into this one repository, so the release tag prefix is what tells this
    // updater which builds are its own. This line tags v0.2.12-<run_number> and attaches a single
    // asset named mobile-release.apk; the Leapmotor line tags v2.0-<run> with a differently named
    // asset, and offering one of those here would install the wrong build.
    private const val TAG_PREFIX = "v0.2.12-"
    private const val RELEASE_ASSET = "mobile-release.apk"
    private const val LOCAL_PREFIX = "DiPlay-0.2.12-"
    private const val LOCAL_SUFFIX = ".apk"

    // The beta channel is a second variant of this same line, shipped under its own applicationId
    // so both can be installed side by side. It publishes under its own tag namespace, and a build
    // must only ever resolve builds from the namespace it was published into: otherwise the two
    // channels would hand each other their APKs, and each would report the other's run number as
    // an available update.
    private const val BETA_TAG_PREFIX = "v0.2.12-beta-"
    private const val BETA_APPLICATION_ID_SUFFIX = ".psabeta"

    /** The release-tag namespace this build looks in, decided by the channel it was built as. */
    fun tagPrefix(context: Context): String =
        if (context.packageName.endsWith(BETA_APPLICATION_ID_SUFFIX)) BETA_TAG_PREFIX else TAG_PREFIX

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

    /**
     * The CI stamps the run number into the version name. This line writes 0.2.12（189-6f275b6c）,
     * the Leapmotor line writes 2.11（90）, so only the opening bracket is relied on.
     */
    fun currentBuild(context: Context): Int? {
        val name = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: return null
        return Regex("（(\\d+)").find(name)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("\\((\\d+)").find(name)?.groupValues?.get(1)?.toIntOrNull()
    }

    /**
     * Resolves the newest build number published for this line. Both lines share the repository, so
     * /releases/latest names whichever line pushed last, and that is regularly the other one. The
     * atom feed lists the recent releases of both, so it is read first and every tag matching
     * [tagPrefix] is considered; the redirect stays as a fallback for mirrors that do not serve the
     * feed. Mirrors may pass a redirect through or stream the page themselves, so both a Location
     * header and the page body are accepted. Tries the selected source first, then every other.
     */
    fun latestBuild(context: Context, preferred: String): Int {
        val order = listOf(preferred) + sources().filter { it != preferred }
        val prefix = tagPrefix(context)
        var lastError: Exception? = null
        for (source in order) {
            try {
                val build = fetchLatestBuild(source, prefix)
                if (build != null) return build
            } catch (failure: Exception) {
                lastError = failure
            }
        }
        throw lastError ?: error("无法获取最新构建")
    }

    private fun fetchLatestBuild(source: String, tagPrefix: String): Int? =
        newestFromFeed(source, tagPrefix) ?: newestFromRedirect(source, tagPrefix)

    /** releases.atom carries one <link .../releases/tag/<tag>> per recent release, both lines mixed. */
    private fun newestFromFeed(source: String, tagPrefix: String): Int? {
        val connection = open(source, "github.com/$REPO/releases.atom", redirectless = true)
        try {
            if (connection.responseCode != 200) return null
            val body = connection.inputStream.use { readText(it) }
            return buildNumbers(body, tagPrefix).maxOrNull()
        } finally {
            connection.disconnect()
        }
    }

    private fun newestFromRedirect(source: String, tagPrefix: String): Int? {
        val connection = open(source, "github.com/$REPO/releases/latest", redirectless = true)
        try {
            val code = connection.responseCode
            if (code in 300..399) {
                val target = connection.getHeaderField("Location") ?: return null
                return buildNumbers(target, tagPrefix).maxOrNull()
            }
            if (code != 200) return null
            val body = connection.inputStream.use { readText(it) }
            return buildNumbers(body, tagPrefix).maxOrNull()
        } finally {
            connection.disconnect()
        }
    }

    /** Every run number this line has published, found anywhere in the given text. */
    private fun buildNumbers(text: String, tagPrefix: String): List<Int> =
        Regex(Regex.escape(tagPrefix) + "(\\d+)").findAll(text)
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .toList()

    private fun readText(stream: java.io.InputStream): String {
        val buffer = ByteArray(64 * 1024)
        val collected = StringBuilder()
        while (collected.length < 256 * 1024) {
            val read = stream.read(buffer)
            if (read <= 0) break
            collected.append(String(buffer, 0, read, Charsets.UTF_8))
        }
        return collected.toString()
    }

    /** The release job attaches one fixed asset name, so the build lives in the tag, not the file. */
    fun assetName(build: Int) = "$LOCAL_PREFIX$build$LOCAL_SUFFIX"

    fun downloadApk(context: Context, build: Int, source: String, onProgress: (Int, Int) -> Unit): File {
        val path = "github.com/$REPO/releases/download/${tagPrefix(context)}$build/$RELEASE_ASSET"
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
            ?.filter { it.name.startsWith(LOCAL_PREFIX) && it.name.endsWith(LOCAL_SUFFIX) }
            ?.maxByOrNull { it.lastModified() }
    }

    /** Removes every downloaded update APK; called after a successful install. */
    fun cleanup(context: Context) {
        val directory = context.getExternalFilesDir(null) ?: context.filesDir
        directory.listFiles()?.forEach {
            if (it.name.startsWith(LOCAL_PREFIX) && it.name.endsWith(LOCAL_SUFFIX)) it.delete()
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

    private const val MANUAL_APK_KEY = "manual_install_apk"

    /** Remembers which APK the manual installer should fall back to. */
    fun rememberManualApk(context: Context, apk: File) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(MANUAL_APK_KEY, apk.absolutePath).apply()
    }

    fun pendingManualApk(context: Context): File? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(MANUAL_APK_KEY, null)?.let(::File)?.takeIf { it.exists() }

    fun clearManualApk(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(MANUAL_APK_KEY).apply()
    }

    /**
     * Opens the downloaded APK in the system installer UI. The fallback for ROMs whose
     * PackageInstaller refuses silent sessions (non-platform-signed builds report an
     * opaque failure there); the user confirms with two taps instead.
     */
    fun installManually(context: Context, apk: File) {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            context.packageName + ".fileprovider",
            apk,
        )
        val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        rememberManualApk(context, apk)
        context.startActivity(intent)
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

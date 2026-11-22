package app.aemu.update

import app.aemu.core.LocalizedIOException
import app.aemu.core.LocalizedIllegalArgumentException
import app.aemu.R
import android.content.Context
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Environment
import android.provider.MediaStore
import java.security.MessageDigest
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import app.aemu.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

data class ReleaseInfo(
    val versionName: String,
    val tagName: String,
    val title: String,
    val body: String,
    val downloadUrl: String,
    val fileName: String,
    val sizeBytes: Long
)

sealed interface UpdateState {
    object Idle : UpdateState
    object Checking : UpdateState
    data class Available(val release: ReleaseInfo) : UpdateState
    data class UpToDate(val currentVersion: String, val latestTag: String) : UpdateState
    data class Downloading(val release: ReleaseInfo, val progress: Float, val downloadedBytes: Long, val totalBytes: Long) : UpdateState
    data class ReadyToInstall(val release: ReleaseInfo, val file: File) : UpdateState
    data class Error(val message: String) : UpdateState
}

/** Modified for AEmulator Sunset on 2026-09-30: use the fork's releases.
 * Modified for AEmulator Plus on 2026-10-04: use the Plus releases. GPL-3.0; see NOTICE.md. */
object AppUpdateManager {
    private const val RELEASES_API = "https://api.github.com/repos/Zyoo-0X/QuDroid/releases"
    private const val GITHUB_REPO_URL = "https://github.com/Zyoo-0X/QuDroid"

    /**
     * Запрос к GitHub API для получения информации о последнем релизе с APK-файлом.
     */
    suspend fun checkLatestRelease(): Result<ReleaseInfo?> = withContext(Dispatchers.IO) {
        runCatching {
            val url = URL(RELEASES_API)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "QuDroid-App")

            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                throw Exception("HTTP ${conn.responseCode}: ${conn.responseMessage}")
            }

            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val array = JSONArray(text)
            if (array.length() == 0) return@runCatching null

            for (i in 0 until array.length()) {
                val rel = array.getJSONObject(i)
                if (rel.optBoolean("draft", false)) continue

                val tagName = rel.optString("tag_name", "").trim()
                val versionName = tagName.removePrefix("v").removePrefix("V")
                val title = rel.optString("name", tagName)
                val body = rel.optString("body", "")

                val assets = rel.optJSONArray("assets") ?: continue
                // any .apk of the release; the one named after this package first, unsigned / debug ones last
                val asset = (0 until assets.length()).map { assets.getJSONObject(it) }
                    .filter { UpdateAssetPolicy.matches(it.optString("name", ""), BuildConfig.APPLICATION_ID) }
                    .sortedBy { a ->
                        val n = a.optString("name", "").lowercase()
                        when {
                            BuildConfig.APPLICATION_ID.lowercase() in n -> 0
                            "unsigned" in n || "debug" in n -> 2
                            else -> 1
                        }
                    }.firstOrNull() ?: continue
                return@runCatching ReleaseInfo(
                    versionName = versionName,
                    tagName = tagName,
                    title = title,
                    body = body,
                    downloadUrl = asset.getString("browser_download_url"),
                    fileName = asset.optString("name", ""),
                    sizeBytes = asset.optLong("size", 0L)
                )
            }
            null
        }
    }

    /**
     * Сравнение версий (семантическое сравнение сегментов чисел).
     * Возвращает true, если latestTag новее чем currentVersion.
     */
    fun isNewerVersion(latestTag: String, currentVersion: String): Boolean {
        val cleanLatest = latestTag.trim().removePrefix("v").removePrefix("V")
        val cleanCurrent = currentVersion.trim().removePrefix("v").removePrefix("V")

        if (cleanLatest.equals(cleanCurrent, ignoreCase = true)) return false

        // the first dotted number in the tag ("v1.2.3", "build-12", "1.2.3-beta")
        val num = Regex("""\d+(?:\.\d+)*""")
        val latestParts = (num.find(cleanLatest)?.value ?: return false).split('.').map { it.toIntOrNull() ?: 0 }
        val currentParts = (num.find(cleanCurrent)?.value ?: return true).split('.').map { it.toIntOrNull() ?: 0 }

        val maxLen = maxOf(latestParts.size, currentParts.size)
        for (i in 0 until maxLen) {
            val l = latestParts.getOrElse(i) { 0 }
            val c = currentParts.getOrElse(i) { 0 }
            if (l > c) return true
            if (l < c) return false
        }

        return false
    }

    /**
     * Скачивание APK с поддержкой редиректов (GitHub перенаправляет на AWS S3) и отслеживанием прогресса.
     */
    suspend fun downloadApk(
        context: Context,
        release: ReleaseInfo,
        onProgress: (Float, Long, Long) -> Unit
    ): File = withContext(Dispatchers.IO) {
        if (!UpdateAssetPolicy.matches(release.fileName, context.packageName))
            throw LocalizedIllegalArgumentException("Update APK does not match this application variant", R.string.update_err_wrong_variant)
        val updatesDir = File(context.getExternalFilesDir(null) ?: context.cacheDir, "updates")
        if (!updatesDir.exists()) updatesDir.mkdirs()

        // Очищаем старые apk перед новой загрузкой
        updatesDir.listFiles()?.forEach { if (it.extension.equals("apk", ignoreCase = true)) it.delete() }

        val targetFile = File(updatesDir, "qudroid-${release.versionName}.apk")

        // Fix: cancelling the coroutine did not stop the download. Reads on HttpURLConnection block and ignore
        // coroutine cancellation, so the transfer ran to the end and its progress callbacks flipped the UI back to
        // "Downloading". Now a watcher child coroutine closes the socket the moment the job is cancelled (which
        // unblocks connect/read), the loop checks for cancellation on every chunk, and the partial file is removed.
        val active = AtomicReference<HttpURLConnection?>(null)
        var finished = false
        try {
            coroutineScope {
                val watcher = launch {
                    try { awaitCancellation() } finally { runCatching { active.get()?.disconnect() } }
                }
                try {
                    var currentUrl = release.downloadUrl
                    var redirects = 0
                    var conn: HttpURLConnection
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val url = URL(currentUrl)
                        conn = url.openConnection() as HttpURLConnection
                        active.set(conn)
                        conn.connectTimeout = 15000
                        conn.readTimeout = 30000
                        conn.instanceFollowRedirects = true
                        conn.setRequestProperty("User-Agent", "QuDroid-App")

                        val code = conn.responseCode
                        if (code in listOf(HttpURLConnection.HTTP_MOVED_PERM, HttpURLConnection.HTTP_MOVED_TEMP, 307, 308)) {
                            val newUrl = conn.getHeaderField("Location")
                            conn.disconnect()
                            if (newUrl != null && redirects < 5) {
                                currentUrl = newUrl
                                redirects++
                                continue
                            }
                        }
                        break
                    }

                    if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                        throw Exception("HTTP ${conn.responseCode}: ${conn.responseMessage}")
                    }

                    val totalLength = conn.contentLengthLong.takeIf { it > 0 } ?: release.sizeBytes
                    var downloadedBytes = 0L

                    conn.inputStream.use { input ->
                        FileOutputStream(targetFile).use { output ->
                            val buffer = ByteArray(16 * 1024)
                            var bytesRead: Int
                            while (input.read(buffer).also { bytesRead = it } != -1) {
                                currentCoroutineContext().ensureActive()
                                output.write(buffer, 0, bytesRead)
                                downloadedBytes += bytesRead
                                val progress = if (totalLength > 0) downloadedBytes.toFloat() / totalLength else 0f
                                onProgress(progress.coerceIn(0f, 1f), downloadedBytes, totalLength)
                            }
                            output.flush()
                        }
                    }
                    // a cut connection must not end up as a "downloaded" (and then broken) APK
                    if (totalLength > 0 && downloadedBytes != totalLength)
                        throw IOException("Incomplete download: $downloadedBytes of $totalLength bytes")
                    finished = true
                } finally {
                    watcher.cancel()
                }
            }
        } catch (e: IOException) {
            // A socket closed by the cancel hook surfaces as an IOException; report it as a cancellation, not an error.
            if (!currentCoroutineContext().isActive) throw CancellationException("Update download cancelled").also { it.initCause(e) }
            throw e
        } finally {
            runCatching { active.get()?.disconnect() }
            if (!finished) targetFile.delete()
        }

        if (context.packageManager.getPackageArchiveInfo(targetFile.absolutePath, 0)?.packageName != context.packageName) {
            targetFile.delete()
            throw LocalizedIOException("Downloaded APK has the wrong application package", R.string.update_err_wrong_package)
        }
        targetFile
    }

    /** The reason the last [installApk] call did not start an install (shown to the person), or null. */
    @Volatile var lastError: String? = null
        private set

    internal const val ACTION_INSTALL_RESULT = "app.aemu.update.INSTALL_RESULT"
    internal const val EXTRA_APK_PATH = "apk_path"

    private fun sha256(b: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun signerSet(pi: PackageInfo?): Set<String> {
        if (pi == null) return emptySet()
        val sigs = if (Build.VERSION.SDK_INT >= 28) {
            val si = pi.signingInfo ?: return emptySet()
            if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
        } else {
            @Suppress("DEPRECATION") pi.signatures
        }
        return sigs?.map { sha256(it.toByteArray()) }?.toSet() ?: emptySet()
    }

    @Suppress("DEPRECATION")
    private fun signingFlags(): Int =
        if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

    @Suppress("DEPRECATION")
    private fun versionCodeOf(pi: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()

    /**
     * Checks the downloaded APK against the installed app BEFORE Android is asked to replace it.
     * Android refuses an update that is signed with another key ("package conflicts with an existing package")
     * or that has a lower versionCode; here the person is told which one it is. Returns null when it is fine.
     */
    fun checkCompatible(context: Context, file: File): String? {
        val pm = context.packageManager
        val archive = pm.getPackageArchiveInfo(file.absolutePath, signingFlags())
            ?: return "The downloaded file is not a valid APK. Download it again."
        if (archive.packageName != context.packageName)
            return "The downloaded APK is for another app (${archive.packageName})."
        val installed = runCatching { pm.getPackageInfo(context.packageName, signingFlags()) }.getOrNull() ?: return null
        if (versionCodeOf(archive) < versionCodeOf(installed))
            return "The update (version code ${versionCodeOf(archive)}) is older than the installed app (${versionCodeOf(installed)}). Android does not allow a downgrade."
        val have = signerSet(installed)
        val want = signerSet(archive)
        if (have.isNotEmpty() && want.isNotEmpty() && have.intersect(want).isEmpty())
            return SIGNATURE_MISMATCH
        return null
    }

    const val SIGNATURE_MISMATCH =
        "This update is signed with a different key than the installed QuDroid, so Android refuses to update over it " +
        "(\"package conflicts with an existing package\"). This happens once when the older version was signed with another key. " +
        "A copy of the update was saved in your Downloads folder: uninstall QuDroid once, then install that file. " +
        "From then on every update installs over the old one without uninstalling."

    /** Puts a copy of the APK in the phone's Downloads, so it survives an uninstall of the app. */
    fun saveCopyToDownloads(context: Context, file: File): Boolean = runCatching {
        val name = "QuDroid-update-${file.nameWithoutExtension.removePrefix("qudroid-")}.apk"
        if (Build.VERSION.SDK_INT >= 29) {
            val cv = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "application/vnd.android.package-archive")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: return@runCatching false
            context.contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } } ?: return@runCatching false
            true
        } else {
            @Suppress("DEPRECATION")
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            dir.mkdirs()
            file.copyTo(File(dir, name), overwrite = true)
            true
        }
    }.getOrDefault(false)

    /**
     * Installs the downloaded APK over the running app through the system PackageInstaller (a session). Unlike a plain
     * ACTION_VIEW intent this reports WHY an install failed (see [InstallResultReceiver]) and restarts cleanly.
     * Returns true when the install was handed to Android; false (and [lastError]) when it could not be started.
     */
    fun installApk(context: Context, file: File): Boolean {
        lastError = null
        val app = context.applicationContext
        if (!file.exists()) { lastError = "The update file is gone. Download it again."; return false }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !app.packageManager.canRequestPackageInstalls()) {
            val permIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${app.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(permIntent) }
            lastError = "Allow QuDroid to install apps in the screen that opened, then tap Install again."
            return false
        }

        checkCompatible(app, file)?.let { problem ->
            if (problem == SIGNATURE_MISMATCH) saveCopyToDownloads(app, file)
            lastError = problem
            return false
        }

        return try {
            val installer = app.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(app.packageName)
                if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            val id = installer.createSession(params)
            installer.openSession(id).use { session ->
                file.inputStream().use { input ->
                    session.openWrite("base.apk", 0, file.length()).use { out ->
                        input.copyTo(out)
                        session.fsync(out)
                    }
                }
                val intent = Intent(app, InstallResultReceiver::class.java).apply {
                    action = ACTION_INSTALL_RESULT
                    putExtra(EXTRA_APK_PATH, file.absolutePath)
                }
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                session.commit(PendingIntent.getBroadcast(app, id, intent, flags).intentSender)
            }
            true
        } catch (t: Throwable) {
            // no session possible on this system: fall back to the system's own installer screen
            installWithViewIntent(app, file)
        }
    }

    private fun installWithViewIntent(context: Context, file: File): Boolean = try {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        context.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        true
    } catch (t: Throwable) {
        lastError = "Could not start the installer: ${t.message ?: t.javaClass.simpleName}"
        false
    }

    fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
        val group = digitGroups.coerceIn(0, units.size - 1)
        val value = bytes / Math.pow(1024.0, group.toDouble())
        return String.format(Locale.US, "%.1f %s", value, units[group])
    }
}

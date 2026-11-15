package io.github.abhishekkaisar.glassos

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.net.HttpURLConnection
import java.net.URL

/**
 * In-app updates from this fork's GitHub releases: finds the newest release, downloads its APK and hands it to the
 * system package installer. Android asks the user to confirm the install; nothing is installed silently.
 */
object Updater {
    /** The repository whose releases are installed. */
    const val REPO = "7ulez/glassos-tv"
    private const val LATEST = "https://api.github.com/repos/$REPO/releases/latest"
    private const val LIST = "https://api.github.com/repos/$REPO/releases?per_page=10"
    private const val DOWNLOAD_PREFIX = "https://github.com/$REPO/releases/download/"

    /** An automatic check (on start) runs at most this often, so pressing Home does not hit GitHub's rate limit. */
    const val AUTO_INTERVAL_MS = 6L * 60 * 60 * 1000

    private const val TIMEOUT_MS = 15_000
    private val main = Handler(Looper.getMainLooper())

    class Release(val tag: String, val version: String, val notes: String, val apkUrl: String, val apkSize: Long)

    sealed class Result {
        class Available(val release: Release) : Result()
        class UpToDate(val version: String) : Result()
        class Failed(val message: String) : Result()
    }

    /** Calls [done] on the main thread. [force] ignores the 6 hour limit (a manual check). */
    fun check(ctx: Context, force: Boolean, done: (Result?) -> Unit) {
        val app = ctx.applicationContext
        val prefs = Prefs(app)
        val now = System.currentTimeMillis()
        if (!force && now - prefs.updateCheckedAt < AUTO_INTERVAL_MS && now >= prefs.updateCheckedAt) {
            done(null)
            return
        }
        // Set before the request: two homes (this one and the overlay) must not both ask.
        prefs.updateCheckedAt = now
        Thread {
            val r = try { fetch() } catch (e: Exception) { Result.Failed(failure(e)) }
            // A failed check does not use up the interval.
            if (r is Result.Failed) prefs.updateCheckedAt = 0L
            if (r is Result.Available) prefs.updateAvailable = r.release.version else if (r is Result.UpToDate) prefs.updateAvailable = ""
            main.post { done(r) }
        }.apply { priority = Thread.MIN_PRIORITY }.start()
    }

    private fun failure(e: Exception) = when (e) {
        is java.net.UnknownHostException, is java.net.SocketTimeoutException, is java.net.ConnectException -> "No connection to GitHub"
        is RateLimited -> "GitHub is limiting requests. Try again later"
        else -> "Could not check for updates"
    }

    private class RateLimited : Exception()

    private fun get(url: String): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = TIMEOUT_MS
            c.readTimeout = TIMEOUT_MS
            c.setRequestProperty("Accept", "application/vnd.github.v3+json")
            c.setRequestProperty("User-Agent", "GlassOS/${BuildConfig.VERSION_NAME}")
            val code = c.responseCode
            if (code == 403 || code == 429) throw RateLimited()
            val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            return code to body
        } finally {
            c.disconnect()
        }
    }

    private fun fetch(): Result {
        val (code, body) = get(LATEST)
        var found: JSONObject? = null
        if (code == 200) found = JSONObject(body)
        else if (code == 404) {
            // "latest" skips pre-releases: take the newest published release of any kind.
            val (c2, b2) = get(LIST)
            if (c2 == 200) {
                val arr = JSONArray(b2)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    if (!o.optBoolean("draft", false)) { found = o; break }
                }
            } else if (c2 != 404) return Result.Failed("Could not check for updates")
        } else return Result.Failed("Could not check for updates")
        val json: JSONObject = found ?: return Result.UpToDate(BuildConfig.VERSION_NAME)

        val tag = json.optString("tag_name")
        val version = tag.removePrefix("v").removePrefix("V")
        if (version.isEmpty()) return Result.Failed("Could not check for updates")
        if (compareVersions(version, BuildConfig.VERSION_NAME) <= 0) return Result.UpToDate(BuildConfig.VERSION_NAME)

        val assets = json.optJSONArray("assets") ?: JSONArray()
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            val url = a.optString("browser_download_url")
            if (a.optString("name").endsWith(".apk", ignoreCase = true) && url.startsWith(DOWNLOAD_PREFIX)) {
                return Result.Available(Release(tag, version, json.optString("body").trim(), url, a.optLong("size", -1L)))
            }
        }
        return Result.Failed("Version $version has no APK attached")
    }

    /**
     * Semantic Versioning order: numbers first, and a version with a pre-release part ("-beta") is older than the
     * same version without one. Build metadata ("+...") is ignored. Returns <0, 0 or >0.
     */
    fun compareVersions(a: String, b: String): Int {
        fun split(v: String): Pair<List<Long>, List<String>> {
            val clean = v.trim().removePrefix("v").removePrefix("V").substringBefore('+')
            val core = clean.substringBefore('-')
            val pre = if (clean.contains('-')) clean.substringAfter('-').split('.') else emptyList()
            return core.split('.').map { it.filter(Char::isDigit).toLongOrNull() ?: 0L } to pre
        }
        val (na, pa) = split(a)
        val (nb, pb) = split(b)
        for (i in 0 until maxOf(na.size, nb.size)) {
            val c = (na.getOrElse(i) { 0L }).compareTo(nb.getOrElse(i) { 0L })
            if (c != 0) return c
        }
        if (pa.isEmpty() && pb.isEmpty()) return 0
        if (pa.isEmpty()) return 1
        if (pb.isEmpty()) return -1
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrNull(i) ?: return -1
            val y = pb.getOrNull(i) ?: return 1
            val xn = x.toLongOrNull()
            val yn = y.toLongOrNull()
            val c = when {
                xn != null && yn != null -> xn.compareTo(yn)
                xn != null -> -1
                yn != null -> 1
                else -> x.compareTo(y)
            }
            if (c != 0) return c
        }
        return 0
    }

    // ---------- Download ----------

    /** A running download; [cancel] stops it. */
    class Download {
        @Volatile var cancelled = false
        fun cancel() { cancelled = true }
    }

    fun updatesDir(ctx: Context): File = File(ctx.externalCacheDir ?: ctx.cacheDir, "updates").apply { mkdirs() }

    fun apkFile(ctx: Context, r: Release): File = File(updatesDir(ctx), "GlassOS-${r.version.replace(Regex("[^A-Za-z0-9._-]"), "_")}.apk")

    /**
     * Downloads [r] into the app's cache. [progress] gets 0..100 and [done] the file or an error message, both on
     * the main thread. A file downloaded earlier (the user went to allow installs and came back) is used as it is.
     */
    fun download(ctx: Context, r: Release, progress: (Int) -> Unit, done: (File?, String?) -> Unit): Download {
        val app = ctx.applicationContext
        val job = Download()
        Thread {
            var err: String? = null
            var out: File? = null
            try {
                val dir = updatesDir(app)
                val target = apkFile(app, r)
                // Older downloads are not needed any more.
                dir.listFiles()?.forEach { if (it != target) it.delete() }
                if (target.exists() && r.apkSize > 0 && target.length() == r.apkSize) {
                    main.post { progress(100) }
                    out = target
                } else {
                    val part = File(dir, target.name + ".part")
                    val c = URL(r.apkUrl).openConnection() as HttpURLConnection
                    try {
                        c.connectTimeout = TIMEOUT_MS
                        c.readTimeout = TIMEOUT_MS
                        c.setRequestProperty("User-Agent", "GlassOS/${BuildConfig.VERSION_NAME}")
                        if (c.responseCode != 200) throw java.io.IOException("HTTP ${c.responseCode}")
                        val total = if (c.contentLength > 0) c.contentLength.toLong() else r.apkSize
                        var read = 0L
                        var last = -1
                        c.inputStream.use { input ->
                            part.outputStream().use { o ->
                                val buf = ByteArray(32 * 1024)
                                while (true) {
                                    if (job.cancelled) break
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    o.write(buf, 0, n)
                                    read += n
                                    if (total > 0) {
                                        val p = (read * 100 / total).toInt().coerceIn(0, 100)
                                        if (p != last) { last = p; main.post { if (!job.cancelled) progress(p) } }
                                    }
                                }
                            }
                        }
                        if (job.cancelled) { part.delete(); return@Thread }
                        if (total > 0 && read != total) throw java.io.IOException("incomplete")
                    } finally {
                        c.disconnect()
                    }
                    target.delete()
                    if (!part.renameTo(target)) throw java.io.IOException("rename")
                    out = target
                }
                err = verify(app, out!!)
                if (err != null) { out?.delete(); out = null }
            } catch (e: Exception) {
                err = if (e is java.net.UnknownHostException || e is java.net.SocketTimeoutException) "No connection. Download stopped" else "Download failed"
                out = null
            }
            if (!job.cancelled) main.post { done(out, err) }
        }.apply { priority = Thread.MIN_PRIORITY }.start()
        return job
    }

    /**
     * Checks the file is a newer GlassOS signed by the same key as the installed one, so a wrong file does not
     * reach the installer (which would only say "App not installed"). Null when fine, else a message.
     */
    @Suppress("DEPRECATION")
    private fun verify(ctx: Context, f: File): String? {
        val pm = ctx.packageManager
        val info = pm.getPackageArchiveInfo(f.path, PackageManager.GET_SIGNATURES) ?: return "The downloaded file is not a valid app"
        if (info.packageName != ctx.packageName) return "The downloaded file is not GlassOS"
        val mine = try { pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNATURES).signatures } catch (e: Exception) { null }
        val theirs = info.signatures
        if (mine != null && theirs != null && mine.isNotEmpty() && theirs.isNotEmpty() && mine[0].toCharsString() != theirs[0].toCharsString()) {
            return "The update is signed with a different key than the installed GlassOS"
        }
        return null
    }

    // ---------- Install ----------

    /** False when the system still has to be told that GlassOS may install apps (Android 8 and later). */
    fun canInstall(ctx: Context): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ctx.packageManager.canRequestPackageInstalls()

    /** Opens the "Install unknown apps" screen for GlassOS. */
    fun openInstallSettings(ctx: Context) {
        val i = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { ctx.startActivity(i) } catch (e: Exception) {
            // Fire OS builds without that screen: the general Settings.
            try { ctx.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e2: Exception) { }
        }
    }

    /** Hands the APK to the package installer. Returns false when no installer answers. */
    fun install(ctx: Context, apk: File): Boolean {
        // From Android 7 a file:// URI in an Intent throws; the system installer reads a content:// URI instead.
        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) UpdateProvider.uriFor(ctx, apk) else Uri.fromFile(apk)
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return try { ctx.startActivity(i); true } catch (e: Exception) { false }
    }
}

/**
 * Serves the downloaded update to the package installer. A small provider of our own rather than AndroidX's
 * FileProvider, because this app has no AndroidX. Read-only, and only files in the updates folder.
 */
class UpdateProvider : ContentProvider() {
    override fun onCreate() = true

    private fun fileFor(uri: Uri): File {
        val ctx = context ?: throw FileNotFoundException()
        val name = uri.lastPathSegment ?: throw FileNotFoundException()
        val dir = Updater.updatesDir(ctx).canonicalFile
        val f = File(dir, name).canonicalFile
        if (f.parentFile != dir || !f.name.endsWith(".apk") || !f.isFile) throw FileNotFoundException()
        return f
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("read only")
        return ParcelFileDescriptor.open(fileFor(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        val f = try { fileFor(uri) } catch (e: FileNotFoundException) { return null }
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val row = cols.map { if (it == OpenableColumns.SIZE) f.length() else f.name }
        return MatrixCursor(cols).apply { addRow(row) }
    }

    override fun getType(uri: Uri) = "application/vnd.android.package-archive"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0

    companion object {
        fun uriFor(ctx: Context, f: File): Uri =
            Uri.Builder().scheme("content").authority("${ctx.packageName}.updates").appendPath(f.name).build()
    }
}

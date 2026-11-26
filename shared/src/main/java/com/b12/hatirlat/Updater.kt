package com.b12.hatirlat

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings as SysSettings
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.concurrent.thread

/**
 * Kendini güncelleme: GitHub'daki son sürüme bakar, daha yeniyse telefona B12-telefon.apk,
 * saate B12-saat.apk indirir, SHA-256'yı denetler ve Android'in yükleme ekranını açar.
 * İmza aynı olduğu için veriler korunur.
 */
object Updater {
    private const val API = "https://api.github.com/repos/Sefikak/B12/releases/latest"
    private const val EVERY = 6 * 60 * 60 * 1000L
    private val main = Handler(Looper.getMainLooper())

    data class Release(val version: String, val notes: String, val url: String, val size: Long, val sha256: String?)

    @Volatile private var busy = false
    private var waitingPermission: Release? = null
    private var waitingState: ((String) -> Unit)? = null

    private fun prefs(c: Context) = c.getSharedPreferences("update", Context.MODE_PRIVATE)
    fun isWatch(c: Context) = c.packageManager.hasSystemFeature(PackageManager.FEATURE_WATCH)
    private fun assetName(c: Context) = if (isWatch(c)) "B12-saat.apk" else "B12-telefon.apk"

    fun current(c: Context): String =
        c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: "0"

    /** "1.0.10" > "1.0.9" gibi sayısal karşılaştırma. */
    fun newer(a: String, b: String): Boolean {
        val x = a.trim().removePrefix("v").split('.').map { it.toIntOrNull() ?: 0 }
        val y = b.trim().removePrefix("v").split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }

    /** Daha önce bulunmuş ve hâlâ yeni olan sürüm (ağa çıkmadan). */
    fun available(c: Context): Release? {
        val p = prefs(c)
        val v = p.getString("version", null) ?: return null
        if (!newer(v, current(c))) return null
        return Release(v, p.getString("notes", "") ?: "", p.getString("url", "") ?: "", p.getLong("size", 0), p.getString("sha", null))
    }

    /** Telefondaki "kapat" düğmesi: bu sürüm için şeridi gizle. */
    fun dismissed(c: Context, r: Release) = prefs(c).getString("dismissed", null) == r.version
    fun dismiss(c: Context, r: Release) = prefs(c).edit().putString("dismissed", r.version).apply()

    /** GitHub'a sorar (force değilse en çok 6 saatte bir). Sonuç ana iş parçacığında gelir; yeni sürüm yoksa null. */
    fun check(c: Context, force: Boolean = false, done: (Release?, String?) -> Unit) {
        val ctx = c.applicationContext
        val p = prefs(ctx)
        if (!force && System.currentTimeMillis() - p.getLong("checkedAt", 0) < EVERY) {
            done(available(ctx), null); return
        }
        thread(name = "b12-update-check") {
            var err: String? = null
            try {
                val o = JSONObject(get(ctx, apiUrl(ctx)))
                val version = o.getString("tag_name").removePrefix("v")
                val assets = o.getJSONArray("assets")
                val want = assetName(ctx)
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name") != want) continue
                    val digest = a.optString("digest", "")
                    p.edit()
                        .putString("version", version)
                        .putString("notes", cleanNotes(o.optString("body", "")))
                        .putString("url", a.getString("browser_download_url"))
                        .putLong("size", a.optLong("size", 0))
                        .putString("sha", digest.removePrefix("sha256:").takeIf { digest.startsWith("sha256:") })
                        .apply()
                    break
                }
                p.edit().putLong("checkedAt", System.currentTimeMillis()).apply()
            } catch (e: Exception) {
                err = "Denetlenemedi: ${e.message ?: e.javaClass.simpleName}"
            }
            val r = available(ctx)
            main.post { done(r, err) }
        }
    }

    /**
     * İndir → denetle → yükle. [state] düğmede/şeritte gösterilecek kısa metni alır.
     * "Bilinmeyen uygulamaları yükle" izni yoksa önce ayarı açar; izin verilip geri dönülünce [resume] kaldığı yerden sürdürür.
     */
    fun start(a: Activity, r: Release, state: (String) -> Unit) {
        if (busy) return
        if (Build.VERSION.SDK_INT >= 26 && !a.packageManager.canRequestPackageInstalls()) {
            waitingPermission = r
            waitingState = state
            state("Önce izin ver")
            Toast.makeText(a, "B12'nin güncelleme yükleyebilmesi için izni aç, sonra geri dön", Toast.LENGTH_LONG).show()
            try {
                a.startActivity(Intent(SysSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${a.packageName}")))
            } catch (_: Exception) {
                a.startActivity(Intent(SysSettings.ACTION_SECURITY_SETTINGS))
            }
            return
        }
        busy = true
        val ctx = a.applicationContext
        state("İndiriliyor…")
        thread(name = "b12-update") {
            try {
                val f = download(ctx, r) { pct -> main.post { state("İndiriliyor %$pct") } }
                main.post { state("Yükleniyor…") }
                install(ctx, f)
            } catch (e: Exception) {
                busy = false
                main.post { state("Olmadı: ${e.message ?: e.javaClass.simpleName}") }
            }
        }
    }

    /** onResume'da çağrılır: izin ekranından dönüldüyse ve izin verildiyse indirmeye geç. */
    fun resume(a: Activity) {
        val r = waitingPermission ?: return
        val s = waitingState ?: return
        if (Build.VERSION.SDK_INT >= 26 && !a.packageManager.canRequestPackageInstalls()) return
        waitingPermission = null
        waitingState = null
        start(a, r, s)
    }

    internal fun finished(c: Context, msg: String?) {
        busy = false
        if (msg != null) main.post { Toast.makeText(c, msg, Toast.LENGTH_LONG).show() }
    }

    // ---- ayrıntılar

    /** Hata ayıklama derlemesinde testler için GitHub yerine başka bir adres kullanılabilir. */
    private fun apiUrl(c: Context): String {
        val debug = c.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        return if (debug) prefs(c).getString("api", null) ?: API else API
    }

    private fun open(url: String): HttpURLConnection {
        var u = URL(url)
        repeat(5) {
            val con = (u.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "B12-updater")
                setRequestProperty("Accept", if (url.contains("api.github.com")) "application/vnd.github+json" else "*/*")
            }
            val code = con.responseCode
            if (code in 300..399) {
                u = URL(u, con.getHeaderField("Location"))
                con.disconnect()
                return@repeat
            }
            if (code != 200) throw IllegalStateException("sunucu $code")
            return con
        }
        throw IllegalStateException("çok fazla yönlendirme")
    }

    private fun get(c: Context, url: String): String =
        open(url).let { con -> con.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }.also { con.disconnect() } }

    private fun download(c: Context, r: Release, progress: (Int) -> Unit): File {
        val dir = File(c.cacheDir, "update").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val f = File(dir, "B12-${r.version}.apk")
        val con = open(r.url)
        val total = con.contentLengthLong.takeIf { it > 0 } ?: r.size
        val md = MessageDigest.getInstance("SHA-256")
        var got = 0L
        var last = -1
        con.inputStream.use { input ->
            f.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    md.update(buf, 0, n)
                    got += n
                    if (total > 0) {
                        val pct = (got * 100 / total).toInt()
                        if (pct != last) { last = pct; progress(pct) }
                    }
                }
            }
        }
        con.disconnect()
        if (r.size > 0 && got != r.size) throw IllegalStateException("indirme yarım kaldı")
        val sha = md.digest().joinToString("") { "%02x".format(it) }
        if (r.sha256 != null && !sha.equals(r.sha256, ignoreCase = true)) {
            f.delete()
            throw IllegalStateException("dosya bozuk indi, tekrar dene")
        }
        return f
    }

    private fun install(c: Context, f: File) {
        val pi = c.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(c.packageName)
            setSize(f.length())
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            s.openWrite("base.apk", 0, f.length()).use { out ->
                f.inputStream().use { it.copyTo(out) }
                s.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(c, 7, Intent(c, UpdateReceiver::class.java).setPackage(c.packageName), flags)
            s.commit(pending.intentSender)
        }
    }

    /** Sürüm notlarındaki Markdown işaretlerini at, kısa tut. */
    private fun cleanNotes(md: String): String = md.lines()
        .map { it.trim().removePrefix("#").removePrefix("#").removePrefix("#").trim().replace("**", "") }
        .filter { it.isNotEmpty() && !it.startsWith("⬇") && !it.contains("](") }
        .joinToString("\n")
        .take(1200)
}

/** Yükleyicinin sonucu: kullanıcı onayı gerekiyorsa yükleme ekranını açar. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        when (val st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm != null) c.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                else Updater.finished(c, "Güncelleme ekranı açılamadı")
            }
            PackageInstaller.STATUS_SUCCESS -> Updater.finished(c, null)
            PackageInstaller.STATUS_FAILURE_ABORTED -> Updater.finished(c, "Güncelleme iptal edildi")
            else -> Updater.finished(c, "Güncelleme yüklenemedi ($st): ${i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""}")
        }
    }
}

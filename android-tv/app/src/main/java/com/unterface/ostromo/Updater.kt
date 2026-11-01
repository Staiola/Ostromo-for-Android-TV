// Updates from inside the app: looks for a newer release on GitHub (the same
// APK staiola.studio/android-tv hands out), downloads it and gives it to
// Android's installer, which asks the viewer to confirm. Android only takes
// it over the installed app if it is signed with the same key, so a release
// can only be replaced by another release.
//   api/update            -> { current, latest, newer, state, progress, error }
//   OstromoAndroid.update -> download and install the newer one
// The check is made once per app start, when the home screen first asks.
package com.unterface.ostromo

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

object Updater {
    const val ACTION_STATUS = "com.unterface.ostromo.INSTALL_STATUS"
    private const val APK = "ostromo-android-tv.apk"

    // idle, downloading, installing, permission (allow installs, then OK again),
    // cancelled (on the confirm screen, or at Play Protect's warning), failed
    @Volatile private var state = "idle"
    @Volatile private var progress = 0
    @Volatile private var error: String? = null
    @Volatile private var checked = false
    @Volatile private var latest: String? = null
    @Volatile private var apkUrl: String? = null

    // 0.10.1 (or v0.10.1) -> 1001, as versionCode is made in build.gradle.kts
    private fun code(version: String): Int? = version.removePrefix("v").split(".")
        .mapNotNull { it.toIntOrNull() }.takeIf { it.size == 3 }?.let { (a, b, c) -> a * 10000 + b * 100 + c }

    private fun newer() = latest?.let { code(it) }?.let { it > BuildConfig.VERSION_CODE } == true && apkUrl != null

    // called from Api, off the UI thread
    @Synchronized
    fun status(): JSONObject {
        if (!checked) {
            checked = true
            try {
                val release = JSONObject(Http.get(BuildConfig.UPDATE_URL, 8000, "application/vnd.github+json").text)
                val assets = release.optJSONArray("assets")
                val apk = (0 until (assets?.length() ?: 0)).map { assets!!.getJSONObject(it) }
                    .firstOrNull { it.optString("name") == APK }?.optString("browser_download_url")
                latest = release.optString("tag_name").removePrefix("v").ifEmpty { null }
                apkUrl = apk?.takeIf { it.startsWith("https://") || BuildConfig.DEBUG }
            } catch (e: Exception) {
                checked = false // offline: ask again next time the home screen does
            }
        }
        return JSONObject()
            .put("current", BuildConfig.VERSION_NAME)
            .put("latest", latest ?: JSONObject.NULL)
            .put("newer", newer())
            .put("state", state)
            .put("progress", progress)
            .put("error", error ?: JSONObject.NULL)
    }

    fun install(activity: Activity) {
        if (!newer() || state == "downloading") return
        // Android 8+ asks once whether Ostromo may install apps
        if (!activity.packageManager.canRequestPackageInstalls()) {
            state = "permission"
            try {
                activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
            } catch (e: Exception) {
                fail("ALLOW OSTROMO UNDER UNKNOWN SOURCES IN SETTINGS")
            }
            return
        }
        state = "downloading"
        progress = 0
        error = null
        Thread {
            val installer = activity.packageManager.packageInstaller
            var id = -1
            try {
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                    .apply { setAppPackageName(activity.packageName) }
                id = installer.createSession(params)
                installer.openSession(id).use { session ->
                    download(apkUrl!!) { input, length ->
                        session.openWrite(APK, 0, length).use { out ->
                            val buf = ByteArray(64 * 1024)
                            var done = 0L
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                if (length > 0) progress = (done * 100 / length).toInt()
                            }
                            session.fsync(out)
                        }
                    }
                    state = "installing"
                    progress = 100
                    // the installer answers to MainActivity (onNewIntent -> result)
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                        (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                    val back = PendingIntent.getActivity(activity, 0,
                        Intent(activity, MainActivity::class.java).setAction(ACTION_STATUS), flags)
                    session.commit(back.intentSender)
                }
            } catch (e: Exception) {
                if (id >= 0) try { installer.abandonSession(id) } catch (_: Exception) { }
                fail("DOWNLOAD FAILED")
            }
        }.start()
    }

    // what the installer said: show its confirm screen, or why it refused
    fun result(activity: Activity, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm != null) activity.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> state = "idle"
            PackageInstaller.STATUS_FAILURE_ABORTED -> state = "cancelled"
            else -> {
                val why = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
                // Play Protect warns about apps it doesn't know; "Don't install" ends here
                if ("VERIFICATION" in why.uppercase()) state = "cancelled"
                else fail(why.uppercase().take(60).ifEmpty { "INSTALL FAILED" })
            }
        }
    }

    private fun fail(message: String) {
        error = message
        state = "failed"
    }

    // GitHub sends the APK from another host: redirects followed by hand, as in Http
    private fun download(url: String, write: (java.io.InputStream, Long) -> Unit) {
        var u = URL(url)
        repeat(5) {
            val c = u.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 15000
            c.readTimeout = 30000
            try {
                val location = c.getHeaderField("Location")
                if (c.responseCode in 300..399 && location != null) {
                    u = URL(u, location)
                    return@repeat
                }
                if (c.responseCode != 200) throw IOException("$u -> HTTP ${c.responseCode}")
                c.inputStream.use { write(it, c.contentLengthLong) }
                return
            } finally {
                c.disconnect()
            }
        }
        throw IOException("$url -> too many redirects")
    }
}

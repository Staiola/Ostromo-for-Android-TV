// The Kotlin port of nostromo-service: the feeds whose hosts send no CORS
// headers, answered on the app's own origin at the paths the Mac preview's
// dev-server.js uses, so K.data in kit.js needs no Android branch.
//   api/aircraft?icao=&lat=&lon=, api/eds/<dataset>?<query>, api/nyiso?day=, api/cam?id=, api/abyss, api/teletext?page=
// WebView calls shouldInterceptRequest off the UI thread, so blocking is fine.
package com.unterface.ostromo

import android.net.Uri
import android.webkit.WebResourceResponse
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

object Api {

    fun handle(url: Uri): WebResourceResponse = try {
        val path = url.path.orEmpty()
        val body = when {
            path == "/api/aircraft" -> Aircraft.get(Aircraft.center(
                url.getQueryParameter("icao"), url.getQueryParameter("lat"), url.getQueryParameter("lon"))).toString()
            path == "/api/teletext" -> Teletext.getPage(url.getQueryParameter("page")).toString()
            path == "/api/cam" -> Cams.getStill(url.getQueryParameter("id").orEmpty()).toString()
            path == "/api/abyss" -> Cams.getClip().toString()
            path == "/api/nyiso" -> Energy.fetchNyiso(url.getQueryParameter("day"))
            path.startsWith("/api/eds/") -> Energy.fetchDataset(path.removePrefix("/api/eds/"), url.encodedQuery.orEmpty())
            else -> return json(404, "Not Found", JSONObject().put("error", "no such api: $path").toString())
        }
        json(200, "OK", body)
    } catch (e: Exception) {
        json(502, "Bad Gateway", JSONObject().put("error", e.message ?: e.toString()).toString())
    }

    private fun json(status: Int, reason: String, body: String) = WebResourceResponse(
        "application/json", "utf-8", status, reason,
        mapOf("Cache-Control" to "no-store"),
        ByteArrayInputStream(body.toByteArray())
    )
}

// https.get as the service uses it: one User-Agent, a timeout, redirects
// followed by hand (HttpURLConnection won't follow one between hosts' schemes)
object Http {
    private const val USER_AGENT = "Ostromo/0.9 (personal TV display)"

    class Result(val body: ByteArray, val modified: Long?) {
        val text get() = String(body, Charsets.UTF_8)
    }

    fun get(url: String, timeoutMs: Int, accept: String? = null): Result {
        var u = URL(url)
        repeat(5) {
            val c = u.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            c.setRequestProperty("User-Agent", USER_AGENT)
            if (accept != null) c.setRequestProperty("Accept", accept)
            try {
                val code = c.responseCode
                val location = c.getHeaderField("Location")
                if (code in 300..399 && location != null) {
                    u = URL(u, location)
                    return@repeat
                }
                if (code != 200) throw IOException("$url -> HTTP $code")
                val body = c.inputStream.use { it.readBytes() }
                return Result(body, c.getHeaderFieldDate("Last-Modified", 0).takeIf { it > 0 })
            } finally {
                c.disconnect()
            }
        }
        throw IOException("$url -> too many redirects")
    }
}

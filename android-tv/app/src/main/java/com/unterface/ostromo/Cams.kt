// Outpost cameras (nostromo-service/cams.js): public webcams at polar research
// stations, plus the latest deep-sea clip from the OOI camera at Axial
// Seamount. The image hosts send no CORS headers, so stills come through here
// as base64 JPEG (a data: URL can be drawn into the WebGL-fed canvas without
// tainting it).
package com.unterface.ostromo

import android.util.Base64
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Calendar
import java.util.TimeZone

object Cams {
    private const val USAP = "https://www.usap.gov"
    private const val USAP_IMAGE_DIR = "$USAP/videoclipsandmaps/SouthPoleWebcam/"
    private const val CAMHD = "https://rawdata.oceanobservatories.org/files/RS03ASHS/PN03B/CAMHDA301/"
    private const val STILL_CACHE_MS = 45 * 1000L
    private const val CLIP_CACHE_MS = 10 * 60 * 1000L
    private const val TIMEOUT_MS = 15000

    private class Cam(val usap: Pair<String, String>? = null, val url: String? = null, val page: String? = null, val pattern: Regex? = null)

    // id -> how to get the current image
    private val CAMS = mapOf(
        "sp-aro" to Cam(usap = "South Pole" to "aro"),
        "sp-dark" to Cam(usap = "South Pole" to "darksector"),
        "mcm-bore" to Cam(usap = "McMurdo" to "boreSite"),
        "oresund-east" to Cam(url = "https://www.oresundsbron.com/cams/pyloneast"),
        // the station page links the newest still, whose name carries its time
        "mawson" to Cam(page = "https://www.antarctica.gov.au/antarctic-operations/webcams/mawson/",
            pattern = Regex("https://images\\.antarctica\\.gov\\.au/webcams/mawson/[^\"']+\\.jpg")),
        "gemini-north" to Cam(url = "https://www.gemini.edu/sciops/schedules/obsStatus/currentstill_North.png"),
        "svalbard-zeppelin" to Cam(url = "https://data.npolar.no/_file/zeppelin/camera/Latest/zeppCam2.jpg")
    )

    private val stills = HashMap<String, Pair<Long, JSONObject>>() // id -> (at, result)
    private var clip: Pair<Long, JSONObject>? = null

    // USAP's page script asks this endpoint for the current file name:
    // "spole00022.jpg?=123,Live,Live" or "camera-offline.jpg,Disabled,Seasonal Downtime"
    private fun usapImageUrl(location: String, camera: String): String {
        val q = "/components/webcams.cfc?method=outputCurrentCamImage&cameraLocation=" +
            URLEncoder.encode(location, "UTF-8").replace("+", "%20") + "&camera=" + URLEncoder.encode(camera, "UTF-8")
        val parts = Http.get(USAP + q, TIMEOUT_MS).text.trim().split(",")
        val file = parts[0].substringBefore("?")
        if (file.isEmpty() || parts.getOrNull(1) != "Live") throw IllegalStateException("offline: ${parts.getOrNull(2) ?: "unknown"}")
        return USAP_IMAGE_DIR + file
    }

    fun getStill(id: String): JSONObject {
        val cam = CAMS[id] ?: throw IllegalArgumentException("unknown camera $id")
        val now = System.currentTimeMillis()
        synchronized(stills) {
            // drop stills nobody has asked for lately (the Svalbard one alone is 1.5 MB)
            stills.entries.removeAll { now - it.value.first > STILL_CACHE_MS }
            stills[id]?.let { return it.second }
        }
        val url = when {
            cam.usap != null -> usapImageUrl(cam.usap.first, cam.usap.second)
            cam.page != null -> cam.pattern!!.find(Http.get(cam.page, TIMEOUT_MS).text)?.value
                ?: throw IllegalStateException("no image on ${cam.page}")
            else -> cam.url!!
        }
        val r = Http.get(url, TIMEOUT_MS)
        val result = JSONObject()
            .put("id", id)
            .put("fetched", System.currentTimeMillis())
            .put("modified", r.modified ?: JSONObject.NULL)
            .put("jpeg", Base64.encodeToString(r.body, Base64.NO_WRAP))
        synchronized(stills) { stills[id] = System.currentTimeMillis() to result }
        return result
    }

    private fun hrefs(html: String, pattern: Regex) =
        Regex("href=\"([^\"]+)\"").findAll(html).map { it.groupValues[1] }.filter { pattern.containsMatchIn(it) }.toList()

    private fun last(path: String, pattern: Regex): List<String> {
        val list = hrefs(Http.get(CAMHD + path, TIMEOUT_MS).text, pattern).sorted()
        if (list.isEmpty()) throw IllegalStateException("nothing in $path")
        return list
    }

    // newest 14-minute CAMHD clip: walk year/month/day directories backwards
    @Synchronized
    fun getClip(): JSONObject {
        clip?.let { (at, result) -> if (System.currentTimeMillis() - at < CLIP_CACHE_MS) return result }
        val dir = Regex("^\\d{2}/$")
        val y = last("", Regex("^\\d{4}/$")).last()
        val m = last(y, dir).last()
        // today's folder may be empty early in the UTC day: try the last two
        var result: JSONObject? = null
        for (d in last(y + m, dir).takeLast(2).reversed()) {
            val f = try { last(y + m + d, Regex("\\.mp4$")).last() } catch (e: Exception) { continue }
            val t = Regex("(\\d{8})T(\\d{6})").find(f)?.groupValues
            val start = t?.let {
                Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                    clear()
                    set(it[1].take(4).toInt(), it[1].substring(4, 6).toInt() - 1, it[1].substring(6, 8).toInt(),
                        it[2].take(2).toInt(), it[2].substring(2, 4).toInt(), it[2].substring(4, 6).toInt())
                }.timeInMillis
            }
            result = JSONObject().put("url", CAMHD + y + m + d + f).put("start", start ?: JSONObject.NULL)
            break
        }
        result ?: throw IllegalStateException("no clips")
        clip = System.currentTimeMillis() to result
        return result
    }
}

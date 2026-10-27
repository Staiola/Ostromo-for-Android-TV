// Live traffic around an airport (Copenhagen unless the app asks for another),
// normalised for the radar
// (nostromo-service/aircraft.js; the same output, field for field).
// Positions: adsb.fi open data (personal, non-commercial use; credit adsb.fi).
// Routes: adsbdb, looked up once per callsign and kept in memory only.
package com.unterface.ostromo

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

object Aircraft {
    class Center(val icao: String, val lat: Double, val lon: Double)
    private val EKCH = Center("EKCH", 55.6180, 12.6508)
    private const val RADIUS_NM = 80
    private const val ROUTE_URL = "https://api.adsbdb.com/v0/callsign/"

    private const val CACHE_MS = 4000L          // answer repeated polls from memory
    private const val ROUTE_TTL_MS = 6 * 3600 * 1000L
    private const val ROUTE_RETRY_MS = 60 * 1000L   // after an error that wasn't "unknown callsign"
    private const val ROUTE_LOOKUPS_PER_POLL = 8

    private val snapshots = HashMap<String, Pair<Long, JSONObject>>() // icao -> (at, result)
    private class Route(val route: JSONObject?, val at: Long)
    private val routes = HashMap<String, Route>() // callsign -> route
    private val lookups = Executors.newFixedThreadPool(ROUTE_LOOKUPS_PER_POLL)

    // The airport the app asked for, or Kastrup when it didn't say or said nonsense
    fun center(icao: String?, lat: String?, lon: String?): Center {
        val la = lat?.toDoubleOrNull()
        val lo = lon?.toDoubleOrNull()
        if (icao == null || !Regex("^[A-Z]{4}$").matches(icao) || la == null || lo == null ||
            kotlin.math.abs(la) > 90 || kotlin.math.abs(lo) > 180) return EKCH
        return Center(icao, la, lo)
    }

    // the latest snapshot for that airport, at most one upstream call per
    // CACHE_MS; callers that arrive during a poll wait for it instead of
    // starting another
    @Synchronized
    fun get(c: Center): JSONObject {
        snapshots[c.icao]?.let { (at, result) -> if (System.currentTimeMillis() - at < CACHE_MS) return result }
        val result = poll(c)
        snapshots[c.icao] = System.currentTimeMillis() to result
        return result
    }

    // JS truthiness for the fields the normaliser reads with ||
    private fun JSONObject.num(key: String): Double? = (opt(key) as? Number)?.toDouble()?.takeIf { it != 0.0 }
    private fun JSONObject.str(key: String): String? = optString(key, "").takeIf { has(key) && !isNull(key) && it.isNotEmpty() }

    private fun distanceKm(c: Center, lat: Double, lon: Double): Double {
        val toRad = Math.PI / 180
        val dLat = (lat - c.lat) * toRad
        val dLon = (lon - c.lon) * toRad
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(c.lat * toRad) * cos(lat * toRad) * sin(dLon / 2) * sin(dLon / 2)
        return 6371 * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    private fun place(p: JSONObject?): JSONObject? {
        p ?: return null
        return JSONObject()
            .put("icao", p.str("icao_code"))
            .put("iata", p.str("iata_code"))
            .put("city", (p.str("municipality") ?: p.str("name") ?: "").uppercase())
    }

    private fun lookupRoute(cs: String) {
        var at = System.currentTimeMillis()
        val route = try {
            val fr = JSONObject(Http.get(ROUTE_URL + java.net.URLEncoder.encode(cs, "UTF-8"), 8000, "application/json").text)
                .optJSONObject("response")?.optJSONObject("flightroute")
            fr?.let { JSONObject().put("from", place(it.optJSONObject("origin")) ?: JSONObject.NULL).put("to", place(it.optJSONObject("destination")) ?: JSONObject.NULL) }
        } catch (e: Exception) {
            // unknown callsigns (404) are cached as "no route" so we don't ask
            // again; anything else (rate limit, timeout) is tried again soon
            if (e.message?.endsWith(" 404") != true) at -= ROUTE_TTL_MS - ROUTE_RETRY_MS
            null
        }
        synchronized(routes) { routes[cs] = Route(route, at) }
    }

    private fun classify(c: Center, a: JSONObject): String {
        val route = a.optJSONObject("route")
        if (route != null) {
            if (route.optJSONObject("from")?.optString("icao") == c.icao) return "DEP"
            if (route.optJSONObject("to")?.optString("icao") == c.icao) return "ARR"
            return "OVR"
        }
        if (a.getBoolean("ground")) return "GND" // parked or taxiing, direction unknown
        // no route known: low and close to the airport means it's ours
        val alt = a.opt("alt") as? Number
        if (alt != null && alt.toDouble() < 10000 && a.getDouble("distKm") < 45) return if (a.getDouble("vs") < 0) "ARR" else "DEP"
        return "OVR"
    }

    private fun normalise(c: Center, raw: JSONObject): JSONObject {
        val cs = raw.optString("flight", "").trim()
        val ground = raw.opt("alt_baro") == "ground"
        val altBaro = raw.opt("alt_baro") as? Number
        val lat = raw.getDouble("lat")
        val lon = raw.getDouble("lon")
        val hex = raw.optString("hex")
        val a = JSONObject()
            .put("id", hex)
            .put("cs", cs.ifEmpty { raw.str("r") ?: hex.uppercase() })
            .put("hasCallsign", cs.isNotEmpty())
            .put("reg", raw.str("r") ?: JSONObject.NULL)
            .put("type", raw.str("t") ?: JSONObject.NULL)
            .put("lat", lat)
            .put("lon", lon)
            .put("ground", ground)
            .put("alt", if (ground) 0 else altBaro ?: raw.num("alt_geom") ?: JSONObject.NULL)
            .put("gs", raw.num("gs") ?: 0)
            .put("hdg", (raw.opt("track") as? Number) ?: raw.num("true_heading") ?: 0)
            .put("vs", raw.num("baro_rate") ?: raw.num("geom_rate") ?: 0)
            .put("sqk", raw.str("squawk") ?: JSONObject.NULL)
            .put("mlat", (raw.optJSONArray("mlat")?.length() ?: 0) > 0)
            .put("seenPos", raw.num("seen_pos") ?: 0)
            .put("distKm", distanceKm(c, lat, lon))
        val cached = if (cs.isNotEmpty()) synchronized(routes) { routes[cs] } else null
        a.put("route", cached?.route ?: JSONObject.NULL)
        a.put("dir", classify(c, a))
        return a
    }

    private fun poll(c: Center): JSONObject {
        val url = "https://opendata.adsb.fi/api/v3/lat/${c.lat}/lon/${c.lon}/dist/$RADIUS_NM"
        val json = JSONObject(Http.get(url, 8000, "application/json").text)
        val ac = json.optJSONArray("ac") ?: JSONArray()
        val list = (0 until ac.length()).map { ac.getJSONObject(it) }.filter { r ->
            // drop airport vehicles: ADS-B categories C1-C3 are surface vehicles
            val vehicle = r.optString("category").startsWith("C") || r.optString("t") in setOf("TWR", "GND")
            r.opt("lat") is Number && r.opt("lon") is Number && !vehicle
        }

        // look up a few routes we haven't seen, nearest first, so the boards
        // fill before the overflights; the rest arrive on later polls (around
        // a busy airport like JFK that can take a few minutes)
        val now = System.currentTimeMillis()
        val missing = list.sortedBy { distanceKm(c, it.getDouble("lat"), it.getDouble("lon")) }
            .map { it.optString("flight", "").trim() }
            .filter { cs -> cs.isNotEmpty() && synchronized(routes) { routes[cs]?.let { now - it.at > ROUTE_TTL_MS } ?: true } }
            .distinct()
            .take(ROUTE_LOOKUPS_PER_POLL)
        lookups.invokeAll(missing.map { cs -> Callable { lookupRoute(cs) } })

        val t = (json.opt("now") as? Number)?.toDouble()?.takeIf { it != 0.0 } ?: now.toDouble()
        return JSONObject()
            .put("t", (t / 1000).roundToLong())
            .put("source", "adsb.fi")
            .put("routes", "adsbdb")
            .put("aircraft", JSONArray(list.map { normalise(c, it) }))
    }
}

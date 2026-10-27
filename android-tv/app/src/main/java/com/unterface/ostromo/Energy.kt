// Proxy for Energi Data Service and NYISO (nostromo-service/energy.js).
// Energi Data Service only answers requests without an Origin header, and
// NYISO sends no CORS headers, so the page can't call either.
// Only what the power screen uses is passed through.
package com.unterface.ostromo

object Energy {
    private const val BASE = "https://api.energidataservice.dk/dataset/"
    private val ALLOWED = setOf("DayAheadPrices", "DatahubPricelist")
    private const val CACHE_MS = 5 * 60 * 1000L
    private val cache = HashMap<String, Pair<Long, String>>() // url -> (at, body)

    // query: the raw query string, passed on as is
    fun fetchDataset(dataset: String, query: String): String {
        require(dataset in ALLOWED) { "dataset not allowed: $dataset" }
        val url = BASE + dataset + if (query.isNotEmpty()) "?$query" else ""
        synchronized(cache) {
            cache[url]?.let { (at, body) -> if (System.currentTimeMillis() - at < CACHE_MS) return body }
        }
        val body = Http.get(url, 10000, "application/json").text
        synchronized(cache) { cache[url] = System.currentTimeMillis() to body }
        return body
    }

    // NYISO day-ahead zonal prices for one day (YYYYMMDD, New York time), as
    // the CSV NYISO publishes, wrapped as { csv }
    fun fetchNyiso(day: String?): String {
        require(day != null && Regex("^\\d{8}$").matches(day)) { "bad day: $day" }
        val url = "https://mis.nyiso.com/public/csv/damlbmp/${day}damlbmp_zone.csv"
        synchronized(cache) {
            cache[url]?.let { (at, body) -> if (System.currentTimeMillis() - at < CACHE_MS) return body }
        }
        val body = org.json.JSONObject().put("csv", Http.get(url, 10000).text).toString()
        synchronized(cache) { cache[url] = System.currentTimeMillis() to body }
        return body
    }
}

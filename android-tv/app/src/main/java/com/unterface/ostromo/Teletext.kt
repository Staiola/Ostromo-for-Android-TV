// DR Tekst-TV from DR's old CGI (nostromo-service/teletext.js): the 40-column
// grid inside one <PRE>, page references as links, Danish letters as HTML
// entities. Text only.
package com.unterface.ostromo

import org.json.JSONArray
import org.json.JSONObject

object Teletext {
    private const val BASE = "https://www.dr.dk/cgi-bin/fttx1.exe/"
    private const val CACHE_MS = 30 * 1000L
    private val cache = HashMap<Int, Pair<Long, JSONObject>>() // page -> (at, result)

    private val ENTITIES = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "aelig" to "æ", "oslash" to "ø", "aring" to "å", "AElig" to "Æ", "Oslash" to "Ø", "Aring" to "Å",
        "auml" to "ä", "ouml" to "ö", "uuml" to "ü", "Auml" to "Ä", "Ouml" to "Ö", "Uuml" to "Ü",
        "eacute" to "é", "egrave" to "è", "Eacute" to "É", "aacute" to "á", "oacute" to "ó", "iacute" to "í",
        "ntilde" to "ñ", "ccedil" to "ç", "szlig" to "ß", "deg" to "°", "middot" to "·", "ndash" to "–", "mdash" to "—"
    )

    private fun decode(s: String) = Regex("&(#x?[0-9a-f]+|\\w+);", RegexOption.IGNORE_CASE).replace(s) { m ->
        val e = m.groupValues[1]
        when {
            e.startsWith("#x", ignoreCase = true) -> e.drop(2).toInt(16).toChar().toString()
            e.startsWith("#") -> e.drop(1).toInt().toChar().toString()
            else -> ENTITIES[e] ?: m.value
        }
    }

    // { page, lines: [..] (empty if the page doesn't exist), links: [page numbers] }
    fun getPage(param: String?): JSONObject {
        val page = param?.toIntOrNull()
        require(page != null && page in 100..899) { "no such page $param" }
        val now = System.currentTimeMillis()
        synchronized(cache) {
            cache[page]?.let { (at, result) -> if (now - at < CACHE_MS) return result }
        }
        // entities carry the Danish letters anyway
        val html = String(Http.get(BASE + page, 10000).body, Charsets.ISO_8859_1)
        val pre = Regex("<PRE>([\\s\\S]*?)</PRE>", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1).orEmpty()
        val links = Regex("<a[^>]*>(\\d{3})</a>", RegexOption.IGNORE_CASE).findAll(pre)
            .map { it.groupValues[1].toInt() }.distinct().toList()
        val lines = decode(pre.replace(Regex("<[^>]+>"), "")).replace("\r", "").split("\n").toMutableList()
        while (lines.isNotEmpty() && lines.last().isBlank()) lines.removeAt(lines.size - 1)
        val result = JSONObject().put("page", page).put("lines", JSONArray(lines)).put("links", JSONArray(links))
        synchronized(cache) {
            cache[page] = now to result
            // keep the cache small: pages nobody looked at for a while go
            cache.entries.removeAll { now - it.value.first > CACHE_MS * 4 }
        }
        return result
    }
}

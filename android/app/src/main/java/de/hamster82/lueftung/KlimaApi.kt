package de.hamster82.lueftung

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

// ------------------------------------------------------------------ Datenmodelle
data class ClimateReading(val valid: Boolean, val t: Double?, val rh: Double?, val ah: Double?, val src: String, val agoS: Long)

data class Rule(
    val sensor: String,
    val op: String,
    val value: Double,
    val hyst: Double,
    val cmd: String,
    val en: Boolean,
    val active: Boolean = false,
)

data class GroupAuto(val rule: Int, val suppressed: Boolean, val base: String)

data class Klima(
    val outdoor: ClimateReading,
    val indoor: ClimateReading,
    val sensor: String,
    val weatherErr: String,
    val lat: Double?,
    val lon: Double?,
    val place: String,
    val autoEn: Boolean,
    val holdMin: Int,
    val rules: List<Rule>,
    val groups: Map<String, GroupAuto>,
)

/** Ein 5-Minuten-Punkt. Werte null = nicht vorhanden. */
data class HistPoint(
    val epoch: Long,
    val outT: Double?,
    val outRH: Double?,
    val inT: Double?,
    val inRH: Double?,
    val stufe: Int?,
    val feuchte: Int?,
    val modus: Int?,
)

data class History(val step: Long, val now: Long, val points: List<HistPoint>)

data class Place(val name: String, val detail: String, val lat: Double, val lon: Double)

object Sensors {
    val all = listOf("aussenT", "aussenRH", "innenT", "innenRH", "absDiff")
    fun label(s: String) = when (s) {
        "aussenT" -> "Außentemperatur"
        "aussenRH" -> "Außenfeuchte"
        "innenT" -> "Innentemperatur"
        "innenRH" -> "Innenfeuchte"
        "absDiff" -> "Außenluft trockener als innen"
        else -> s
    }
    fun unit(s: String) = when (s) {
        "aussenT", "innenT" -> "°C"
        "aussenRH", "innenRH" -> "%"
        "absDiff" -> "g/m³"
        else -> ""
    }
    fun needsIndoor(s: String) = s == "innenT" || s == "innenRH" || s == "absDiff"
}

// ------------------------------------------------------------------ Aufrufe an die Brücke
private fun JSONObject.optDoubleOrNull(k: String): Double? = if (has(k) && !isNull(k)) optDouble(k).takeIf { !it.isNaN() } else null

private fun reading(o: JSONObject?): ClimateReading {
    if (o == null) return ClimateReading(false, null, null, null, "", -1)
    return ClimateReading(
        o.optBoolean("valid"), o.optDoubleOrNull("t"), o.optDoubleOrNull("rh"), o.optDoubleOrNull("ah"),
        o.optString("src"), o.optLong("agoS", -1)
    )
}

private fun ruleToJson(r: Rule) = JSONObject()
    .put("sensor", r.sensor).put("op", r.op).put("value", r.value).put("hyst", r.hyst).put("cmd", r.cmd).put("en", r.en)

suspend fun Api.klima(): Klima {
    val o = JSONObject(request("GET", "/api/klima"))
    val c = o.optJSONObject("cfg") ?: JSONObject()
    val ra = o.optJSONArray("rules") ?: JSONArray()
    val rules = List(ra.length()) {
        val r = ra.getJSONObject(it)
        Rule(
            r.optString("sensor"), r.optString("op", ">"), r.optDouble("value", 0.0), r.optDouble("hyst", 1.0),
            r.optString("cmd"), r.optBoolean("en", true), r.optBoolean("active")
        )
    }
    val g = o.optJSONObject("groups") ?: JSONObject()
    val groups = mutableMapOf<String, GroupAuto>()
    for (k in g.keys()) {
        val x = g.getJSONObject(k)
        groups[k] = GroupAuto(x.optInt("rule", -1), x.optBoolean("suppressed"), x.optString("base"))
    }
    return Klima(
        outdoor = reading(o.optJSONObject("outdoor")),
        indoor = reading(o.optJSONObject("indoor")),
        sensor = o.optString("sensor"),
        weatherErr = o.optString("weatherErr"),
        lat = c.optDoubleOrNull("lat"),
        lon = c.optDoubleOrNull("lon"),
        place = c.optString("place"),
        autoEn = c.optBoolean("autoEn", true),
        holdMin = c.optInt("holdMin", 10),
        rules = rules,
        groups = groups,
    )
}

suspend fun Api.saveKlimaConfig(place: Place?, autoEn: Boolean?, holdMin: Int?) {
    val o = JSONObject()
    if (place != null) o.put("lat", place.lat).put("lon", place.lon).put("place", place.name)
    if (autoEn != null) o.put("autoEn", autoEn)
    if (holdMin != null) o.put("holdMin", holdMin)
    post("/api/klima/config", o)
}

suspend fun Api.saveRules(list: List<Rule>) {
    val a = JSONArray()
    list.forEach { a.put(ruleToJson(it)) }
    request("POST", "/api/klima/rules", a.toString())
}

suspend fun Api.refreshWeather(): String {
    val o = JSONObject(post("/api/klima/refresh"))
    return if (o.optBoolean("ok")) "" else o.optString("error", "Abruf fehlgeschlagen")
}

suspend fun Api.history(): History {
    val o = JSONObject(request("GET", "/api/history", readTimeout = 10000))
    val a = o.optJSONArray("points") ?: JSONArray()
    fun JSONArray.d(i: Int): Double? = if (isNull(i)) null else optDouble(i).takeIf { !it.isNaN() }
    fun JSONArray.n(i: Int): Int? = if (isNull(i)) null else optInt(i)
    val pts = List(a.length()) {
        val p = a.getJSONArray(it)
        HistPoint(p.getLong(0), p.d(1), p.d(2), p.d(3), p.d(4), p.n(5), p.n(6), p.n(7))
    }
    return History(o.optLong("step", 300), o.optLong("now"), pts)
}

// ------------------------------------------------------------------ Ortssuche (direkt vom Handy)
suspend fun searchPlaces(query: String): List<Place> = withContext(Dispatchers.IO) {
    val q = URLEncoder.encode(query.trim(), "UTF-8")
    val url = URL("https://geocoding-api.open-meteo.com/v1/search?name=$q&count=6&language=de&format=json")
    val conn = url.openConnection() as HttpURLConnection
    try {
        conn.connectTimeout = 6000
        conn.readTimeout = 8000
        if (conn.responseCode != 200) throw ApiException("Ortssuche fehlgeschlagen (${conn.responseCode})")
        val o = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        val a = o.optJSONArray("results") ?: return@withContext emptyList()
        List(a.length()) {
            val r = a.getJSONObject(it)
            val detail = listOf(r.optString("admin1"), r.optString("country")).filter { s -> s.isNotBlank() }.joinToString(", ")
            Place(r.optString("name"), detail, r.optDouble("latitude"), r.optDouble("longitude"))
        }
    } catch (e: ApiException) {
        throw e
    } catch (e: Exception) {
        throw ApiException("Ortssuche nicht erreichbar – Internet am Handy prüfen")
    } finally {
        conn.disconnect()
    }
}

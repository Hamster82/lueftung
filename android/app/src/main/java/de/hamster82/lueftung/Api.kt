package de.hamster82.lueftung

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class ApiException(msg: String) : Exception(msg)

// ------------------------------------------------------------------ Datenmodelle
data class GroupState(val key: String?, val label: String, val src: String, val time: String)

data class Status(
    val fw: String,
    val ap: Boolean,
    val ip: String,
    val rssi: Int,
    val timeValid: Boolean,
    val time: String,
    val baud: Int,
    val format: String,
    val bytes: Long,
    val frames: Long,
    val unique: Int,
    val lastRxAgoMs: Long,
    val stufe: GroupState,
    val feuchte: GroupState,
    val modus: GroupState,
    val filterHours: Double,
    val filterIntervalH: Int,
    val filterDue: Boolean,
    val boostActive: Boolean,
    val boostRemainingS: Int,
    val boostKey: String,
    val learnActive: Boolean,
    val variant: String = "",
    val otaState: String = "",
    val otaError: String = "",
)

data class Cmd(val key: String, val label: String, val group: String, val frames: List<String>) {
    val learned get() = frames.isNotEmpty()
}

data class Schedule(val days: Int, val h: Int, val m: Int, val cmd: String, val en: Boolean)

data class Frame(val seq: Long, val t: Long, val hex: String, val isNew: Boolean, val tx: Boolean, val known: String?)

data class Candidate(val hex: String, val count: Int, val duplicateOf: String?)

data class LearnState(val active: Boolean, val key: String, val remainingS: Int, val candidates: List<Candidate>)

data class DeviceConfig(
    val ssid: String,
    val hostname: String,
    val baud: Int,
    val format: String,
    val gapChars: Int,
    val repeat: Int,
    val sendGapMs: Int,
    val filterIntervalH: Int,
)

data class BaudResult(val baud: Int, val minPulseUs: Int, val samples: Int, val hint: String)

// ------------------------------------------------------------------ HTTP
class Api(private val hostProvider: () -> String) {

    fun baseUrl(): String {
        val h = hostProvider().trim().removePrefix("http://").trimEnd('/')
        if (h.isEmpty()) throw ApiException("Keine Geräte-Adresse eingestellt")
        return "http://$h"
    }

    internal suspend fun request(method: String, path: String, body: String? = null, readTimeout: Int = 6000): String =
        withContext(Dispatchers.IO) {
            val conn = try {
                URL(baseUrl() + path).openConnection() as HttpURLConnection
            } catch (e: ApiException) {
                throw e
            } catch (e: Exception) {
                throw ApiException("Ungültige Adresse")
            }
            try {
                conn.requestMethod = method
                conn.connectTimeout = 4000
                conn.readTimeout = readTimeout
                if (body != null) {
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.outputStream.use { it.write(body.toByteArray()) }
                }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
                if (code !in 200..299) {
                    val msg = runCatching { JSONObject(text).optString("error") }.getOrNull().orEmpty()
                    throw ApiException(msg.ifEmpty { "HTTP-Fehler $code" })
                }
                text
            } catch (e: ApiException) {
                throw e
            } catch (e: Exception) {
                throw ApiException("Gerät nicht erreichbar (${e.javaClass.simpleName})")
            } finally {
                conn.disconnect()
            }
        }

    internal suspend fun post(path: String, body: JSONObject = JSONObject()) = request("POST", path, body.toString())

    private fun group(o: JSONObject?): GroupState {
        if (o == null || o.isNull("key")) return GroupState(null, "unbekannt", "", "")
        return GroupState(o.optString("key"), o.optString("label"), o.optString("src"), o.optString("time"))
    }

    suspend fun status(): Status {
        val o = JSONObject(request("GET", "/api/status"))
        val bus = o.getJSONObject("bus")
        val st = o.getJSONObject("state")
        val fl = o.getJSONObject("filter")
        val b = o.getJSONObject("boost")
        val l = o.getJSONObject("learn")
        return Status(
            fw = o.optString("fw"),
            ap = o.optBoolean("ap"),
            ip = o.optString("ip"),
            rssi = o.optInt("rssi"),
            timeValid = o.optBoolean("timeValid"),
            time = o.optString("time"),
            baud = bus.optInt("baud"),
            format = bus.optString("format"),
            bytes = bus.optLong("bytes"),
            frames = bus.optLong("frames"),
            unique = bus.optInt("unique"),
            lastRxAgoMs = bus.optLong("lastRxAgoMs", -1),
            stufe = group(st.optJSONObject("stufe")),
            feuchte = group(st.optJSONObject("feuchte")),
            modus = group(st.optJSONObject("modus")),
            filterHours = fl.optDouble("hours"),
            filterIntervalH = fl.optInt("intervalH", 2160),
            filterDue = fl.optBoolean("due"),
            boostActive = b.optBoolean("active"),
            boostRemainingS = b.optInt("remainingS"),
            boostKey = b.optString("key"),
            learnActive = l.optBoolean("active"),
            variant = o.optString("variant"),
            otaState = o.optJSONObject("ota")?.optString("state").orEmpty(),
            otaError = o.optJSONObject("ota")?.optString("error").orEmpty(),
        )
    }

    suspend fun commands(): List<Cmd> {
        val o = JSONObject(request("GET", "/api/commands"))
        val list = mutableListOf<Cmd>()
        for (k in o.keys()) {
            val c = o.getJSONObject(k)
            val fr = c.optJSONArray("frames") ?: JSONArray()
            list += Cmd(k, c.optString("label", k), c.optString("group", "sonst"), List(fr.length()) { fr.getString(it) })
        }
        return list
    }

    suspend fun sendCmd(key: String, minutes: Int = 0) {
        post("/api/cmd", JSONObject().put("key", key).put("minutes", minutes))
    }

    suspend fun cancelBoost() { post("/api/boost/cancel") }

    suspend fun saveCommand(key: String, label: String, frames: List<String>?) {
        val o = JSONObject().put("key", key).put("label", label)
        if (frames != null) o.put("frames", JSONArray(frames))
        post("/api/commands", o)
    }

    suspend fun deleteCommand(key: String) { post("/api/commands/delete", JSONObject().put("key", key)) }

    suspend fun sendFrames(frames: List<String>) { post("/api/send", JSONObject().put("frames", JSONArray(frames))) }

    suspend fun learnStart(key: String, seconds: Int) {
        post("/api/learn/start", JSONObject().put("key", key).put("seconds", seconds))
    }

    suspend fun learnStop() { post("/api/learn/stop") }

    suspend fun learn(): LearnState {
        val o = JSONObject(request("GET", "/api/learn"))
        val a = o.optJSONArray("candidates") ?: JSONArray()
        val c = List(a.length()) {
            val x = a.getJSONObject(it)
            Candidate(x.optString("hex"), x.optInt("count"), x.optString("duplicateOf").ifEmpty { null })
        }
        return LearnState(o.optBoolean("active"), o.optString("key"), o.optInt("remainingS"), c)
    }

    suspend fun schedules(): List<Schedule> {
        val a = JSONArray(request("GET", "/api/schedules"))
        return List(a.length()) {
            val o = a.getJSONObject(it)
            Schedule(o.optInt("days", 127), o.optInt("h"), o.optInt("m"), o.optString("cmd"), o.optBoolean("en", true))
        }
    }

    suspend fun saveSchedules(list: List<Schedule>) {
        val a = JSONArray()
        list.forEach {
            a.put(JSONObject().put("days", it.days).put("h", it.h).put("m", it.m).put("cmd", it.cmd).put("en", it.en))
        }
        request("POST", "/api/schedules", a.toString())
    }

    suspend fun frames(since: Long, onlyNew: Boolean): Pair<Long, List<Frame>> {
        val o = JSONObject(request("GET", "/api/frames?since=$since" + if (onlyNew) "&new=1" else ""))
        val a = o.optJSONArray("frames") ?: JSONArray()
        val list = List(a.length()) {
            val x = a.getJSONObject(it)
            Frame(
                x.optLong("seq"), x.optLong("t"), x.optString("hex"), x.optBoolean("new"), x.optBoolean("tx"),
                x.optString("known").ifEmpty { null }
            )
        }
        return o.optLong("seq") to list
    }

    suspend fun clearUnique() { post("/api/unique/clear") }

    suspend fun config(): DeviceConfig {
        val o = JSONObject(request("GET", "/api/config"))
        return DeviceConfig(
            o.optString("ssid"), o.optString("hostname"), o.optInt("baud"), o.optString("format"),
            o.optInt("gapChars"), o.optInt("repeat"), o.optInt("sendGapMs"), o.optInt("filterIntervalH")
        )
    }

    suspend fun saveConfig(c: DeviceConfig) {
        post(
            "/api/config", JSONObject()
                .put("baud", c.baud).put("format", c.format).put("gapChars", c.gapChars)
                .put("repeat", c.repeat).put("sendGapMs", c.sendGapMs).put("filterIntervalH", c.filterIntervalH)
        )
    }

    suspend fun detectBaud(): BaudResult {
        val o = JSONObject(request("GET", "/api/baud/detect", readTimeout = 12000))
        return BaudResult(o.optInt("baud"), o.optInt("minPulseUs"), o.optInt("samples"), o.optString("hint"))
    }

    suspend fun resetFilter() { post("/api/filter/reset") }

    suspend fun restart() { post("/api/restart") }

    suspend fun onlineUpdate() { post("/api/ota/github") }
}

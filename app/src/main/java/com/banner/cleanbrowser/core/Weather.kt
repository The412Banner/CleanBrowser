package com.banner.cleanbrowser.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.banner.cleanbrowser.App
import com.banner.cleanbrowser.net.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale

/** Weather from Open-Meteo (free, no account). Data: Open-Meteo.com, CC BY 4.0. */
object Weather {
    data class Now(
        val temp: Int, val high: Int, val low: Int, val code: Int, val isDay: Boolean,
        val unit: String, val place: String, val time: Long,
    )

    data class Place(val name: String, val detail: String, val lat: Double, val lon: Double)

    var now by mutableStateOf<Now?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    private const val MAX_AGE = 30 * 60 * 1000L

    fun fahrenheit(s: Settings): Boolean = when (s.weatherUnit) {
        "f" -> true
        "c" -> false
        else -> Locale.getDefault().country in setOf("US", "LR", "MM", "BS", "BZ", "KY", "PW")
    }

    /** Coordinates are rounded to 0.1° (about 10 km) before they leave the phone. */
    fun round(v: Double): String = String.format(Locale.US, "%.1f", v)

    fun loadCached() {
        if (now != null) return
        val raw = App.prefs.getString("weatherCache")
        if (raw.isNotEmpty()) now = runCatching { parseCache(JSONObject(raw)) }.getOrNull()
    }

    suspend fun refresh(force: Boolean = false) {
        val s = App.prefs.state
        if (s.weatherLat.isEmpty() || s.weatherLon.isEmpty()) return
        loadCached()
        val unit = if (fahrenheit(s)) "F" else "C"
        val cur = now
        if (!force && cur != null && cur.unit == unit && cur.place == s.weatherCity && System.currentTimeMillis() - cur.time < MAX_AGE) return
        val url = "https://api.open-meteo.com/v1/forecast?latitude=${s.weatherLat}&longitude=${s.weatherLon}" +
            "&current=temperature_2m,weather_code,is_day&daily=temperature_2m_max,temperature_2m_min&forecast_days=1&timezone=auto" +
            (if (unit == "F") "&temperature_unit=fahrenheit" else "")
        val result = withContext(Dispatchers.IO) {
            runCatching {
                Net.client.newCall(Request.Builder().url(url).header("User-Agent", "CleanBrowser").build()).execute().use { r ->
                    if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code}")
                    val o = JSONObject(r.body!!.string())
                    val c = o.getJSONObject("current")
                    val d = o.getJSONObject("daily")
                    Now(
                        temp = Math.round(c.getDouble("temperature_2m")).toInt(),
                        high = Math.round(d.getJSONArray("temperature_2m_max").getDouble(0)).toInt(),
                        low = Math.round(d.getJSONArray("temperature_2m_min").getDouble(0)).toInt(),
                        code = c.getInt("weather_code"),
                        isDay = c.optInt("is_day", 1) == 1,
                        unit = unit,
                        place = s.weatherCity,
                        time = System.currentTimeMillis(),
                    )
                }
            }
        }
        result.onSuccess { n ->
            now = n
            error = null
            App.prefs.putString("weatherCache", toCache(n).toString())
        }.onFailure { error = "Weather unavailable" }
    }

    suspend fun search(name: String): List<Place> = withContext(Dispatchers.IO) {
        runCatching {
            val q = URLEncoder.encode(name.trim(), "UTF-8")
            val lang = Locale.getDefault().language
            val url = "https://geocoding-api.open-meteo.com/v1/search?name=$q&count=8&language=$lang&format=json"
            Net.client.newCall(Request.Builder().url(url).header("User-Agent", "CleanBrowser").build()).execute().use { r ->
                val arr = JSONObject(r.body!!.string()).optJSONArray("results") ?: return@use emptyList()
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    Place(
                        o.getString("name"),
                        listOf(o.optString("admin1"), o.optString("country")).filter { it.isNotBlank() }.joinToString(", "),
                        o.getDouble("latitude"), o.getDouble("longitude"),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun clearCache() {
        now = null
        App.prefs.putString("weatherCache", "")
    }

    fun describe(code: Int): String = when (code) {
        0 -> "Clear"
        1 -> "Mostly clear"
        2 -> "Partly cloudy"
        3 -> "Cloudy"
        45, 48 -> "Fog"
        51, 53, 55 -> "Drizzle"
        56, 57 -> "Freezing drizzle"
        61, 63, 65 -> "Rain"
        66, 67 -> "Freezing rain"
        71, 73, 75, 77 -> "Snow"
        80, 81, 82 -> "Showers"
        85, 86 -> "Snow showers"
        95 -> "Thunderstorm"
        96, 99 -> "Thunderstorm, hail"
        else -> "—"
    }

    /** A simple symbol for the condition. */
    fun symbol(code: Int, day: Boolean): String = when (code) {
        0 -> if (day) "☀" else "☾"
        1, 2 -> if (day) "⛅" else "☁"
        3 -> "☁"
        45, 48 -> "≋"
        in 51..67, in 80..82 -> "☂"
        in 71..77, 85, 86 -> "❄"
        in 95..99 -> "⚡"
        else -> "·"
    }

    private fun toCache(n: Now) = JSONObject().put("t", n.temp).put("h", n.high).put("l", n.low).put("c", n.code)
        .put("d", n.isDay).put("u", n.unit).put("p", n.place).put("time", n.time)

    private fun parseCache(o: JSONObject) = Now(
        o.getInt("t"), o.getInt("h"), o.getInt("l"), o.getInt("c"), o.getBoolean("d"), o.getString("u"), o.getString("p"), o.getLong("time"),
    )
}

package com.haloapps.halobot

import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.concurrent.thread

private val mainHandler = Handler(Looper.getMainLooper())

private fun httpGet(url: String): String {
    val c = URL(url).openConnection() as HttpURLConnection
    c.connectTimeout = 8000; c.readTimeout = 8000
    c.setRequestProperty("User-Agent", "HaloBot/1.0 (Android)")
    return try { c.inputStream.bufferedReader().readText() } finally { c.disconnect() }
}

/** Free weather from Open-Meteo (no API key needed). */
object Weather {
    data class Now(val tempC: Double, val code: Int, val city: String, val high: Double?, val low: Double?)

    fun fetch(city: String, cb: (Now?) -> Unit) {
        thread {
            val r = try {
                val geo = JSONObject(httpGet("https://geocoding-api.open-meteo.com/v1/search?count=1&name=" + URLEncoder.encode(city, "UTF-8")))
                val res = geo.optJSONArray("results")?.optJSONObject(0)
                if (res == null) null else {
                    val lat = res.getDouble("latitude"); val lon = res.getDouble("longitude")
                    val name = res.optString("name", city)
                    val w = JSONObject(httpGet("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,weather_code&daily=temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=1"))
                    val cur = w.getJSONObject("current")
                    val daily = w.optJSONObject("daily")
                    Now(
                        cur.getDouble("temperature_2m"), cur.getInt("weather_code"), name,
                        daily?.optJSONArray("temperature_2m_max")?.optDouble(0),
                        daily?.optJSONArray("temperature_2m_min")?.optDouble(0),
                    )
                }
            } catch (e: Exception) { null }
            mainHandler.post { cb(r) }
        }
    }

    /** WMO weather code -> words, face and scene. */
    fun describe(code: Int): Triple<String, String, Overlay> = when (code) {
        0 -> Triple("clear and sunny", "happy", Overlay.SUN)
        1, 2 -> Triple("partly cloudy", "neutral", Overlay.CLOUD)
        3 -> Triple("cloudy", "bored", Overlay.CLOUD)
        45, 48 -> Triple("foggy", "confused", Overlay.CLOUD)
        in 51..57 -> Triple("drizzly", "sad", Overlay.RAIN)
        in 61..67, in 80..82 -> Triple("rainy", "sad", Overlay.RAIN)
        in 71..77, 85, 86 -> Triple("snowy", "cold", Overlay.SNOW)
        in 95..99 -> Triple("stormy", "scared", Overlay.STORM)
        else -> Triple("a bit mysterious", "curious", Overlay.NONE)
    }
}

/** Optional cloud brain: Claude API, used for open-ended chat when the user adds a key. */
class AiChat(private val prefs: Prefs) {
    private val history = ArrayList<Pair<String, String>>()
    val enabled: Boolean get() = prefs.apiKey.isNotBlank()

    fun ask(text: String, robotName: String, userName: String, cb: (String?) -> Unit) {
        thread {
            val reply = try { call(text, robotName, userName) } catch (e: Exception) { null }
            mainHandler.post { cb(reply) }
        }
    }

    @Synchronized
    private fun call(text: String, robotName: String, userName: String): String? {
        history.add("user" to text)
        while (history.size > 16) history.removeAt(0)
        while (history.isNotEmpty() && history[0].first != "user") history.removeAt(0)
        val msgs = JSONArray()
        for ((role, content) in history) msgs.put(JSONObject().put("role", role).put("content", content))
        val who = if (userName.isBlank()) "your human friend" else userName
        val system = "You are $robotName, a tiny expressive desk robot pet that lives inside a phone. You can see $who through the camera, " +
            "make faces, and wiggle using vibration. You are playful, curious, kind and a little cheeky, like a cute pet. " +
            "Reply in one or two short spoken sentences, no emojis, no markdown, no lists. " +
            "Begin every reply with exactly one emotion tag from: [happy] [joy] [laugh] [excited] [love] [surprised] [sad] [angry] " +
            "[curious] [confused] [shy] [thinking] [proud] [cool] [sleepy] [scared] [smug] [wink]."
        val body = JSONObject()
            .put("model", prefs.aiModel.ifBlank { "claude-haiku-5-5" })
            .put("max_tokens", 300)
            .put("system", system)
            .put("messages", msgs)
        val c = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 10000; c.readTimeout = 25000
            c.doOutput = true
            c.setRequestProperty("content-type", "application/json")
            c.setRequestProperty("x-api-key", prefs.apiKey)
            c.setRequestProperty("anthropic-version", "2023-06-01")
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            if (c.responseCode !in 200..299) { history.removeAt(history.size - 1); return null }
            val resp = JSONObject(c.inputStream.bufferedReader().readText())
            val arr = resp.getJSONArray("content")
            val sb = StringBuilder()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (o.optString("type") == "text") sb.append(o.optString("text"))
            }
            val out = sb.toString().trim()
            if (out.isEmpty()) { history.removeAt(history.size - 1); return null }
            history.add("assistant" to out)
            return out
        } finally { c.disconnect() }
    }

    fun reset() { synchronized(this) { history.clear() } }
}

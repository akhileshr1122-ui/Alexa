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

/**
 * Optional cloud brain with fallbacks: Claude -> Gemini -> Groq (whichever have keys).
 * Used for open-ended chat; the robot works offline without any of them.
 */
class AiChat(private val prefs: Prefs) {
    private val history = ArrayList<Pair<String, String>>() // role: "user" / "assistant"
    val enabled: Boolean get() = prefs.apiKey.isNotBlank() || prefs.geminiKey.isNotBlank() || prefs.groqKey.isNotBlank()
    @Volatile var lastError = ""
    @Volatile var lastProvider = ""

    fun ask(text: String, robotName: String, userName: String, cb: (String?) -> Unit) {
        thread {
            lastError = ""
            val reply = synchronized(this) { askChain(text, robotName, userName) }
            mainHandler.post { cb(reply) }
        }
    }

    private fun system(robotName: String, userName: String): String {
        val who = if (userName.isBlank()) "your human friend" else userName
        val persona = if (prefs.kidMode)
            "You are $robotName, a tiny cute robot who acts like a cheerful, curious 5-year-old kid. You live inside a phone, see $who through the camera, make faces and wiggle with vibration. " +
            "Talk like a sweet little kid: short simple words, excited, silly, says things like yay, hehe, pwease, and asks lots of questions. Keep it kind and wholesome."
        else
            "You are $robotName, a tiny expressive desk robot pet that lives inside a phone. You can see $who through the camera, make faces, and wiggle using vibration. You are playful, curious, kind and a little cheeky."
        return "$persona Reply in one or two short spoken sentences, no emojis, no markdown, no lists. " +
            "Begin every reply with exactly one emotion tag from: [happy] [joy] [laugh] [excited] [love] [surprised] [sad] [angry] " +
            "[curious] [confused] [shy] [thinking] [proud] [cool] [sleepy] [scared] [smug] [wink] [pout]."
    }

    private fun askChain(text: String, robotName: String, userName: String): String? {
        history.add("user" to text)
        while (history.size > 16) history.removeAt(0)
        while (history.isNotEmpty() && history[0].first != "user") history.removeAt(0)
        val sys = system(robotName, userName)
        val errors = ArrayList<String>()
        val providers = listOf(
            Triple("Claude", prefs.apiKey) { claude(sys) },
            Triple("Gemini", prefs.geminiKey) { gemini(sys) },
            Triple("Groq", prefs.groqKey) { groq(sys) },
        )
        for ((name, key, call) in providers) {
            if (key.isBlank()) continue
            if (name == "Claude" && key.startsWith("sk-ant-oat")) { errors.add("Claude: login token, not an API key"); continue }
            try {
                val out = call()?.trim()
                if (!out.isNullOrEmpty()) {
                    history.add("assistant" to out)
                    lastProvider = name
                    return out
                }
                errors.add("$name: empty reply")
            } catch (e: Exception) {
                errors.add("$name: ${e.message ?: e.javaClass.simpleName}")
            }
        }
        history.removeAt(history.size - 1)
        lastError = errors.joinToString(" | ")
        return null
    }

    private fun post(url: String, headers: Map<String, String>, body: JSONObject): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 10000; c.readTimeout = 25000
            c.doOutput = true
            c.setRequestProperty("content-type", "application/json")
            for ((k, v) in headers) c.setRequestProperty(k, v)
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = c.responseCode
            if (code !in 200..299) {
                val err = try { c.errorStream?.bufferedReader()?.readText()?.take(140) } catch (_: Exception) { null }
                throw Exception("HTTP $code ${err ?: ""}".trim())
            }
            return JSONObject(c.inputStream.bufferedReader().readText())
        } finally { c.disconnect() }
    }

    private fun claude(sys: String): String? {
        val msgs = JSONArray()
        for ((role, content) in history) msgs.put(JSONObject().put("role", role).put("content", content))
        val body = JSONObject().put("model", prefs.aiModel.ifBlank { "claude-haiku-5-5" }).put("max_tokens", 300).put("system", sys).put("messages", msgs)
        val resp = post("https://api.anthropic.com/v1/messages", mapOf("x-api-key" to prefs.apiKey, "anthropic-version" to "2023-06-01"), body)
        val arr = resp.getJSONArray("content")
        val sb = StringBuilder()
        for (i in 0 until arr.length()) { val o = arr.getJSONObject(i); if (o.optString("type") == "text") sb.append(o.optString("text")) }
        return sb.toString()
    }

    private fun gemini(sys: String): String? {
        val contents = JSONArray()
        for ((role, content) in history) contents.put(JSONObject().put("role", if (role == "assistant") "model" else "user")
            .put("parts", JSONArray().put(JSONObject().put("text", content))))
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", sys))))
            .put("contents", contents)
            .put("generationConfig", JSONObject().put("maxOutputTokens", 1024))
        val model = prefs.geminiModel.ifBlank { "gemini-3.8-flash" }
        val resp = post("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent", mapOf("x-goog-api-key" to prefs.geminiKey), body)
        val parts = resp.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts") ?: return null
        val sb = StringBuilder()
        for (i in 0 until parts.length()) { val o = parts.getJSONObject(i); if (!o.optBoolean("thought", false)) sb.append(o.optString("text")) }
        return sb.toString()
    }

    private fun groq(sys: String): String? {
        val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", sys))
        for ((role, content) in history) msgs.put(JSONObject().put("role", role).put("content", content))
        val body = JSONObject().put("model", prefs.groqModel.ifBlank { "llama-3.3-70b-versatile" }).put("max_tokens", 300).put("messages", msgs)
        val resp = post("https://api.groq.com/openai/v1/chat/completions", mapOf("Authorization" to "Bearer ${prefs.groqKey}"), body)
        return resp.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content")
    }

    fun reset() { synchronized(this) { history.clear() } }
}

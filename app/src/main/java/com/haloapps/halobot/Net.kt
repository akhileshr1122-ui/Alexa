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
            } catch (e: java.net.SocketTimeoutException) {
                errors.add("$name: timeout")
            } catch (e: Exception) {
                errors.add("$name: ${e.message ?: e.javaClass.simpleName}")
            }
        }
        history.removeAt(history.size - 1)
        lastError = errors.joinToString(" | ")
        return null
    }

    private class HttpError(val code: Int, msg: String) : Exception(msg)

    private fun get(url: String, headers: Map<String, String>): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10000; c.readTimeout = 15000
            for ((k, v) in headers) c.setRequestProperty(k, v)
            if (c.responseCode !in 200..299) throw HttpError(c.responseCode, "HTTP ${c.responseCode}")
            return JSONObject(c.inputStream.bufferedReader().readText())
        } finally { c.disconnect() }
    }

    private fun post(url: String, headers: Map<String, String>, body: JSONObject, readTimeout: Int = 30000): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 10000; c.readTimeout = readTimeout
            c.doOutput = true
            c.setRequestProperty("content-type", "application/json")
            for ((k, v) in headers) c.setRequestProperty(k, v)
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = c.responseCode
            if (code !in 200..299) {
                val err = try { c.errorStream?.bufferedReader()?.readText()?.take(140) } catch (_: Exception) { null }
                throw HttpError(code, "HTTP $code ${err ?: ""}".trim())
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
        fun body(thinking: Boolean): JSONObject {
            val gen = JSONObject().put("maxOutputTokens", 1024)
            if (thinking) gen.put("thinkingConfig", JSONObject().put("thinkingLevel", "low"))
            return JSONObject()
                .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", sys))))
                .put("contents", contents)
                .put("generationConfig", gen)
        }
        val headers = mapOf("x-goog-api-key" to prefs.geminiKey)
        fun call(model: String): JSONObject {
            val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"
            return try { post(url, headers, body(true), 45000) } catch (e: HttpError) {
                if (e.code == 400) post(url, headers, body(false), 45000) else throw e
            }
        }
        val model = prefs.geminiModel.ifBlank { "gemini-3.8-flash" }
        val resp = try { call(model) } catch (e: HttpError) {
            if (e.code != 404) throw e
            val better = pickGeminiModel() ?: throw e
            prefs.geminiModel = better
            call(better)
        }
        val parts = resp.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts") ?: return null
        val sb = StringBuilder()
        for (i in 0 until parts.length()) { val o = parts.getJSONObject(i); if (!o.optBoolean("thought", false)) sb.append(o.optString("text")) }
        return sb.toString()
    }

    /** Newest "flash" model this key can use (prefers flash-lite for speed). */
    private fun pickGeminiModel(): String? {
        val list = get("https://generativelanguage.googleapis.com/v1beta/models?pageSize=200", mapOf("x-goog-api-key" to prefs.geminiKey)).optJSONArray("models") ?: return null
        val names = ArrayList<String>()
        for (i in 0 until list.length()) {
            val m = list.getJSONObject(i)
            val methods = m.optJSONArray("supportedGenerationMethods")?.toString() ?: ""
            val name = m.optString("name").removePrefix("models/")
            if ("generateContent" in methods && name.contains("flash") && !name.contains("image") && !name.contains("tts") && !name.contains("live") && !name.contains("preview")) names.add(name)
        }
        return names.sortedWith(compareByDescending<String> { Regex("(\\d+(?:\\.\\d+)?)").find(it)?.value?.toDoubleOrNull() ?: 0.0 }.thenBy { if (it.contains("lite")) 0 else 1 }).firstOrNull()
    }

    private fun groq(sys: String): String? {
        val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", sys))
        for ((role, content) in history) msgs.put(JSONObject().put("role", role).put("content", content))
        val headers = mapOf("Authorization" to "Bearer ${prefs.groqKey}")
        fun call(model: String): JSONObject {
            val body = JSONObject().put("model", model).put("max_tokens", 800).put("messages", msgs)
            if (model.contains("gpt-oss")) body.put("reasoning_effort", "low")
            return try { post("https://api.groq.com/openai/v1/chat/completions", headers, body) } catch (e: HttpError) {
                if (e.code == 400 && body.has("reasoning_effort")) { body.remove("reasoning_effort"); post("https://api.groq.com/openai/v1/chat/completions", headers, body) } else throw e
            }
        }
        var model = prefs.groqModel.ifBlank { DEFAULT_GROQ }
        if (model in RETIRED_GROQ) { model = DEFAULT_GROQ; prefs.groqModel = model }
        val resp = try { call(model) } catch (e: HttpError) {
            if (e.code != 404 && e.code != 400) throw e
            val better = pickGroqModel(model) ?: throw e
            prefs.groqModel = better
            call(better)
        }
        val text = resp.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content") ?: return null
        return text.replace(Regex("(?s)<think>.*?</think>"), "").trim()
    }

    /** Picks a chat model this Groq key can use right now. */
    private fun pickGroqModel(failed: String): String? {
        val data = get("https://api.groq.com/openai/v1/models", mapOf("Authorization" to "Bearer ${prefs.groqKey}")).optJSONArray("data") ?: return null
        val ids = ArrayList<String>()
        for (i in 0 until data.length()) {
            val o = data.getJSONObject(i)
            val id = o.optString("id")
            if (id == failed || !o.optBoolean("active", true)) continue
            if (id.contains("whisper") || id.contains("tts") || id.contains("guard") || id.contains("orpheus") || id.contains("prompt")) continue
            ids.add(id)
        }
        val prefer = listOf("openai/gpt-oss-120b", "qwen/qwen3.6-27b", "openai/gpt-oss-20b")
        return prefer.firstOrNull { it in ids } ?: ids.firstOrNull { it.contains("gpt-oss") || it.contains("qwen") || it.contains("llama") || it.contains("kimi") } ?: ids.firstOrNull()
    }

    companion object {
        const val DEFAULT_GROQ = "openai/gpt-oss-120b"
        val RETIRED_GROQ = setOf("llama-3.3-70b-versatile", "llama-3.1-8b-instant", "llama3-70b-8192", "llama3-8b-8192", "mixtral-8x7b-32768", "gemma2-9b-it")
    }

    fun reset() { synchronized(this) { history.clear() } }
}


/** Speech-to-text through Groq's hosted Whisper (uses the Groq key). */
object Stt {
    @Volatile var lastError = ""

    @Volatile var hint = ""

    /** [cb] gets (text, language code) or (null, null) on failure. */
    fun groq(key: String, pcm: ShortArray, cb: (String?, String?) -> Unit) {
        thread {
            val res = try { call(key, pcm, "whisper-large-v3-turbo") } catch (e: Exception) {
                try { call(key, pcm, "whisper-large-v3") } catch (e2: Exception) { lastError = e2.message ?: "speech-to-text failed"; null }
            }
            mainHandler.post { cb(res?.first, res?.second) }
        }
    }

    private val LANGS = mapOf(
        "english" to "en", "hindi" to "hi", "telugu" to "te", "tamil" to "ta", "kannada" to "kn", "malayalam" to "ml", "marathi" to "mr",
        "bengali" to "bn", "gujarati" to "gu", "punjabi" to "pa", "urdu" to "ur", "spanish" to "es", "french" to "fr", "german" to "de",
        "arabic" to "ar", "chinese" to "zh", "japanese" to "ja", "korean" to "ko", "portuguese" to "pt", "italian" to "it", "russian" to "ru",
        "dutch" to "nl", "turkish" to "tr", "polish" to "pl", "indonesian" to "id", "vietnamese" to "vi", "thai" to "th", "nepali" to "ne",
    )

    private fun wav(pcm: ShortArray, rate: Int = 16000): ByteArray {
        val data = pcm.size * 2
        val b = java.nio.ByteBuffer.allocate(44 + data).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()); b.putInt(36 + data); b.put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()); b.putInt(16); b.putShort(1); b.putShort(1); b.putInt(rate); b.putInt(rate * 2); b.putShort(2); b.putShort(16)
        b.put("data".toByteArray()); b.putInt(data)
        for (v in pcm) b.putShort(v)
        return b.array()
    }

    private fun call(key: String, pcm: ShortArray, model: String): Pair<String, String> {
        val boundary = "----halobot" + System.nanoTime()
        val c = URL("https://api.groq.com/openai/v1/audio/transcriptions").openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"; c.doOutput = true
            c.connectTimeout = 8000; c.readTimeout = 20000
            c.setRequestProperty("Authorization", "Bearer $key")
            c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            c.outputStream.use { o ->
                fun field(name: String, value: String) {
                    o.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray())
                }
                field("model", model)
                field("response_format", "verbose_json")
                field("temperature", "0")
                if (hint.isNotBlank()) field("prompt", hint)
                o.write("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"speech.wav\"\r\nContent-Type: audio/wav\r\n\r\n".toByteArray())
                o.write(wav(pcm))
                o.write("\r\n--$boundary--\r\n".toByteArray())
            }
            val code = c.responseCode
            if (code !in 200..299) {
                val err = try { c.errorStream?.bufferedReader()?.readText()?.take(140) } catch (_: Exception) { null }
                throw Exception("HTTP $code ${err ?: ""}".trim())
            }
            lastError = ""
            val j = JSONObject(c.inputStream.bufferedReader().readText())
            val lang = j.optString("language").lowercase().let { LANGS[it] ?: it.take(2).ifBlank { "en" } }
            return j.optString("text").trim() to lang
        } finally { c.disconnect() }
    }

    /** Whisper sometimes "hears" these in silence or noise. */
    fun junk(t: String): Boolean {
        val s = t.lowercase().trim().trim('.', '!', '?', ',', ' ')
        return s.isEmpty() || s.length < 2 || s in setOf("you", "thank you", "thanks", "thanks for watching", "thank you for watching", "bye", "okay", "oh", "uh", "um", "hmm", "so", "the", "i") ||
            s.startsWith("subtitles by") || s.contains("amara.org")
    }
}

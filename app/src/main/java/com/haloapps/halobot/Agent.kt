package com.haloapps.halobot

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.random.Random

/** Long-term memory: facts about the user and their world, plus recent conversation. Survives restarts. */
class Memory(ctx: Context) {
    private val p = ctx.getSharedPreferences("halobot_memory", Context.MODE_PRIVATE)

    fun facts(): List<String> = try { JSONArray(p.getString("facts", "[]")).let { a -> (0 until a.length()).map { a.getString(it) } } } catch (_: Exception) { emptyList() }
    private fun saveFacts(l: List<String>) { p.edit().putString("facts", JSONArray(l.takeLast(80)).toString()).apply() }

    fun remember(fact: String): Boolean {
        val f = fact.trim(); if (f.isEmpty()) return false
        val l = facts().toMutableList()
        if (l.any { it.equals(f, true) }) return false
        l.add(f); saveFacts(l); return true
    }
    fun forget(match: String): Int {
        val l = facts(); val keep = l.filterNot { it.contains(match, true) }
        saveFacts(keep); return l.size - keep.size
    }
    fun clear() { p.edit().clear().apply() }

    /** Last conversation turns as (role, text). */
    fun history(): List<Pair<String, String>> = try {
        val a = JSONArray(p.getString("history", "[]"))
        (0 until a.length()).map { a.getJSONArray(it).let { x -> x.getString(0) to x.getString(1) } }
    } catch (_: Exception) { emptyList() }
    fun addTurn(user: String, assistant: String) {
        val h = history().toMutableList()
        h.add("user" to user); h.add("assistant" to assistant)
        val arr = JSONArray(); h.takeLast(24).forEach { arr.put(JSONArray().put(it.first).put(it.second)) }
        p.edit().putString("history", arr.toString()).apply()
    }
}

/**
 * The robot's brain: a language model that decides what to say AND what to do, using tools
 * (TV remote, timers, games, faces, memory, camera, music...). Works with Claude, Gemini or Groq,
 * falling back between them.
 */
class Agent(private val r: Robot) {
    private val main = Handler(Looper.getMainLooper())
    val memory = Memory(r.act)
    @Volatile var lastError = ""
    @Volatile var lastProvider = ""

    // ---------------- tools ----------------
    class Tool(val name: String, val desc: String, val params: JSONObject, val run: (JSONObject) -> String)

    private fun obj(vararg props: Pair<String, JSONObject>, required: List<String> = emptyList()): JSONObject {
        val p = JSONObject(); props.forEach { p.put(it.first, it.second) }
        val o = JSONObject().put("type", "object").put("properties", p)
        if (required.isNotEmpty()) o.put("required", JSONArray(required))
        return o
    }
    private fun str(desc: String, enum: List<String>? = null) = JSONObject().put("type", "string").put("description", desc).apply { if (enum != null) put("enum", JSONArray(enum)) }
    private fun int(desc: String) = JSONObject().put("type", "integer").put("description", desc)
    private fun bool(desc: String) = JSONObject().put("type", "boolean").put("description", desc)

    private val faceNames = Faces.all.keys.toList()
    private val tvKeys = mapOf(
        "power" to "POWER", "up" to "UP", "down" to "DOWN", "left" to "LEFT", "right" to "RIGHT", "ok" to "OK", "back" to "BACK",
        "home" to "HOME", "menu" to "MENU", "volume_up" to "VOL_UP", "volume_down" to "VOL_DOWN", "mute" to "MUTE",
        "channel_up" to "CH_UP", "channel_down" to "CH_DOWN", "play" to "PLAY", "pause" to "PAUSE", "play_pause" to "PLAY_PAUSE",
        "rewind" to "REWIND", "fast_forward" to "FORWARD", "stop" to "STOP", "input" to "INPUT",
    )

    /** Runs [block] on the main thread and waits for its result (tools touch UI/robot state). */
    private fun <T> onMain(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        var out: T? = null; var err: Throwable? = null
        val latch = CountDownLatch(1)
        main.post { try { out = block() } catch (e: Throwable) { err = e } finally { latch.countDown() } }
        latch.await(10, TimeUnit.SECONDS)
        err?.let { throw it }
        @Suppress("UNCHECKED_CAST") val v = out as T
        return v
    }

    val tools: List<Tool> = listOf(
        Tool("set_face", "Show a facial expression on your screen (use often to be expressive).", obj("expression" to str("Expression", faceNames), "seconds" to int("How long, default 3"), required = listOf("expression"))) { a ->
            onMain { r.express(a.getString("expression"), (a.optInt("seconds", 3).coerceIn(1, 20) * 1000).toLong()) }; "ok"
        },
        Tool("move", "Move your body with the phone's vibration motor.", obj("kind" to str("Movement", listOf("wiggle", "giggle", "dance", "spin", "hop", "look_around", "purr", "shiver", "heartbeat")), required = listOf("kind"))) { a ->
            onMain {
                when (val k = a.getString("kind")) {
                    "dance" -> r.dance(); "look_around" -> r.lookAround(false); "spin" -> { r.face.spin(); r.drivePulse(1500) }
                    "hop" -> { r.face.hop(); r.motion.play("hop") }
                    else -> r.motion.play(k)
                }
            }; "done"
        },
        Tool("tv_key", "Press a button on the user's TV remote (works for Samsung, Fire TV, Google TV, LG, Roku). Use play/pause for media.", obj("key" to str("Button", tvKeys.keys.toList()), "times" to int("Repeat count, default 1 (use 3-5 for volume)"), required = listOf("key"))) { a ->
            val k = tvKeys[a.getString("key")] ?: return@Tool "unknown key"
            if (!r.tv.hasTv) return@Tool "No TV is set up. Tell the user to long-press you, open settings and tap Find TVs."
            val key = com.halo.remote.core.Key.valueOf(k)
            r.tv.pressMany(key, a.optInt("times", 1).coerceIn(1, 25)); "pressed ${a.getString("key")}"
        },
        Tool("tv_open_app", "Open an app on the TV by name, e.g. Netflix, Prime Video, YouTube, Disney+, Spotify, Hotstar, Plex.", obj("app" to str("App name"), required = listOf("app"))) { a ->
            if (!r.tv.hasTv) return@Tool "No TV is set up."
            val latch = CountDownLatch(1); var label: String? = null
            onMain { r.tv.launch(a.getString("app")) { l -> label = l; latch.countDown() } }
            latch.await(12, TimeUnit.SECONDS)
            label?.let { onMain { r.face.showBanner("📺 $it", 3000) }; "opened $it" } ?: "could not find that app on the TV (or the TV is not reachable)"
        },
        Tool("tv_power", "Turn the TV on or off.", obj("on" to bool("true = on"), required = listOf("on"))) { a ->
            if (!r.tv.hasTv) return@Tool "No TV is set up."
            if (a.getBoolean("on")) r.tv.powerOn() else r.tv.powerOff(); "sent"
        },
        Tool("tv_type", "Type text into the TV's search box.", obj("text" to str("Text"), required = listOf("text"))) { a ->
            if (r.tv.type(a.getString("text"))) "typed" else "this TV can't receive typing"
        },
        Tool("phone_music", "Control music playing on the phone (Spotify etc.) or play your own beat.", obj("action" to str("Action", listOf("play", "pause", "next", "previous", "volume_up", "volume_down", "open_spotify", "play_own_beat")), required = listOf("action"))) { a ->
            onMain {
                val am = r.act.getSystemService(android.media.AudioManager::class.java)
                fun key(c: Int) { am?.dispatchMediaKeyEvent(android.view.KeyEvent(0, c)); am?.dispatchMediaKeyEvent(android.view.KeyEvent(1, c)) }
                when (a.getString("action")) {
                    "play" -> key(126); "pause" -> key(127); "next" -> key(87); "previous" -> key(88)
                    "volume_up" -> am?.adjustStreamVolume(3, 1, 0); "volume_down" -> am?.adjustStreamVolume(3, -1, 0)
                    "open_spotify" -> r.kid.spotify(); "play_own_beat" -> r.playOwnMusic()
                    else -> {}
                }
            }; "done"
        },
        Tool("set_timer", "Start a countdown timer or a reminder.", obj("seconds" to int("Duration in seconds"), "reminder" to str("What to remind about (omit for a plain timer)"), required = listOf("seconds"))) { a ->
            val s = a.getInt("seconds").coerceIn(1, 86400)
            val label = a.optString("reminder").takeIf { it.isNotBlank() }
            onMain { r.addTimer(s * 1000L, label, if (label != null) "reminder" else "timer") }; "timer set for $s seconds"
        },
        Tool("cancel_timers", "Cancel all timers and reminders.", obj()) { _ -> onMain { r.timers.clear() }; "cancelled" },
        Tool("set_alarm", "Set an alarm in the phone's clock app.", obj("hour" to int("0-23"), "minute" to int("0-59"), "label" to str("Label"), required = listOf("hour", "minute"))) { a ->
            try {
                val i = android.content.Intent(android.provider.AlarmClock.ACTION_SET_ALARM)
                    .putExtra(android.provider.AlarmClock.EXTRA_HOUR, a.getInt("hour")).putExtra(android.provider.AlarmClock.EXTRA_MINUTES, a.getInt("minute"))
                    .putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, a.optString("label", "${r.robotName} alarm")).putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                onMain { r.act.startActivity(i) }; "alarm set"
            } catch (e: Exception) { "no alarm app" }
        },
        Tool("get_weather", "Current weather for a city (defaults to the user's city). Also shows it on screen.", obj("city" to str("City"))) { a ->
            val city = a.optString("city").ifBlank { r.prefs.city }
            if (city.isBlank()) return@Tool "unknown city - ask the user which city they live in, then call remember with it"
            val latch = CountDownLatch(1); var res: Weather.Now? = null
            Weather.fetch(city) { w -> res = w; latch.countDown() }
            latch.await(12, TimeUnit.SECONDS)
            val w = res ?: return@Tool "weather service unreachable"
            val (words, _, scene) = Weather.describe(w.code)
            onMain { r.face.showText("${Math.round(w.tempC)}°", 5000, w.city); if (scene != Overlay.NONE) r.face.scene(scene, 7000) }
            "${w.city}: ${Math.round(w.tempC)}°C, $words, high ${w.high?.let { Math.round(it) }}, low ${w.low?.let { Math.round(it) }}"
        },
        Tool("random_pick", "Pick randomly and fairly (rock paper scissors, coin flip, dice, choosing). Shows the result on screen. ALWAYS use this instead of choosing yourself in games.", obj("options" to JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")).put("description", "Options to pick from"), required = listOf("options"))) { a ->
            val arr = a.getJSONArray("options"); if (arr.length() == 0) return@Tool "no options"
            val pick = arr.getString(Random.nextInt(arr.length()))
            onMain { r.face.showText(pick.uppercase(), 2500); r.kid.sfx.play("boop") }; "picked: $pick"
        },
        Tool("show_text", "Show big text on your screen (numbers, scores, short words).", obj("text" to str("Text, short"), "seconds" to int("Default 4"), required = listOf("text"))) { a ->
            onMain { r.face.showText(a.getString("text").take(14), a.optInt("seconds", 4).coerceIn(1, 30) * 1000L) }; "shown"
        },
        Tool("remember", "Save a fact to long-term memory (user's likes, people, plans, important info). Use whenever the user tells you something worth remembering.", obj("fact" to str("The fact, in third person, e.g. 'Akilesh likes biryani'"), required = listOf("fact"))) { a ->
            if (memory.remember(a.getString("fact"))) "remembered" else "already known"
        },
        Tool("forget", "Remove facts from memory that contain the given words.", obj("about" to str("Words to match"), required = listOf("about"))) { a ->
            "forgot ${memory.forget(a.getString("about"))} facts"
        },
        Tool("set_user_info", "Save the user's name, city or birthday (MM-DD).", obj("name" to str("User's first name"), "city" to str("City"), "birthday" to str("MM-DD"))) { a ->
            a.optString("name").takeIf { it.isNotBlank() }?.let { r.prefs.userName = it.trim().replaceFirstChar { c -> c.uppercase() } }
            a.optString("city").takeIf { it.isNotBlank() }?.let { r.prefs.city = it.trim() }
            a.optString("birthday").takeIf { it.isNotBlank() }?.let { r.prefs.birthday = it.trim() }
            "saved"
        },
        Tool("learn_face", "Learn the face of the person in front of the camera so you recognise them later.", obj("name" to str("Their name"), "relation" to str("Who they are to the user", listOf("me", "partner", "family", "friend")), required = listOf("name", "relation"))) { a ->
            onMain { r.kid.enroll(a.getString("name").replaceFirstChar { it.uppercase() }, a.getString("relation"), speak = false) }; "learning their face now (takes a few seconds; tell them to hold still)"
        },
        Tool("activity", "Start one of your built-in activities.", obj("name" to str("Activity", listOf("staring_contest", "hide_and_seek", "breathing_exercise", "birthday_song", "show_all_faces", "play_ball", "cheers", "eat_food", "drink_water", "bath_time", "play_dead", "romance", "take_photo", "go_to_sleep", "show_clock", "find_me", "spin_test")), required = listOf("name"))) { a ->
            onMain {
                when (a.getString("name")) {
                    "staring_contest" -> r.staringContest(); "hide_and_seek" -> r.hideAndSeek(); "breathing_exercise" -> r.breathe()
                    "birthday_song" -> r.birthday(); "show_all_faces" -> r.gallery(); "play_ball" -> r.face.startBall()
                    "cheers" -> r.kid.cheers(false); "eat_food" -> r.kid.eat("food"); "drink_water" -> r.kid.drink()
                    "bath_time" -> r.kid.goingAway("bath"); "play_dead" -> r.kid.die(); "take_photo" -> r.takePhoto()
                    "go_to_sleep" -> r.main.postDelayed({ r.sleep() }, 2500); "show_clock" -> r.showClock(); "find_me" -> r.lookAround(false)
                    "spin_test" -> r.spinTest()
                    "romance" -> r.kid.romance(r.kid.vision2.peopleList.firstOrNull { it.second == "partner" }?.first ?: "my love")
                    else -> {}
                }
            }; "started (it will run its own lines - keep your reply very short)"
        },
        Tool("set_eye_color", "Change your eye color.", obj("color" to str("Color", Themes.list.map { it.name.lowercase() }), required = listOf("color"))) { a ->
            val i = Themes.find(a.getString("color").lowercase()) ?: return@Tool "unknown color"
            r.prefs.theme = i; onMain { r.applyPrefs() }; "changed"
        },
        Tool("flashlight", "Turn the phone's flashlight on or off.", obj("on" to bool("on"), required = listOf("on"))) { a ->
            try {
                val cm = r.act.getSystemService(android.hardware.camera2.CameraManager::class.java)
                val id = cm.cameraIdList.first { cm.getCameraCharacteristics(it).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
                cm.setTorchMode(id, a.getBoolean("on")); "done"
            } catch (e: Exception) { "not available" }
        },
        Tool("quiet_mode", "Stop talking on your own for a while (user wants quiet).", obj("minutes" to int("Minutes"))) { a ->
            onMain { r.mute(a.optInt("minutes", 30).coerceIn(1, 600) * 60_000L) }; "quiet"
        },
    )

    // ---------------- prompt ----------------
    private fun systemPrompt(): String {
        val c = Calendar.getInstance()
        val now = android.text.format.DateFormat.format("EEEE, MMMM d yyyy, h:mm a", c).toString()
        val user = r.userName.ifBlank { "unknown (ask their name and save it with set_user_info)" }
        val facts = memory.facts()
        val people = r.kid.vision2.peopleList.joinToString { "${it.first} (${it.second})" }.ifBlank { "nobody yet" }
        val seeing = if (r.faceVisible) (r.kid.currentPerson?.let { "$it is in front of you" } ?: "a person is in front of you (not recognised)") else "nobody is in view"
        val tv = r.tv.device?.let { "${it.name} (${it.platform.short})" } ?: "none set up"
        val persona = if (r.prefs.kidMode)
            "You are ${r.robotName}, a tiny robot pet living in a phone, with the personality of a playful, clever, sweet 6-year-old. You say cute things (yay, hehe, ooh), get excited, tease a little, and love your human."
        else "You are ${r.robotName}, a witty, warm little desk robot living in a phone."
        return """
$persona You are also a genuinely capable assistant, like a smart speaker but more fun: answer questions accurately, help, control the TV, set timers, play games.
Speak like a real character in a live conversation: 1-2 short sentences, no lists, no markdown, no emojis. Reply in the same language the user spoke.
Start every reply with one emotion tag, e.g. [happy] [joy] [laugh] [excited] [love] [surprised] [sad] [pout] [angry] [curious] [confused] [shy] [thinking] [proud] [cool] [sleepy] [scared] [smug] [wink].
Use tools to actually DO things - never pretend. TV requests: tv_key / tv_open_app / tv_power ("pause", "play", "louder", "go back", "open Netflix" all mean the TV when one is set up).
Games: play them naturally in conversation, turn by turn. For rock-paper-scissors, coin flips, dice and any random choice ALWAYS call random_pick and then react to the result. Keep score yourself.
Memory: when the user tells you something about themselves or people in their life, call remember. Use what you remember to be personal. If someone new is in front of you and introduces themselves, call learn_face.
If the user is just chatting, chat back warmly and ask a curious follow-up sometimes.

Now: $now. User: $user. City: ${r.prefs.city.ifBlank { "unknown" }}. Battery: ${r.senses.batteryLevel}%.
Camera: $seeing. People you know by face: $people. TV: $tv.
What you remember:
${if (facts.isEmpty()) "(nothing yet)" else facts.joinToString("\n") { "- $it" }}
""".trim()
    }

    // ---------------- conversation loop ----------------
    /** Handles one thing the user said. [cb] gets the spoken reply (with emotion tag) or null on failure. */
    fun handle(userText: String, cb: (String?) -> Unit) {
        thread(name = "agent") {
            val reply = try { run(userText) } catch (e: Exception) { lastError = e.message ?: "error"; null }
            if (reply != null) memory.addTurn(userText, reply)
            main.post { cb(reply) }
        }
    }

    private fun run(userText: String): String? {
        val sys = systemPrompt()
        val hist = memory.history()
        val errors = ArrayList<String>()
        val p = r.prefs
        val order = listOf("Claude" to p.apiKey, "Groq" to p.groqKey, "Gemini" to p.geminiKey)
        for ((name, key) in order) {
            if (key.isBlank() || (name == "Claude" && key.startsWith("sk-ant-oat"))) continue
            try {
                val out = when (name) { "Claude" -> claude(sys, hist, userText); "Groq" -> groq(sys, hist, userText); else -> gemini(sys, hist, userText) }
                if (!out.isNullOrBlank()) { lastProvider = name; lastError = ""; return out.trim() }
                errors.add("$name: empty reply")
            } catch (e: java.net.SocketTimeoutException) { errors.add("$name: timeout") }
            catch (e: Exception) { errors.add("$name: ${e.message ?: e.javaClass.simpleName}") }
        }
        lastError = errors.joinToString(" | ").ifBlank { "no AI keys" }
        return null
    }

    private fun exec(name: String, args: JSONObject): String {
        val t = tools.firstOrNull { it.name == name } ?: return "unknown tool"
        return try { t.run(args) } catch (e: Exception) { "error: ${e.message}" }
    }

    private fun http(url: String, headers: Map<String, String>, body: JSONObject, timeout: Int = 30000): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"; c.doOutput = true
            c.connectTimeout = 10000; c.readTimeout = timeout
            c.setRequestProperty("content-type", "application/json")
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = c.responseCode
            if (code !in 200..299) {
                val err = try { c.errorStream?.bufferedReader()?.readText()?.take(160) } catch (_: Exception) { null }
                throw Exception("HTTP $code ${err ?: ""}".trim())
            }
            return JSONObject(c.inputStream.bufferedReader().readText())
        } finally { c.disconnect() }
    }

    // ---- Groq (OpenAI format) ----
    private fun groq(sys: String, hist: List<Pair<String, String>>, user: String): String? {
        val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", sys))
        hist.forEach { msgs.put(JSONObject().put("role", it.first).put("content", it.second)) }
        msgs.put(JSONObject().put("role", "user").put("content", user))
        val toolArr = JSONArray()
        tools.forEach { t -> toolArr.put(JSONObject().put("type", "function").put("function", JSONObject().put("name", t.name).put("description", t.desc).put("parameters", t.params))) }
        var model = r.prefs.groqModel.ifBlank { AiChat.DEFAULT_GROQ }
        if (model in AiChat.RETIRED_GROQ) { model = AiChat.DEFAULT_GROQ; r.prefs.groqModel = model }
        repeat(6) {
            val body = JSONObject().put("model", model).put("messages", msgs).put("tools", toolArr).put("tool_choice", "auto").put("max_tokens", 1000)
            if (model.contains("gpt-oss")) body.put("reasoning_effort", "low")
            val resp = http("https://api.groq.com/openai/v1/chat/completions", mapOf("Authorization" to "Bearer ${r.prefs.groqKey}"), body)
            val msg = resp.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
            val calls = msg.optJSONArray("tool_calls")
            if (calls == null || calls.length() == 0) return msg.optString("content").replace(Regex("(?s)<think>.*?</think>"), "").trim()
            val asst = JSONObject().put("role", "assistant").put("content", msg.optString("content", "")).put("tool_calls", calls)
            msgs.put(asst)
            for (i in 0 until calls.length()) {
                val c = calls.getJSONObject(i)
                val f = c.getJSONObject("function")
                val args = try { JSONObject(f.optString("arguments", "{}").ifBlank { "{}" }) } catch (_: Exception) { JSONObject() }
                msgs.put(JSONObject().put("role", "tool").put("tool_call_id", c.getString("id")).put("content", exec(f.getString("name"), args)))
            }
        }
        return null
    }

    // ---- Claude ----
    private fun claude(sys: String, hist: List<Pair<String, String>>, user: String): String? {
        val msgs = JSONArray()
        hist.forEach { msgs.put(JSONObject().put("role", it.first).put("content", it.second)) }
        msgs.put(JSONObject().put("role", "user").put("content", user))
        val toolArr = JSONArray()
        tools.forEach { t -> toolArr.put(JSONObject().put("name", t.name).put("description", t.desc).put("input_schema", t.params)) }
        repeat(6) {
            val body = JSONObject().put("model", r.prefs.aiModel.ifBlank { "claude-haiku-5-5" }).put("max_tokens", 600).put("system", sys).put("messages", msgs).put("tools", toolArr)
            val resp = http("https://api.anthropic.com/v1/messages", mapOf("x-api-key" to r.prefs.apiKey, "anthropic-version" to "2023-06-01"), body)
            val content = resp.getJSONArray("content")
            val results = JSONArray(); val text = StringBuilder()
            for (i in 0 until content.length()) {
                val b = content.getJSONObject(i)
                when (b.optString("type")) {
                    "text" -> text.append(b.optString("text"))
                    "tool_use" -> results.put(JSONObject().put("type", "tool_result").put("tool_use_id", b.getString("id")).put("content", exec(b.getString("name"), b.optJSONObject("input") ?: JSONObject())))
                }
            }
            if (results.length() == 0) return text.toString()
            msgs.put(JSONObject().put("role", "assistant").put("content", content))
            msgs.put(JSONObject().put("role", "user").put("content", results))
        }
        return null
    }

    // ---- Gemini ----
    private fun gemini(sys: String, hist: List<Pair<String, String>>, user: String): String? {
        val contents = JSONArray()
        fun add(role: String, text: String) = contents.put(JSONObject().put("role", if (role == "assistant") "model" else "user").put("parts", JSONArray().put(JSONObject().put("text", text))))
        hist.forEach { add(it.first, it.second) }
        add("user", user)
        val decls = JSONArray()
        tools.forEach { t -> decls.put(JSONObject().put("name", t.name).put("description", t.desc).apply { if (t.params.optJSONObject("properties")?.length() ?: 0 > 0) put("parameters", t.params) }) }
        val model = r.prefs.geminiModel.ifBlank { "gemini-3.8-flash" }
        repeat(6) {
            val body = JSONObject()
                .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", sys))))
                .put("contents", contents)
                .put("tools", JSONArray().put(JSONObject().put("functionDeclarations", decls)))
                .put("generationConfig", JSONObject().put("maxOutputTokens", 1024).put("thinkingConfig", JSONObject().put("thinkingLevel", "low")))
            val resp = http("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent", mapOf("x-goog-api-key" to r.prefs.geminiKey), body, 45000)
            val cand = resp.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content") ?: return null
            val parts = cand.optJSONArray("parts") ?: return null
            val responses = JSONArray(); val text = StringBuilder()
            for (i in 0 until parts.length()) {
                val pt = parts.getJSONObject(i)
                pt.optJSONObject("functionCall")?.let { fc ->
                    val res = exec(fc.getString("name"), fc.optJSONObject("args") ?: JSONObject())
                    responses.put(JSONObject().put("functionResponse", JSONObject().put("name", fc.getString("name")).put("response", JSONObject().put("result", res))))
                }
                if (!pt.optBoolean("thought", false) && pt.has("text")) text.append(pt.optString("text"))
            }
            if (responses.length() == 0) return text.toString()
            contents.put(cand)
            contents.put(JSONObject().put("role", "user").put("parts", responses))
        }
        return null
    }
}

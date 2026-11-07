package com.haloapps.halobot

import android.content.Context

/** Everything the robot remembers between launches. */
class Prefs(ctx: Context) {
    private val p = ctx.getSharedPreferences("halobot", Context.MODE_PRIVATE)

    private fun str(k: String, d: String) = p.getString(k, d) ?: d
    private fun putStr(k: String, v: String) = p.edit().putString(k, v).apply()
    private fun bool(k: String, d: Boolean) = p.getBoolean(k, d)
    private fun putBool(k: String, v: Boolean) = p.edit().putBoolean(k, v).apply()
    private fun int(k: String, d: Int) = p.getInt(k, d)
    private fun putInt(k: String, v: Int) = p.edit().putInt(k, v).apply()
    private fun long(k: String, d: Long) = p.getLong(k, d)
    private fun putLong(k: String, v: Long) = p.edit().putLong(k, v).apply()
    private fun float(k: String, d: Float) = p.getFloat(k, d)
    private fun putFloat(k: String, v: Float) = p.edit().putFloat(k, v).apply()

    var robotName: String get() = str("robotName", "Halo"); set(v) = putStr("robotName", v)
    var userName: String get() = str("userName", ""); set(v) = putStr("userName", v)
    /** "MM-dd", e.g. "04-21" */
    var birthday: String get() = str("birthday", ""); set(v) = putStr("birthday", v)
    var city: String get() = str("city", ""); set(v) = putStr("city", v)
    var theme: Int get() = int("theme", 0); set(v) = putInt("theme", v)
    var pitch: Float get() = float("pitch", 1.5f); set(v) = putFloat("pitch", v)
    var rate: Float get() = float("rate", 1.08f); set(v) = putFloat("rate", v)

    var move: Boolean get() = bool("move", true); set(v) = putBool("move", v)
    var seek: Boolean get() = bool("seek", true); set(v) = putBool("seek", v)
    var talk: Boolean get() = bool("talk", true); set(v) = putBool("talk", v)
    var chatty: Boolean get() = bool("chatty", true); set(v) = putBool("chatty", v)
    var handsFree: Boolean get() = bool("handsFree", false); set(v) = putBool("handsFree", v)
    var micReact: Boolean get() = bool("micReact", true); set(v) = putBool("micReact", v)
    var captions: Boolean get() = bool("captions", true); set(v) = putBool("captions", v)
    var showCamera: Boolean get() = bool("showCamera", false); set(v) = putBool("showCamera", v)
    var water: Boolean get() = bool("water", false); set(v) = putBool("water", v)
    var nightClock: Boolean get() = bool("nightClock", false); set(v) = putBool("nightClock", v)
    var scanlines: Boolean get() = bool("scanlines", false); set(v) = putBool("scanlines", v)

    var apiKey: String get() = str("apiKey", ""); set(v) = putStr("apiKey", v.trim())
    var aiModel: String get() = str("aiModel", "claude-haiku-5-5"); set(v) = putStr("aiModel", v.trim())

    var geminiKey: String get() = str("geminiKey", ""); set(v) = putStr("geminiKey", v.trim())
    var geminiModel: String get() = str("geminiModel", "gemini-3.8-flash"); set(v) = putStr("geminiModel", v.trim())
    var groqKey: String get() = str("groqKey", ""); set(v) = putStr("groqKey", v.trim())
    var groqModel: String get() = str("groqModel", "openai/gpt-oss-120b"); set(v) = putStr("groqModel", v.trim())

    var alwaysListen: Boolean get() = bool("alwaysListen", true); set(v) = putBool("alwaysListen", v)
    var cloudVoice: Boolean get() = bool("cloudVoice", true); set(v) = putBool("cloudVoice", v)
    var cloudVoiceName: String get() = str("cloudVoiceName", "hannah"); set(v) = putStr("cloudVoiceName", v)
    var kidMode: Boolean get() = bool("kidMode", true); set(v) = putBool("kidMode", v)
    var sounds: Boolean get() = bool("sounds", true); set(v) = putBool("sounds", v)
    var seeThings: Boolean get() = bool("seeThings", true); set(v) = putBool("seeThings", v)
    var weatherAmbient: Boolean get() = bool("weatherAmbient", true); set(v) = putBool("weatherAmbient", v)
    var idlePlay: Boolean get() = bool("idlePlay", true); set(v) = putBool("idlePlay", v)
    var romance: Boolean get() = bool("romance", true); set(v) = putBool("romance", v)

    // stats & memory
    var firstDay: Long get() = long("firstDay", 0L); set(v) = putLong("firstDay", v)
    var lastSeenDay: String get() = str("lastSeenDay", ""); set(v) = putStr("lastSeenDay", v)
    var lastSeenWall: Long get() = long("lastSeenWall", 0L); set(v) = putLong("lastSeenWall", v)
    var pets: Int get() = int("pets", 0); set(v) = putInt("pets", v)
    var affection: Int get() = int("affection", 0); set(v) = putInt("affection", v)
    var photos: Int get() = int("photos", 0); set(v) = putInt("photos", v)
    var gamesWon: Int get() = int("gamesWon", 0); set(v) = putInt("gamesWon", v)
    var gamesLost: Int get() = int("gamesLost", 0); set(v) = putInt("gamesLost", v)
    var triviaBest: Int get() = int("triviaBest", 0); set(v) = putInt("triviaBest", v)
    var greetings: Int get() = int("greetings", 0); set(v) = putInt("greetings", v)
    var aiTipShown: Boolean get() = bool("aiTipShown", false); set(v) = putBool("aiTipShown", v)
    var birthdayDoneYear: Int get() = int("birthdayDoneYear", 0); set(v) = putInt("birthdayDoneYear", v)
    var pendingTest: String get() = str("pendingTest", ""); set(v) = putStr("pendingTest", v)
    var favoriteColor: String get() = str("favColor", ""); set(v) = putStr("favColor", v)
    var favoriteFood: String get() = str("favFood", ""); set(v) = putStr("favFood", v)
}

/** Eye color themes. All original palettes. */
data class EyeTheme(val name: String, val top: Int, val bottom: Int, val glow: Int, val words: List<String>)

object Themes {
    val list = listOf(
        EyeTheme("Aqua", 0xFFB8FBFF.toInt(), 0xFF22C7E8.toInt(), 0xFF2BD9F0.toInt(), listOf("aqua", "blue", "cyan", "teal", "default")),
        EyeTheme("Mint", 0xFFC9FFE4.toInt(), 0xFF2EE59D.toInt(), 0xFF35F0A6.toInt(), listOf("mint", "green", "lime")),
        EyeTheme("Amber", 0xFFFFE2A6.toInt(), 0xFFFF9A1F.toInt(), 0xFFFFA733.toInt(), listOf("amber", "orange", "yellow", "gold")),
        EyeTheme("Rose", 0xFFFFD0E0.toInt(), 0xFFFF4F8B.toInt(), 0xFFFF5C95.toInt(), listOf("rose", "pink", "red", "magenta")),
        EyeTheme("Lilac", 0xFFE6D6FF.toInt(), 0xFF9B6BFF.toInt(), 0xFFA77BFF.toInt(), listOf("lilac", "purple", "violet", "lavender")),
        EyeTheme("Snow", 0xFFFFFFFF.toInt(), 0xFFBFD3E6.toInt(), 0xFFDDEBFF.toInt(), listOf("snow", "white", "silver", "grey", "gray")),
    )
    fun get(i: Int) = list[i.coerceIn(0, list.size - 1)]
    fun find(text: String): Int? = list.indexOfFirst { th -> th.words.any { text.contains(it) } }.takeIf { it >= 0 }
}

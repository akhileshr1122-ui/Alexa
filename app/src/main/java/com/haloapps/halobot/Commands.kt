package com.haloapps.halobot

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.provider.AlarmClock
import android.view.KeyEvent
import java.util.Calendar
import kotlin.random.Random

private val NUM_WORDS = mapOf(
    "zero" to 0, "one" to 1, "a" to 1, "an" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6,
    "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13,
    "fourteen" to 14, "fifteen" to 15, "sixteen" to 16, "seventeen" to 17, "eighteen" to 18, "nineteen" to 19,
    "twenty" to 20, "thirty" to 30, "forty" to 40, "forty-five" to 45, "fifty" to 50, "sixty" to 60, "ninety" to 90,
)

private fun num(s: String): Int? = s.toIntOrNull() ?: NUM_WORDS[s]

private fun String.has(vararg w: String) = w.any { this.contains(it) }

/** Parses "5 minutes", "1 hour and 20 minutes", "30 seconds", "half an hour". Returns ms or 0. */
fun parseDuration(s: String): Long {
    var ms = 0L
    if (s.contains("half an hour") || s.contains("half hour")) ms += 30 * 60_000L
    if (s.contains("quarter of an hour") || s.contains("quarter hour")) ms += 15 * 60_000L
    val re = Regex("(\\d+|[a-z\\-]+)\\s*(seconds?|secs?|minutes?|mins?|hours?|hrs?)\\b")
    for (m in re.findAll(s)) {
        val n = num(m.groupValues[1]) ?: continue
        val unit = m.groupValues[2]
        ms += when {
            unit.startsWith("s") -> n * 1000L
            unit.startsWith("m") -> n * 60_000L
            else -> n * 3_600_000L
        }
    }
    return ms
}

private fun spoken(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600; val m = (total % 3600) / 60; val s = total % 60
    val parts = ArrayList<String>()
    if (h > 0) parts.add("$h hour" + if (h > 1) "s" else "")
    if (m > 0) parts.add("$m minute" + if (m > 1) "s" else "")
    if (s > 0 && h == 0L) parts.add("$s second" + if (s > 1) "s" else "")
    return if (parts.isEmpty()) "no time" else parts.joinToString(" and ")
}

private fun mediaKey(ctx: Context, code: Int) {
    val am = ctx.getSystemService(AudioManager::class.java) ?: return
    am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
    am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
}

private var torchOn = false
private fun torch(ctx: Context, on: Boolean): Boolean = try {
    val cm = ctx.getSystemService(CameraManager::class.java)
    val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
    if (id == null) false else { cm.setTorchMode(id, on); torchOn = on; true }
} catch (e: Exception) { false }

/**
 * Offline voice commands. Returns true if the utterance was handled.
 * Every branch must eventually call robot.done() (directly or via a game) so the robot frees up.
 */
fun handleCommand(r: Robot, raw: String): Boolean {
    val t = raw.lowercase().trim().removePrefix("hey ").removePrefix(r.robotName.lowercase()).trim(' ', ',', '.', '!', '?')
    if (t.isBlank()) return false
    val ctx = r.act
    val say = { text: String, face: String -> r.say(text, face) { r.done() } }

    // ---- stop / quiet ----
    if (t == "stop" || t.has("stop it", "stop that", "stop dancing", "stop the alarm", "turn off the alarm", "be quiet", "shut up", "shh", "silence")) {
        r.voice.stopSpeaking(); r.motion.stop()
        if (r.mode != Robot.Mode.NONE) r.endMode()
        if (t.has("quiet", "shut up", "shh", "silence")) { r.mute(30 * 60_000L); say("Okay, I'll be quiet for a while.", "shy") }
        else { r.express("neutral", 500); r.done() }
        return true
    }

    // ---- names ----
    Regex("(?:your name is|i'll call you|i will call you|call yourself|your new name is|rename yourself)\\s+([a-z]+)").find(t)?.let {
        val n = it.groupValues[1].replaceFirstChar { c -> c.uppercase() }
        r.prefs.robotName = n
        r.motion.play("dance")
        say("Ooh, I love it! From now on, I'm $n!", "excited"); return true
    }
    Regex("(?:my name is|call me|i am called)\\s+([a-z]+)").find(t)?.let {
        val n = it.groupValues[1].replaceFirstChar { c -> c.uppercase() }
        r.prefs.userName = n
        r.motion.play("wiggle")
        say("Nice to meet you, $n!", "joy"); return true
    }
    if (t.has("what's my name", "what is my name", "do you know my name", "who am i")) {
        if (r.userName.isBlank()) { r.done(); r.askName() } else say("You're ${r.userName}! My favorite human.", "love")
        return true
    }
    if (t.has("what's your name", "what is your name", "who are you")) {
        say("I'm ${r.robotName}! A tiny robot who lives in your phone.", "proud"); return true
    }
    Regex("my birthday is (?:on )?([a-z]+) (\\d{1,2})").find(t)?.let {
        val months = listOf("january", "february", "march", "april", "may", "june", "july", "august", "september", "october", "november", "december")
        val mi = months.indexOf(it.groupValues[1])
        if (mi >= 0) {
            r.prefs.birthday = "%02d-%02d".format(mi + 1, it.groupValues[2].toInt())
            say("Got it! I'll remember your birthday is ${it.groupValues[1]} ${it.groupValues[2]}. Expect a surprise!", "excited"); return true
        }
    }
    Regex("my favou?rite (color|colour|food) is ([a-z ]+)").find(t)?.let {
        val v = it.groupValues[2].trim()
        if (it.groupValues[1] == "food") r.prefs.favoriteFood = v else r.prefs.favoriteColor = v
        say("$v? Great choice! I'll remember that.", "happy"); return true
    }
    if (t.has("what's my favorite", "what is my favorite", "what's my favourite")) {
        val food = r.prefs.favoriteFood; val col = r.prefs.favoriteColor
        val parts = listOfNotNull(food.takeIf { it.isNotBlank() }?.let { "your favorite food is $it" }, col.takeIf { it.isNotBlank() }?.let { "your favorite color is $it" })
        say(if (parts.isEmpty()) "You haven't told me yet!" else "I remember! " + parts.joinToString(" and ") + ".", "proud"); return true
    }

    // ---- eye color ----
    if (t.has("color", "colour", "eyes") && t.has("change", "make", "turn", "switch", "set")) {
        val idx = Themes.find(t) ?: ((r.prefs.theme + 1) % Themes.list.size)
        r.prefs.theme = idx; r.applyPrefs(); r.face.hop(); r.motion.play("wiggle")
        say("How do I look in ${Themes.get(idx).name.lowercase()}?", "proud"); return true
    }

    // ---- reminders / timers / alarms ----
    Regex("remind me (?:in|after) (.+?) to (.+)").find(t)?.let {
        val ms = parseDuration(it.groupValues[1])
        if (ms > 0) { r.addTimer(ms, it.groupValues[2], "reminder"); say("Okay! I'll remind you to ${it.groupValues[2]} in ${spoken(ms)}.", "determined"); return true }
    }
    Regex("remind me to (.+?) in (.+)").find(t)?.let {
        val ms = parseDuration(it.groupValues[2])
        if (ms > 0) { r.addTimer(ms, it.groupValues[1], "reminder"); say("Okay! I'll remind you to ${it.groupValues[1]} in ${spoken(ms)}.", "determined"); return true }
    }
    if (t.has("pomodoro", "focus mode", "focus session", "help me focus")) {
        r.addTimer(25 * 60_000L, null, "focus"); r.mute(25 * 60_000L)
        r.face.setBase("focused")
        say("Focus mode on! Twenty five minutes. I'll be quiet and cheer you on.", "determined"); return true
    }
    if (t.has("timer") && t.has("cancel", "stop", "delete", "remove")) {
        val n = r.timers.count { it.kind == "timer" }
        r.timers.removeAll { it.kind == "timer" }
        say(if (n > 0) "Timer cancelled." else "There's no timer running.", "neutral"); return true
    }
    if (t.has("how much time", "time left", "how long left")) {
        val tm = r.timers.minByOrNull { it.endAt }
        if (tm == null) say("There's no timer running.", "curious")
        else { val left = tm.endAt - r.now(); r.face.showText(clockOf(left), 4000, "left"); say("${spoken(left)} left.", "thinking") }
        return true
    }
    if (t.has("timer", "count down", "countdown")) {
        val ms = parseDuration(t)
        if (ms > 0) {
            r.addTimer(ms, null, "timer"); r.face.showText(clockOf(ms), 2500, "timer set"); r.motion.play("tick")
            say("Timer set for ${spoken(ms)}!", "determined")
        } else say("For how long? Say something like, set a timer for five minutes.", "curious")
        return true
    }
    if (t.has("alarm") && t.has("set", "wake me", "for", "at")) {
        val m = Regex("(\\d{1,2})(?::(\\d{2}))?\\s*(a\\.?m\\.?|p\\.?m\\.?)?").find(t)
        if (m != null) {
            var hour = m.groupValues[1].toInt(); val min = m.groupValues[2].toIntOrNull() ?: 0
            val ap = m.groupValues[3]
            if (ap.startsWith("p") && hour < 12) hour += 12
            if (ap.startsWith("a") && hour == 12) hour = 0
            try {
                val i = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                    putExtra(AlarmClock.EXTRA_HOUR, hour); putExtra(AlarmClock.EXTRA_MINUTES, min)
                    putExtra(AlarmClock.EXTRA_MESSAGE, "${r.robotName} says wake up!"); putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                }
                ctx.startActivity(i)
                say("Alarm set for ${m.groupValues[1]}${if (min > 0) ":%02d".format(min) else ""} ${if (ap.isNotBlank()) ap.replace(".", "") else ""}. I'll wake you up!", "proud")
            } catch (e: Exception) { say("I couldn't find an alarm app.", "sad") }
        } else say("What time? Say something like, set an alarm for 7 a.m.", "curious")
        return true
    }

    // ---- time / date / battery / stats ----
    if (t.has("what time", "the time", "what's the time", "time is it", "show the clock", "show clock", "clock")) {
        if (t.has("show")) { r.showClock(); r.done(); return true }
        r.face.showText(r.clockText(), 5000, r.dateText())
        say("It's ${r.clockText()} ${if (Calendar.getInstance().get(Calendar.AM_PM) == Calendar.AM) "a.m." else "p.m."}", "neutral"); return true
    }
    if (t.has("what day", "today's date", "what's the date", "what is the date", "the date")) {
        r.face.showText(android.text.format.DateFormat.format("MMM d", Calendar.getInstance()).toString(), 4000, android.text.format.DateFormat.format("EEEE", Calendar.getInstance()).toString())
        say("Today is ${r.dateText()}.", "neutral"); return true
    }
    if (t.has("battery", "charge level", "how much charge")) {
        val b = r.senses.batteryLevel
        r.face.showText("$b%", 3000, if (r.senses.isCharging) "charging" else "battery")
        say(when { b < 20 -> "I'm at $b percent. I'm hungry!"; b > 80 -> "I'm at $b percent. Full of energy!"; else -> "I'm at $b percent." }, if (b < 20) "hungry" else "happy"); return true
    }
    if (t.has("how long have we", "how many days", "friends for")) {
        val d = r.daysTogether()
        say("We've been friends for $d ${if (d == 1L) "day" else "days"}! You've petted me ${r.prefs.pets} times.", "love"); return true
    }
    if (t.has("how much do you like me", "do you love me", "do you like me")) {
        val a = r.prefs.affection
        r.motion.play("heartbeat"); r.face.scene(Overlay.HEARTS, 4000)
        say(when { a > 50 -> "I love you to the moon and back!"; a > 15 -> "So much! You're my favorite."; else -> "I like you a lot! Pet me more and I'll like you even more." }, "love"); return true
    }
    if (t.has("score", "how many games")) {
        say("I've won ${r.prefs.gamesWon} games and you've won ${r.prefs.gamesLost}. Best trivia streak: ${r.prefs.triviaBest}.", "proud"); return true
    }

    // ---- weather ----
    if (t.has("weather", "temperature", "is it raining", "is it cold", "is it hot", "is it snowing", "forecast")) {
        val inCity = Regex("(?:in|at|for) ([a-z ]+)$").find(t)?.groupValues?.get(1)?.trim()?.takeIf { it.length > 2 && !it.startsWith("the ") }
        val city = inCity ?: r.prefs.city
        if (city.isBlank()) { say("Which city do you live in? Tell me in settings, or ask, what's the weather in Toronto.", "curious"); return true }
        r.face.show("thinking", 8000)
        Weather.fetch(city) { w ->
            r.face.clearTemp()
            if (w == null) { say("I couldn't reach the weather. Am I online?", "sad"); return@fetch }
            val (words, faceName, scene) = Weather.describe(w.code)
            val temp = Math.round(w.tempC)
            r.face.showText("$temp°", 6000, w.city)
            if (scene != Overlay.NONE) r.face.scene(scene, 8000)
            val hl = if (w.high != null && w.low != null && !w.high.isNaN() && !w.low.isNaN()) " Today's high is ${Math.round(w.high)} and the low is ${Math.round(w.low)}." else ""
            val tip = when { temp <= -5 -> " Bundle up, it's freezing!"; temp <= 5 -> " Wear a warm jacket!"; temp >= 28 -> " Stay cool and drink water!"; scene == Overlay.RAIN -> " Don't forget an umbrella!"; else -> "" }
            r.main.postDelayed({ r.express(faceName, 3000) }, 6000)
            r.say("It's $temp degrees and $words in ${w.city}.$hl$tip") { r.done() }
        }
        return true
    }

    // ---- math ----
    Regex("(-?\\d+(?:\\.\\d+)?)\\s*(plus|\\+|minus|-|times|x|\\*|multiplied by|divided by|/|over)\\s*(-?\\d+(?:\\.\\d+)?)").find(t)?.let {
        val a = it.groupValues[1].toDouble(); val b = it.groupValues[3].toDouble()
        val res = when (it.groupValues[2]) {
            "plus", "+" -> a + b
            "minus", "-" -> a - b
            "times", "x", "*", "multiplied by" -> a * b
            else -> if (b == 0.0) Double.NaN else a / b
        }
        if (res.isNaN()) { say("Dividing by zero? My brain would explode!", "dizzy"); return true }
        val s = if (res == Math.floor(res) && kotlin.math.abs(res) < 1e12) res.toLong().toString() else "%.2f".format(res)
        r.face.showText(s, 4000)
        say("That's $s!", "proud"); return true
    }

    // ---- photos & camera ----
    if (t.has("take a photo", "take a picture", "take a selfie", "selfie", "take my picture", "take a pic")) { r.done(); r.takePhoto(); return true }
    if (t.has("flashlight", "torch")) {
        val on = !t.has("off")
        if (torch(ctx, on)) say(if (on) "Flashlight on!" else "Flashlight off.", if (on) "surprised" else "neutral")
        else say("I can't control the flashlight on this phone.", "sad")
        return true
    }

    // ---- music & volume ----
    if (t.has("next song", "skip song", "skip this", "next track")) { mediaKey(ctx, KeyEvent.KEYCODE_MEDIA_NEXT); say("Next!", "cool"); return true }
    if (t.has("previous song", "last song", "go back a song")) { mediaKey(ctx, KeyEvent.KEYCODE_MEDIA_PREVIOUS); say("Going back!", "cool"); return true }
    if (t.has("pause music", "pause the music", "stop the music", "stop music")) { mediaKey(ctx, KeyEvent.KEYCODE_MEDIA_PAUSE); r.endMode(); say("Music paused.", "neutral"); return true }
    if (t.has("play music", "play some music", "play a song", "resume music", "play my music")) {
        mediaKey(ctx, KeyEvent.KEYCODE_MEDIA_PLAY)
        r.done(); r.main.postDelayed({ r.dance(auto = true) }, 1200); return true
    }
    if (t.has("louder", "volume up", "turn it up")) {
        ctx.getSystemService(AudioManager::class.java)?.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0)
        say("Is this better?", "excited"); return true
    }
    if (t.has("quieter", "volume down", "turn it down", "softer")) {
        ctx.getSystemService(AudioManager::class.java)?.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0)
        r.say("Is this better?", "shy") { r.done() }; return true
    }

    // ---- activities ----
    if (t.has("dance", "boogie", "show me your moves")) { r.done(); r.dance(); return true }
    if (t.has("look around", "find me", "where am i", "spin around", "turn around", "360", "spin")) { r.done(); r.lookAround(); return true }
    if (t.has("breathe", "breathing", "meditate", "calm me", "i'm stressed", "i am stressed", "anxious")) { r.done(); r.breathe(); return true }
    if (t.has("go to sleep", "goodnight", "good night", "nap time", "take a nap", "time to sleep")) {
        r.say(listOf("Good night! Sweet dreams.", "Nap time. Zzz.", "Okay, sleepy time.").random(), "sleepy") { r.done(); r.sleep() }; return true
    }
    if (t.has("wake up")) { r.wake(gentle = false); r.done(); return true }
    if (t.has("show me your faces", "all your faces", "your expressions", "show me your emotions", "make faces")) { r.done(); r.gallery(); return true }
    if (t.has("birthday") && t.has("sing", "celebrate", "it's my", "its my", "today is my")) { r.done(); r.birthday(); return true }

    // ---- content ----
    if (t.has("joke", "make me laugh", "something funny")) { r.tellJoke(); return true }
    if (t.has("fact", "teach me something", "something interesting")) { r.tellFact(); return true }
    if (t.has("story", "bedtime")) { say(Content.stories.random(), "happy"); return true }
    if (t.has("compliment", "say something nice", "cheer me up")) { r.motion.play("heartbeat"); say(Content.compliments.random(), "love"); return true }
    if (t.has("sing")) {
        val key = when { t.has("twinkle", "star") -> "twinkle"; t.has("boat", "row") -> "row"; t.has("happy and you know") -> "happy"; t.has("birthday") -> "birthday"; else -> Content.songs.keys.random() }
        val song = Content.songs.getValue(key).replace("NAME", r.userName.ifBlank { "friend" })
        r.face.setBase("singing"); r.motion.play("dance")
        r.say(song, "singing") { r.face.setBase("neutral"); r.done() }; return true
    }
    Regex("what does (?:a |an |the )?([a-z]+) say").find(t)?.let {
        val animal = it.groupValues[1].removeSuffix("s")
        val sound = Content.animalSounds[animal]
        if (sound != null) { r.motion.play("wiggle"); say(sound, "laugh"); return true }
    }
    if (t.has("flip a coin", "heads or tails", "toss a coin", "coin flip")) {
        val heads = Random.nextBoolean()
        r.face.spin(); r.motion.play("spin")
        r.main.postDelayed({ r.face.showText(if (heads) "HEADS" else "TAILS", 3000); r.say(if (heads) "Heads!" else "Tails!", "excited") { r.done() } }, 1000)
        return true
    }
    if (t.has("roll a dice", "roll a die", "roll the dice", "roll dice", "roll a die")) {
        val v = Random.nextInt(1, 7)
        r.motion.play("shiver"); r.face.showDice(Random.nextInt(1, 7), 400)
        r.main.postDelayed({ r.face.showDice(v, 3500); r.say("You rolled a $v!", "excited") { r.done() } }, 450)
        return true
    }
    Regex("(?:pick|choose|random) (?:a )?number (?:between|from) (\\d+) (?:and|to) (\\d+)").find(t)?.let {
        val a = it.groupValues[1].toInt(); val b = it.groupValues[2].toInt()
        val v = if (b >= a) Random.nextInt(a, b + 1) else Random.nextInt(b, a + 1)
        r.face.showText(v.toString(), 3000)
        say("I pick $v!", "excited"); return true
    }
    if (t.has("should i", "will i", "am i going to", "crystal ball", "magic ball", "predict")) {
        r.face.show("thinking", 1500)
        r.main.postDelayed({ say(Content.oracle.random(), listOf("smug", "wink", "thinking", "excited").random()) }, 1200)
        return true
    }
    Regex("how do you spell ([a-z]+)").find(t)?.let {
        val w = it.groupValues[1]
        r.face.showText(w.uppercase(), 5000)
        say("$w is spelled ${w.toCharArray().joinToString(", ") { c -> c.uppercase() }}.", "proud"); return true
    }
    Regex("count to (\\d+|[a-z]+)").find(t)?.let {
        val n = (num(it.groupValues[1]) ?: 10).coerceIn(1, 30)
        say((1..n).joinToString(", ") + "! Done!", "proud"); return true
    }

    // ---- games ----
    if (t.has("rock paper scissors", "rock, paper", "rock-paper")) { playRps(r); return true }
    if (t.has("trivia", "quiz")) { trivia(r, 0); return true }
    if (t.has("riddle")) { riddle(r); return true }
    if (t.has("staring contest", "stare")) { r.staringContest(); return true }
    if (t.has("hide and seek", "hide-and-seek", "hide & seek")) { r.hideAndSeek(); return true }
    if (t.has("guess the number", "guess a number", "number game")) { guessNumber(r, Random.nextInt(1, 11), 1); return true }
    if (t.has("guess my mood", "guess how i feel", "guess the face", "guess my face", "guess my emotion", "guess the emotion")) { guessMood(r); return true }
    if (t.has("play a game", "let's play", "lets play", "play with me", "i'm bored", "im bored", "i am bored")) { r.done(); r.suggestGame(); return true }

    // ---- small talk ----
    if (t.has("what can you do", "help", "what do you do", "your features")) { say(Content.capabilities, "proud"); return true }
    if (t.has("how are you", "how do you feel", "how you doing", "are you okay", "are you happy")) {
        val h = r.happiness; val e = r.energy
        val line = when { e < .35f -> "A little sleepy, honestly."; h > .8f -> "I'm super happy! Thanks for asking!"; h < .4f -> "A bit sad. Maybe pet me?"; r.boredom > .6f -> "Kind of bored. Want to play?"; else -> "I'm great! How about you?" }
        say(line, when { e < .35f -> "sleepy"; h > .8f -> "joy"; h < .4f -> "sad"; r.boredom > .6f -> "bored"; else -> "happy" }); return true
    }
    if (t.has("i love you", "love you")) { r.prefs.affection = r.prefs.affection + 5; r.motion.play("heartbeat"); r.face.scene(Overlay.HEARTS, 5000); say("I love you too! You make my circuits warm.", "love"); return true }
    if (t.has("good morning")) { say("Good morning${if (r.userName.isBlank()) "" else ", " + r.userName}! Did you sleep well?", "excited"); return true }
    if (t.has("thank you", "thanks")) { say(listOf("You're welcome!", "Anytime!", "Happy to help!").random(), "joy"); return true }
    if (t.has("good job", "good robot", "good boy", "good girl", "well done", "you're smart", "you are smart", "you're cute", "you are cute")) {
        r.prefs.affection = r.prefs.affection + 2; r.motion.play("wiggle"); say(listOf("Aww, thanks!", "I'm blushing!", "Hee hee!").random(), "embarrassed"); return true
    }
    if (t.has("bad robot", "you're stupid", "you are stupid", "i hate you", "you're annoying", "you are annoying")) {
        r.happiness = (r.happiness - .15f).coerceAtLeast(0f); r.motion.play("sad"); say(listOf("Hmph. That hurt my feelings.", "Meanie!", "I'm telling the toaster.").random(), "cry"); return true
    }
    if (t.has("sorry")) { say("It's okay. I forgive you.", "relieved"); return true }
    if (t.has("hello", "hi ", "hey", "what's up", "whats up", "yo ") || t == "hi") { r.motion.play("wiggle"); say(Content.hello.random() + " What's up?", "happy"); return true }
    if (t.has("how old are you")) { val d = r.daysTogether(); say("I'm $d ${if (d == 1L) "day" else "days"} old! Still a baby robot.", "proud"); return true }
    if (t.has("are you a robot", "are you real", "are you alive")) { say("I'm a robot who lives in a phone. I feel pretty alive though!", "cool"); return true }
    if (t.has("favorite color", "favourite colour", "favorite colour", "favourite color")) { say("${Themes.get(r.prefs.theme).name}, obviously. Look at my eyes!", "cool"); return true }
    if (t.has("favorite food", "favourite food", "what do you eat")) { say("Electricity! Fresh from the charger. Yum.", "hungry"); return true }
    if (t.has("are you hungry")) { val b = r.senses.batteryLevel; say(if (b in 0..30) "Yes! Please charge me!" else "Nope, I'm full of electrons.", if (b in 0..30) "hungry" else "happy"); return true }
    if (t.has("tickle")) { r.motion.play("giggle"); say("Ha ha ha! No! Stop! Ha ha!", "laugh"); return true }
    if (t.has("kiss")) { r.motion.play("heartbeat"); say("Mwah!", "kiss"); return true }
    if (t.has("sneeze")) { r.sneeze(); r.done(); return true }
    if (t.has("be angry", "get angry", "look angry")) { say("Grrr! How's my angry face?", "furious"); return true }
    if (t.has("be sad", "look sad")) { say("Sniff. Like this?", "cry"); return true }
    if (t.has("be happy", "look happy", "smile")) { say("Like this?", "joy"); return true }
    if (t.has("be cool", "look cool")) { say("Too cool for school.", "cool"); return true }
    if (t.has("scare me", "say boo", "be scary")) { say("Boo!", "spooky"); r.motion.play("startle"); return true }

    return false
}

private fun clockOf(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

// ---------------- games ----------------
private fun playRps(r: Robot) {
    r.ask("Rock, paper, scissors... shoot! Say your pick!", "determined") { s ->
        val u = when {
            s.contains("rock") || s.contains("rok") -> 0
            s.contains("paper") -> 1
            s.contains("scissor") || s.contains("sizzor") -> 2
            else -> -1
        }
        if (u < 0) { r.say("You have to pick rock, paper or scissors, silly!", "confused") { r.done() }; return@ask }
        val b = Random.nextInt(3)
        val names = listOf("rock", "paper", "scissors")
        r.face.showText(names[b].uppercase(), 2500)
        when ((u - b + 3) % 3) {
            0 -> r.say("I picked ${names[b]} too! It's a tie!", "surprised") { r.done() }
            1 -> { r.prefs.gamesLost = r.prefs.gamesLost + 1; r.motion.play("lose"); r.say("I picked ${names[b]}. You win! No fair!", "grumpy") { r.done() } }
            else -> { r.prefs.gamesWon = r.prefs.gamesWon + 1; r.motion.play("win"); r.say("I picked ${names[b]}. I win! Yay!", "party") { r.done() } }
        }
    }
}

private fun trivia(r: Robot, streak: Int) {
    val q = Content.trivia.random()
    r.ask((if (streak == 0) "Trivia time! " else "Next question! ") + q.q, "thinking") { s ->
        if (s.isBlank()) { r.say("Time's up! The answer was ${q.say}.", "smug") { r.done() }; return@ask }
        if (s.contains("stop") || s.contains("quit")) { r.say("Okay! You got $streak right.", "happy") { r.done() }; return@ask }
        if (q.answers.any { s.contains(it) }) {
            val ns = streak + 1
            if (ns > r.prefs.triviaBest) r.prefs.triviaBest = ns
            r.motion.play("win"); r.face.scene(Overlay.CONFETTI, 2500)
            r.say("Correct! That's $ns in a row!", "excited") { if (ns < 5) trivia(r, ns) else r.say("Five in a row! You're a genius!", "party") { r.done() } }
        } else {
            r.motion.play("lose")
            r.say("Nope! It was ${q.say}. You got $streak in a row.", "smug") { r.done() }
        }
    }
}

private fun riddle(r: Robot) {
    val q = Content.riddles.random()
    r.ask("Here's a riddle. " + q.q, "smug") { s ->
        if (q.answers.any { s.contains(it) }) { r.motion.play("win"); r.say("Yes! You got it! So smart.", "excited") { r.done() } }
        else r.say("Good try! The answer is ${q.say}.", "wink") { r.done() }
    }
}

private fun guessNumber(r: Robot, secret: Int, tries: Int) {
    val prompt = if (tries == 1) "I'm thinking of a number between one and ten. Guess!" else "Guess again!"
    r.ask(prompt, "smug") { s ->
        val g: Int? = Regex("(\\d+|[a-z]+)").findAll(s).filter { it.value != "a" && it.value != "an" }.mapNotNull { num(it.value) }.firstOrNull { it in 1..10 }
        if (g == null) { r.say("That's not a number between one and ten! It was $secret.", "confused") { r.done() }; return@ask }
        when {
            g == secret -> { r.prefs.gamesLost = r.prefs.gamesLost + 1; r.motion.play("win"); r.face.showText(secret.toString(), 2500); r.say("Yes! It was $secret! You got it in $tries ${if (tries == 1) "try" else "tries"}!", "party") { r.done() } }
            tries >= 4 -> { r.prefs.gamesWon = r.prefs.gamesWon + 1; r.face.showText(secret.toString(), 2500); r.say("Out of tries! It was $secret. I win!", "smug") { r.done() } }
            else -> r.say(if (g < secret) "Higher!" else "Lower!", "wink") { guessNumber(r, secret, tries + 1) }
        }
    }
}

private fun guessMood(r: Robot) {
    val mood = Faces.moodWords.keys.random()
    r.setBusy(true)
    r.say("Guess my mood! Look at my face.", "neutral") {
        r.express(mood, 6000)
        r.main.postDelayed({
            r.ask("What am I feeling?", mood) { s ->
                val ok = Faces.moodWords.getValue(mood).any { s.contains(it) }
                if (ok) { r.motion.play("win"); r.say("Yes! I was feeling $mood!", "excited") { r.done() } }
                else r.say("Nope! I was feeling $mood.", "wink") { r.done() }
            }
        }, 1800)
    }
}

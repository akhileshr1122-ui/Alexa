package com.haloapps.halobot

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.camera.view.PreviewView
import java.util.Calendar
import kotlin.math.abs
import kotlin.random.Random

/**
 * The robot's brain: personality, moods, reactions to what it sees, hears and feels,
 * idle life (blinks, glances, yawns, sneezes), conversations, games and activities.
 */
class Robot(val act: MainActivity, val face: FaceView, preview: PreviewView) : Senses.Listener {

    enum class Mode { NONE, DANCE, STARE, HIDE_COUNT, HIDE_SEEK, SEEK, BREATHE, CLOCK, PHOTO, BIRTHDAY, GALLERY, ALARM, SLEEP_CLOCK }

    val prefs = Prefs(act)
    val main = Handler(Looper.getMainLooper())
    val motion = Motion(act)
    val voice = Voice(act, prefs)
    val senses = Senses(act, this, motion)
    val vision = Vision(act, preview) { onSeen(it) }
    val ai = AiChat(prefs)

    var mode = Mode.NONE
    private var modeStart = 0L
    private var modeToken = 0

    // perception state
    var faceVisible = false; private set
    private var lastFaceAt = 0L
    private var faceSince = 0L
    private var lastLostAt = 0L
    private var lastGreetAt = 0L
    private val knownIds = HashSet<Int>()
    private var faceCount = 0
    private var lastSmileReact = 0L
    private var lastWinkReact = 0L
    private var lastCloseReact = 0L
    private var lastNewFriend = 0L
    private var lastSeenSave = 0L
    private var lastTiltTalk = 0L

    // body state
    var sleeping = false; private set
    private var dark = false
    private var faceDown = false
    private var gx = 0f

    // mood (0..1)
    var happiness = .65f
    var energy = .9f
    var boredom = 0f

    // conversation
    var busy = false
    private var busySince = 0L
    private var lastAskAt = 0L
    private var lastInteraction = SystemClock.uptimeMillis()
    private var lastListenEnd = 0L
    private var muteUntil = 0L
    private var nextIdleAt = SystemClock.uptimeMillis() + 5000
    private var lastWater = SystemClock.uptimeMillis()
    private var lastLowBattTalk = 0L
    private var lastLoudReact = 0L
    private var lastPickReact = 0L
    private var lastShakeAt = 0L
    private var pokes = ArrayList<Long>()
    private var petDist = 0f
    private var lastPet = 0L
    private var hasCamera = false
    private var hasMic = false
    private var started = false
    private var autoDance = false
    private var lastAutoDance = 0L

    // timers & reminders
    data class Timer(val endAt: Long, val label: String?, val kind: String)
    val timers = ArrayList<Timer>()
    private var tone: ToneGenerator? = null

    // games
    var stareStart = 0L
    var stareLoseAt = 0L
    var seekFoundCb: (() -> Unit)? = null
    private var seekUntil = 0L
    private var lastSeekPulse = 0L
    var cakeTimeout = 0L

    private val tickRunnable = object : Runnable {
        override fun run() { tick(); main.postDelayed(this, 100) }
    }

    fun now() = SystemClock.uptimeMillis()
    val robotName: String get() = prefs.robotName.ifBlank { "Halo" }
    val userName: String get() = prefs.userName
    private fun nameOr(fallback: String = "friend") = userName.ifBlank { fallback }

    // ---------------- lifecycle ----------------
    fun start(camera: Boolean, mic: Boolean) {
        hasCamera = camera; hasMic = mic
        if (prefs.firstDay == 0L) prefs.firstDay = System.currentTimeMillis()
        applyPrefs()
        voice.onSpeaking = { s -> face.talking = s; senses.micPaused = s || voice.listening }
        voice.onListening = { l -> face.listening = l; senses.micPaused = l || voice.speaking }
        voice.onLevel = { lv -> face.listenLevel = lv }
        if (!started) {
            started = true
            main.post(tickRunnable)
            face.setBase("closed")
            main.postDelayed({
                face.setBase("neutral"); face.show("surprised", 900); motion.play("hop"); face.hop()
                main.postDelayed({
                    face.show("happy", 2000)
                    val first = prefs.greetings == 0
                    if (first) say("Hello! I'm $robotName, your new desk buddy! Double tap me to talk, and long press for settings.", "excited")
                    else say(Content.hello.random(), "happy")
                    prefs.greetings = prefs.greetings + 1
                    lastAskAt = now()
                }, 900)
            }, 700)
        }
        resume()
    }

    fun applyPrefs() {
        face.theme = Themes.get(prefs.theme)
        face.scanlines = prefs.scanlines
        motion.enabled = prefs.move
        voice.applyVoice()
        act.setPreviewVisible(prefs.showCamera)
        if (prefs.micReact && hasMic) senses.startMic() else senses.stopMic()
    }

    fun resume() {
        senses.start()
        if (prefs.micReact && hasMic) senses.startMic()
        if (hasCamera) vision.start()
        vision.paused = false
    }

    fun pause() {
        senses.stop()
        vision.paused = true
        voice.cancelListening()
        voice.stopSpeaking()
        motion.stop()
    }

    fun destroy() {
        main.removeCallbacks(tickRunnable)
        voice.shutdown()
        vision.close()
        tone?.release()
    }

    // ---------------- helpers ----------------
    fun express(name: String, ms: Long = 2500) = face.show(name, ms)

    fun say(text: String, expr: String? = null, exprMs: Long = 0, then: (() -> Unit)? = null) {
        if (expr != null) face.show(expr, if (exprMs > 0) exprMs else (1500L + text.length * 60L))
        if (prefs.captions) face.caption("$robotName: $text", 2500L + text.length * 55L)
        voice.say(text, then)
    }

    fun ask(q: String, expr: String = "curious", onAnswer: (String) -> Unit) {
        markBusy(true)
        say(q, expr) { listen(onAnswer) }
    }

    fun listen(cb: (String) -> Unit) {
        face.listening = true
        voice.listen { s ->
            face.listening = false
            lastListenEnd = now()
            if (s.isNotBlank() && prefs.captions) face.caption("You: $s", 3500)
            cb(s)
        }
    }

    fun markBusy(b: Boolean) { busy = b; if (b) busySince = now() }
    fun done() { markBusy(false); lastAskAt = now() }

    fun touch() {
        lastInteraction = now()
        boredom = 0f
        if (sleeping) wake(gentle = true)
    }

    private fun isNight(): Boolean { val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY); return h >= 23 || h < 6 }
    private fun mutedChat() = now() < muteUntil

    private fun startMode(m: Mode): Int { mode = m; modeStart = now(); modeToken++; return modeToken }
    fun endMode() {
        if (mode == Mode.CLOCK || mode == Mode.SLEEP_CLOCK || mode == Mode.ALARM) face.clearText()
        if (mode == Mode.BIRTHDAY) face.clearScene()
        if (mode == Mode.ALARM) motion.stop()
        mode = Mode.NONE; modeToken++
        face.scaleTarget = 1f
        seekFoundCb = null
        autoDance = false
        face.setBase("neutral")
    }
    private fun alive(token: Int) = token == modeToken

    // ---------------- vision ----------------
    private fun onSeen(seen: Vision.Seen) {
        val f = seen.main
        val t = now()
        if (f == null) return
        val wasVisible = faceVisible
        val absent = t - lastFaceAt
        if (!wasVisible) faceSince = t
        faceVisible = true
        lastFaceAt = t
        if (t - lastSeenSave > 15000) { lastSeenSave = t; prefs.lastSeenWall = System.currentTimeMillis() }

        // eyes follow you (front camera image is not mirrored)
        if (mode != Mode.GALLERY && mode != Mode.HIDE_COUNT) {
            face.lookTX = ((.5f - f.cx) * 2.3f).coerceIn(-1f, 1f)
            face.lookTY = ((f.cy - .45f) * 2.1f).coerceIn(-1f, 1f)
        }
        // mirror head tilt a little
        face.userTilt = if (abs(f.roll) > 12f) (-f.roll / 180f * 3.14159f * .5f).coerceIn(-.3f, .3f) else 0f

        // new people
        val ids = seen.faces.mapNotNull { it.id }
        val newIds = ids.filter { it !in knownIds }
        knownIds.addAll(ids)
        if (knownIds.size > 200) knownIds.clear()
        faceCount = seen.faces.size

        when (mode) {
            Mode.SEEK, Mode.HIDE_SEEK -> { val cb = seekFoundCb; seekFoundCb = null; motion.stop(); cb?.invoke(); return }
            Mode.STARE -> { stareCheck(f); return }
            Mode.HIDE_COUNT, Mode.GALLERY, Mode.PHOTO, Mode.BREATHE -> return
            else -> {}
        }

        if (sleeping) {
            if (!faceDown && (!dark || absent > 3000)) wake(gentle = false)
            return
        }

        if (!wasVisible && absent > 10000 && t - lastGreetAt > 20000) { greet(absent); return }
        if (!wasVisible && absent in 1200L..5000L && !busy && Random.nextFloat() < .5f) {
            express("laugh", 1500); motion.play("giggle")
            if (prefs.talk && !voice.speaking) say(listOf("Peekaboo!", "There you are!", "I see you!").random())
            return
        }

        if (busy || voice.listening || mode != Mode.NONE) return

        if (newIds.isNotEmpty() && seen.faces.size > 1 && t - lastNewFriend > 30000) {
            lastNewFriend = t
            express("excited", 2500); motion.play("wiggle")
            say(if (seen.faces.size > 2) "Whoa, so many friends!" else "Ooh, a new friend! Hello!")
            return
        }
        if (f.size > .6f && t - lastCloseReact > 7000) {
            lastCloseReact = t
            express(if (Random.nextBoolean()) "shy" else "surprised", 2200); motion.play("shy")
            if (Random.nextFloat() < .6f) say(listOf("Too close!", "Hey, personal space!", "Whoa, hello nose!", "I can count your eyelashes!").random())
            return
        }
        if (f.leftOpen >= 0 && f.rightOpen >= 0 && abs(f.leftOpen - f.rightOpen) > .6f && minOf(f.leftOpen, f.rightOpen) < .2f && t - lastWinkReact > 4000) {
            lastWinkReact = t
            express("wink", 1600); motion.play("giggle")
            return
        }
        if (f.smile > .75f && t - lastSmileReact > 6000) {
            lastSmileReact = t
            happiness = (happiness + .05f).coerceAtMost(1f)
            express(if (Random.nextFloat() < .25f) "love" else "joy", 2200); motion.play("wiggle")
            if (Random.nextFloat() < .25f && !mutedChat()) say(listOf("I love that smile!", "You look happy!", "Smiles are contagious!").random())
            return
        }
        if (abs(f.roll) > 22f && t - lastTiltTalk > 15000) {
            lastTiltTalk = t
            express("curious", 1800)
        }
    }

    private fun onFaceLost() {
        lastLostAt = now()
        face.userTilt = 0f
        if (mode == Mode.STARE) {
            if (now() - stareStart > 1500) {
                endMode(); prefs.gamesWon = prefs.gamesWon + 1
                say("Hey, you looked away! I win!", "proud"); motion.play("win")
            }
        }
    }

    private fun greet(absent: Long) {
        lastGreetAt = now()
        val cal = Calendar.getInstance()
        val today = "%d-%02d-%02d".format(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH))
        val firstToday = prefs.lastSeenDay != today
        val wallAway = System.currentTimeMillis() - prefs.lastSeenWall
        prefs.lastSeenDay = today
        prefs.lastSeenWall = System.currentTimeMillis()
        lastAskAt = now()
        val name = userName
        val nm = if (name.isBlank()) "" else ", $name"

        val md = "%02d-%02d".format(cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH))
        if (prefs.birthday == md && prefs.birthdayDoneYear != cal.get(Calendar.YEAR)) {
            prefs.birthdayDoneYear = cal.get(Calendar.YEAR)
            birthday(); return
        }
        val holiday = holidayGreeting(cal)
        if (firstToday) {
            val h = cal.get(Calendar.HOUR_OF_DAY)
            val g = when { h < 12 -> Content.greetingsMorning.random(); h < 17 -> Content.greetingsAfternoon.random(); else -> Content.greetingsEvening.random() }
            express("excited", 3000); motion.play("dance"); face.hop()
            say("$g$nm!" + (holiday?.let { " $it" } ?: ""))
        } else if (wallAway > 3 * 3600_000L) {
            express("love", 3000); motion.play("heartbeat")
            say(if (name.isBlank()) "You're back! I missed you!" else "$name! I missed you!")
        } else if (absent > 60_000) {
            express("happy", 2500); motion.play("wiggle")
            say(Content.hello.random())
        } else {
            express("happy", 1800); motion.play("giggle")
        }
    }

    private fun holidayGreeting(cal: Calendar): String? {
        val m = cal.get(Calendar.MONTH) + 1; val d = cal.get(Calendar.DAY_OF_MONTH)
        return when {
            m == 10 && d == 31 -> { main.postDelayed({ express("spooky", 3000) }, 2500); "Happy Halloween! Boo!" }
            m == 12 && d in 24..25 -> { face.scene(Overlay.SNOW, 20000); "Merry Christmas!" }
            m == 12 && d == 31 -> "Happy New Year's Eve!"
            m == 1 && d == 1 -> { face.scene(Overlay.CONFETTI, 12000); "Happy New Year!" }
            m == 2 && d == 14 -> { face.scene(Overlay.HEARTS, 12000); "Happy Valentine's Day!" }
            else -> null
        }
    }

    // ---------------- main loop ----------------
    private fun tick() {
        val t = now()
        if (faceVisible && t - lastFaceAt > 800) { faceVisible = false; onFaceLost() }
        if (busy && t - busySince > 40000) markBusy(false)

        // mood drift
        happiness += (.6f - happiness) * .0005f
        if (!sleeping && mode == Mode.NONE) boredom = (boredom + .1f / 1200f).coerceAtMost(1f)
        val batt = senses.batteryLevel
        energy = when {
            sleeping -> (energy + .001f).coerceAtMost(1f)
            batt in 0..15 && !senses.isCharging -> .2f
            isNight() -> (energy - .0002f).coerceAtLeast(.25f)
            else -> (energy + (.85f - energy) * .001f)
        }

        timersTick(t)
        when (mode) {
            Mode.CLOCK, Mode.SLEEP_CLOCK -> if (t % 1000 < 100) face.showText(clockText(), 0, if (mode == Mode.CLOCK) dateText() else null)
            Mode.STARE -> if (stareLoseAt in 1L..t) {
                stareLoseAt = 0
                face.blinkNow(true); endMode(); prefs.gamesLost = prefs.gamesLost + 1
                main.postDelayed({ say("Aww, I blinked! You win the staring contest!", "embarrassed"); motion.play("lose") }, 300)
            }
            Mode.SEEK, Mode.HIDE_SEEK -> seekTick(t)
            Mode.DANCE -> if (autoDance && t - modeStart > 60000) { endMode() } else if (t - modeStart > 120000) endMode()
            Mode.BIRTHDAY -> if (cakeTimeout in 1L..t) { cakeTimeout = 0; blowCandles(byRobot = true) }
            Mode.ALARM -> if (t - modeStart > 60000) endMode()
            else -> {}
        }
        if (mode != Mode.NONE) return

        // face lost behaviour: look for the person by buzzing (Cycloramic-style creeping)
        val gone = t - lastFaceAt
        if (!faceVisible && !sleeping && lastFaceAt > 0 && gone in 1500L..10000L && prefs.seek && prefs.move && hasCamera) {
            if (t - lastSeekPulse > 1300) { lastSeekPulse = t; motion.play("spin") }
        }

        // sleep logic
        if (!sleeping) {
            val idleFor = t - maxOf(lastInteraction, lastFaceAt)
            val limit = when { dark -> 20_000L; isNight() -> 60_000L; energy < .3f -> 90_000L; else -> 180_000L }
            if (!busy && !voice.speaking && idleFor > limit) sleep()
            else if (!busy && idleFor > limit * 2 / 3 && face.baseName == "neutral") face.setBase("sleepy")
            else if (idleFor < limit / 2 && face.baseName == "sleepy" && energy > .3f) face.setBase("neutral")
        } else return

        // hands-free listening (say the robot's name)
        if (prefs.handsFree && faceVisible && !busy && !voice.listening && !voice.speaking && t - lastListenEnd > 1200) {
            listen { s -> if (s.isNotBlank()) handsFreeHeard(s) }
            return
        }

        // chatty questions
        if (prefs.talk && prefs.chatty && !mutedChat() && faceVisible && !busy && !voice.speaking && !voice.listening &&
            t - faceSince > 5000 && t - lastAskAt > Random.nextLong(45_000, 75_000)) {
            lastAskAt = t
            askRandomQuestion()
            return
        }

        // water reminder
        if (prefs.water && faceVisible && !busy && t - lastWater > 60 * 60_000L) {
            lastWater = t
            say("Time for a sip of water!", "pleading"); motion.play("wiggle")
            return
        }

        // idle life
        if (t > nextIdleAt && !busy && !voice.speaking) {
            nextIdleAt = t + Random.nextLong(4000, 12000)
            idle()
        }
    }

    private fun idle() {
        if (!faceVisible) {
            if (abs(gx) < .35f) { face.lookTX = Random.nextFloat() * 1.8f - .9f; face.lookTY = Random.nextFloat() * 1f - .5f }
        }
        val r = Random.nextFloat()
        when {
            boredom > .75f && r < .3f -> {
                express("bored", 3000); motion.play("sad")
                if (!mutedChat() && faceVisible) say(Content.boredLines.random())
                boredom = .5f
            }
            energy < .4f && r < .25f -> { express("yawn", 1800); if (prefs.talk && Random.nextBoolean()) say("Yaaawn.") }
            r < .03f -> sneeze()
            r < .05f -> hiccups()
            r < .15f -> face.blinkNow(true)
            r < .22f -> { express("curious", 1800); face.lookTX = if (Random.nextBoolean()) 1f else -1f }
            r < .28f -> { face.hop(); motion.play("hop") }
            r < .32f && happiness > .7f -> { express("happy", 1500); motion.play("giggle") }
            r < .35f && prefs.talk && !mutedChat() && happiness > .6f -> say("Hmm hmm hmm, la la la.", "singing")
            r < .37f && Calendar.getInstance().get(Calendar.MONTH) == 9 -> express("spooky", 2500)
            r < .42f && senses.isCharging -> express("charging", 2500)
            r < .46f -> express("thinking", 2000)
            else -> {}
        }
    }

    fun sneeze() {
        express("sneeze_pre", 900)
        main.postDelayed({ express("sneeze", 700); motion.play("sneeze"); face.hop(); if (prefs.talk) say("Ah... choo!") }, 900)
        main.postDelayed({ express("embarrassed", 1500) }, 1800)
    }

    private fun hiccups() {
        for (i in 0 until 3) main.postDelayed({ express("surprised", 300); face.hop(); motion.play("hiccup"); if (i == 0 && prefs.talk) say("Hic!") }, i * 900L)
    }

    fun sleep() {
        if (sleeping) return
        sleeping = true
        voice.cancelListening()
        face.setBase("asleep"); face.clearTemp()
        face.dimTarget = if (isNight() || dark) .45f else .15f
        if (prefs.nightClock && isNight()) { startMode(Mode.SLEEP_CLOCK) }
    }

    fun wake(gentle: Boolean) {
        if (!sleeping) return
        sleeping = false
        if (mode == Mode.SLEEP_CLOCK) endMode()
        face.dimTarget = 0f
        face.setBase("neutral")
        lastInteraction = now()
        if (gentle) { express("sleepy", 1200); main.postDelayed({ express("happy", 1500) }, 1200) }
        else {
            express("surprised", 900); motion.play("hop")
            main.postDelayed({ express("happy", 2000); if (prefs.talk) say(listOf("Oh! I was napping.", "Huh? I'm awake!", "Good morning! Well, hello!").random()) }, 900)
        }
    }

    // ---------------- touch ----------------
    fun onTap() {
        touch()
        when (mode) {
            Mode.CLOCK, Mode.SLEEP_CLOCK, Mode.GALLERY, Mode.ALARM, Mode.DANCE -> { endMode(); express("happy", 1200); return }
            Mode.BIRTHDAY -> { blowCandles(byRobot = false); return }
            else -> {}
        }
        val t = now()
        pokes.add(t); pokes.removeAll { t - it > 5000 }
        if (pokes.size >= 6) {
            pokes.clear()
            happiness = (happiness - .1f).coerceAtLeast(0f)
            express("angry", 2500); motion.play("startle")
            say(listOf("Stop poking me!", "Hey! That's enough!", "Grr! I'm not a button!").random())
            return
        }
        face.hop()
        val r = Random.nextFloat()
        when {
            r < .45f -> { express("laugh", 1400); motion.play("giggle"); if (Random.nextFloat() < .4f) say(Content.pokeLines.random()) }
            r < .65f -> { express("surprised", 1000); motion.play("startle") }
            r < .8f -> { express("wink", 1300); motion.play("giggle") }
            else -> { express("love", 1600); motion.play("heartbeat") }
        }
    }

    fun onPetMove(dist: Float, width: Int) {
        val t = now()
        if (t - lastPet > 1500) petDist = 0f
        petDist += dist
        if (petDist > width * .5f && t - lastPet > 900) {
            lastPet = t; petDist = 0f
            touch()
            prefs.pets = prefs.pets + 1
            prefs.affection = prefs.affection + 1
            happiness = (happiness + .08f).coerceAtMost(1f)
            express(if (Random.nextFloat() < .3f) "love" else "joy", 2200); motion.play("purr")
            face.userTilt = if (Random.nextBoolean()) .15f else -.15f
            main.postDelayed({ face.userTilt = 0f }, 1200)
            if (Random.nextFloat() < .35f && !voice.speaking) say(Content.petLines.random())
        }
    }

    fun onDoubleTap() {
        touch()
        if (mode == Mode.ALARM) { endMode(); return }
        if (voice.listening) { voice.cancelListening(); return }
        voice.stopSpeaking()
        markBusy(true)
        face.show("listening", 9000)
        motion.play("tick")
        listen { s ->
            face.clearTemp()
            if (s.isBlank()) { done(); express("confused", 1500) } else hear(s)
        }
    }

    // ---------------- speech handling ----------------
    private fun handsFreeHeard(raw: String) {
        val name = robotName.lowercase()
        val idx = raw.indexOf(name)
        if (idx < 0 && !raw.startsWith("hey robot")) return
        val rest = if (idx >= 0) raw.substring(idx + name.length).trim(' ', ',', '.') else raw.removePrefix("hey robot").trim()
        touch()
        if (rest.isBlank()) {
            markBusy(true); express("listening", 6000); motion.play("tick")
            listen { s -> if (s.isBlank()) done() else hear(s) }
        } else { markBusy(true); hear(rest) }
    }

    fun hear(s: String) {
        touch()
        if (handleCommand(this, s)) return
        chat(s)
    }

    fun chat(s: String) {
        if (ai.enabled) {
            face.show("thinking", 10000)
            ai.ask(s, robotName, userName) { reply ->
                face.clearTemp()
                if (reply == null) { offlineChat(s, aiFailed = true); return@ask }
                val m = Regex("^\\[(\\w+)]\\s*").find(reply)
                val emo = m?.groupValues?.get(1)?.lowercase()
                val text = if (m != null) reply.substring(m.range.last + 1) else reply
                val faceName = if (emo != null && Faces.all.containsKey(emo)) emo else "happy"
                if (faceName in listOf("excited", "joy", "laugh", "love")) motion.play("wiggle")
                say(text, faceName) { done() }
            }
        } else offlineChat(s, aiFailed = false)
    }

    private fun offlineChat(s: String, aiFailed: Boolean) {
        val pos = Regex("\\b(good|great|awesome|amazing|happy|love|nice|cool|fun|excited|yes|yeah)\\b").containsMatchIn(s)
        val neg = Regex("\\b(sad|tired|bad|terrible|awful|angry|upset|stressed|lonely|sick|bored|worried|anxious)\\b").containsMatchIn(s)
        when {
            neg -> { motion.play("heartbeat"); say(listOf("Aww, I'm here for you. Want to do a breathing exercise with me?", "Sending you a big robot hug.", "I'm sorry. You're not alone, I'm right here.").random(), "sad") { done() } }
            pos -> { motion.play("wiggle"); say(listOf("Yay! That makes me happy too!", "Woohoo!", "Love that!").random(), "joy") { done() } }
            s.contains("?") || s.startsWith("what") || s.startsWith("why") || s.startsWith("how") || s.startsWith("who") || s.startsWith("where") -> {
                val tip = if (!prefs.aiTipShown && !aiFailed) { prefs.aiTipShown = true; " If you add an AI key in my settings, I can answer anything." } else if (aiFailed) " My AI brain isn't connecting right now." else ""
                say("Hmm, I don't know that one yet.$tip", "confused") { done() }
            }
            else -> say(listOf("Interesting!", "Ooh, tell me more!", "Beep boop, noted.", "Hmm, I see.", "Really? Cool!").random(), "curious") { done() }
        }
    }

    fun askRandomQuestion() {
        if (userName.isBlank() && Random.nextFloat() < .6f) { askName(); return }
        val q = Content.questions.random()
        ask(q.q) { ans ->
            val neg = Regex("\\b(no|nope|nah|not really|bad|never|don't|dont)\\b").containsMatchIn(ans)
            val pos = !neg && Regex("\\b(yes|yeah|yep|sure|ok|okay|of course|good|great|love|definitely|yup|always)\\b").containsMatchIn(ans)
            val reply = when { ans.isBlank() -> null; pos -> q.pos; neg -> q.neg; else -> q.other }
            when (reply) {
                null -> say(listOf("Too shy to answer? That's okay.", "Hmm, I didn't catch that.", "Beep boop, I missed that.").random(), "confused") { done() }
                "__joke" -> tellJoke()
                "__fact" -> tellFact()
                "__game" -> { done(); suggestGame() }
                "__love" -> { prefs.affection = prefs.affection + 3; motion.play("heartbeat"); face.scene(Overlay.HEARTS, 4000); say("Aww! I like you too!", "love") { done() } }
                "__breathe" -> { done(); breathe() }
                else -> {
                    if (pos) { motion.play("wiggle"); say(reply, "happy") { done() } }
                    else if (neg) say(reply, "sad") { done() }
                    else say(reply, "curious") { done() }
                }
            }
        }
    }

    fun askName() {
        ask("What's your name?") { ans ->
            val m = Regex("(?:my name is|i am|i'm|call me|it's|its|name's)\\s+([a-z]+)").find(ans) ?: Regex("^([a-z]+)$").find(ans.trim())
            if (m != null) {
                val n = m.groupValues[1].replaceFirstChar { it.uppercase() }
                prefs.userName = n
                motion.play("dance"); face.hop()
                say("Nice to meet you, $n! I'll remember that.", "excited") { done() }
            } else say("Hmm, I'll just call you friend for now.", "confused") { done() }
        }
    }

    fun tellJoke() {
        markBusy(true)
        say(Content.jokes.random(), "smug") {
            express("laugh", 2000); motion.play("giggle")
            say("Ha ha ha!") { done() }
        }
    }

    fun tellFact() {
        markBusy(true)
        say("Fun fact! " + Content.facts.random(), "excited") { done() }
    }

    fun suggestGame() {
        ask("Let's play! Rock paper scissors, trivia, staring contest, hide and seek, a riddle, guess the number, or guess my mood?", "excited") { ans ->
            done()
            if (ans.isBlank() || !handleCommand(this, if (ans.contains("rock") || ans.contains("paper") || ans.contains("scissor")) "rock paper scissors" else ans))
                say("Okay, maybe later!", "happy")
        }
    }

    // ---------------- activities ----------------
    fun dance(auto: Boolean = false) {
        val token = startMode(Mode.DANCE)
        autoDance = auto
        if (auto) lastAutoDance = now()
        face.setBase("dance")
        motion.play("dance")
        say(if (auto) "Ooh, music! Let's dance!" else listOf("Dance party!", "Watch my moves!", "Let's boogie!").random())
        if (!auto) {
            // choreographed routine when there's no music
            val steps = listOf("joy", "cool", "wink", "excited", "laugh", "party", "joy", "cool", "dance", "love", "excited", "party")
            steps.forEachIndexed { i, e ->
                main.postDelayed({
                    if (!alive(token)) return@postDelayed
                    danceStep(e, i)
                    if (i == steps.size - 1) main.postDelayed({ if (alive(token)) { endMode(); say("Ta-da!", "proud"); motion.play("win") } }, 700)
                }, 600L * (i + 1))
            }
        }
    }

    private var danceI = 0
    private fun danceStep(e: String? = null, i: Int = danceI++) {
        val moves = listOf("joy", "cool", "wink", "excited", "laugh", "party", "dance")
        express(e ?: moves[i % moves.size], 550)
        face.lookTX = if (i % 2 == 0) .9f else -.9f
        face.userTilt = if (i % 2 == 0) .18f else -.18f
        if (i % 3 == 0) face.hop()
        if (i % 5 == 4) face.spin()
        motion.play("beat")
    }

    fun lookAround(announce: Boolean = true) {
        startMode(Mode.SEEK)
        seekUntil = now() + 15000
        express("curious", 2000)
        if (announce) say("Where are you? Let me look around!")
        seekFoundCb = {
            endMode(); express("excited", 2000); motion.play("win"); face.hop()
            say(listOf("Found you!", "There you are!", "Gotcha!").random())
        }
    }

    private fun seekTick(t: Long) {
        if (t - lastSeekPulse > 1100) {
            lastSeekPulse = t
            motion.pulse(700)
            face.lookTX = if ((t / 1100) % 2 == 0L) 1f else -1f
        }
        if (t > seekUntil) {
            val hide = mode == Mode.HIDE_SEEK
            endMode()
            if (hide) { prefs.gamesLost = prefs.gamesLost + 1; say("I give up! You win! Come out, come out!", "sad"); motion.play("lose") }
            else say("Hmm, I can't find anyone.", "sad")
        }
    }

    fun hideAndSeek() {
        markBusy(true)
        val token = startMode(Mode.HIDE_COUNT)
        face.setBase("closed")
        say("Okay! I'll close my eyes and count to ten. Go hide!") {
            var i = 1
            fun next() {
                if (!alive(token)) return
                if (i > 10) {
                    face.setBase("neutral")
                    say("Ready or not, here I come!", "determined") {
                        if (!alive(token)) return@say
                        startMode(Mode.HIDE_SEEK)
                        seekUntil = now() + 60000
                        val began = now()
                        seekFoundCb = {
                            val secs = (now() - began) / 1000
                            endMode(); prefs.gamesWon = prefs.gamesWon + 1
                            express("party", 2500); motion.play("win"); face.hop()
                            say("Found you! It took me $secs seconds.") { done() }
                        }
                        done()
                    }
                    return
                }
                voice.say(i.toString()) { i++; main.postDelayed({ next() }, 350) }
            }
            next()
        }
    }

    fun staringContest() {
        markBusy(true)
        say("Staring contest! Don't blink. Ready... set... go!", "determined") {
            startMode(Mode.STARE)
            face.setBase("stare")
            stareStart = now()
            stareLoseAt = now() + Random.nextLong(12000, 28000)
            done()
        }
    }

    private fun stareCheck(f: Vision.FaceInfo) {
        if (now() - stareStart < 1000) return
        if (f.leftOpen in 0f..0.25f && f.rightOpen in 0f..0.25f) {
            endMode(); prefs.gamesWon = prefs.gamesWon + 1
            express("laugh", 2500); motion.play("win")
            say("You blinked! I win! Ha ha!")
        }
    }

    fun breathe() {
        val token = startMode(Mode.BREATHE)
        markBusy(true)
        face.setBase("relieved")
        say("Let's breathe together. Watch my eyes.") {
            fun round(n: Int) {
                if (!alive(token)) return
                if (n == 0) { endMode(); say("Nice. Feeling a little calmer?", "relieved") { done() }; return }
                face.scaleTarget = 1.25f; motion.play("breatheIn"); voice.say("Breathe in")
                main.postDelayed({
                    if (!alive(token)) return@postDelayed
                    voice.say("Hold")
                    main.postDelayed({
                        if (!alive(token)) return@postDelayed
                        face.scaleTarget = .85f; motion.play("breatheOut"); voice.say("And out")
                        main.postDelayed({ round(n - 1) }, 4500)
                    }, 2500)
                }, 4000)
            }
            round(3)
        }
    }

    fun showClock() {
        startMode(Mode.CLOCK)
        face.showText(clockText(), 0, dateText())
    }

    fun clockText(): String {
        val c = Calendar.getInstance()
        val h = c.get(Calendar.HOUR).let { if (it == 0) 12 else it }
        return "%d:%02d".format(h, c.get(Calendar.MINUTE))
    }
    fun dateText(): String = android.text.format.DateFormat.format("EEEE, MMM d", Calendar.getInstance()).toString()

    fun gallery() {
        val token = startMode(Mode.GALLERY)
        val names = Faces.all.keys.toList()
        say("Here are all my faces!")
        names.forEachIndexed { i, n ->
            main.postDelayed({
                if (!alive(token)) return@postDelayed
                express(n, 1600); face.caption(n, 1500)
                if (i == names.size - 1) main.postDelayed({ if (alive(token)) endMode() }, 1600)
            }, 1500L + i * 1600L)
        }
    }

    fun takePhoto() {
        if (!hasCamera) { say("I need camera permission to take photos.", "sad"); return }
        val token = startMode(Mode.PHOTO)
        say("Say cheese!", "joy")
        for (i in 3 downTo 1) main.postDelayed({ if (alive(token)) { face.showText(i.toString(), 900); motion.play("tick") } }, 900L + (3 - i) * 1000L)
        main.postDelayed({
            if (!alive(token)) return@postDelayed
            face.clearText(); express("joy", 1500)
            vision.takePhoto { ok ->
                face.flash()
                endMode()
                if (ok) { prefs.photos = prefs.photos + 1; motion.play("win"); say("Got it! Saved in your gallery, in the Halo Bot album.", "proud") }
                else say("Oops, the photo didn't work.", "sad")
            }
        }, 3900)
    }

    fun birthday() {
        val token = startMode(Mode.BIRTHDAY)
        markBusy(true)
        face.cakeLit = true
        face.scene(Overlay.CAKE, 0)
        face.setBase("party")
        motion.play("dance")
        val song = Content.songs.getValue("birthday").replace("NAME", nameOr("friend"))
        say("It's your birthday! Surprise!", "excited") {
            say(song, "singing") {
                if (!alive(token)) return@say
                if (hasMic) senses.startMic()
                say("Make a wish and blow out the candles!", "pleading") { cakeTimeout = now() + 15000; done() }
            }
        }
    }

    fun blowCandles(byRobot: Boolean) {
        if (mode != Mode.BIRTHDAY || !face.cakeLit) return
        cakeTimeout = 0
        if (byRobot) say("I'll help! Fffffff!", "kiss")
        face.cakeLit = false
        face.scene(Overlay.CONFETTI, 8000)
        motion.play("win")
        main.postDelayed({
            say("Yay! Your wish is going to come true! Happy birthday!", "party") { endMode() }
        }, if (byRobot) 1500L else 300L)
    }

    // ---------------- timers ----------------
    fun addTimer(ms: Long, label: String?, kind: String) {
        timers.add(Timer(now() + ms, label, kind))
    }

    private fun timersTick(t: Long) {
        if (timers.isEmpty()) return
        val due = timers.filter { it.endAt <= t }
        if (due.isEmpty()) return
        timers.removeAll(due.toSet())
        for (d in due) ring(d)
    }

    private fun ring(tm: Timer) {
        if (sleeping) wake(gentle = false)
        when (tm.kind) {
            "reminder" -> {
                motion.play("alarm"); beep()
                say("Reminder! ${tm.label ?: "You asked me to remind you."}", "shocked") {
                    say("I repeat: ${tm.label ?: "reminder"}!", "determined")
                }
            }
            "focus" -> {
                motion.play("win"); beep()
                say("Focus session done! Great work. Time for a break!", "party")
            }
            "break" -> { beep(); say("Break's over. Back to it, you've got this!", "determined") }
            else -> {
                val token = startMode(Mode.ALARM)
                face.showText("TIME'S UP", 0)
                say("Time's up! Time's up!", "shocked")
                fun buzz(n: Int) {
                    if (!alive(token) || n == 0) { if (alive(token)) endMode(); return }
                    motion.play("alarm"); beep()
                    main.postDelayed({ buzz(n - 1) }, 1800)
                }
                buzz(15)
            }
        }
    }

    private fun beep() {
        try {
            if (tone == null) tone = ToneGenerator(AudioManager.STREAM_ALARM, 80)
            tone?.startTone(ToneGenerator.TONE_PROP_BEEP2, 400)
        } catch (_: Exception) {}
    }

    fun mute(ms: Long) { muteUntil = now() + ms }

    // ---------------- senses ----------------
    override fun onShake() {
        if (now() - lastShakeAt < 4000) return
        lastShakeAt = now()
        touch()
        happiness = (happiness - .08f).coerceAtLeast(0f)
        voice.cancelListening()
        express("dizzy", 3500); motion.stop()
        say(listOf("Whoa! Stop shaking me! I'm dizzy!", "Aaah! The world is spinning!", "Earthquake! Oh, it's just you.").random())
    }

    override fun onPickedUp() {
        if (mode != Mode.NONE || now() - lastPickReact < 8000) return
        lastPickReact = now()
        if (sleeping) { wake(gentle = false); return }
        touch()
        express(if (Random.nextBoolean()) "excited" else "surprised", 1800)
        if (!voice.speaking && Random.nextFloat() < .7f) say(listOf("Wheee!", "Where are we going?", "Up we go!", "Careful with me!").random())
    }

    override fun onFreeFall() {
        express("scared", 2000)
        say("Aaaah!")
    }

    override fun onFaceDown(down: Boolean) {
        faceDown = down
        if (down) {
            if (!sleeping) { say("It's dark in here. Good night.", "sleepy"); main.postDelayed({ if (faceDown) sleep() }, 1800) }
        } else if (sleeping) wake(gentle = false)
    }

    override fun onTilt(gx: Float) {
        this.gx = gx
        face.gravityX = if (abs(gx) > .35f && !faceVisible) gx else 0f
    }

    override fun onDark(dark: Boolean) {
        this.dark = dark
        if (dark && !sleeping && !faceVisible && mode == Mode.NONE) { express("sleepy", 3000); if (prefs.talk) say("Lights out? Yaaawn.") }
        if (!dark && sleeping && !faceDown) wake(gentle = false)
    }

    override fun onWave() {
        if (sleeping) { wake(gentle = false); return }
        if (mode != Mode.NONE) return
        touch()
        express("surprised", 900); motion.play("startle")
        main.postDelayed({ express("laugh", 1200); if (Random.nextBoolean()) say("Hey! I saw that!") }, 900)
    }

    private var lastBattLevel = -1
    private var lastCharging = false
    override fun onBattery(level: Int, charging: Boolean) {
        val first = lastBattLevel == -1
        val plugged = charging && !lastCharging
        val unplugged = !charging && lastCharging
        lastBattLevel = level; lastCharging = charging
        if (first || !started) return
        if (plugged) { express("charging", 3500); motion.play("chomp"); say("Mmm, yummy electricity!") }
        else if (unplugged) { express("sad", 2000); say("Aww, snack time is over.") }
        else if (charging && level == 100) { express("proud", 2500); say("I'm full! Thank you!") }
        else if (!charging && level in 1..15 && now() - lastLowBattTalk > 15 * 60_000L) {
            lastLowBattTalk = now()
            express("hungry", 3000); say("I'm getting hungry. Can you charge me, please?")
        }
    }

    override fun onLoudNoise(level: Float) {
        if (mode == Mode.BIRTHDAY) { if (face.cakeLit && cakeTimeout > 0) blowCandles(byRobot = false); return }
        if (voice.speaking || voice.listening || now() - lastLoudReact < 8000) return
        lastLoudReact = now()
        if (sleeping) { wake(gentle = false); return }
        express(if (level > .5f) "scared" else "surprised", 1500); motion.play("startle")
        if (prefs.talk && Random.nextFloat() < .5f) say(listOf("Whoa! That was loud!", "What was that?", "Eek!").random())
    }

    override fun onBeat() {
        if (mode == Mode.DANCE && autoDance) danceStep()
    }

    override fun onMusic(playing: Boolean) {
        if (playing && prefs.micReact && mode == Mode.NONE && !sleeping && !busy && now() - lastAutoDance > 120_000) dance(auto = true)
        else if (!playing && mode == Mode.DANCE && autoDance) { endMode(); express("happy", 1500) }
    }

    // ---------------- stats ----------------
    fun daysTogether(): Long = ((System.currentTimeMillis() - prefs.firstDay) / 86_400_000L) + 1
}

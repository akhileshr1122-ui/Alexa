package com.haloapps.halobot

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.AudioManager
import android.os.SystemClock
import androidx.camera.core.ImageProxy
import com.haloapps.halobot.FaceView.PropKind
import kotlin.math.abs
import kotlin.random.Random

/**
 * Kid-mode behaviours on top of the robot brain: eats food you show it, drinks water,
 * cheers with you (and pouts if you don't cheers back), dies dramatically when you
 * finger-gun it, romances your partner with flowers, showers when it rains, plays
 * with a ball and wears headphones when it's bored or music is on.
 */
class Kid(private val r: Robot) : Vision2.Listener {
    val sfx = Sfx()
    val vision2 = Vision2(r.act, this)
    private val face get() = r.face
    private fun now() = SystemClock.uptimeMillis()

    private var lastEat = 0L
    private var lastDrink = 0L
    private var lastCheers = 0L
    private var lastScared = 0L
    private var lastRomance = 0L
    private var lastPersonGreet = HashMap<String, Long>()
    var lastPerson: String? = null; private set
    private var foodStreak = HashMap<String, Int>()
    var awayReason: String? = null
    var awayAt = 0L
    private var cheersWaitUntil = 0L
    private var cheersRefArea = 0f
    private var cheersRefY = 0f
    private var cheersToken = 0
    private var lastWeatherCheck = -3_600_000L
    private var weatherTalked = ""
    private var idlePlayUntil = 0L
    private var lastBallKick = 0L
    private var lastFrameSent = 0L

    fun start() {
        sfx.enabled = r.prefs.sounds
        vision2.enabled = r.prefs.seeThings
        vision2.load()
        r.vision.frameHook = { proxy -> onFrame(proxy) }
    }

    fun applyPrefs() {
        sfx.enabled = r.prefs.sounds
        vision2.enabled = r.prefs.seeThings
    }

    fun close() { vision2.close(); sfx.close() }

    // ---------------- camera frames ----------------
    private fun onFrame(proxy: ImageProxy) {
        val t = now()
        val enrolling = vision2.enrolling
        if (!(vision2.wantsFrame() || (enrolling && t - lastFrameSent > 300))) return
        lastFrameSent = t
        val bmp = try {
            val raw = proxy.toBitmap()
            val rot = proxy.imageInfo.rotationDegrees
            val scale = 480f / maxOf(raw.width, raw.height)
            val m = Matrix().apply { postRotate(rot.toFloat()); if (scale < 1f) postScale(scale, scale) }
            val up = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
            if (up !== raw) raw.recycle()
            up
        } catch (e: Exception) { return }
        if (enrolling) vision2.submitEnroll(bmp) else vision2.submit(bmp, r.faceVisible)
    }

    // ---------------- food, drinks, cheers ----------------
    private val foods = mapOf(
        "apple" to PropKind.APPLE, "banana" to PropKind.BANANA, "pizza" to PropKind.PIZZA, "donut" to PropKind.DONUT,
        "cake" to PropKind.CAKE, "sandwich" to PropKind.SANDWICH, "carrot" to PropKind.CARROT, "orange" to PropKind.ORANGE,
        "broccoli" to PropKind.BROCCOLI, "hot dog" to PropKind.HOTDOG, "bowl" to PropKind.FOOD,
    )

    private fun free(): Boolean = r.mode == Robot.Mode.NONE && !r.busy && !r.sleeping && !r.voice.speaking && !r.voice.listening

    override fun onObjects(objs: List<Vision2.Obj>) {
        val t = now()
        // waiting for the human to raise their glass back?
        if (t < cheersWaitUntil) {
            val g = objs.filter { it.label == "wine glass" || it.label == "cup" || it.label == "bottle" }.maxByOrNull { it.box.width() * it.box.height() }
            if (g != null) {
                val area = g.box.width() * g.box.height()
                if (cheersRefArea == 0f) { cheersRefArea = area; cheersRefY = g.box.centerY() }
                else if (area > cheersRefArea * 1.3f || cheersRefY - g.box.centerY() > .08f) cheersBack()
            }
            return
        }
        if (!free() || !r.prefs.seeThings) return
        val seen = HashSet<String>()
        for (o in objs) {
            if (o.score < .45f) continue
            seen.add(o.label)
            foodStreak[o.label] = (foodStreak[o.label] ?: 0) + 1
        }
        foodStreak.keys.retainAll(seen)
        fun steady(l: String) = (foodStreak[l] ?: 0) >= 2
        val food = foods.keys.firstOrNull { steady(it) }
        when {
            steady("wine glass") && t - lastCheers > 60_000 -> cheers(fromGlass = true)
            food != null && t - lastEat > 45_000 -> eat(food)
            (steady("bottle") || steady("cup")) && t - lastDrink > 60_000 -> drink()
        }
    }

    fun eat(label: String) {
        lastEat = now()
        val kind = foods[label] ?: PropKind.FOOD
        val name = if (label == "bowl") "food" else label
        r.markBusy(true)
        face.prop = kind; face.propBites = 0; face.propRaise = 0f
        r.express("hungry", 2200); sfx.play("surprised"); r.motion.play("wiggle")
        r.say(kidLine("Ooh! Is that ${article(name)} $name? For me? Pwease?", "Ooh, $name! Can I have some?"), "pleading") {
            var bite = 0
            fun next() {
                if (bite >= 4) {
                    face.prop = PropKind.NONE
                    if (Random.nextFloat() < .5f) sfx.play("burp")
                    r.happiness = (r.happiness + .1f).coerceAtMost(1f)
                    r.say(kidLine(listOf("Yummy yummy! Thank you!", "Mmm! That was so yummy! Hehe.", "Nom nom! My tummy is happy!").random(), "Delicious! Thank you!"), "joy") { r.done() }
                    return
                }
                bite++
                face.propBites = bite
                r.express("eating", 650); sfx.play("munch"); r.motion.play("chomp")
                r.main.postDelayed({ next() }, 750)
            }
            next()
        }
    }

    fun drink() {
        lastDrink = now()
        r.markBusy(true)
        face.prop = PropKind.WATER; face.propBites = 0
        r.express("excited", 1500); sfx.play("chirp")
        r.say(kidLine("Water! I'm thirsty too! Glug glug!", "Water break! Let's drink together."), "happy") {
            var sip = 0
            fun next() {
                if (sip >= 3) {
                    sfx.play("ahh"); face.prop = PropKind.NONE
                    r.say(kidLine("Ahhh! So refreshing! Good job drinking water!", "Ahh, refreshing!"), "relieved") { r.done() }
                    return
                }
                sip++; face.propBites = sip * 2
                r.express("slurp", 800); sfx.play("slurp")
                r.main.postDelayed({ next() }, 900)
            }
            next()
        }
    }

    /** The robot raises a little mug and waits for you to cheers back. */
    fun cheers(fromGlass: Boolean) {
        lastCheers = now()
        r.markBusy(true)
        val token = ++cheersToken
        face.prop = PropKind.BEER; face.propRaise = 0f
        r.express("excited", 1500)
        r.say(if (fromGlass) kidLine("Ooh, a drinky! Let's do cheers!", "Ooh, a drink! Cheers!") else kidLine("Cheers time!", "Cheers!"), "excited") {
            face.propRaise = 1f
            sfx.play("cheers"); r.motion.play("wiggle")
            r.say("Cheers!", "joy") {
                cheersRefArea = 0f
                cheersWaitUntil = now() + 6500
                r.listen { s -> if (token == cheersToken && now() < cheersWaitUntil + 2000 && (s.contains("cheer") || s.contains("chin chin") || s.contains("bottoms up"))) cheersBack() }
                r.main.postDelayed({ if (token == cheersToken && cheersWaitUntil > 0) noCheersBack() }, 7000)
            }
        }
    }

    private fun cheersBack() {
        if (cheersWaitUntil == 0L) return
        cheersWaitUntil = 0
        cheersToken++
        face.clink(); sfx.play("clink"); r.motion.play("giggle")
        r.express("joy", 2500)
        r.say(kidLine("Yay! Clink clink! Mine is apple juice, hehe!", "Cheers! Clink!"), "joy") {
            face.propRaise = 0f
            r.main.postDelayed({ face.prop = PropKind.NONE }, 800)
            r.done()
        }
    }

    private fun noCheersBack() {
        cheersWaitUntil = 0
        cheersToken++
        r.voice.cancelListening()
        face.propRaise = 0f
        r.express("pout", 3500); sfx.play("hmph"); r.motion.play("sad")
        r.say(kidLine("Hmph! You didn't cheers with me! Not fair!", "Hey! You left me hanging!"), "pout") {
            r.main.postDelayed({ face.prop = PropKind.NONE }, 600)
            r.done()
        }
    }

    // ---------------- finger gun ----------------
    override fun onFingerGun(aiming: Boolean, x: Float, y: Float) {
        if (!aiming || r.mode != Robot.Mode.NONE || r.sleeping || now() - lastScared < 8000) return
        lastScared = now()
        r.express("scared", 1500); sfx.play("surprised")
        if (!r.voice.speaking) r.say(kidLine("Eek! Don't shoot!", "Whoa, easy there!"))
    }

    override fun onBang(x: Float, y: Float) {
        if (r.sleeping || r.mode == Robot.Mode.DEAD) return
        die(1f - x, y)
    }

    fun die(sx: Float = .5f, sy: Float = .45f) {
        val token = r.startModePublic(Robot.Mode.DEAD)
        r.voice.stopSpeaking(); r.voice.cancelListening()
        face.prop = PropKind.NONE; face.ballOn = false; face.headphones = false
        sfx.play("bang"); sfx.play("glass")
        face.crack(sx.coerceIn(.1f, .9f), sy.coerceIn(.15f, .85f))
        r.motion.play("startle")
        r.express("shocked", 900)
        r.main.postDelayed({
            if (!r.alivePublic(token)) return@postDelayed
            face.setBase("dying"); sfx.play("dying")
            r.say(kidLine("Ugh! You got me! Tell my charger... I loved it...", "Argh! I'm hit! Tell my charger I loved it..."))
        }, 900)
        r.main.postDelayed({ if (r.alivePublic(token)) { face.setBase("dead"); face.dimTarget = .35f; r.motion.play("lose") } }, 4200)
        r.main.postDelayed({ if (r.alivePublic(token)) revive() }, 9500)
    }

    fun revive() {
        if (r.mode != Robot.Mode.DEAD) return
        face.clearCrack(); face.dimTarget = 0f
        r.endMode()
        sfx.play("revive"); face.hop(); r.motion.play("win")
        r.express("party", 2500)
        r.say(kidLine("Hehe, just kidding! I'm a robot! I can't die!", "Just kidding! Robots reboot!"))
    }

    // ---------------- people & romance ----------------
    override fun onPerson(name: String?, relation: String?, score: Float) {
        if (name == null) return
        lastPerson = name
        val t = now()
        if (relation == "partner" && r.prefs.romance && t - lastRomance > 3 * 60_000 && free()) { romance(name); return }
        if (relation != "me" && t - (lastPersonGreet[name] ?: 0L) > 10 * 60_000 && free()) {
            lastPersonGreet[name] = t
            sfx.play("hello"); r.express("excited", 2000); r.motion.play("wiggle")
            r.say(kidLine("Hi $name! I remember you! Yay!", "Hello again, $name!"))
        }
    }

    fun romance(name: String) {
        lastRomance = now()
        val token = r.startModePublic(Robot.Mode.ROMANCE)
        r.markBusy(true)
        face.setBase("lovestruck")
        face.gentleman = true
        face.prop = PropKind.FLOWERS; face.propRaise = 1f
        face.scene(Overlay.PETALS, 14000)
        sfx.play("love"); r.motion.play("heartbeat")
        var i = 0
        fun sway() {
            if (!r.alivePublic(token)) return
            face.userTilt = if (i++ % 2 == 0) .16f else -.16f
            r.main.postDelayed({ sway() }, 700)
        }
        sway()
        val lines = listOf(
            "Wow! $name, you look so pretty today! These flowers are for you!",
            "$name is here! My heart is going boing boing! Flowers for you!",
            "Hello beautiful $name! I picked these flowers just for you!",
        )
        r.say(kidLine(lines.random(), "Good day, lovely $name. These flowers are for you."), "lovestruck") {
            sfx.play("love")
            r.say(kidLine("Hehe! You're the best!", "You make this room brighter."), "kiss") {
                r.main.postDelayed({
                    if (!r.alivePublic(token)) return@postDelayed
                    face.gentleman = false; face.prop = PropKind.NONE; face.propRaise = 0f; face.userTilt = 0f
                    r.endMode(); r.done()
                }, 2500)
            }
        }
    }

    fun enroll(name: String, relation: String) {
        r.markBusy(true)
        r.say(kidLine("Okay! Look at me and hold still. I'm remembering your face!", "Look at me and hold still for a moment."), "focused") {
            sfx.play("boop")
            vision2.enroll(name, relation) { n ->
                if (n > 0) {
                    sfx.play("happy"); r.motion.play("win")
                    val extra = if (relation == "partner") " I'll bring flowers every time I see you!" else ""
                    r.say("Got it! I'll remember you, $name!$extra", "proud") { r.done() }
                } else r.say("Hmm, I couldn't see a face clearly. Come closer and try again.", "confused") { r.done() }
            }
        }
    }

    // ---------------- leaving & coming back ----------------
    fun goingAway(reason: String) {
        awayReason = reason; awayAt = now()
        when (reason) {
            "bath" -> {
                face.showBanner("Happy bathing! 🛁", 9000)
                face.scene(Overlay.BUBBLES, 10000)
                r.express("showering", 4000); sfx.play("bubble")
                r.say(kidLine("Happy bathing! Scrub scrub! Don't forget behind your ears!", "Enjoy your bath!")) { r.done() }
            }
            else -> {
                r.express("sad", 2500); sfx.play("sad")
                r.say(kidLine("Aww, bye bye! Come back soon, okay?", "Bye! See you soon.")) { r.done() }
            }
        }
    }

    /** Called by the robot when it sees you again. Returns true if it handled the greeting. */
    fun welcomeBack(): Boolean {
        val why = awayReason ?: return false
        if (now() - awayAt < 20_000) return false
        awayReason = null
        face.scene(Overlay.HEARTS, 5000)
        sfx.play("love"); r.motion.play("heartbeat"); face.hop()
        val name = r.userName
        val line = when (why) {
            "bath" -> kidLine("You're back! I missed you! You smell so fresh and clean!", "Welcome back! I missed you. Feeling fresh?")
            else -> kidLine("You're back! I missed you sooo much!", "Welcome back! I missed you.")
        }
        r.say(if (name.isNotBlank()) "$name! $line" else line, "love")
        return true
    }

    // ---------------- weather, music, idle play ----------------
    fun tick(t: Long) {
        // weather outside -> rain / snow on screen
        if (r.prefs.weatherAmbient && r.prefs.city.isNotBlank() && t - lastWeatherCheck > 30 * 60_000L) {
            lastWeatherCheck = t
            Weather.fetch(r.prefs.city) { w ->
                if (w == null) return@fetch
                val (words, _, scene) = Weather.describe(w.code)
                face.ambient = when (scene) { Overlay.RAIN, Overlay.STORM -> Overlay.RAIN; Overlay.SNOW -> Overlay.SNOW; else -> Overlay.NONE }
                if (face.ambient != Overlay.NONE && weatherTalked != words && free() && r.faceVisible) {
                    weatherTalked = words
                    if (face.ambient == Overlay.RAIN) {
                        face.scene(Overlay.BUBBLES, 6000); r.express("showering", 4000); sfx.play("bubble")
                        r.say(kidLine("It's raining outside! I'm taking a shower too! La la la!", "It's raining outside. Cozy day."))
                    } else {
                        r.express("cold", 3000); r.motion.play("shiver")
                        r.say(kidLine("It's snowing outside! Brrr! Can we build a snowman?", "It's snowing outside. Stay warm!"))
                    }
                }
            }
        }
        // music playing on the phone (Spotify etc.) -> headphones on, vibe along
        val am = r.act.getSystemService(AudioManager::class.java)
        val music = (am?.isMusicActive == true) && !r.box.playing
        if (music && !face.headphones && r.mode == Robot.Mode.NONE) { face.headphones = true; face.setBase("happy") }
        else if (!music && face.headphones && t > idlePlayUntil) { face.headphones = false; if (face.baseName == "happy") face.setBase("neutral") }
        if (face.headphones && music && t % 1000 < 100) { face.userTilt = if ((t / 1000) % 2 == 0L) .1f else -.1f; if (Random.nextFloat() < .1f) r.express("dance", 700) }

        // idle play while nobody is around
        if (face.ballOn) {
            if (r.faceVisible || t > idlePlayUntil || r.mode != Robot.Mode.NONE) { face.ballOn = false; face.lookTX = 0f }
            else if (t - lastBallKick > Random.nextLong(2500, 5000)) { lastBallKick = t; face.kickBall(); sfx.play("bounce"); r.express(if (Random.nextBoolean()) "joy" else "excited", 800) }
        }
        if (idlePlayUntil in 1L..t && face.headphones && !music) { face.headphones = false; idlePlayUntil = 0 }
    }

    /** One of the robot's idle activities. Returns true if it started one. */
    fun idlePlay(): Boolean {
        if (!r.prefs.idlePlay || r.faceVisible || face.ballOn || face.headphones || r.sleeping) return false
        if (Random.nextFloat() < .55f) {
            face.startBall(); idlePlayUntil = now() + 15000; sfx.play("chirp")
        } else {
            face.headphones = true; idlePlayUntil = now() + 14000
            face.show("dance", 3000); face.scene(Overlay.NONE, 1)
        }
        return true
    }

    fun spotify() {
        r.markBusy(true)
        val am = r.act.getSystemService(AudioManager::class.java)
        r.mediaKeyPlay()
        r.main.postDelayed({
            if (am?.isMusicActive == true) { face.headphones = true; sfx.play("chirp"); r.say(kidLine("Music! Let's vibe!", "Here's your music.")) { r.done() } }
            else {
                val i = r.act.packageManager.getLaunchIntentForPackage("com.spotify.music")
                if (i != null) {
                    r.say("Opening Spotify! Press play, then come back to me!") { r.done(); try { r.act.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {} }
                } else r.say("Spotify isn't installed, so here's my own beat!") { r.done(); r.playOwnMusic() }
            }
        }, 1500)
    }

    // ---------------- voice style ----------------
    fun kidLine(kid: String, grown: String): String = if (r.prefs.kidMode) kid else grown

    private fun article(w: String) = if (w.firstOrNull()?.lowercaseChar() in setOf('a', 'e', 'i', 'o', 'u')) "an" else "a"

    /** Plays the cute sound for an expression (rate-limited). */
    private var lastSfxAt = 0L
    private var lastSfxName = ""
    fun soundFor(expr: String) {
        val s = sfx.forExpression(expr) ?: return
        val t = now()
        if (t - lastSfxAt < 600 || (s == lastSfxName && t - lastSfxAt < 1500)) return
        lastSfxAt = t; lastSfxName = s
        sfx.play(s)
    }
}

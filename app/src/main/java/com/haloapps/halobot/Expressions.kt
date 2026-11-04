package com.haloapps.halobot

/**
 * Procedural eye shapes. Each eye is a rounded rectangle that can be stretched, rounded,
 * shifted, cut by an angled top lid (sad / angry) and a curved bottom lid (happy squint).
 */
data class Eye(
    val w: Float = 1f,
    val h: Float = 1f,
    val r: Float = .32f,
    val dy: Float = 0f,
    val top: Float = 0f,
    val ang: Float = 0f,
    val bot: Float = 0f,
)

enum class EyeStyle { NORMAL, HEART, STAR, SPIRAL, CROSS, SHADES, ARC, LINE, DOT }
enum class Mouth { NONE, SMILE, GRIN, O, SMALL_O, FROWN, FLAT, WAVY, TONGUE, CAT, CHOMP, BIG_O }
enum class Overlay { NONE, ZZZ, TEARS, SWEAT, QUESTION, EXCLAIM, NOTES, ANGER, HEARTS, SPARKLES, SNOW, RAIN, SUN, CLOUD, STORM, CAKE, PUMPKIN, CONFETTI, BOLT, STEAM }

data class Face(
    val l: Eye,
    val r: Eye = l,
    val style: EyeStyle = EyeStyle.NORMAL,
    val rStyle: EyeStyle? = null,
    val mouth: Mouth = Mouth.NONE,
    val overlay: Overlay = Overlay.NONE,
    val cheeks: Boolean = false,
    val tint: Int = 0,
    val jitterX: Float = 0f,
    val jitterY: Float = 0f,
    val tilt: Float = 0f,
)

object Faces {
    private val BLUE = 0xFF5B8CFF.toInt()
    private val RED = 0xFFFF3B3B.toInt()
    private val ORANGE = 0xFFFF8C1A.toInt()
    private val PURPLE = 0xFFB061FF.toInt()
    private val GOLD = 0xFFFFD23F.toInt()
    private val PINK = 0xFFFF6FA8.toInt()
    private val GREEN = 0xFF4BFF8A.toInt()

    val all: LinkedHashMap<String, Face> = linkedMapOf(
        "neutral" to Face(Eye()),
        "happy" to Face(Eye(bot = .38f), mouth = Mouth.SMILE),
        "joy" to Face(Eye(), style = EyeStyle.ARC, mouth = Mouth.GRIN, cheeks = true),
        "laugh" to Face(Eye(), style = EyeStyle.ARC, mouth = Mouth.GRIN, cheeks = true, jitterY = .05f),
        "excited" to Face(Eye(1.08f, 1.08f), style = EyeStyle.STAR, mouth = Mouth.GRIN, overlay = Overlay.SPARKLES, tint = GOLD),
        "love" to Face(Eye(), style = EyeStyle.HEART, mouth = Mouth.SMILE, overlay = Overlay.HEARTS, cheeks = true, tint = PINK),
        "surprised" to Face(Eye(1.1f, 1.25f, .45f), mouth = Mouth.O),
        "shocked" to Face(Eye(.78f, 1.3f, .45f), mouth = Mouth.BIG_O, overlay = Overlay.EXCLAIM, jitterX = .02f),
        "sad" to Face(Eye(.95f, .85f, top = .32f, ang = -.35f, dy = .06f), mouth = Mouth.FROWN, tint = BLUE),
        "cry" to Face(Eye(.95f, .8f, top = .34f, ang = -.42f, dy = .06f), mouth = Mouth.WAVY, overlay = Overlay.TEARS, tint = BLUE),
        "angry" to Face(Eye(1f, .85f, top = .4f, ang = .45f), mouth = Mouth.FLAT, overlay = Overlay.ANGER, tint = RED, jitterX = .012f),
        "furious" to Face(Eye(1.05f, .8f, top = .45f, ang = .55f), mouth = Mouth.WAVY, overlay = Overlay.STEAM, tint = RED, jitterX = .03f),
        "grumpy" to Face(Eye(1f, .8f, top = .46f, ang = .2f), mouth = Mouth.FLAT),
        "sleepy" to Face(Eye(1f, .9f, top = .6f, dy = .05f)),
        "asleep" to Face(Eye(), style = EyeStyle.LINE, overlay = Overlay.ZZZ),
        "closed" to Face(Eye(), style = EyeStyle.LINE),
        "bored" to Face(Eye(1.05f, .75f, top = .48f, dy = .1f), mouth = Mouth.FLAT),
        "curious" to Face(Eye(1.1f, 1.15f), Eye(.92f, .82f, top = .25f, ang = .12f), overlay = Overlay.QUESTION, tilt = -.08f),
        "confused" to Face(Eye(1.05f, 1.1f), Eye(.9f, .75f, top = .3f, ang = -.2f), mouth = Mouth.WAVY, overlay = Overlay.QUESTION, jitterX = .025f),
        "suspicious" to Face(Eye(1.12f, .5f, top = .18f), mouth = Mouth.FLAT),
        "shy" to Face(Eye(.9f, .85f, bot = .28f, dy = .08f), mouth = Mouth.WAVY, cheeks = true, overlay = Overlay.SWEAT),
        "wink" to Face(Eye(bot = .3f), style = EyeStyle.NORMAL, rStyle = EyeStyle.ARC, mouth = Mouth.SMILE, cheeks = true),
        "smug" to Face(Eye(1.05f, .7f, top = .38f, ang = -.12f), mouth = Mouth.CAT),
        "dizzy" to Face(Eye(), style = EyeStyle.SPIRAL, mouth = Mouth.WAVY, jitterX = .015f),
        "scared" to Face(Eye(.72f, 1.05f, .45f), mouth = Mouth.WAVY, overlay = Overlay.SWEAT, jitterX = .018f, tint = BLUE),
        "focused" to Face(Eye(1f, .7f, top = .26f, ang = .14f), mouth = Mouth.FLAT),
        "thinking" to Face(Eye(1f, 1f, dy = -.04f), Eye(1f, .78f, top = .22f, dy = -.04f), mouth = Mouth.CAT, tilt = .06f),
        "proud" to Face(Eye(bot = .32f, top = .14f), mouth = Mouth.GRIN, cheeks = true, overlay = Overlay.SPARKLES),
        "hungry" to Face(Eye(1.12f, 1.15f), mouth = Mouth.TONGUE),
        "eating" to Face(Eye(), style = EyeStyle.ARC, mouth = Mouth.CHOMP, cheeks = true),
        "dead" to Face(Eye(), style = EyeStyle.CROSS, mouth = Mouth.WAVY),
        "cool" to Face(Eye(), style = EyeStyle.SHADES, mouth = Mouth.SMILE),
        "singing" to Face(Eye(bot = .35f), mouth = Mouth.O, overlay = Overlay.NOTES),
        "dance" to Face(Eye(), style = EyeStyle.ARC, mouth = Mouth.GRIN, overlay = Overlay.NOTES, cheeks = true),
        "listening" to Face(Eye(1.08f, 1.12f, bot = .1f)),
        "charging" to Face(Eye(bot = .35f), mouth = Mouth.CHOMP, overlay = Overlay.BOLT, tint = GREEN),
        "cold" to Face(Eye(.9f, .82f, top = .15f), mouth = Mouth.WAVY, overlay = Overlay.SNOW, jitterX = .02f, tint = BLUE),
        "spooky" to Face(Eye(1f, 1.2f, top = .22f, ang = .3f), mouth = Mouth.GRIN, overlay = Overlay.PUMPKIN, tint = ORANGE),
        "sneeze_pre" to Face(Eye(1f, .6f, top = .32f, ang = -.22f), mouth = Mouth.SMALL_O),
        "sneeze" to Face(Eye(), style = EyeStyle.ARC, mouth = Mouth.BIG_O, jitterY = .06f),
        "yawn" to Face(Eye(1f, .5f, top = .5f), mouth = Mouth.BIG_O),
        "relieved" to Face(Eye(bot = .3f, top = .3f), mouth = Mouth.SMILE),
        "pleading" to Face(Eye(1.2f, 1.25f, .48f), mouth = Mouth.SMALL_O, overlay = Overlay.SPARKLES),
        "evil" to Face(Eye(1f, .7f, top = .3f, ang = .38f), mouth = Mouth.GRIN, tint = PURPLE),
        "stare" to Face(Eye(1.12f, 1.22f, .4f)),
        "tiny" to Face(Eye(.45f, .5f, .5f), style = EyeStyle.DOT, mouth = Mouth.SMALL_O),
        "party" to Face(Eye(), style = EyeStyle.STAR, mouth = Mouth.GRIN, overlay = Overlay.CONFETTI, cheeks = true),
        "kiss" to Face(Eye(), style = EyeStyle.ARC, rStyle = EyeStyle.HEART, mouth = Mouth.SMALL_O, cheeks = true, overlay = Overlay.HEARTS),
        "embarrassed" to Face(Eye(.85f, .7f, top = .2f, dy = .12f), mouth = Mouth.WAVY, cheeks = true, tint = PINK),
        "determined" to Face(Eye(1.05f, .85f, top = .3f, ang = .25f), mouth = Mouth.FLAT, overlay = Overlay.SPARKLES),
    )

    /** Friendly names used when the robot plays "guess my mood". */
    val moodWords: Map<String, List<String>> = mapOf(
        "happy" to listOf("happy", "glad", "good"),
        "sad" to listOf("sad", "unhappy", "down", "blue"),
        "angry" to listOf("angry", "mad", "furious", "annoyed"),
        "surprised" to listOf("surprised", "shocked", "amazed"),
        "sleepy" to listOf("sleepy", "tired"),
        "love" to listOf("love", "in love", "loving"),
        "scared" to listOf("scared", "afraid", "frightened", "nervous"),
        "confused" to listOf("confused", "puzzled"),
        "bored" to listOf("bored"),
        "cool" to listOf("cool", "chill"),
        "dizzy" to listOf("dizzy"),
        "shy" to listOf("shy", "embarrassed"),
    )

    fun get(name: String): Face = all[name] ?: all.getValue("neutral")
}

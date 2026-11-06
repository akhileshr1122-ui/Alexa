package com.haloapps.halobot

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.view.View
import androidx.core.graphics.ColorUtils
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Draws the robot face: two glowing rounded-rect eyes, an optional little mouth,
 * cheeks, particles (hearts, notes, zzz, tears...), big text screens (clock, timer,
 * weather), dice, captions and a listening indicator. Everything is procedural and
 * tweened, so moods blend smoothly into each other.
 */
class FaceView(context: Context) : View(context) {

    private class EyeState(
        var w: Float = 1f, var h: Float = 1f, var r: Float = .32f, var dy: Float = 0f,
        var top: Float = 0f, var ang: Float = 0f, var bot: Float = 0f,
    ) {
        fun lerp(e: Eye, k: Float) {
            w += (e.w - w) * k; h += (e.h - h) * k; r += (e.r - r) * k; dy += (e.dy - dy) * k
            top += (e.top - top) * k; ang += (e.ang - ang) * k; bot += (e.bot - bot) * k
        }
    }

    var theme: EyeTheme = Themes.get(0)
    var scanlines = false

    private val le = EyeState()
    private val re = EyeState()
    private var baseFace = Faces.get("neutral")
    var baseName = "neutral"; private set
    private var tempFace: Face? = null
    var tempName: String? = null; private set
    private var tempUntil = 0L
    val currentName: String get() = tempName ?: baseName
    private val face: Face get() = tempFace ?: baseFace

    // gaze
    var lookTX = 0f
    var lookTY = 0f
    private var lookX = 0f
    private var lookY = 0f
    var gravityX = 0f
    var userTilt = 0f
    private var tilt = 0f
    var scaleTarget = 1f
    private var scale = 1f

    var talking = false
    var listening = false
    var listenLevel = 0f
    var dimTarget = 0f
    private var dim = 0f
    var cakeLit = true

    // blink
    private var blink = 0f
    private var blinkStart = -1L
    private var nextBlink = SystemClock.uptimeMillis() + 2500
    private var doubleBlink = false

    private var tintMix = 0f
    private var tintColor = 0
    private var flashA = 0f
    private var hopStart = -1L
    private var spinStart = -1L

    private var bigText: String? = null
    private var bigSub: String? = null
    private var bigUntil = 0L
    private var dice = 0
    private var diceUntil = 0L
    private var caption: String? = null
    private var captionUntil = 0L
    private var sceneOverlay: Overlay = Overlay.NONE
    private var sceneUntil = 0L

    private var lastFrame = 0L

    // ---------- props & extras (kid mode) ----------
    enum class PropKind { NONE, APPLE, BANANA, PIZZA, DONUT, CAKE, SANDWICH, CARROT, ORANGE, BROCCOLI, HOTDOG, FOOD, WATER, BEER, FLOWERS }
    var prop = PropKind.NONE
    var propBites = 0
    var propRaise = 0f        // 0 = resting, 1 = raised for cheers
    private var propRaiseNow = 0f
    private var clinkStart = -1L
    var headphones = false
    var gentleman = false
    var banner: String? = null
    private var bannerUntil = 0L
    var ambient: Overlay = Overlay.NONE
    private var crackX = -1f
    private var crackY = -1f
    private var crackStart = -1L
    var ballOn = false
    private var ballX = 0f; private var ballY = 0f; private var ballVX = 0f; private var ballVY = 0f
    private var ballSpin = 0f
    private var faceCx = 0f; private var faceCy = 0f; private var faceBase = 1f

    fun showBanner(text: String, ms: Long) { banner = text; bannerUntil = if (ms <= 0) Long.MAX_VALUE else SystemClock.uptimeMillis() + ms }
    fun clink() { clinkStart = SystemClock.uptimeMillis() }
    fun crack(nx: Float, ny: Float) { crackX = nx; crackY = ny; crackStart = SystemClock.uptimeMillis() }
    fun clearCrack() { crackStart = -1 }
    val cracked: Boolean get() = crackStart > 0
    fun startBall() {
        ballOn = true
        ballX = width * .2f; ballY = height * .3f
        ballVX = width * .55f * (if (Math.random() < .5) 1 else -1); ballVY = 0f
    }
    fun kickBall() { if (ballOn) { ballVY = -height * 1.4f; ballVX += (Math.random().toFloat() - .5f) * width * .6f } }

    // paints
    private val eyePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
    private val bigPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD) }
    private val capPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; color = Color.argb(200, 220, 235, 245) }
    private val tmpPath = Path()
    private val tmpMatrix = Matrix()

    // ---------- public API ----------
    fun setBase(name: String) { baseName = name; baseFace = Faces.get(name) }
    fun show(name: String, ms: Long) {
        tempName = name; tempFace = Faces.get(name); tempUntil = SystemClock.uptimeMillis() + ms
    }
    fun clearTemp() { tempFace = null; tempName = null }
    fun blinkNow(double: Boolean = false) { blinkStart = SystemClock.uptimeMillis(); doubleBlink = double }
    fun flash() { flashA = 1f }
    fun hop() { hopStart = SystemClock.uptimeMillis() }
    fun spin() { spinStart = SystemClock.uptimeMillis() }
    fun showText(text: String, ms: Long, sub: String? = null) {
        bigText = text; bigSub = sub; bigUntil = if (ms <= 0) Long.MAX_VALUE else SystemClock.uptimeMillis() + ms
    }
    fun clearText() { bigText = null; bigSub = null }
    val showingText: Boolean get() = bigText != null && SystemClock.uptimeMillis() < bigUntil
    fun showDice(v: Int, ms: Long) { dice = v; diceUntil = SystemClock.uptimeMillis() + ms }
    fun caption(text: String, ms: Long = 4000) { caption = text; captionUntil = SystemClock.uptimeMillis() + ms }
    fun scene(o: Overlay, ms: Long) { sceneOverlay = o; sceneUntil = if (ms <= 0) Long.MAX_VALUE else SystemClock.uptimeMillis() + ms }
    fun clearScene() { sceneOverlay = Overlay.NONE }

    // ---------- drawing ----------
    override fun onDraw(c: Canvas) {
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 0.016f else ((now - lastFrame) / 1000f).coerceIn(0f, .05f)
        lastFrame = now
        val t = now / 1000f
        if (tempFace != null && now > tempUntil) clearTemp()
        if (bigText != null && now > bigUntil) clearText()
        if (sceneOverlay != Overlay.NONE && now > sceneUntil) sceneOverlay = Overlay.NONE

        val f = face
        val k = 1f - exp(-dt * 14f)
        le.lerp(f.l, k); re.lerp(f.r, k)
        if (ballOn && width > 0) { lookTX = ((ballX / width) - .5f) * 2.2f; lookTY = ((ballY / height) - .45f) * 2f }
        val kl = 1f - exp(-dt * 9f)
        lookX += (lookTX + gravityX * .8f - lookX) * kl
        lookY += (lookTY - lookY) * kl
        lookX = lookX.coerceIn(-1.3f, 1.3f)
        tilt += (f.tilt + userTilt - tilt) * kl
        scale += (scaleTarget - scale) * (1f - exp(-dt * 3f))
        dim += (dimTarget - dim) * (1f - exp(-dt * 2f))
        val wantTint = if (f.tint != 0) 1f else 0f
        if (f.tint != 0) tintColor = f.tint
        tintMix += (wantTint - tintMix) * (1f - exp(-dt * 6f))

        updateBlink(now)

        val w = width.toFloat(); val h = height.toFloat()
        c.drawColor(Color.BLACK)
        val base = min(w * .19f, h * .33f)

        val top = ColorUtils.blendARGB(theme.top, lighten(tintColor), tintMix * .55f)
        val bottom = ColorUtils.blendARGB(theme.bottom, tintColor, tintMix * .7f)
        val glow = ColorUtils.blendARGB(theme.glow, tintColor, tintMix * .7f)

        val textOn = bigText != null
        val diceOn = dice > 0 && now < diceUntil

        if (textOn) {
            drawBigText(c, w, h, base, glow, top, t)
        } else if (diceOn) {
            drawDice(c, w / 2, h / 2, base * 1.3f, glow, top)
        } else {
            // hop / squash
            var hopY = 0f; var squash = 1f
            if (hopStart > 0) {
                val p = (now - hopStart) / 450f
                if (p >= 1f) hopStart = -1 else { hopY = -sin(p * PI.toFloat()) * base * .35f; squash = 1f + .12f * sin(p * 2 * PI.toFloat()) }
            }
            var spinA = 0f
            if (spinStart > 0) {
                val p = (now - spinStart) / 900f
                if (p >= 1f) spinStart = -1 else spinA = p * 360f
            }
            val bob = sin(t * 1.7f) * base * .025f
            val jx = sin(t * 52f) * f.jitterX * base * 2.5f
            val jy = sin(t * 38f) * f.jitterY * base * 2.5f
            val cx = w / 2 + lookX * base * .55f + jx
            val cy = h / 2 - base * .12f + lookY * base * .35f + bob + jy + hopY

            faceCx = cx; faceCy = cy; faceBase = base * scale
            c.save()
            c.translate(cx, cy)
            c.rotate(Math.toDegrees(tilt.toDouble()).toFloat() + spinA)
            c.scale(scale, scale * squash)
            val gap = base * .98f
            // curiosity: the eye on the side you look at grows a little
            val curR = 1f + .16f * max(0f, lookX) - .06f * max(0f, -lookX)
            val curL = 1f + .16f * max(0f, -lookX) - .06f * max(0f, lookX)
            drawEye(c, -gap, 0f, base, le, 1, f.style, curL, top, bottom, glow, t)
            drawEye(c, gap, 0f, base, re, -1, f.rStyle ?: f.style, curR, top, bottom, glow, t)
            if (f.style == EyeStyle.SHADES) {
                linePaint.color = glow; linePaint.strokeWidth = base * .08f; linePaint.setShadowLayer(base * .15f, 0f, 0f, glow)
                c.drawLine(-gap + base * .62f, -base * .1f, gap - base * .62f, -base * .1f, linePaint)
                linePaint.clearShadowLayer()
            }
            if (f.cheeks) drawCheeks(c, gap, base)
            val mouth = if (talking) null else f.mouth
            drawMouth(c, 0f, base * 1.02f, base, mouth, glow, t)
            drawOverlay(c, f.overlay, gap, base, glow, t)
            c.restore()
        }
        if (sceneOverlay != Overlay.NONE) drawScene(c, sceneOverlay, w, h, base, glow, t)
        if (ambient != Overlay.NONE && sceneOverlay == Overlay.NONE) { c.save(); c.clipRect(0f, 0f, w, h); drawScene(c, ambient, w, h, base, glow, t); c.restore() }
        if (!textOn && !diceOn) drawExtras(c, w, h, base, glow, top, t, dt, now)
        banner?.let { if (now < bannerUntil) drawText(c, it, w / 2, h * .16f, h * .1f, glow) else banner = null }
        if (crackStart > 0) drawCrack(c, w, h, base, now)

        if (listening) drawListening(c, w, h, base, glow, t)
        caption?.let { if (now < captionUntil) drawCaption(c, it, w, h, base) else caption = null }

        if (dim > .01f) c.drawColor(Color.argb((dim * 190).toInt(), 0, 0, 0))
        if (flashA > .01f) { c.drawColor(Color.argb((flashA * 255).toInt(), 255, 255, 255)); flashA *= exp(-dt * 5f) }

        postInvalidateOnAnimation()
    }

    private fun lighten(col: Int) = if (col == 0) Color.WHITE else ColorUtils.blendARGB(col, Color.WHITE, .55f)

    private fun updateBlink(now: Long) {
        val f = face
        if (blinkStart < 0 && now > nextBlink && f.style == EyeStyle.NORMAL) { blinkStart = now; doubleBlink = Math.random() < .18 }
        if (blinkStart >= 0) {
            val p = ((now - blinkStart) / 160f).coerceAtLeast(0f)
            blink = if (p < .5f) p * 2 else max(0f, 2 - p * 2)
            if (p >= 1f) {
                blink = 0f
                if (doubleBlink) { doubleBlink = false; blinkStart = now + 90 } else {
                    blinkStart = -1; nextBlink = now + 1800 + (Math.random() * 4200).toLong()
                }
            }
        }
    }

    private fun drawEye(
        c: Canvas, cx: Float, cy0: Float, base: Float, s: EyeState, side: Int, style: EyeStyle,
        cur: Float, topCol: Int, botCol: Int, glow: Int, t: Float,
    ) {
        val ew = base * 1.22f * s.w
        val eh = base * 1.36f * s.h * cur
        val cy = cy0 + s.dy * base
        when (style) {
            EyeStyle.NORMAL -> {
                val bh = eh * (1f - blink * .93f)
                val p = Path()
                val rad = min(ew, bh) * s.r.coerceIn(.05f, .5f)
                p.addRoundRect(cx - ew / 2, cy - bh / 2, cx + ew / 2, cy + bh / 2, rad, rad, Path.Direction.CW)
                if (s.top > .01f) {
                    val y = cy - bh / 2 + s.top * bh
                    tmpPath.reset()
                    tmpPath.addRect(cx - ew * 1.6f, y - eh * 3f, cx + ew * 1.6f, y, Path.Direction.CW)
                    tmpMatrix.setRotate(Math.toDegrees((s.ang * side).toDouble()).toFloat(), cx, y)
                    tmpPath.transform(tmpMatrix)
                    p.op(tmpPath, Path.Op.DIFFERENCE)
                }
                if (s.bot > .01f) {
                    val rr = ew * .9f
                    tmpPath.reset()
                    tmpPath.addCircle(cx, cy + bh / 2 - s.bot * bh + rr, rr, Path.Direction.CW)
                    p.op(tmpPath, Path.Op.DIFFERENCE)
                }
                eyePaint.shader = LinearGradient(0f, cy - bh / 2, 0f, cy + bh / 2, topCol, botCol, Shader.TileMode.CLAMP)
                eyePaint.setShadowLayer(base * .28f, 0f, 0f, glow)
                c.drawPath(p, eyePaint)
                eyePaint.clearShadowLayer(); eyePaint.shader = null
                // glossy glint + optional scanlines (clipped to the eye)
                c.save()
                c.clipPath(p)
                fillPaint.color = Color.argb(95, 255, 255, 255)
                val gx = cx - ew * .3f + lookX * ew * .06f
                val gy = cy - bh * .3f + lookY * bh * .05f
                c.drawRoundRect(RectF(gx - ew * .1f, gy - bh * .07f, gx + ew * .1f, gy + bh * .07f), ew * .06f, ew * .06f, fillPaint)
                fillPaint.color = Color.argb(60, 255, 255, 255)
                c.drawCircle(gx + ew * .2f, gy + bh * .02f, ew * .035f, fillPaint)
                if (scanlines) {
                    fillPaint.color = Color.argb(45, 0, 0, 0)
                    var y = cy - bh / 2
                    val step = max(4f, base * .045f)
                    while (y < cy + bh / 2) { c.drawRect(cx - ew / 2, y, cx + ew / 2, y + step * .4f, fillPaint); y += step }
                }
                c.restore()
            }
            EyeStyle.ARC -> {
                strokeGlow(glow, base * .2f, base)
                tmpPath.reset()
                tmpPath.moveTo(cx - ew * .4f, cy + eh * .1f)
                tmpPath.quadTo(cx, cy - eh * .42f, cx + ew * .4f, cy + eh * .1f)
                c.drawPath(tmpPath, linePaint); linePaint.clearShadowLayer()
            }
            EyeStyle.LINE -> {
                strokeGlow(glow, base * .16f, base)
                tmpPath.reset()
                tmpPath.moveTo(cx - ew * .4f, cy + eh * .05f)
                tmpPath.quadTo(cx, cy + eh * .25f, cx + ew * .4f, cy + eh * .05f)
                c.drawPath(tmpPath, linePaint); linePaint.clearShadowLayer()
            }
            EyeStyle.HEART -> {
                val sc = min(ew, eh) * .62f * (1f + .07f * sin(t * 9f))
                heartPath(tmpPath, cx, cy, sc)
                fillPaint.color = 0xFFFF5A8A.toInt(); fillPaint.setShadowLayer(base * .3f, 0f, 0f, 0xFFFF5A8A.toInt())
                c.drawPath(tmpPath, fillPaint); fillPaint.clearShadowLayer()
            }
            EyeStyle.STAR -> {
                starPath(tmpPath, cx, cy, min(ew, eh) * .58f, t * 40f * side)
                fillPaint.color = 0xFFFFE066.toInt(); fillPaint.setShadowLayer(base * .3f, 0f, 0f, 0xFFFFC21A.toInt())
                c.drawPath(tmpPath, fillPaint); fillPaint.clearShadowLayer()
            }
            EyeStyle.SPIRAL -> {
                strokeGlow(glow, base * .09f, base)
                tmpPath.reset()
                val maxR = min(ew, eh) * .48f
                var i = 0
                while (i <= 60) {
                    val q = i / 60f
                    val a = q * 6f * PI.toFloat() + t * 6f * side
                    val x = cx + cos(a) * maxR * q; val y = cy + sin(a) * maxR * q
                    if (i == 0) tmpPath.moveTo(x, y) else tmpPath.lineTo(x, y)
                    i++
                }
                c.drawPath(tmpPath, linePaint); linePaint.clearShadowLayer()
            }
            EyeStyle.CROSS -> {
                strokeGlow(glow, base * .18f, base)
                val d = min(ew, eh) * .32f
                c.drawLine(cx - d, cy - d, cx + d, cy + d, linePaint)
                c.drawLine(cx - d, cy + d, cx + d, cy - d, linePaint)
                linePaint.clearShadowLayer()
            }
            EyeStyle.SHADES -> {
                val r = RectF(cx - ew * .58f, cy - eh * .3f, cx + ew * .58f, cy + eh * .3f)
                fillPaint.color = 0xFF07090C.toInt()
                c.drawRoundRect(r, ew * .22f, ew * .22f, fillPaint)
                strokeGlow(glow, base * .07f, base)
                c.drawRoundRect(r, ew * .22f, ew * .22f, linePaint)
                linePaint.clearShadowLayer()
                val g = ((t * .4f) % 1.6f) - .3f
                linePaint.color = Color.argb(150, 255, 255, 255); linePaint.strokeWidth = base * .06f
                c.save(); c.clipRect(r)
                c.drawLine(r.left + r.width() * g, r.bottom, r.left + r.width() * (g + .3f), r.top, linePaint)
                c.restore()
            }
            EyeStyle.DOT -> {
                fillPaint.shader = LinearGradient(0f, cy - eh / 2, 0f, cy + eh / 2, topCol, botCol, Shader.TileMode.CLAMP)
                fillPaint.setShadowLayer(base * .25f, 0f, 0f, glow)
                c.drawCircle(cx, cy, min(ew, eh) * .5f * (1f - blink * .9f), fillPaint)
                fillPaint.clearShadowLayer(); fillPaint.shader = null
            }
        }
    }

    private fun strokeGlow(col: Int, width: Float, base: Float) {
        linePaint.color = col; linePaint.strokeWidth = width; linePaint.setShadowLayer(base * .22f, 0f, 0f, col)
    }

    private fun heartPath(p: Path, x: Float, y: Float, s: Float) {
        p.reset()
        p.moveTo(x, y + s * .62f)
        p.cubicTo(x + s * 1.3f, y - s * .1f, x + s * .62f, y - s * .95f, x, y - s * .32f)
        p.cubicTo(x - s * .62f, y - s * .95f, x - s * 1.3f, y - s * .1f, x, y + s * .62f)
        p.close()
    }

    private fun starPath(p: Path, x: Float, y: Float, r: Float, rotDeg: Float) {
        p.reset()
        val rot = Math.toRadians(rotDeg.toDouble()).toFloat()
        for (i in 0 until 10) {
            val rr = if (i % 2 == 0) r else r * .45f
            val a = -PI.toFloat() / 2 + i * PI.toFloat() / 5 + rot
            val px = x + cos(a) * rr; val py = y + sin(a) * rr
            if (i == 0) p.moveTo(px, py) else p.lineTo(px, py)
        }
        p.close()
    }

    private fun drawCheeks(c: Canvas, gap: Float, base: Float) {
        fillPaint.color = Color.argb(110, 255, 110, 150)
        fillPaint.setShadowLayer(base * .15f, 0f, 0f, Color.argb(160, 255, 90, 140))
        for (s in intArrayOf(-1, 1)) c.drawOval(RectF(s * gap * 1.32f - base * .22f, base * .62f, s * gap * 1.32f + base * .22f, base * .8f), fillPaint)
        fillPaint.clearShadowLayer()
    }

    private fun drawMouth(c: Canvas, x: Float, y: Float, base: Float, m: Mouth?, glow: Int, t: Float) {
        val mw = base * .62f
        linePaint.color = glow; linePaint.strokeWidth = base * .09f; linePaint.setShadowLayer(base * .18f, 0f, 0f, glow)
        fillPaint.color = glow; fillPaint.setShadowLayer(base * .18f, 0f, 0f, glow)
        val p = tmpPath; p.reset()
        if (m == null) {
            // talking: lively oval
            val o = (sin(t * 21f) * .5f + .5f) * (sin(t * 6.7f) * .35f + .65f)
            c.drawOval(RectF(x - mw * .3f, y - base * (.04f + .16f * o), x + mw * .3f, y + base * (.04f + .16f * o)), fillPaint)
        } else when (m) {
            Mouth.NONE -> {}
            Mouth.SMILE -> { p.moveTo(x - mw * .35f, y); p.quadTo(x, y + mw * .38f, x + mw * .35f, y); c.drawPath(p, linePaint) }
            Mouth.GRIN -> { p.moveTo(x - mw * .45f, y - mw * .05f); p.quadTo(x, y + mw * .65f, x + mw * .45f, y - mw * .05f); p.close(); c.drawPath(p, fillPaint) }
            Mouth.O -> c.drawOval(RectF(x - mw * .17f, y - mw * .22f, x + mw * .17f, y + mw * .22f), linePaint)
            Mouth.BIG_O -> c.drawOval(RectF(x - mw * .25f, y - mw * .32f, x + mw * .25f, y + mw * .32f), fillPaint)
            Mouth.SMALL_O -> c.drawCircle(x, y, mw * .09f, fillPaint)
            Mouth.FROWN -> { p.moveTo(x - mw * .3f, y + mw * .15f); p.quadTo(x, y - mw * .18f, x + mw * .3f, y + mw * .15f); c.drawPath(p, linePaint) }
            Mouth.FLAT -> c.drawLine(x - mw * .28f, y, x + mw * .28f, y, linePaint)
            Mouth.WAVY -> {
                for (i in 0..20) { val xx = x - mw * .4f + mw * .8f * i / 20f; val yy = y + sin(i * 1.5f + t * 7f) * base * .035f; if (i == 0) p.moveTo(xx, yy) else p.lineTo(xx, yy) }
                c.drawPath(p, linePaint)
            }
            Mouth.TONGUE -> {
                p.moveTo(x - mw * .35f, y); p.quadTo(x, y + mw * .3f, x + mw * .35f, y); c.drawPath(p, linePaint)
                fillPaint.color = 0xFFFF6F91.toInt(); fillPaint.setShadowLayer(base * .1f, 0f, 0f, 0xFFFF6F91.toInt())
                c.drawArc(RectF(x - mw * .12f, y + mw * .02f, x + mw * .16f, y + mw * .34f), 0f, 180f, true, fillPaint)
            }
            Mouth.CAT -> {
                p.moveTo(x - mw * .34f, y); p.quadTo(x - mw * .17f, y + mw * .26f, x, y); p.quadTo(x + mw * .17f, y + mw * .26f, x + mw * .34f, y)
                c.drawPath(p, linePaint)
            }
            Mouth.CHOMP -> {
                val o = abs(sin(t * 9f))
                c.drawOval(RectF(x - mw * .25f, y - base * (.03f + .14f * o), x + mw * .25f, y + base * (.03f + .14f * o)), fillPaint)
            }
        }
        linePaint.clearShadowLayer(); fillPaint.clearShadowLayer()
    }

    private fun drawText(c: Canvas, s: String, x: Float, y: Float, size: Float, col: Int, alpha: Int = 255) {
        textPaint.textSize = size; textPaint.color = col; textPaint.alpha = alpha
        textPaint.setShadowLayer(size * .25f, 0f, 0f, col)
        c.drawText(s, x, y, textPaint)
        textPaint.clearShadowLayer()
    }

    private fun drawOverlay(c: Canvas, o: Overlay, gap: Float, base: Float, glow: Int, t: Float) {
        when (o) {
            Overlay.NONE -> {}
            Overlay.ZZZ -> for (i in 0 until 3) {
                val p = ((t * .35f + i / 3f) % 1f)
                drawText(c, "z", gap * 1.3f + p * base * .9f, -base * .5f - p * base * 1.3f, base * (.28f + p * .35f), glow, ((1 - p) * 255).toInt())
            }
            Overlay.TEARS -> for (s in intArrayOf(-1, 1)) for (i in 0 until 2) {
                val p = ((t * .8f + i * .5f + (if (s > 0) .25f else 0f)) % 1f)
                fillPaint.color = 0xFF7FC8FF.toInt(); fillPaint.alpha = ((1 - p) * 230).toInt()
                c.drawCircle(s * gap + s * base * .2f, base * .55f + p * base * 1.1f, base * .08f, fillPaint)
                fillPaint.alpha = 255
            }
            Overlay.SWEAT -> {
                val p = (t * .6f) % 1f
                fillPaint.color = 0xFF8FD8FF.toInt(); fillPaint.alpha = 220
                c.drawOval(RectF(gap + base * .55f, -base * .75f + p * base * .4f, gap + base * .72f, -base * .5f + p * base * .4f), fillPaint)
                fillPaint.alpha = 255
            }
            Overlay.QUESTION -> drawText(c, "?", gap + base * .85f, -base * .75f + sin(t * 3f) * base * .06f, base * .75f, glow)
            Overlay.EXCLAIM -> drawText(c, "!", gap + base * .85f, -base * .7f + sin(t * 9f) * base * .05f, base * .8f, 0xFFFFD23F.toInt())
            Overlay.NOTES -> for (i in 0 until 3) {
                val p = ((t * .4f + i / 3f) % 1f)
                val x = (if (i % 2 == 0) -1 else 1) * (gap * 1.5f + sin(p * 6f + i) * base * .25f)
                drawText(c, if (i == 1) "♫" else "♪", x, base * .3f - p * base * 1.8f, base * .55f, glow, ((1 - p) * 255).toInt())
            }
            Overlay.ANGER -> {
                val x = gap + base * .7f; val y = -base * .8f; val s = base * .18f * (1 + .15f * sin(t * 10f))
                linePaint.color = 0xFFFF3B3B.toInt(); linePaint.strokeWidth = base * .07f
                for (a in 0 until 4) {
                    c.save(); c.rotate(a * 90f + 45f, x, y)
                    c.drawLine(x - s * .3f, y - s, x + s * .3f, y - s * .4f, linePaint)
                    c.restore()
                }
            }
            Overlay.HEARTS -> for (i in 0 until 4) {
                val p = ((t * .3f + i / 4f) % 1f)
                val x = (if (i % 2 == 0) -1 else 1) * (gap * (1.2f + .25f * i)) + sin(p * 5f) * base * .2f
                heartPath(tmpPath, x, base * .6f - p * base * 2.2f, base * .2f)
                fillPaint.color = 0xFFFF5A8A.toInt(); fillPaint.alpha = ((1 - p) * 255).toInt()
                c.drawPath(tmpPath, fillPaint); fillPaint.alpha = 255
            }
            Overlay.SPARKLES, Overlay.CONFETTI -> for (i in 0 until 7) {
                val ph = (t * 1.3f + i * .37f) % 1f
                val a = i * 2.1f
                val x = cos(a) * gap * 1.7f; val y = sin(a) * base * 1.1f
                val col = if (o == Overlay.CONFETTI) intArrayOf(0xFFFF5A8A.toInt(), 0xFFFFD23F.toInt(), 0xFF3FE3F2.toInt(), 0xFF7CFF6B.toInt())[i % 4] else Color.WHITE
                fillPaint.color = col; fillPaint.alpha = (sin(ph * PI.toFloat()) * 255).toInt()
                starPath(tmpPath, x, y + (if (o == Overlay.CONFETTI) ph * base * .8f else 0f), base * .1f * (.5f + sin(ph * PI.toFloat())), ph * 90f)
                c.drawPath(tmpPath, fillPaint); fillPaint.alpha = 255
            }
            Overlay.BOLT -> {
                val x = gap * 1.45f; val y = -base * .6f; val s = base * .3f
                tmpPath.reset(); tmpPath.moveTo(x + s * .2f, y - s); tmpPath.lineTo(x - s * .4f, y + s * .1f); tmpPath.lineTo(x, y + s * .1f)
                tmpPath.lineTo(x - s * .2f, y + s); tmpPath.lineTo(x + s * .45f, y - s * .15f); tmpPath.lineTo(x + s * .05f, y - s * .15f); tmpPath.close()
                fillPaint.color = 0xFF7CFF6B.toInt(); fillPaint.alpha = (180 + 75 * sin(t * 6f)).toInt().coerceIn(0, 255)
                c.drawPath(tmpPath, fillPaint); fillPaint.alpha = 255
            }
            Overlay.STEAM -> for (s in intArrayOf(-1, 1)) for (i in 0 until 2) {
                val p = ((t * .9f + i * .5f) % 1f)
                fillPaint.color = Color.WHITE; fillPaint.alpha = ((1 - p) * 140).toInt()
                c.drawCircle(s * gap * 1.6f + s * p * base * .4f, -base * .6f - p * base, base * (.08f + p * .15f), fillPaint)
                fillPaint.alpha = 255
            }
            Overlay.PUMPKIN -> drawPumpkin(c, gap * 1.55f, -base * .65f, base * .32f, t)
            Overlay.CAKE -> drawCake(c, 0f, base * 1.35f, base * .55f, t)
            else -> {}
        }
    }

    private fun drawScene(c: Canvas, o: Overlay, w: Float, h: Float, base: Float, glow: Int, t: Float) {
        when (o) {
            Overlay.SNOW -> for (i in 0 until 40) {
                val sx = (i * 97.3f) % w; val sp = .15f + (i % 5) * .04f
                val y = ((t * sp + i * .123f) % 1f) * (h + 40) - 20
                fillPaint.color = Color.WHITE; fillPaint.alpha = 200
                c.drawCircle(sx + sin(t + i) * 20f, y, 3f + (i % 3) * 2f, fillPaint)
            }
            Overlay.RAIN -> {
                linePaint.color = 0xFF7FB8FF.toInt(); linePaint.strokeWidth = 3f; linePaint.alpha = 170
                for (i in 0 until 45) {
                    val sx = (i * 73.1f) % w
                    val y = ((t * (1.2f + (i % 4) * .2f) + i * .17f) % 1f) * (h + 60) - 30
                    c.drawLine(sx, y, sx - 6f, y + 26f, linePaint)
                }
                linePaint.alpha = 255
            }
            Overlay.STORM -> {
                drawScene(c, Overlay.RAIN, w, h, base, glow, t)
                if ((t % 3f) < .12f) c.drawColor(Color.argb(90, 255, 255, 255))
            }
            Overlay.SUN -> {
                val x = w * .12f; val y = h * .2f; val r = base * .35f
                fillPaint.color = 0xFFFFD23F.toInt(); fillPaint.setShadowLayer(r, 0f, 0f, 0xFFFFB000.toInt())
                c.drawCircle(x, y, r, fillPaint); fillPaint.clearShadowLayer()
                linePaint.color = 0xFFFFD23F.toInt(); linePaint.strokeWidth = base * .06f
                for (i in 0 until 8) {
                    val a = i * PI.toFloat() / 4 + t * .5f
                    c.drawLine(x + cos(a) * r * 1.3f, y + sin(a) * r * 1.3f, x + cos(a) * r * 1.75f, y + sin(a) * r * 1.75f, linePaint)
                }
            }
            Overlay.CLOUD -> {
                fillPaint.color = 0xFFDDE6EE.toInt(); fillPaint.alpha = 220
                val x = w * .14f + sin(t * .4f) * base * .2f; val y = h * .2f; val r = base * .22f
                c.drawCircle(x, y, r, fillPaint); c.drawCircle(x + r, y - r * .4f, r * 1.2f, fillPaint); c.drawCircle(x + r * 2.1f, y, r, fillPaint)
                c.drawRect(x, y, x + r * 2.1f, y + r, fillPaint); fillPaint.alpha = 255
            }
            Overlay.CONFETTI -> for (i in 0 until 50) {
                val sx = (i * 61.7f) % w
                val y = ((t * (.25f + (i % 5) * .05f) + i * .07f) % 1f) * (h + 40) - 20
                fillPaint.color = intArrayOf(0xFFFF5A8A.toInt(), 0xFFFFD23F.toInt(), 0xFF3FE3F2.toInt(), 0xFF7CFF6B.toInt(), 0xFFB061FF.toInt())[i % 5]
                c.save(); c.rotate(t * 200f + i * 30f, sx, y); c.drawRect(sx - 6f, y - 3f, sx + 6f, y + 3f, fillPaint); c.restore()
            }
            Overlay.CAKE -> drawCake(c, w / 2, h * .82f, base * .55f, t)
            Overlay.PUMPKIN -> drawPumpkin(c, w * .88f, h * .78f, base * .4f, t)
            Overlay.BUBBLES -> {
                drawScene(c, Overlay.RAIN, w, h, base, glow, t)
                linePaint.color = 0xCCBFEFFF.toInt(); linePaint.strokeWidth = 3f
                for (i in 0 until 18) {
                    val sx = (i * 113.7f) % w
                    val p = ((t * (.12f + (i % 4) * .03f) + i * .13f) % 1f)
                    val r = 10f + (i % 5) * 7f
                    c.drawCircle(sx + kotlin.math.sin(t * 2 + i) * 20f, h - p * (h + 60), r, linePaint)
                }
            }
            Overlay.PETALS -> for (i in 0 until 22) {
                val sx = (i * 97.1f) % w
                val y = ((t * (.1f + (i % 5) * .03f) + i * .09f) % 1f) * (h + 40) - 20
                fillPaint.color = if (i % 2 == 0) 0xFFFF8FB8.toInt() else 0xFFFF4F8B.toInt()
                c.save(); c.rotate(t * 90f + i * 40f, sx, y); c.drawOval(RectF(sx - 9f, y - 5f, sx + 9f, y + 5f), fillPaint); c.restore()
            }
            Overlay.HEARTS -> for (i in 0 until 14) {
                val sx = (i * 89.3f) % w
                val p = ((t * .15f + i * .11f) % 1f)
                heartPath(tmpPath, sx + sin(t + i) * 25f, h - p * (h + 60), base * .12f)
                fillPaint.color = 0xFFFF5A8A.toInt(); fillPaint.alpha = 160
                c.drawPath(tmpPath, fillPaint); fillPaint.alpha = 255
            }
            else -> {}
        }
    }

    private fun drawCake(c: Canvas, x: Float, y: Float, s: Float, t: Float) {
        fillPaint.color = 0xFFFF8FB8.toInt()
        c.drawRoundRect(RectF(x - s, y - s * .5f, x + s, y + s * .3f), s * .15f, s * .15f, fillPaint)
        fillPaint.color = 0xFFFFF1E0.toInt()
        c.drawRoundRect(RectF(x - s, y - s * .62f, x + s, y - s * .38f), s * .12f, s * .12f, fillPaint)
        for (i in -1..1) {
            val cx = x + i * s * .55f
            fillPaint.color = 0xFF8FD8FF.toInt()
            c.drawRect(cx - s * .06f, y - s * 1.0f, cx + s * .06f, y - s * .6f, fillPaint)
            if (cakeLit) {
                fillPaint.color = 0xFFFFC23F.toInt(); fillPaint.setShadowLayer(s * .3f, 0f, 0f, 0xFFFF9A1F.toInt())
                c.drawOval(RectF(cx - s * .08f, y - s * 1.25f + sin(t * 14f + i) * s * .02f, cx + s * .08f, y - s * 1.0f), fillPaint)
                fillPaint.clearShadowLayer()
            }
        }
    }

    private fun drawPumpkin(c: Canvas, x: Float, y: Float, s: Float, t: Float) {
        fillPaint.color = 0xFFFF8C1A.toInt(); fillPaint.setShadowLayer(s * .4f, 0f, 0f, 0xFFFF6A00.toInt())
        c.drawOval(RectF(x - s, y - s * .8f, x + s, y + s * .8f), fillPaint); fillPaint.clearShadowLayer()
        fillPaint.color = 0xFF3A7D2C.toInt(); c.drawRect(x - s * .1f, y - s * 1.1f, x + s * .1f, y - s * .7f, fillPaint)
        fillPaint.color = if (sin(t * 4f) > 0) 0xFFFFE066.toInt() else 0xFFFFC21A.toInt()
        tmpPath.reset(); tmpPath.moveTo(x - s * .55f, y - s * .1f); tmpPath.lineTo(x - s * .3f, y - s * .4f); tmpPath.lineTo(x - s * .15f, y - s * .1f); tmpPath.close()
        tmpPath.moveTo(x + s * .55f, y - s * .1f); tmpPath.lineTo(x + s * .3f, y - s * .4f); tmpPath.lineTo(x + s * .15f, y - s * .1f); tmpPath.close()
        c.drawPath(tmpPath, fillPaint)
        c.drawRect(x - s * .45f, y + s * .2f, x + s * .45f, y + s * .35f, fillPaint)
    }

    private fun drawBigText(c: Canvas, w: Float, h: Float, base: Float, glow: Int, top: Int, t: Float) {
        val s = bigText ?: return
        var size = h * .42f
        bigPaint.textSize = size
        val maxW = w * .86f
        val tw = bigPaint.measureText(s)
        if (tw > maxW) { size *= maxW / tw; bigPaint.textSize = size }
        bigPaint.color = top
        bigPaint.setShadowLayer(size * .18f, 0f, 0f, glow)
        val y = h / 2 + size * .35f - (if (bigSub != null) h * .06f else 0f)
        c.drawText(s, w / 2, y, bigPaint)
        bigPaint.clearShadowLayer()
        bigSub?.let { drawText(c, it, w / 2, y + h * .16f, h * .08f, glow) }
    }

    private fun drawDice(c: Canvas, x: Float, y: Float, s: Float, glow: Int, top: Int) {
        val r = RectF(x - s / 2, y - s / 2, x + s / 2, y + s / 2)
        strokeGlow(glow, s * .05f, s)
        c.drawRoundRect(r, s * .18f, s * .18f, linePaint); linePaint.clearShadowLayer()
        fillPaint.color = top; fillPaint.setShadowLayer(s * .1f, 0f, 0f, glow)
        val d = s * .27f
        val pips = when (dice) {
            1 -> listOf(0 to 0)
            2 -> listOf(-1 to -1, 1 to 1)
            3 -> listOf(-1 to -1, 0 to 0, 1 to 1)
            4 -> listOf(-1 to -1, 1 to -1, -1 to 1, 1 to 1)
            5 -> listOf(-1 to -1, 1 to -1, 0 to 0, -1 to 1, 1 to 1)
            else -> listOf(-1 to -1, 1 to -1, -1 to 0, 1 to 0, -1 to 1, 1 to 1)
        }
        for ((px, py) in pips) c.drawCircle(x + px * d, y + py * d, s * .08f, fillPaint)
        fillPaint.clearShadowLayer()
    }

    private fun drawListening(c: Canvas, w: Float, h: Float, base: Float, glow: Int, t: Float) {
        fillPaint.color = glow
        for (i in 0 until 5) {
            val a = .35f + .65f * (sin(t * 7f - i * .7f) * .5f + .5f)
            val hh = base * (.06f + .22f * listenLevel * a)
            fillPaint.alpha = (a * 255).toInt()
            val x = w / 2 + (i - 2) * base * .16f
            c.drawRoundRect(RectF(x - base * .045f, h - base * .28f - hh, x + base * .045f, h - base * .28f + hh), base * .05f, base * .05f, fillPaint)
        }
        fillPaint.alpha = 255
    }

    private fun drawCaption(c: Canvas, s: String, w: Float, h: Float, base: Float) {
        capPaint.textSize = max(28f, base * .14f)
        val text = if (s.length > 90) s.take(87) + "..." else s
        c.drawText(text, w / 2, h - base * .08f, capPaint)
    }
    // ================= extras =================
    private fun drawExtras(c: Canvas, w: Float, h: Float, base0: Float, glow: Int, top: Int, t: Float, dt: Float, now: Long) {
        val base = faceBase
        val gap = base * .98f
        // headphones (blue band over the head, cups beside the eyes)
        if (headphones) {
            val blue = 0xFF2F7BFF.toInt()
            linePaint.color = blue; linePaint.strokeWidth = base * .16f; linePaint.setShadowLayer(base * .2f, 0f, 0f, blue)
            val r = RectF(faceCx - gap * 1.75f, faceCy - base * 1.55f, faceCx + gap * 1.75f, faceCy + base * 1.2f)
            c.drawArc(r, 200f, 140f, false, linePaint); linePaint.clearShadowLayer()
            fillPaint.color = blue; fillPaint.setShadowLayer(base * .25f, 0f, 0f, blue)
            val bob = kotlin.math.sin(t * 6.3f) * base * .03f
            for (sx in intArrayOf(-1, 1)) {
                val x = faceCx + sx * gap * 1.72f
                c.drawRoundRect(RectF(x - base * .2f, faceCy - base * .45f + bob, x + base * .2f, faceCy + base * .35f + bob), base * .16f, base * .16f, fillPaint)
            }
            fillPaint.clearShadowLayer()
            fillPaint.color = 0xFF9CC4FF.toInt()
            for (sx in intArrayOf(-1, 1)) c.drawCircle(faceCx + sx * gap * 1.72f, faceCy - base * .05f + bob, base * .07f, fillPaint)
        }
        // gentleman: top hat + bow tie
        if (gentleman) {
            fillPaint.color = 0xFF15151A.toInt(); linePaint.color = glow; linePaint.strokeWidth = base * .04f
            val hy = faceCy - base * 1.0f
            val brim = RectF(faceCx - base * 1.1f, hy - base * .1f, faceCx + base * 1.1f, hy + base * .06f)
            val crown = RectF(faceCx - base * .65f, hy - base * .95f, faceCx + base * .65f, hy - base * .05f)
            c.drawRoundRect(crown, base * .08f, base * .08f, fillPaint); c.drawRoundRect(crown, base * .08f, base * .08f, linePaint)
            c.drawRoundRect(brim, base * .06f, base * .06f, fillPaint); c.drawRoundRect(brim, base * .06f, base * .06f, linePaint)
            fillPaint.color = 0xFFFF4F8B.toInt(); c.drawRect(faceCx - base * .65f, hy - base * .3f, faceCx + base * .65f, hy - base * .16f, fillPaint)
            // bow tie under the mouth
            val by = faceCy + base * 1.45f
            fillPaint.color = 0xFFFF4F8B.toInt()
            tmpPath.reset()
            tmpPath.moveTo(faceCx, by); tmpPath.lineTo(faceCx - base * .38f, by - base * .2f); tmpPath.lineTo(faceCx - base * .38f, by + base * .2f); tmpPath.close()
            tmpPath.moveTo(faceCx, by); tmpPath.lineTo(faceCx + base * .38f, by - base * .2f); tmpPath.lineTo(faceCx + base * .38f, by + base * .2f); tmpPath.close()
            c.drawPath(tmpPath, fillPaint)
            c.drawCircle(faceCx, by, base * .08f, fillPaint)
        }
        // prop in a little hand
        if (prop != PropKind.NONE) {
            propRaiseNow += (propRaise - propRaiseNow) * (1f - exp(-dt * 7f))
            val hx = faceCx + gap * 1.55f - propRaiseNow * gap * .7f
            val hy = faceCy + base * 1.25f - propRaiseNow * base * .9f
            var tiltDeg = -propRaiseNow * 15f
            if (clinkStart > 0) {
                val p = (now - clinkStart) / 600f
                if (p >= 1f) clinkStart = -1 else tiltDeg += kotlin.math.sin(p * PI.toFloat() * 3) * 18f
            }
            c.save(); c.translate(hx, hy); c.rotate(tiltDeg)
            drawProp(c, prop, base * .55f, t)
            // the hand: a small mitten in eye color
            fillPaint.color = ColorUtils.blendARGB(top, Color.WHITE, .2f)
            fillPaint.setShadowLayer(base * .12f, 0f, 0f, glow)
            c.drawRoundRect(RectF(-base * .22f, base * .05f, base * .22f, base * .38f), base * .14f, base * .14f, fillPaint)
            c.drawRoundRect(RectF(-base * .3f, base * .08f, -base * .12f, base * .24f), base * .08f, base * .08f, fillPaint)
            fillPaint.clearShadowLayer()
            c.restore()
            if (clinkStart > 0) {
                val p = (now - clinkStart) / 600f
                drawText(c, "CLINK!", hx, hy - base * 1.0f - p * base * .4f, base * .32f, 0xFFFFD23F.toInt(), ((1 - p).coerceIn(0f, 1f) * 255).toInt())
                for (i in 0 until 6) {
                    val a = i * PI.toFloat() / 3
                    fillPaint.color = Color.WHITE; fillPaint.alpha = ((1 - p).coerceIn(0f, 1f) * 255).toInt()
                    starPath(tmpPath, hx + kotlin.math.cos(a) * base * (.4f + p * .5f), hy - base * .5f + kotlin.math.sin(a) * base * (.4f + p * .5f), base * .08f, p * 90f)
                    c.drawPath(tmpPath, fillPaint)
                }
                fillPaint.alpha = 255
            }
        }
        // bouncing ball
        if (ballOn) {
            val g = h * 2.6f
            ballVY += g * dt
            ballX += ballVX * dt; ballY += ballVY * dt
            val r = base * .28f
            if (ballY > h - r) { ballY = h - r; ballVY = -kotlin.math.abs(ballVY) * .82f; if (kotlin.math.abs(ballVY) < h * .5f) ballVY = -h * 1.3f }
            if (ballX < r) { ballX = r; ballVX = kotlin.math.abs(ballVX) }
            if (ballX > w - r) { ballX = w - r; ballVX = -kotlin.math.abs(ballVX) }
            ballSpin += ballVX * dt * .8f
            fillPaint.color = 0xFFFF5A5A.toInt(); fillPaint.setShadowLayer(r * .5f, 0f, 0f, 0xFFFF5A5A.toInt())
            c.drawCircle(ballX, ballY, r, fillPaint); fillPaint.clearShadowLayer()
            linePaint.color = Color.WHITE; linePaint.strokeWidth = r * .18f
            c.save(); c.rotate(ballSpin, ballX, ballY)
            c.drawArc(RectF(ballX - r * .8f, ballY - r * .8f, ballX + r * .8f, ballY + r * .8f), 20f, 140f, false, linePaint)
            c.restore()
        }
    }

    private fun drawProp(c: Canvas, k: PropKind, s: Float, t: Float) {
        val bites = propBites
        when (k) {
            PropKind.APPLE -> { fillPaint.color = 0xFFFF3B3B.toInt(); c.drawCircle(0f, -s * .45f, s * .5f, fillPaint); fillPaint.color = 0xFF3A7D2C.toInt(); c.drawOval(RectF(s * .02f, -s * 1.12f, s * .32f, -s * .9f), fillPaint) }
            PropKind.ORANGE -> { fillPaint.color = 0xFFFF9A1F.toInt(); c.drawCircle(0f, -s * .45f, s * .5f, fillPaint) }
            PropKind.BANANA -> { linePaint.color = 0xFFFFD23F.toInt(); linePaint.strokeWidth = s * .3f; c.drawArc(RectF(-s * .6f, -s * 1.3f, s * .6f, -s * .1f), 100f, 150f, false, linePaint) }
            PropKind.PIZZA -> {
                tmpPath.reset(); tmpPath.moveTo(-s * .5f, -s * 1.1f); tmpPath.lineTo(s * .5f, -s * 1.1f); tmpPath.lineTo(0f, 0f); tmpPath.close()
                fillPaint.color = 0xFFFFC24D.toInt(); c.drawPath(tmpPath, fillPaint)
                fillPaint.color = 0xFFD9381E.toInt(); c.drawCircle(-s * .12f, -s * .8f, s * .09f, fillPaint); c.drawCircle(s * .14f, -s * .6f, s * .08f, fillPaint)
                fillPaint.color = 0xFFC98A3A.toInt(); c.drawRect(-s * .52f, -s * 1.18f, s * .52f, -s * 1.05f, fillPaint)
            }
            PropKind.DONUT -> { linePaint.color = 0xFFFF8FB8.toInt(); linePaint.strokeWidth = s * .3f; c.drawCircle(0f, -s * .5f, s * .34f, linePaint) }
            PropKind.CAKE -> { fillPaint.color = 0xFFFF8FB8.toInt(); c.drawRect(-s * .45f, -s * .8f, s * .45f, -s * .05f, fillPaint); fillPaint.color = 0xFFFFF1E0.toInt(); c.drawRect(-s * .45f, -s * .9f, s * .45f, -s * .7f, fillPaint) }
            PropKind.SANDWICH -> {
                tmpPath.reset(); tmpPath.moveTo(-s * .55f, -s * .1f); tmpPath.lineTo(s * .55f, -s * .1f); tmpPath.lineTo(0f, -s * 1.0f); tmpPath.close()
                fillPaint.color = 0xFFE8C07A.toInt(); c.drawPath(tmpPath, fillPaint)
                fillPaint.color = 0xFF6BD16B.toInt(); c.drawRect(-s * .45f, -s * .3f, s * .45f, -s * .2f, fillPaint)
            }
            PropKind.CARROT -> {
                tmpPath.reset(); tmpPath.moveTo(-s * .18f, -s * 1.1f); tmpPath.lineTo(s * .18f, -s * 1.1f); tmpPath.lineTo(0f, 0f); tmpPath.close()
                fillPaint.color = 0xFFFF8C1A.toInt(); c.drawPath(tmpPath, fillPaint)
                fillPaint.color = 0xFF3A7D2C.toInt(); c.drawOval(RectF(-s * .15f, -s * 1.35f, s * .15f, -s * 1.08f), fillPaint)
            }
            PropKind.BROCCOLI -> { fillPaint.color = 0xFF3FAE4A.toInt(); c.drawCircle(-s * .2f, -s * .75f, s * .25f, fillPaint); c.drawCircle(s * .2f, -s * .75f, s * .25f, fillPaint); c.drawCircle(0f, -s * .95f, s * .25f, fillPaint); c.drawRect(-s * .08f, -s * .6f, s * .08f, -s * .1f, fillPaint) }
            PropKind.HOTDOG -> { fillPaint.color = 0xFFE8C07A.toInt(); c.drawRoundRect(RectF(-s * .6f, -s * .55f, s * .6f, -s * .15f), s * .2f, s * .2f, fillPaint); fillPaint.color = 0xFFC0392B.toInt(); c.drawRoundRect(RectF(-s * .7f, -s * .45f, s * .7f, -s * .28f), s * .1f, s * .1f, fillPaint) }
            PropKind.FOOD -> { fillPaint.color = 0xFFF2F2F2.toInt(); c.drawOval(RectF(-s * .7f, -s * .35f, s * .7f, -s * .05f), fillPaint); fillPaint.color = 0xFFFFB35C.toInt(); c.drawCircle(0f, -s * .35f, s * .3f, fillPaint) }
            PropKind.WATER -> {
                // glass with water and a straw
                fillPaint.color = 0x553FE3F2; c.drawRect(-s * .32f, -s * 1.1f, s * .32f, 0f, fillPaint)
                fillPaint.color = 0xCC3FA9F5.toInt(); c.drawRect(-s * .3f, -s * (.75f - bites * .12f).coerceAtLeast(.1f), s * .3f, 0f, fillPaint)
                linePaint.color = Color.WHITE; linePaint.strokeWidth = s * .05f
                c.drawLine(-s * .32f, -s * 1.1f, -s * .32f, 0f, linePaint); c.drawLine(s * .32f, -s * 1.1f, s * .32f, 0f, linePaint)
                linePaint.color = 0xFFFF5A8A.toInt(); linePaint.strokeWidth = s * .07f
                c.drawLine(s * .1f, -s * .3f, -s * .2f, -s * 1.45f, linePaint)
            }
            PropKind.BEER -> {
                // mug with foam and a handle
                fillPaint.color = 0xFFFFB21F.toInt(); c.drawRoundRect(RectF(-s * .38f, -s * 1.0f, s * .38f, 0f), s * .08f, s * .08f, fillPaint)
                fillPaint.color = Color.WHITE
                for (i in -1..1) c.drawCircle(i * s * .24f, -s * 1.0f + kotlin.math.sin(t * 3f + i) * s * .02f, s * .2f, fillPaint)
                linePaint.color = 0xFFFFE0A0.toInt(); linePaint.strokeWidth = s * .1f
                c.drawArc(RectF(s * .25f, -s * .8f, s * .7f, -s * .25f), -90f, 180f, false, linePaint)
                fillPaint.color = 0x88FFFFFF.toInt(); c.drawRect(-s * .25f, -s * .85f, -s * .15f, -s * .15f, fillPaint)
            }
            PropKind.FLOWERS -> {
                linePaint.color = 0xFF3FAE4A.toInt(); linePaint.strokeWidth = s * .06f
                val heads = listOf(-.35f to -1.2f, 0f to -1.4f, .35f to -1.2f, -.15f to -1.0f, .18f to -1.0f)
                for ((fx, fy) in heads) c.drawLine(0f, 0f, fx * s, fy * s, linePaint)
                val cols = intArrayOf(0xFFFF4F8B.toInt(), 0xFFFF6B6B.toInt(), 0xFFFFD23F.toInt(), 0xFFB061FF.toInt(), 0xFFFF8FB8.toInt())
                heads.forEachIndexed { i, (fx, fy) ->
                    fillPaint.color = cols[i % cols.size]
                    for (pp in 0 until 5) {
                        val a = pp * 2 * PI.toFloat() / 5 + t * .5f
                        c.drawCircle(fx * s + kotlin.math.cos(a) * s * .1f, fy * s + kotlin.math.sin(a) * s * .1f, s * .09f, fillPaint)
                    }
                    fillPaint.color = 0xFFFFF1A0.toInt(); c.drawCircle(fx * s, fy * s, s * .06f, fillPaint)
                }
                fillPaint.color = 0xFFFF4F8B.toInt()
                tmpPath.reset(); tmpPath.moveTo(-s * .25f, -s * .25f); tmpPath.lineTo(s * .25f, -s * .25f); tmpPath.lineTo(s * .12f, s * .1f); tmpPath.lineTo(-s * .12f, s * .1f); tmpPath.close()
                c.drawPath(tmpPath, fillPaint)
            }
            PropKind.NONE -> {}
        }
        // bites: black semicircles nibbled from the top-right
        if (bites > 0 && k != PropKind.WATER && k != PropKind.BEER && k != PropKind.FLOWERS) {
            fillPaint.color = Color.BLACK
            for (i in 0 until bites.coerceAtMost(4)) c.drawCircle(s * (.35f - i * .22f), -s * (1.0f - i * .1f), s * .22f, fillPaint)
        }
    }

    private fun drawCrack(c: Canvas, w: Float, h: Float, base: Float, now: Long) {
        val x = crackX * w; val y = crackY * h
        val p = ((now - crackStart) / 250f).coerceIn(0f, 1f)
        linePaint.color = Color.argb(230, 235, 245, 255); linePaint.strokeWidth = 3.5f; linePaint.clearShadowLayer()
        val rnd = java.util.Random(7)
        for (i in 0 until 14) {
            val a = i * 2 * PI.toFloat() / 14 + rnd.nextFloat() * .3f
            var px = x; var py = y
            val len = (w * (.25f + rnd.nextFloat() * .5f)) * p
            var d = 0f
            tmpPath.reset(); tmpPath.moveTo(px, py)
            while (d < len) {
                val seg = 18f + rnd.nextFloat() * 40f
                val aa = a + (rnd.nextFloat() - .5f) * .7f
                px += kotlin.math.cos(aa) * seg; py += kotlin.math.sin(aa) * seg; d += seg
                tmpPath.lineTo(px, py)
            }
            c.drawPath(tmpPath, linePaint)
        }
        linePaint.strokeWidth = 2.5f
        for (ring in 1..3) {
            tmpPath.reset()
            val r = base * .25f * ring * p
            for (i in 0..14) {
                val a = i * 2 * PI.toFloat() / 14
                val rr = r * (.85f + rnd.nextFloat() * .3f)
                val px = x + kotlin.math.cos(a) * rr; val py = y + kotlin.math.sin(a) * rr
                if (i == 0) tmpPath.moveTo(px, py) else tmpPath.lineTo(px, py)
            }
            c.drawPath(tmpPath, linePaint)
        }
        fillPaint.color = Color.argb(200, 255, 255, 255)
        c.drawCircle(x, y, base * .06f, fillPaint)
    }

}

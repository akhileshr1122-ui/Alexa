package com.haloapps.halobot

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/** Tiny synthesized "robot pet" sounds: chirps, giggles, boops, munches, slurps, clinks, pew-pews. */
class Sfx {
    private val rate = 22050
    private val cache = ConcurrentHashMap<String, ShortArray>()
    private val exec = Executors.newSingleThreadExecutor()
    @Volatile var enabled = true
    @Volatile var volume = .8f

    fun play(name: String) {
        if (!enabled) return
        exec.execute {
            try {
                val pcm = cache.getOrPut(name) { make(name) }
                if (pcm.isEmpty()) return@execute
                val t = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(pcm.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                t.write(pcm, 0, pcm.size)
                t.setVolume(volume)
                t.play()
                Thread.sleep(pcm.size * 1000L / rate + 60)
                t.release()
            } catch (_: Exception) {}
        }
    }

    // ---------------- synthesis ----------------
    private class Buf(val rate: Int, seconds: Float) {
        val d = DoubleArray((rate * seconds).toInt())
        /** A sine glide from f0 to f1 starting at [start] seconds, lasting [len], with a soft envelope. */
        fun tone(start: Float, len: Float, f0: Double, f1: Double, amp: Double = .5, vibrato: Double = 0.0, square: Boolean = false) {
            val s0 = (start * rate).toInt(); val n = (len * rate).toInt()
            var ph = 0.0
            for (i in 0 until n) {
                val idx = s0 + i
                if (idx >= d.size) break
                val p = i.toDouble() / n
                val f = f0 + (f1 - f0) * p + vibrato * sin(2 * PI * 18 * i / rate) * f0 * .06
                ph += 2 * PI * f / rate
                val env = minOf(1.0, i / (rate * .006)) * minOf(1.0, (n - i) / (rate * .02))
                val v = if (square) (if (sin(ph) > 0) .6 else -.6) else sin(ph)
                d[idx] += v * amp * env
            }
        }
        fun noise(start: Float, len: Float, amp: Double, decay: Double, lowpass: Double = 1.0) {
            val s0 = (start * rate).toInt(); val n = (len * rate).toInt()
            var seed = 1234567; var prev = 0.0
            for (i in 0 until n) {
                val idx = s0 + i
                if (idx >= d.size) break
                seed = seed * 1103515245 + 12345
                val r = ((seed shr 16) and 0x7fff) / 16384.0 - 1.0
                prev = prev + (r - prev) * lowpass
                d[idx] += prev * amp * exp(-i.toDouble() / rate * decay)
            }
        }
        fun pcm(): ShortArray = ShortArray(d.size) { (d[it].coerceIn(-1.0, 1.0) * 28000).toInt().toShort() }
    }

    private fun make(name: String): ShortArray {
        val b: Buf
        when (name) {
            "chirp" -> { b = Buf(rate, .2f); b.tone(0f, .16f, 900.0, 1700.0, .45) }
            "happy" -> { b = Buf(rate, .35f); b.tone(0f, .1f, 1000.0, 1300.0, .4); b.tone(.12f, .16f, 1300.0, 1900.0, .4) }
            "giggle" -> { b = Buf(rate, .55f); for (i in 0 until 4) b.tone(i * .12f, .08f, 1500.0 + i * 120, 1200.0 + i * 120, .4) }
            "boop" -> { b = Buf(rate, .18f); b.tone(0f, .14f, 520.0, 380.0, .55) }
            "surprised" -> { b = Buf(rate, .3f); b.tone(0f, .25f, 600.0, 2000.0, .45) }
            "sad" -> { b = Buf(rate, .7f); b.tone(0f, .3f, 700.0, 550.0, .4, vibrato = 1.0); b.tone(.32f, .35f, 550.0, 380.0, .4, vibrato = 1.0) }
            "hmph" -> { b = Buf(rate, .35f); b.tone(0f, .12f, 300.0, 260.0, .5, square = true); b.tone(.15f, .15f, 240.0, 200.0, .5, square = true) }
            "angry" -> { b = Buf(rate, .45f); for (i in 0 until 3) b.tone(i * .13f, .1f, 260.0, 220.0, .45, square = true) }
            "love" -> { b = Buf(rate, .6f); b.tone(0f, .25f, 800.0, 1000.0, .35, vibrato = 1.0); b.tone(.25f, .3f, 1000.0, 1300.0, .35, vibrato = 1.0) }
            "sleepy" -> { b = Buf(rate, .8f); b.tone(0f, .75f, 500.0, 260.0, .35) }
            "munch" -> { b = Buf(rate, .9f); for (i in 0 until 4) b.noise(i * .2f, .12f, .45, 25.0, .35) }
            "slurp" -> { b = Buf(rate, .7f); b.noise(0f, .6f, .35, 3.0, .15); b.tone(0f, .6f, 300.0, 700.0, .12) }
            "ahh" -> { b = Buf(rate, .6f); b.tone(0f, .55f, 900.0, 600.0, .35, vibrato = .5) }
            "burp" -> { b = Buf(rate, .35f); b.tone(0f, .3f, 140.0, 90.0, .55, square = true) }
            "clink" -> { b = Buf(rate, .8f); b.tone(0f, .75f, 2637.0, 2630.0, .35); b.tone(0f, .6f, 4186.0, 4180.0, .2); b.tone(.04f, .5f, 3520.0, 3515.0, .15) }
            "cheers" -> { b = Buf(rate, .45f); b.tone(0f, .12f, 1200.0, 1500.0, .4); b.tone(.14f, .25f, 1500.0, 2200.0, .4) }
            "pew" -> { b = Buf(rate, .35f); b.tone(0f, .3f, 2200.0, 300.0, .45) }
            "glass" -> { b = Buf(rate, 1.0f); b.noise(0f, .9f, .6, 6.0, .9); for (i in 0 until 6) b.tone(.05f + i * .07f, .2f, 3000.0 + i * 500, 2800.0 + i * 500, .12) }
            "dying" -> { b = Buf(rate, 1.6f); b.tone(0f, 1.5f, 900.0, 120.0, .45, vibrato = 1.0) }
            "revive" -> { b = Buf(rate, .7f); b.tone(0f, .2f, 500.0, 900.0, .4); b.tone(.2f, .2f, 900.0, 1400.0, .4); b.tone(.4f, .25f, 1400.0, 2100.0, .4) }
            "bounce" -> { b = Buf(rate, .12f); b.tone(0f, .1f, 420.0, 300.0, .35) }
            "purr" -> { b = Buf(rate, 1.0f); for (i in 0 until 10) b.tone(i * .1f, .08f, 120.0, 110.0, .3, square = true) }
            "sneeze" -> { b = Buf(rate, .5f); b.tone(0f, .2f, 700.0, 1100.0, .3); b.noise(.22f, .25f, .6, 12.0, .6) }
            "bubble" -> { b = Buf(rate, .6f); for (i in 0 until 5) b.tone(i * .1f, .07f, 600.0 + i * 150, 1400.0 + i * 150, .25) }
            "hello" -> { b = Buf(rate, .5f); b.tone(0f, .14f, 800.0, 1200.0, .4); b.tone(.16f, .12f, 1200.0, 1000.0, .35); b.tone(.3f, .16f, 1000.0, 1600.0, .4) }
            "bang" -> { b = Buf(rate, .4f); b.noise(0f, .35f, .9, 18.0, .7) }
            else -> return ShortArray(0)
        }
        return b.pcm()
    }

    /** The sound that goes with a facial expression, if any. */
    fun forExpression(name: String): String? = when (name) {
        "happy", "proud", "relieved" -> "happy"
        "joy", "excited", "party" -> "chirp"
        "laugh", "wink" -> "giggle"
        "love", "kiss", "lovestruck" -> "love"
        "surprised", "shocked" -> "surprised"
        "sad", "cry" -> "sad"
        "angry", "furious", "grumpy" -> "angry"
        "pout" -> "hmph"
        "sleepy", "yawn" -> "sleepy"
        "curious", "confused", "thinking" -> "boop"
        "eating", "charging" -> "munch"
        "dizzy" -> "dying"
        else -> null
    }

    fun close() { exec.shutdown() }
}

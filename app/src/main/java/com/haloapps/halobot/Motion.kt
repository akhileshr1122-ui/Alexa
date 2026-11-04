package com.haloapps.halobot

import android.content.Context
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * "Body" of the robot: the phone's vibration motor. Short patterns become giggles and
 * wiggles; long strong buzzes make a flat-edged phone creep or spin on a smooth surface
 * (the same trick the Cycloramic app used for hands-free 360 photos).
 */
class Motion(ctx: Context) {
    private val vib: Vibrator? = try {
        if (android.os.Build.VERSION.SDK_INT >= 31) ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
        else @Suppress("DEPRECATION") ctx.getSystemService(Vibrator::class.java)
    } catch (e: Exception) { null }

    var enabled = true
    /** Uptime until which accelerometer readings are polluted by our own buzzing. */
    @Volatile var busyUntil = 0L

    fun play(name: String) {
        val p = PATTERNS[name] ?: return
        wave(p.first, p.second)
    }

    fun wave(timings: LongArray, amps: IntArray) {
        val v = vib ?: return
        if (!enabled || !v.hasVibrator()) return
        busyUntil = SystemClock.uptimeMillis() + timings.sum() + 200
        try { v.vibrate(VibrationEffect.createWaveform(timings, amps, -1)) } catch (_: Exception) {}
    }

    fun pulse(ms: Long, amp: Int = 255) {
        val v = vib ?: return
        if (!enabled || !v.hasVibrator()) return
        busyUntil = SystemClock.uptimeMillis() + ms + 200
        try { v.vibrate(VibrationEffect.createOneShot(ms, amp.coerceIn(1, 255))) } catch (_: Exception) {}
    }

    fun stop() { try { vib?.cancel() } catch (_: Exception) {} }

    companion object {
        private fun p(vararg pairs: Pair<Long, Int>): Pair<LongArray, IntArray> {
            val t = LongArray(pairs.size + 1); val a = IntArray(pairs.size + 1)
            pairs.forEachIndexed { i, (ms, amp) -> t[i + 1] = ms; a[i + 1] = amp }
            return t to a
        }

        val PATTERNS: Map<String, Pair<LongArray, IntArray>> = mapOf(
            "giggle" to p(30L to 170, 40L to 0, 30L to 170, 40L to 0, 30L to 170, 40L to 0, 30L to 170),
            "wiggle" to p(80L to 255, 60L to 0, 80L to 255, 60L to 0, 80L to 255),
            "purr" to p(250L to 45, 80L to 25, 250L to 50, 80L to 25, 250L to 45, 80L to 25, 250L to 50),
            "dance" to p(150L to 255, 90L to 0, 150L to 255, 90L to 0, 90L to 200, 60L to 0, 90L to 200, 60L to 0, 300L to 255),
            "beat" to p(90L to 255),
            "startle" to p(120L to 255, 50L to 0, 60L to 200),
            "sad" to p(400L to 120, 200L to 0, 250L to 70),
            "hop" to p(240L to 255),
            "spin" to p(700L to 255),
            "shy" to p(40L to 120, 120L to 0, 40L to 120),
            "heartbeat" to p(60L to 255, 100L to 0, 60L to 200, 650L to 0, 60L to 255, 100L to 0, 60L to 200),
            "sneeze" to p(60L to 80, 250L to 0, 200L to 255),
            "chomp" to p(50L to 150, 90L to 0, 50L to 150, 90L to 0, 50L to 150, 90L to 0, 50L to 150),
            "alarm" to p(300L to 255, 150L to 0, 300L to 255, 150L to 0, 300L to 255),
            "tick" to p(15L to 120),
            "win" to p(60L to 200, 40L to 0, 60L to 200, 40L to 0, 60L to 200, 80L to 0, 400L to 255),
            "lose" to p(300L to 180, 120L to 0, 500L to 90),
            "breatheIn" to p(400L to 20, 400L to 40, 400L to 60, 400L to 80),
            "breatheOut" to p(400L to 80, 400L to 60, 400L to 40, 400L to 20),
            "hiccup" to p(70L to 255),
            "shiver" to p(25L to 120, 25L to 0, 25L to 120, 25L to 0, 25L to 120, 25L to 0, 25L to 120, 25L to 0, 25L to 120, 25L to 0, 25L to 120),
        )
    }
}

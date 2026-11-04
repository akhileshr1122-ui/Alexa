package com.haloapps.halobot

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/** The robot's own little chiptune beat (original, generated on the fly), 120 BPM. */
class MusicBox {
    private var track: AudioTrack? = null
    @Volatile var playing = false; private set
    val beatMs = 500L

    fun play(seconds: Int = 24, onStart: () -> Unit = {}) {
        stop()
        playing = true
        thread(name = "halobot-music") {
            try {
                val rate = 22050
                val n = rate * seconds
                val pcm = ShortArray(n)
                val beat = rate / 2                // 120 bpm
                val step = beat / 2                // eighth notes
                val chords = arrayOf(intArrayOf(57, 60, 64), intArrayOf(53, 57, 60), intArrayOf(48, 52, 55), intArrayOf(55, 59, 62)) // Am F C G
                val melody = intArrayOf(0, 2, 1, 2, 0, 2, 1, 2, 1, 2, 0, 1, 2, 1, 0, 2)
                fun freq(midi: Int) = 440.0 * 2.0.pow((midi - 69) / 12.0)
                var seed = 12345
                for (i in 0 until n) {
                    val bar = (i / (beat * 4)) % 4
                    val chord = chords[bar]
                    val stepIdx = (i / step) % 16
                    val inStep = (i % step).toDouble() / rate
                    val inBeat = (i % beat).toDouble() / rate
                    val tt = i.toDouble() / rate
                    // lead: square-ish arpeggio an octave up
                    val lf = freq(chord[melody[stepIdx]] + 12)
                    val lead = (if (sin(2 * PI * lf * tt) > 0) 1.0 else -1.0) * .11 * exp(-inStep * 9)
                    // bass: root, pulsing
                    val bf = freq(chord[0] - 12)
                    val bass = sin(2 * PI * bf * tt) * .22 * exp(-inBeat * 3)
                    // kick on every beat
                    val kf = 55 + 90 * exp(-inBeat * 30)
                    val kick = sin(2 * PI * kf * inBeat) * .5 * exp(-inBeat * 14)
                    // hat on off-beats
                    seed = seed * 1103515245 + 12345
                    val noise = ((seed shr 16) and 0x7fff) / 16384.0 - 1.0
                    val off = (i % beat) - beat / 2
                    val hat = if (off >= 0) noise * .07 * exp(-off.toDouble() / rate * 60) else 0.0
                    val fade = minOf(1.0, (n - i) / (rate * 1.5))
                    val v = (lead + bass + kick + hat) * fade
                    pcm[i] = (v.coerceIn(-1.0, 1.0) * 30000).toInt().toShort()
                }
                val t = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(n * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                t.write(pcm, 0, n)
                if (!playing) { t.release(); return@thread }
                track = t
                t.play()
                onStart()
                Thread.sleep(seconds * 1000L)
                if (track === t) stop()
            } catch (e: Exception) { playing = false }
        }
    }

    fun stop() {
        playing = false
        val t = track
        track = null
        try { t?.stop(); t?.release() } catch (_: Exception) {}
    }
}

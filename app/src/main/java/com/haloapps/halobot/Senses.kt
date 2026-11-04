package com.haloapps.halobot

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import androidx.core.content.ContextCompat
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Touch-free senses: accelerometer (shake, pick-up, free-fall, face-down, tilt),
 * light sensor (dark room), proximity (hand wave), battery (charging = eating),
 * and microphone loudness (loud noises, music beats, blowing out candles).
 */
class Senses(private val act: Activity, private val l: Listener, private val motion: Motion) : SensorEventListener {

    interface Listener {
        fun onShake()
        fun onPickedUp()
        fun onFreeFall()
        fun onFaceDown(down: Boolean)
        fun onTilt(gx: Float)
        fun onDark(dark: Boolean)
        fun onWave()
        fun onBattery(level: Int, charging: Boolean)
        fun onLoudNoise(level: Float)
        fun onBeat()
        fun onMusic(playing: Boolean)
    }

    private val sm = act.getSystemService(SensorManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var started = false

    // accelerometer state
    private val g = FloatArray(3)
    private var gInit = false
    private var shakeCount = 0
    private var shakeFirst = 0L
    private var lastShake = 0L
    private var freeStart = 0L
    private var lastFree = 0L
    private var stillSince = 0L
    private var lastPick = 0L
    private var faceDown = false
    private var tiltSent = 0f

    // light / proximity
    private var lux = 100f
    private var dark = false
    private var nearAt = 0L
    private var near = false
    private var lastWave = 0L

    // battery
    private var battLevel = -1
    private var battCharging = false
    val batteryLevel: Int get() = battLevel
    val isCharging: Boolean get() = battCharging

    private val batt = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            i ?: return
            val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            if (pct != battLevel || charging != battCharging) {
                battLevel = pct; battCharging = charging
                l.onBattery(pct, charging)
            }
        }
    }

    fun start() {
        if (started) return
        started = true
        sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        sm?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        sm?.getDefaultSensor(Sensor.TYPE_LIGHT)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        sm?.getDefaultSensor(Sensor.TYPE_PROXIMITY)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        ContextCompat.registerReceiver(act, batt, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_EXPORTED)
    }

    fun stop() {
        if (!started) return
        started = false
        sm?.unregisterListener(this)
        try { act.unregisterReceiver(batt) } catch (_: Exception) {}
        stopMic()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> accel(e.values)
            Sensor.TYPE_GYROSCOPE -> gyro(e.values, e.timestamp)
            Sensor.TYPE_LIGHT -> light(e.values[0])
            Sensor.TYPE_PROXIMITY -> prox(e.values[0], e.sensor.maximumRange)
        }
    }

    private fun displayRotation(): Int = try {
        if (android.os.Build.VERSION.SDK_INT >= 30) act.display?.rotation ?: 0
        else @Suppress("DEPRECATION") act.windowManager.defaultDisplay.rotation
    } catch (_: Exception) { 0 }

    private fun accel(v: FloatArray) {
        val now = SystemClock.uptimeMillis()
        if (!gInit) { for (i in 0..2) g[i] = v[i]; gInit = true }
        for (i in 0..2) g[i] = g[i] * .9f + v[i] * .1f
        val lx = v[0] - g[0]; val ly = v[1] - g[1]; val lz = v[2] - g[2]
        val lin = sqrt(lx * lx + ly * ly + lz * lz)
        val total = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
        val ownBuzz = now < motion.busyUntil

        if (total < 2.5f) {
            if (freeStart == 0L) freeStart = now
            if (now - freeStart > 90 && now - lastFree > 2500) { lastFree = now; l.onFreeFall() }
        } else freeStart = 0L

        if (!ownBuzz) {
            if (lin > 11f) {
                if (now - shakeFirst > 900) { shakeFirst = now; shakeCount = 0 }
                shakeCount++
                if (shakeCount >= 4 && now - lastShake > 2500) { lastShake = now; shakeCount = 0; l.onShake() }
            }
            if (lin < .6f) {
                if (stillSince == 0L) stillSince = now
            } else if (lin > 2.2f) {
                if (lin < 11f && stillSince != 0L && now - stillSince > 3000 && now - lastPick > 6000) {
                    lastPick = now; l.onPickedUp()
                }
                stillSince = 0L
            }
        }

        val down = g[2] < -7.5f
        if (down != faceDown) { faceDown = down; l.onFaceDown(down) }

        val gx = when (displayRotation()) {
            Surface.ROTATION_90 -> g[1] / 9.8f
            Surface.ROTATION_270 -> -g[1] / 9.8f
            Surface.ROTATION_180 -> g[0] / 9.8f
            else -> -g[0] / 9.8f
        }
        if (kotlin.math.abs(gx - tiltSent) > .04f) { tiltSent = gx; l.onTilt(gx) }
    }

    /** Total rotation around the vertical (gravity) axis, in degrees. Used to know if vibration actually turned the phone. */
    @Volatile var yawDeg = 0f
    val hasGyro: Boolean get() = sm?.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null
    private var lastGyroTs = 0L
    private fun gyro(v: FloatArray, ts: Long) {
        if (lastGyroTs != 0L && gInit) {
            val dt = (ts - lastGyroTs) / 1e9f
            val gn = sqrt(g[0] * g[0] + g[1] * g[1] + g[2] * g[2])
            if (gn > 1f && dt in 0f..0.2f) {
                val yawRate = (v[0] * g[0] + v[1] * g[1] + v[2] * g[2]) / gn
                yawDeg += Math.toDegrees((yawRate * dt).toDouble()).toFloat()
            }
        }
        lastGyroTs = ts
    }

    private val darkCheck = Runnable {
        if (lux < 4f && !dark) { dark = true; l.onDark(true) }
    }

    private fun light(v: Float) {
        lux = v
        if (v < 4f) {
            if (!dark) { main.removeCallbacks(darkCheck); main.postDelayed(darkCheck, 3500) }
        } else if (v > 12f) {
            main.removeCallbacks(darkCheck)
            if (dark) { dark = false; l.onDark(false) }
        }
    }

    private fun prox(v: Float, maxRange: Float) {
        val now = SystemClock.uptimeMillis()
        val isNear = v < maxRange && v < 4f
        if (isNear == near) return
        near = isNear
        if (isNear) nearAt = now
        else if (!faceDown && now - nearAt in 120..1500 && now - lastWave > 4000) { lastWave = now; l.onWave() }
    }

    // ---------- microphone ----------
    @Volatile private var micRun = false
    @Volatile var micPaused = false
    private var micThread: Thread? = null
    val micRunning: Boolean get() = micRun

    fun startMic() {
        if (micRun) return
        if (ContextCompat.checkSelfPermission(act, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        micRun = true
        micThread = thread(name = "halobot-mic", isDaemon = true) { micLoop() }
    }

    fun stopMic() {
        micRun = false
        micThread = null
    }

    @SuppressLint("MissingPermission")
    private fun micLoop() {
        val rate = 16000
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        var rec: AudioRecord? = null
        val buf = ShortArray(800)
        var avg = .01f
        val beats = ArrayDeque<Long>()
        var lastBeat = 0L
        var musicOn = false
        var lastLoud = 0L
        while (micRun) {
            if (micPaused) {
                rec?.let { r -> try { r.stop(); r.release() } catch (_: Exception) {} }
                rec = null
                Thread.sleep(150)
                continue
            }
            var r = rec
            if (r == null) {
                try {
                    val nr = AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minBuf, 4096))
                    if (nr.state != AudioRecord.STATE_INITIALIZED) { nr.release(); Thread.sleep(1000); continue }
                    nr.startRecording()
                    r = nr; rec = nr
                } catch (e: Exception) { Thread.sleep(1000); continue }
            }
            val n = try { r.read(buf, 0, buf.size) } catch (e: Exception) { -1 }
            if (n <= 0) { Thread.sleep(50); continue }
            var sum = 0.0
            for (i in 0 until n) { val s = buf[i] / 32768f; sum += (s * s).toDouble() }
            val rms = sqrt(sum / n).toFloat()
            val now = SystemClock.uptimeMillis()
            if (rms > .22f && rms > avg * 6f && now - lastLoud > 2500) {
                lastLoud = now
                val lv = rms
                main.post { l.onLoudNoise(lv) }
            }
            if (rms > avg * 1.6f && rms > .03f && now - lastBeat > 280) {
                lastBeat = now; beats.addLast(now)
                main.post { l.onBeat() }
            }
            while (beats.isNotEmpty() && now - beats.first() > 6000) beats.removeFirst()
            val musical = beats.size >= 9 && avg > .02f
            if (musical && !musicOn) { musicOn = true; main.post { l.onMusic(true) } }
            else if (!musical && musicOn && beats.size < 4) { musicOn = false; main.post { l.onMusic(false) } }
            avg = avg * .95f + rms * .05f
        }
        rec?.let { r -> try { r.stop(); r.release() } catch (_: Exception) {} }
    }
}

package com.haloapps.halobot

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.media.MediaPlayer
import android.media.PlaybackParams
import java.io.File
import java.util.Locale
import kotlin.concurrent.thread

/** Text-to-speech mouth + speech-recognition ears. */
class Voice(private val ctx: Context, private val prefs: Prefs) {
    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ready = false
    private val pending = HashMap<String, () -> Unit>()
    private var counter = 0

    // recognizers, tried in order until one works: 0 = Google service, 1 = phone default, 2 = on-device
    private var sr: SpeechRecognizer? = null
    private var srKind = -1
    private var workingKind = 0
    private var session = 0

    var speaking = false; private set
    var listening = false; private set
    var onSpeaking: ((Boolean) -> Unit)? = null
    var onListening: ((Boolean) -> Unit)? = null
    var onLevel: ((Float) -> Unit)? = null
    /** Human-readable reason the last listen produced nothing ("" when it worked). */
    var lastError = ""; private set
    var engineName = ""; private set

    init {
        tts = TextToSpeech(ctx) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ready = true
                try { tts?.language = Locale.US } catch (_: Exception) {}
                applyVoice()
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) { main.post { speaking = true; onSpeaking?.invoke(true) } }
                    override fun onDone(utteranceId: String?) { main.post { finish(utteranceId, true) } }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) { main.post { finish(utteranceId, true) } }
                    override fun onError(utteranceId: String?, errorCode: Int) { main.post { finish(utteranceId, true) } }
                    override fun onStop(utteranceId: String?, interrupted: Boolean) { main.post { finish(utteranceId, false) } }
                })
            }
        }
    }

    fun applyVoice() {
        tts?.setPitch(if (prefs.cloudVoice) 1f + (prefs.pitch - 1f) * .5f else prefs.pitch)
        tts?.setSpeechRate(prefs.rate)
        pickBestVoice(Locale.US)
    }

    /** Chooses the most natural installed voice for a language (neural / high quality first). */
    private fun pickBestVoice(loc: Locale) {
        val e = tts ?: return
        try {
            val vs = e.voices ?: return
            val best = vs.filter { it.locale.language == loc.language && !it.features.contains("notInstalled") }
                .sortedWith(compareByDescending<android.speech.tts.Voice> { it.quality }
                    .thenBy { if (it.locale.country == loc.country) 0 else 1 }
                    .thenBy { if (it.isNetworkConnectionRequired) 0 else 1 })
                .firstOrNull()
            if (best != null) e.voice = best else e.language = loc
        } catch (_: Exception) { try { e.language = loc } catch (_: Exception) {} }
    }

    // ---------- natural cloud voice (Groq Orpheus) ----------
    private var player: MediaPlayer? = null
    private var cloudBroken = false
    var cloudError = ""; private set
    private var speakSession = 0

    private fun cloudOk(lang: String?) = prefs.cloudVoice && prefs.groqKey.isNotBlank() && !cloudBroken && (lang == null || lang.startsWith("en"))

    private fun chunks(text: String): List<String> {
        val parts = text.split(Regex("(?<=[.!?])\\s+")).filter { it.isNotBlank() }
        val out = ArrayList<String>(); val sb = StringBuilder()
        for (p in parts) {
            if (sb.isNotEmpty() && sb.length + p.length > 180) { out.add(sb.toString()); sb.clear() }
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(p)
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out.flatMap { if (it.length <= 190) listOf(it) else it.chunked(190) }
    }

    private fun synth(text: String): ByteArray {
        val c = java.net.URL("https://api.groq.com/openai/v1/audio/speech").openConnection() as java.net.HttpURLConnection
        try {
            c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 8000; c.readTimeout = 20000
            c.setRequestProperty("Authorization", "Bearer ${prefs.groqKey}")
            c.setRequestProperty("Content-Type", "application/json")
            val body = org.json.JSONObject().put("model", "canopylabs/orpheus-v1-english").put("voice", prefs.cloudVoiceName.ifBlank { "hannah" })
                .put("input", text).put("response_format", "wav")
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            if (c.responseCode !in 200..299) {
                val err = try { c.errorStream?.bufferedReader()?.readText()?.take(200) } catch (_: Exception) { null }
                throw Exception("HTTP ${c.responseCode} ${err ?: ""}")
            }
            return c.inputStream.readBytes()
        } finally { c.disconnect() }
    }

    private fun sayCloud(text: String, id: String) {
        val my = ++speakSession
        thread(name = "cloud-tts") {
            val files = ArrayList<File>()
            try {
                chunks(text).forEachIndexed { i, ch ->
                    val f = File(ctx.cacheDir, "tts_${my}_$i.wav")
                    f.writeBytes(synth(ch))
                    files.add(f)
                }
            } catch (e: Exception) {
                cloudError = e.message ?: "voice error"
                if (cloudError.contains("terms") || cloudError.contains("401") || cloudError.contains("403") || cloudError.contains("404")) cloudBroken = true
                main.post { if (my == speakSession) { tts?.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), id) } }
                return@thread
            }
            main.post { if (my == speakSession) playFiles(files, 0, id, my) }
        }
    }

    private fun playFiles(files: List<File>, i: Int, id: String, my: Int) {
        if (my != speakSession) return
        if (i >= files.size) { files.forEach { it.delete() }; finish(id, true); return }
        try {
            val mp = MediaPlayer()
            player = mp
            mp.setDataSource(files[i].absolutePath)
            mp.prepare()
            val pitch = (1f + (prefs.pitch - 1f) * .35f).coerceIn(.8f, 1.5f)
            try { mp.playbackParams = PlaybackParams().setPitch(pitch).setSpeed(prefs.rate.coerceIn(.7f, 1.5f)) } catch (_: Exception) {}
            mp.setOnCompletionListener { m -> m.release(); if (player === m) player = null; playFiles(files, i + 1, id, my) }
            if (i == 0) { speaking = true; onSpeaking?.invoke(true) }
            mp.start()
        } catch (e: Exception) {
            cloudError = e.message ?: "playback error"
            files.forEach { it.delete() }
            finish(id, true)
        }
    }

    private fun finish(id: String?, runCallback: Boolean) {
        speaking = false
        onSpeaking?.invoke(false)
        val cb = if (id != null) pending.remove(id) else null
        if (runCallback) cb?.invoke()
    }

    /** Speaks [text]; [then] runs when finished (also when speech is disabled). */
    fun say(text: String, lang: String? = null, then: (() -> Unit)? = null) {
        val engine = tts
        if (!prefs.talk || (!ready && !cloudOk(lang)) || (engine == null && !cloudOk(lang))) {
            main.postDelayed({ then?.invoke() }, 500L + text.length * 35L)
            return
        }
        stopPlayer()
        val id = "u${counter++}"
        if (then != null) pending[id] = then
        if (cloudOk(lang)) { sayCloud(text, id); return }
        if (engine == null) return
        val loc = if (lang != null && !lang.startsWith("en")) Locale.forLanguageTag(lang) else Locale.US
        if (loc.language != currentLang) { currentLang = loc.language; pickBestVoice(loc) }
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), id)
    }
    private var currentLang = "en"

    private fun stopPlayer() {
        speakSession++
        player?.let { p -> try { p.stop(); p.release() } catch (_: Exception) {} }
        player = null
    }

    fun stopSpeaking() {
        pending.clear()
        stopPlayer()
        try { tts?.stop() } catch (_: Exception) {}
        speaking = false
        onSpeaking?.invoke(false)
    }

    private fun googleService(): ComponentName? = try {
        val list = ctx.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
        val g = list.firstOrNull { it.serviceInfo.packageName == "com.google.android.googlequicksearchbox" }
            ?: list.firstOrNull { it.serviceInfo.packageName.startsWith("com.google.android") }
        g?.let { ComponentName(it.serviceInfo.packageName, it.serviceInfo.name) }
    } catch (_: Exception) { null }

    private fun recognizer(kind: Int): SpeechRecognizer? {
        if (sr != null && srKind == kind) return sr
        try { sr?.destroy() } catch (_: Exception) {}
        sr = null; srKind = -1
        val r = try {
            when (kind) {
                0 -> googleService()?.let { engineName = "Google"; SpeechRecognizer.createSpeechRecognizer(ctx, it) }
                1 -> if (SpeechRecognizer.isRecognitionAvailable(ctx)) { engineName = "phone default"; SpeechRecognizer.createSpeechRecognizer(ctx) } else null
                2 -> if (Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)) { engineName = "on-device"; SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx) } else null
                else -> null
            }
        } catch (_: Exception) { null }
        if (r != null) { sr = r; srKind = kind }
        return r
    }

    private fun errText(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "I didn't hear any speech"
        SpeechRecognizer.ERROR_NO_MATCH -> "I heard you but couldn't make out words"
        SpeechRecognizer.ERROR_AUDIO -> "microphone error"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "speech needs internet"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "microphone permission missing"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "speech engine busy"
        SpeechRecognizer.ERROR_CLIENT -> "speech engine error"
        else -> "speech error $code"
    }

    /** Listens once; [cb] gets the lowercase transcript, or "" (see [lastError]). */
    fun listen(cb: (String) -> Unit) {
        if (listening) { cb(""); return }
        val my = ++session
        listening = true
        lastError = ""
        onListening?.invoke(true) // lets the robot free the microphone first
        var done = false
        var partial = ""
        val finish: (String) -> Unit = { s ->
            if (!done && my == session) {
                done = true
                listening = false
                onListening?.invoke(false)
                cb(s)
            }
        }

        fun attempt(kind: Int, triesLeft: Int) {
            if (done || my != session) return
            val r = recognizer(kind)
            if (r == null) {
                if (kind < 2) attempt(kind + 1, triesLeft) else { lastError = "no speech engine on this phone"; finish("") }
                return
            }
            r.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) { onLevel?.invoke(((rmsdB + 2f) / 12f).coerceIn(0f, 1f)) }
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) {
                    if (done || my != session) return
                    val engineProblem = error == SpeechRecognizer.ERROR_CLIENT || error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
                        error == SpeechRecognizer.ERROR_AUDIO || error == 11 || error == 12 || error == 13
                    if (partial.isNotBlank()) { lastError = ""; finish(partial); return }
                    if (engineProblem && triesLeft > 0) {
                        try { r.destroy() } catch (_: Exception) {}
                        sr = null; srKind = -1
                        main.postDelayed({ attempt(if (kind < 2) kind + 1 else 0, triesLeft - 1) }, 400)
                        return
                    }
                    lastError = errText(error)
                    finish("")
                }
                override fun onResults(results: Bundle?) {
                    val l = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val s = l?.firstOrNull()?.lowercase(Locale.US)?.trim().orEmpty().ifBlank { partial }
                    if (s.isNotBlank()) { workingKind = kind; lastError = "" } else lastError = "I couldn't make out words"
                    finish(s)
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    val l = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    l?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { partial = it.lowercase(Locale.US).trim() }
                }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            }
            try { r.startListening(i) } catch (e: Exception) {
                if (triesLeft > 0) main.postDelayed({ attempt(if (kind < 2) kind + 1 else 0, triesLeft - 1) }, 300)
                else { lastError = "speech engine wouldn't start"; finish("") }
            }
        }

        // give the robot's own microphone listener time to let go of the mic
        main.postDelayed({ attempt(workingKind, 2) }, 350)
        main.postDelayed({
            if (!done && my == session) {
                try { sr?.stopListening() } catch (_: Exception) {}
                main.postDelayed({ if (partial.isNotBlank()) finish(partial) else { if (lastError.isEmpty()) lastError = "I didn't hear any speech"; finish("") } }, 1800)
            }
        }, 10000)
    }

    fun cancelListening() {
        session++
        try { sr?.cancel() } catch (_: Exception) {}
        if (listening) { listening = false; onListening?.invoke(false) }
    }

    fun shutdown() {
        try { tts?.shutdown() } catch (_: Exception) {}
        try { sr?.destroy() } catch (_: Exception) {}
    }
}

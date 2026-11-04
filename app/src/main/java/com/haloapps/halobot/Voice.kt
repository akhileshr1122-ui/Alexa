package com.haloapps.halobot

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/** Text-to-speech mouth + speech-recognition ears. */
class Voice(private val ctx: Context, private val prefs: Prefs) {
    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ready = false
    private val pending = HashMap<String, () -> Unit>()
    private var counter = 0
    private var sr: SpeechRecognizer? = null

    var speaking = false; private set
    var listening = false; private set
    var onSpeaking: ((Boolean) -> Unit)? = null
    var onListening: ((Boolean) -> Unit)? = null
    var onLevel: ((Float) -> Unit)? = null

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
        tts?.setPitch(prefs.pitch)
        tts?.setSpeechRate(prefs.rate)
    }

    private fun finish(id: String?, runCallback: Boolean) {
        speaking = false
        onSpeaking?.invoke(false)
        val cb = if (id != null) pending.remove(id) else null
        if (runCallback) cb?.invoke()
    }

    /** Speaks [text]; [then] runs when finished (also when speech is disabled). */
    fun say(text: String, then: (() -> Unit)? = null) {
        val engine = tts
        if (!prefs.talk || !ready || engine == null) {
            main.postDelayed({ then?.invoke() }, 500L + text.length * 35L)
            return
        }
        val id = "u${counter++}"
        if (then != null) pending[id] = then
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), id)
    }

    fun stopSpeaking() {
        pending.clear()
        try { tts?.stop() } catch (_: Exception) {}
        speaking = false
        onSpeaking?.invoke(false)
    }

    fun listen(cb: (String) -> Unit) {
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) { cb(""); return }
        if (listening) { cb(""); return }
        val r = sr ?: SpeechRecognizer.createSpeechRecognizer(ctx).also { sr = it }
        var done = false
        val finish: (String) -> Unit = { s ->
            if (!done) {
                done = true
                listening = false
                onListening?.invoke(false)
                cb(s)
            }
        }
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) { onLevel?.invoke(((rmsdB + 2f) / 12f).coerceIn(0f, 1f)) }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) { finish("") }
            override fun onResults(results: Bundle?) {
                val l = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                finish(l?.firstOrNull()?.lowercase(Locale.US)?.trim() ?: "")
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
        }
        listening = true
        onListening?.invoke(true)
        try { r.startListening(i) } catch (e: Exception) { finish("") }
        main.postDelayed({
            if (!done) {
                try { r.stopListening() } catch (_: Exception) {}
                main.postDelayed({ finish("") }, 1500)
            }
        }, 9000)
    }

    fun cancelListening() {
        try { sr?.cancel() } catch (_: Exception) {}
        if (listening) { listening = false; onListening?.invoke(false) }
    }

    fun shutdown() {
        try { tts?.shutdown() } catch (_: Exception) {}
        try { sr?.destroy() } catch (_: Exception) {}
    }
}

package com.haloapps.halobot

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import androidx.activity.ComponentActivity
import java.util.Locale

/** Simple settings screen built in code (no XML), dark themed. */
class SettingsActivity : ComponentActivity() {
    private lateinit var prefs: Prefs
    private var tts: TextToSpeech? = null
    private val saves = ArrayList<() -> Unit>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        val d = resources.displayMetrics.density
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding((20 * d).toInt(), (20 * d).toInt(), (20 * d).toInt(), (40 * d).toInt()) }
        val scroll = ScrollView(this).apply { setBackgroundColor(0xFF0B0F14.toInt()); addView(col) }
        setContentView(scroll)

        fun header(s: String) = col.addView(TextView(this).apply {
            text = s; setTextColor(0xFF3FE3F2.toInt()); textSize = 15f; typeface = Typeface.DEFAULT_BOLD
            setPadding(0, (22 * d).toInt(), 0, (6 * d).toInt())
        })
        fun note(s: String) = col.addView(TextView(this).apply { text = s; setTextColor(0xFF8A99A8.toInt()); textSize = 12.5f; setPadding(0, 0, 0, (6 * d).toInt()) })
        fun field(label: String, value: String, hint: String = "", password: Boolean = false, onSave: (String) -> Unit) {
            col.addView(TextView(this).apply { text = label; setTextColor(Color.WHITE); textSize = 14f; setPadding(0, (8 * d).toInt(), 0, 0) })
            val e = EditText(this).apply {
                setText(value); this.hint = hint; setTextColor(Color.WHITE); setHintTextColor(0xFF5A6570.toInt()); isSingleLine = true
                if (password) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            col.addView(e)
            saves.add { onSave(e.text.toString().trim()) }
        }
        fun toggle(label: String, value: Boolean, onSave: (Boolean) -> Unit) {
            @Suppress("DEPRECATION")
            val sw = Switch(this).apply { text = label; isChecked = value; setTextColor(Color.WHITE); textSize = 14.5f; setPadding(0, (10 * d).toInt(), 0, (10 * d).toInt()) }
            col.addView(sw, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            saves.add { onSave(sw.isChecked) }
        }
        fun slider(label: String, value: Float, lo: Float, hi: Float, onSave: (Float) -> Unit) {
            val tv = TextView(this).apply { setTextColor(Color.WHITE); textSize = 14f; setPadding(0, (10 * d).toInt(), 0, 0) }
            fun upd(v: Float) { tv.text = "$label: ${"%.2f".format(v)}" }
            upd(value)
            col.addView(tv)
            val sb = SeekBar(this)
            sb.max = 100
            sb.progress = (((value - lo) / (hi - lo)) * 100f).toInt().coerceIn(0, 100)
            sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { upd(lo + (hi - lo) * p / 100f) }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
            col.addView(sb)
            saves.add { onSave(lo + (hi - lo) * sb.progress / 100f) }
        }

        col.addView(TextView(this).apply { text = "Halo Bot"; setTextColor(Color.WHITE); textSize = 26f; typeface = Typeface.DEFAULT_BOLD })
        note("Tap = poke · Double tap = talk · Swipe = pet · Long press = this screen")

        header("Identity")
        field("Robot's name (also the hands-free wake word)", prefs.robotName, "Halo") { prefs.robotName = it.ifBlank { "Halo" } }
        field("Your name", prefs.userName, "e.g. Akilesh") { prefs.userName = it }
        field("Your birthday (MM-DD)", prefs.birthday, "e.g. 04-21") { prefs.birthday = it }
        field("Your city (for weather)", prefs.city, "e.g. Toronto") { prefs.city = it }

        header("Look")
        col.addView(TextView(this).apply { text = "Eye color"; setTextColor(Color.WHITE); textSize = 14f })
        val spinner = Spinner(this)
        spinner.adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, Themes.list.map { it.name }) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                (super.getView(position, convertView, parent) as TextView).apply { setTextColor(Themes.get(position).bottom) }
        }
        spinner.setSelection(prefs.theme)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { prefs.theme = pos }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        col.addView(spinner)
        toggle("Retro scanlines on eyes", prefs.scanlines) { prefs.scanlines = it }
        toggle("Show camera preview (corner)", prefs.showCamera) { prefs.showCamera = it }
        toggle("Show captions", prefs.captions) { prefs.captions = it }
        toggle("Show a dim clock while sleeping at night", prefs.nightClock) { prefs.nightClock = it }

        header("Behaviour")
        toggle("Move with vibration", prefs.move) { prefs.move = it }
        toggle("Turn toward me / search for me with vibration", prefs.seek) { prefs.seek = it }
        toggle("Talk", prefs.talk) { prefs.talk = it }
        toggle("Ask me questions on its own", prefs.chatty) { prefs.chatty = it }
        toggle("React to sounds & dance to music", prefs.micReact) { prefs.micReact = it }
        toggle("Hands-free: listen for its name (uses more battery, may beep)", prefs.handsFree) { prefs.handsFree = it }
        toggle("Hourly drink-water reminders", prefs.water) { prefs.water = it }

        header("Voice")
        slider("Pitch", prefs.pitch, .5f, 2.2f) { prefs.pitch = it }
        slider("Speed", prefs.rate, .6f, 1.6f) { prefs.rate = it }
        col.addView(Button(this).apply {
            text = "Test voice"
            setOnClickListener {
                saves.forEach { s -> s() }
                val e = tts
                if (e == null) {
                    tts = TextToSpeech(this@SettingsActivity) { st ->
                        if (st == TextToSpeech.SUCCESS) speakTest()
                    }
                } else speakTest()
            }
        })

        header("Tests")
        note("Run these if the robot doesn't hear you or doesn't move. The robot will do the test and tell you the result.")
        col.addView(Button(this).apply {
            text = "Microphone test"
            setOnClickListener { saves.forEach { s -> s() }; prefs.pendingTest = "mic"; finish() }
        })
        col.addView(Button(this).apply {
            text = "Spin test (vibration movement)"
            setOnClickListener { saves.forEach { s -> s() }; prefs.pendingTest = "spin"; finish() }
        })

        header("AI brain (optional)")
        note("Add a Claude API key from console.anthropic.com to let your robot answer anything. Without it, the robot still works offline with 100+ built-in commands. Your key stays on this phone and is only sent to Anthropic's API.")
        if (prefs.apiKey.startsWith("sk-ant-oat")) col.addView(TextView(this).apply {
            text = "⚠ The saved key starts with sk-ant-oat. That's a Claude subscription login token (from Claude Code), not an API key, so it won't work here. Create an API key at console.anthropic.com → API Keys. It starts with sk-ant-api."
            setTextColor(0xFFFF7A7A.toInt()); textSize = 13f; setPadding(0, (6 * d).toInt(), 0, (6 * d).toInt())
        })
        field("Claude API key (starts with sk-ant-api)", prefs.apiKey, "sk-ant-api03-...", password = true) { prefs.apiKey = it }
        field("Model", prefs.aiModel, "claude-haiku-5-5") { prefs.aiModel = it.ifBlank { "claude-haiku-5-5" } }

        header("Stats")
        val days = if (prefs.firstDay == 0L) 1 else ((System.currentTimeMillis() - prefs.firstDay) / 86_400_000L) + 1
        note("Friends for $days days · Pets: ${prefs.pets} · Affection: ${prefs.affection} · Photos: ${prefs.photos}\n" +
            "Games: robot won ${prefs.gamesWon}, you won ${prefs.gamesLost} · Best trivia streak: ${prefs.triviaBest}")

        header("Things to say (double tap first)")
        note(
            "• what time is it · what's the date · show the clock · battery\n" +
            "• what's the weather (in Toronto)\n" +
            "• set a timer for 5 minutes · how much time is left · cancel timer\n" +
            "• set an alarm for 7 a.m. · remind me in 10 minutes to call mom · focus mode\n" +
            "• take a selfie · flashlight on/off\n" +
            "• play music · next song · pause music · louder · quieter\n" +
            "• dance · look around · go to sleep · wake up · show me your faces · sneeze\n" +
            "• tell me a joke / fact / story / riddle · sing a song · compliment me\n" +
            "• what does a cow say · flip a coin · roll a dice · pick a number between 1 and 100\n" +
            "• 12 times 7 · how do you spell robot · count to ten · should I ...?\n" +
            "• rock paper scissors · trivia · staring contest · hide and seek · guess the number · guess my mood\n" +
            "• breathe with me · how are you · I love you · how long have we been friends\n" +
            "• my name is ... · your name is ... · my birthday is April 21 · my favorite food is ...\n" +
            "• change your eye color to pink · be quiet · stop\n" +
            "• mic test · spin test · play your beat\n" +
            "Physical: pet it, poke it, pick it up, shake it, turn it face down, wave over the top sensor, cover it in the dark, plug in the charger, play music nearby."
        )
    }

    private fun speakTest() {
        val e = tts ?: return
        e.language = Locale.US
        e.setPitch(prefs.pitch); e.setSpeechRate(prefs.rate)
        e.speak("Hi! I'm ${prefs.robotName}. How do I sound?", TextToSpeech.QUEUE_FLUSH, null, "test")
    }

    override fun onPause() {
        super.onPause()
        saves.forEach { it() }
    }

    override fun onDestroy() {
        tts?.shutdown()
        super.onDestroy()
    }
}

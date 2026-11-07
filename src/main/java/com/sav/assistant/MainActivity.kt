package com.sav.assistant

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

class MainActivity : Activity(), TextToSpeech.OnInitListener {

    private val models = listOf("gemini-3.5-flash-lite", "gemini-3.8-flash", "gemini-3.6-flash")
    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView
    private lateinit var keyBox: EditText
    private lateinit var micBtn: Button
    private lateinit var langBtn: Button
    private var tts: TextToSpeech? = null
    private var recognizer: SpeechRecognizer? = null
    private var hindi = false
    private val prefs by lazy { getSharedPreferences("sav", MODE_PRIVATE) }

    private fun button(label: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = label
        b.setOnClickListener { onClick() }
        return b
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this, this)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.parseColor("#101418"))
        root.setPadding(32, 48, 32, 32)

        val title = TextView(this)
        title.text = "Sav"
        title.textSize = 28f
        title.setTextColor(Color.WHITE)

        keyBox = EditText(this)
        keyBox.hint = "Gemini API key"
        keyBox.setHintTextColor(Color.GRAY)
        keyBox.setTextColor(Color.WHITE)
        keyBox.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        keyBox.setText(prefs.getString("key", ""))

        val saveBtn = button("Save key") {
            prefs.edit().putString("key", keyBox.text.toString().trim()).apply()
            addLine("Key saved")
        }
        langBtn = button("Language: English") {
            hindi = !hindi
            langBtn.text = if (hindi) "Language: Hindi" else "Language: English"
        }
        val startBtn = button("Start O Sav listening") { startWake() }
        val stopBtn = button("Stop O Sav listening") { stopWake() }

        logView = TextView(this)
        logView.setTextColor(Color.WHITE)
        logView.textSize = 16f
        scroll = ScrollView(this)
        scroll.addView(logView)

        micBtn = Button(this)
        micBtn.text = "Tap and speak"
        micBtn.textSize = 20f
        micBtn.setOnClickListener { listen() }

        root.addView(title)
        root.addView(keyBox)
        root.addView(saveBtn)
        root.addView(langBtn)
        root.addView(startBtn)
        root.addView(stopBtn)
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(micBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 220))
        setContentView(root)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        val err = prefs.getString("wake_error", null)
        if (err != null) {
            addLine("Wake word error: $err")
            prefs.edit().remove("wake_error").apply()
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        if (i?.getBooleanExtra("autolisten", false) == true) {
            i.removeExtra("autolisten")
            micBtn.post { listen() }
        }
    }

    override fun onInit(status: Int) {}

    private fun addLine(s: String) {
        logView.append(s + "\n\n")
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun speak(t: String) {
        tts?.language = if (hindi) Locale("hi", "IN") else Locale("en", "IN")
        tts?.speak(t, TextToSpeech.QUEUE_FLUSH, null, "sav")
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun startWake() {
        prefs.edit().putString("key", keyBox.text.toString().trim()).apply()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 2)
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 3)
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            addLine("Allow 'Display over other apps' for Sav, come back, and tap Start again")
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")))
            return
        }
        prefs.edit().putBoolean("wake_on", true).apply()
        startForegroundService(Intent(this, WakeService::class.java))
        addLine("Sav is starting. The first time it downloads a speech model, so wait a minute or two. Then say: O Sav")
    }

    private fun stopWake() {
        prefs.edit().putBoolean("wake_on", false).apply()
        startService(Intent(this, WakeService::class.java).setAction("STOP"))
        addLine("Stopped listening for the wake word")
    }

    private fun resumeWake() {
        if (prefs.getBoolean("wake_on", false)) {
            startForegroundService(Intent(this, WakeService::class.java).setAction("RESUME"))
        }
    }

    private fun listen() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            addLine("Speech recognition is not available on this phone")
            return
        }
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) { micBtn.text = "Listening..." }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() { micBtn.text = "Thinking..." }
            override fun onError(e: Int) {
                micBtn.text = "Tap and speak"
                addLine("Could not hear you (code $e)")
                resumeWake()
            }
            override fun onResults(r: Bundle?) {
                micBtn.text = "Tap and speak"
                val t = r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (t != null) {
                    addLine("You: $t")
                    askGemini(t)
                }
                resumeWake()
            }
            override fun onPartialResults(p: Bundle?) {}
            override fun onEvent(t: Int, p: Bundle?) {}
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (hindi) "hi-IN" else "en-IN")
        recognizer?.startListening(i)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (res.isNotEmpty() && res[0] == PackageManager.PERMISSION_GRANTED) {
            if (code == 1) listen() else startWake()
        }
    }

    private fun askGemini(text: String) {
        val key = prefs.getString("key", "") ?: ""
        if (key.isEmpty()) {
            addLine("Save your Gemini API key first")
            return
        }
        Thread {
            val sys = "You are Sav, a personal voice assistant on an Android phone. " +
                "Reply ONLY with JSON: {\"action\":\"...\",\"arg\":\"...\",\"text\":\"...\",\"reply\":\"...\"}. " +
                "action is one of: youtube (arg=search query), maps (arg=place), search (arg=query), " +
                "call (arg=phone number digits only), whatsapp (arg=phone number with country code, text=message), " +
                "open_app (arg=app name), none. " +
                "reply is a short spoken answer in the language the user spoke (Hindi in Devanagari, or English). " +
                "If the user just asks a question, use action none and answer it in reply. " +
                "Keep reply under 2 sentences."
            val body = JSONObject()
                .put("systemInstruction", JSONObject().put("parts",
                    JSONArray().put(JSONObject().put("text", sys))))
                .put("contents", JSONArray().put(JSONObject()
                    .put("role", "user")
                    .put("parts", JSONArray().put(JSONObject().put("text", text)))))
                .put("generationConfig", JSONObject().put("responseMimeType", "application/json"))
            var out: String? = null
            var lastErr = "Unknown error"
            for (m in models) {
                try {
                    val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/" +
                        m + ":generateContent"
                    val c = URL(endpoint).openConnection() as HttpURLConnection
                    c.requestMethod = "POST"
                    c.setRequestProperty("Content-Type", "application/json")
                    c.setRequestProperty("x-goog-api-key", key)
                    c.doOutput = true
                    c.connectTimeout = 15000
                    c.readTimeout = 20000
                    c.outputStream.use { it.write(body.toString().toByteArray()) }
                    val code = c.responseCode
                    val stream = if (code in 200..299) c.inputStream else c.errorStream
                    val resp = stream.bufferedReader().use { it.readText() }
                    if (code in 200..299) {
                        val root = JSONObject(resp)
                        val cand = root.getJSONArray("candidates").getJSONObject(0)
                        val part = cand.getJSONObject("content").getJSONArray("parts").getJSONObject(0)
                        out = part.getString("text")
                        break
                    } else {
                        lastErr = "Error " + code + " (" + m + "): " + resp.take(150)
                    }
                } catch (e: Exception) {
                    lastErr = "Error (" + m + "): " + e.message
                }
            }
            val result = out
            if (result == null) {
                val msg = lastErr
                runOnUiThread { addLine(msg) }
                return@Thread
            }
            try {
                val j = JSONObject(result)
                runOnUiThread { act(j) }
            } catch (e: Exception) {
                runOnUiThread { addLine("Error: " + e.message) }
            }
        }.start()
    }

    private fun act(j: JSONObject) {
        val action = j.optString("action", "none")
        val arg = j.optString("arg", "")
        val reply = j.optString("reply", "")
        if (reply.isNotEmpty()) {
            addLine("Sav: $reply")
            speak(reply)
        }
        try {
            when (action) {
                "youtube" -> go("https://www.youtube.com/results?search_query=" + enc(arg))
                "maps" -> go("geo:0,0?q=" + enc(arg))
                "search" -> go("https://www.google.com/search?q=" + enc(arg))
                "call" -> go("tel:" + arg.filter { it.isDigit() || it == '+' })
                "whatsapp" -> go("https://wa.me/" + arg.filter { it.isDigit() } +
                    "?text=" + enc(j.optString("text", "")))
                "open_app" -> openApp(arg)
            }
        } catch (e: Exception) {
            addLine("Could not do that: " + e.message)
        }
    }

    private fun go(u: String) {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)))
    }

    private fun openApp(name: String) {
        val pm = packageManager
        val i = Intent(Intent.ACTION_MAIN)
        i.addCategory(Intent.CATEGORY_LAUNCHER)
        val match = pm.queryIntentActivities(i, 0)
            .firstOrNull { it.loadLabel(pm).toString().contains(name, true) }
        if (match == null) {
            addLine("App not found: $name")
            return
        }
        val launch = pm.getLaunchIntentForPackage(match.activityInfo.packageName)
        if (launch != null) startActivity(launch)
    }

    override fun onDestroy() {
        recognizer?.destroy()
        tts?.shutdown()
        super.onDestroy()
    }
}

// END OF FILE

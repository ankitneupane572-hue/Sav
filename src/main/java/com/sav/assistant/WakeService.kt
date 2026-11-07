package com.sav.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.net.URL
import java.util.zip.ZipInputStream

class WakeService : Service(), RecognitionListener {

    companion object {
        var model: Model? = null
    }

    private var speech: SpeechService? = null
    private var starting = false
    private var waking = false
    private val modelName = "vosk-model-small-en-us-0.15"
    private val wakeRegex = Regex(
        "\\b(o|oh|ow|oye|hey)\\s*(sav|save|sab|sev|sub|sob)\\b|\\bosav\\b")

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundNow("Starting...")
        if (intent?.action == "STOP") {
            stopListening()
            stopForeground(true)
            stopSelf()
            return START_NOT_STICKY
        }
        startListening()
        return START_STICKY
    }

    private fun buildNotification(text: String): Notification {
        return Notification.Builder(this, "sav")
            .setContentTitle("Sav")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun startForegroundNow(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("sav", "Sav", NotificationManager.IMPORTANCE_LOW))
        val n = buildNotification(text)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1, n)
        }
    }

    private fun setStatus(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(1, buildNotification(text))
    }

    private fun fail(msg: String) {
        getSharedPreferences("sav", MODE_PRIVATE).edit()
            .putString("wake_error", msg).apply()
        setStatus("Error: " + msg.take(80))
    }

    private fun startListening() {
        if (speech != null || starting) return
        starting = true
        waking = false
        Thread {
            try {
                val dir = File(filesDir, modelName)
                if (!File(dir, "am").exists()) {
                    setStatus("Downloading model...")
                    download(dir)
                }
                setStatus("Loading model...")
                if (model == null) model = Model(dir.absolutePath)
                val rec = Recognizer(model, 16000f)
                val s = SpeechService(rec, 16000f)
                s.startListening(this)
                speech = s
                setStatus("Ready. Say: Hey Sav")
            } catch (e: Exception) {
                fail(e.toString())
            }
            starting = false
        }.start()
    }

    private fun download(finalDir: File) {
        val tmp = File(filesDir, "tmp_model")
        tmp.deleteRecursively()
        tmp.mkdirs()
        val conn = URL("https://alphacephei.com/vosk/models/" + modelName + ".zip")
            .openConnection()
        conn.connectTimeout = 20000
        conn.readTimeout = 60000
        var total = 0L
        var lastShown = 0L
        val counting = object : FilterInputStream(conn.getInputStream()) {
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val n = super.read(b, off, len)
                if (n > 0) {
                    total += n
                    if (total - lastShown > 2000000) {
                        lastShown = total
                        setStatus("Downloading model: " + (total / 1000000) + " MB")
                    }
                }
                return n
            }
        }
        ZipInputStream(counting.buffered()).use { zip ->
            var e = zip.nextEntry
            while (e != null) {
                val f = File(tmp, e.name)
                if (!f.canonicalPath.startsWith(tmp.canonicalPath)) {
                    throw SecurityException("Bad zip entry")
                }
                if (e.isDirectory) {
                    f.mkdirs()
                } else {
                    f.parentFile?.mkdirs()
                    FileOutputStream(f).use { out -> zip.copyTo(out) }
                }
                e = zip.nextEntry
            }
        }
        setStatus("Unpacking model...")
        finalDir.deleteRecursively()
        File(tmp, modelName).renameTo(finalDir)
        tmp.deleteRecursively()
    }

    private fun heardText(json: String?): String {
        if (json == null) return ""
        return try {
            val j = JSONObject(json)
            j.optString("partial", j.optString("text", ""))
        } catch (e: Exception) {
            ""
        }
    }

    private fun check(text: String) {
        if (waking) return
        if (wakeRegex.containsMatchIn(text.lowercase())) onWake()
    }

    override fun onPartialResult(hypothesis: String?) {
        check(heardText(hypothesis))
    }

    override fun onResult(hypothesis: String?) {
        val t = heardText(hypothesis)
        if (t.isNotEmpty() && !waking) setStatus("Heard: " + t.takeLast(60))
        check(t)
    }

    override fun onFinalResult(hypothesis: String?) {}

    override fun onError(exception: Exception?) {
        fail(exception.toString())
    }

    override fun onTimeout() {}

    private fun onWake() {
        waking = true
        setStatus("Wake word heard!")
        stopListening()
        val i = Intent(this, MainActivity::class.java)
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        i.putExtra("autolisten", true)
        startActivity(i)
    }

    private fun stopListening() {
        try {
            speech?.stop()
            speech?.shutdown()
        } catch (e: Exception) {
        }
        speech = null
    }

    override fun onDestroy() {
        stopListening()
        super.onDestroy()
    }
}

// END OF FILE

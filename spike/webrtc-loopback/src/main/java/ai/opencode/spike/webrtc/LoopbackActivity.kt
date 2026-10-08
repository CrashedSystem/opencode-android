package ai.opencode.spike.webrtc

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

/**
 * `adb shell am start -n ai.opencode.spike.webrtc/.LoopbackActivity [--ez tcp true] [--ez apm true] [--ei seconds 6] [--ei chunkMs 100]`
 * runs one loopback; lines go to logcat (tag WebRTCSpike), the screen, and files/loopback.txt.
 */
class LoopbackActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = TextView(this).apply {
            textSize = 11f
            setPadding(24, 48, 24, 24)
        }
        setContentView(ScrollView(this).apply { addView(text) })
        val output = File(filesDir, "loopback.txt").apply { writeText("") }
        val log = { line: String ->
            Log.i("WebRTCSpike", line)
            output.appendText(line + "\n")
            runOnUiThread { text.append(line + "\n") }
        }
        Thread {
            runCatching {
                Loopback(
                    context = applicationContext,
                    tcpOnly = intent.getBooleanExtra("tcp", false),
                    processing = intent.getBooleanExtra("apm", false),
                    seconds = intent.getIntExtra("seconds", 6),
                    captureChunkMillis = intent.getIntExtra("chunkMs", 100),
                    verbose = intent.getBooleanExtra("verbose", false),
                    log = log,
                ).run()
            }.onFailure { log("FAIL ${it.stackTraceToString()}") }
            log("DONE")
        }.start()
    }
}

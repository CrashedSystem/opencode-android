@file:PortedFrom("OpenCode/DictationClient.swift", "553a407")

package ai.opencode.mobilecode

import ai.opencode.mobilecode.system.ProcessInfo
import ai.opencode.mobilecode.tca.DependencyKey
import ai.opencode.mobilecode.tca.DependencyValues
import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.math.log10
import kotlin.math.sqrt
import kotlin.random.Random
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/// On-device dictation via the iOS 26 SpeechAnalyzer engine (the one behind
/// Notes/Call transcription) — far better accuracy than keyboard dictation,
/// fully offline, streams live partial results.
class DictationClient(
    /// Downloads the on-device speech model if needed so the first mic tap
    /// doesn't stall behind a silent multi-minute install. Safe to call
    /// repeatedly; a no-op once installed.
    var prepare: suspend () -> Unit,
    /// Starts microphone capture and streams the transcript so far: finalized
    /// text plus the current volatile tail. Each element replaces the previous.
    var start: suspend () -> Flow<String>,
    /// Stops capture and finalizes: any pending final transcript is delivered
    /// on the stream, which then finishes.
    var stop: suspend () -> Unit,
) {
    companion object : DependencyKey<DictationClient> {
        override val liveValue: DictationClient
            get() {
                if (BuildConfig.DEBUG && ProcessInfo.processInfo.environment["OPENCODE_LAB_DICTATION"] == "1") return lab()
                val live = LiveDictation()
                activeStop = { live.stop() }
                return DictationClient(
                    prepare = { live.prepare() },
                    start = { live.start() },
                    stop = { live.stop() },
                )
            }

        override val testValue: DictationClient
            get() = DictationClient(
                prepare = {},
                start = { emptyFlow() },
                stop = {},
            )

        fun install(application: Application) {
            if (installed) return
            installed = true
            appContext = application.applicationContext
            application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                    if (activity is ComponentActivity) permissions[activity] = DictationPermission(activity)
                }

                override fun onActivityStarted(activity: Activity) = Unit

                override fun onActivityResumed(activity: Activity) {
                    resumed = activity
                }

                override fun onActivityPaused(activity: Activity) = Unit

                override fun onActivityStopped(activity: Activity) = Unit

                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

                override fun onActivityDestroyed(activity: Activity) {
                    permissions.remove(activity)
                    if (resumed == activity) resumed = null
                }
            })
            ProcessLifecycleOwner.get().lifecycle.addObserver(
                LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_STOP) scope.launch { activeStop?.invoke() }
                },
            )
        }

        private fun lab(): DictationClient {
            val current = HashMap<Int, LabRun>()
            var token = 0
            val finalize: suspend () -> Unit = {
                DictationLevels.shared.set(0f)
                val run = synchronized(current) { current.remove(token) }
                run?.channel?.trySend(run.transcript + "\n████████████")
                run?.channel?.close()
                run?.job?.cancel()
            }
            return DictationClient(
                prepare = {},
                start = {
                    val channel = Channel<String>(Channel.BUFFERED)
                    val run = LabRun(channel)
                    val id = synchronized(current) {
                        token += 1
                        current[token] = run
                        token
                    }
                    run.job = scope.launch {
                        val count = ProcessInfo.processInfo.environment["OPENCODE_LAB_DICTATION_SENTENCES"]?.toIntOrNull() ?: 14
                        for (index in 1..maxOf(1, count)) {
                            delay(250)
                            if (!isActive) return@launch
                            DictationLevels.shared.set(Random.nextDouble(0.25, 0.9).toFloat())
                            val text = synchronized(current) {
                                val active = current[id] ?: return@synchronized null
                                active.transcript += (if (index == 1) "" else " ") +
                                    "Dictated sentence number $index keeps the composer growing."
                                active.transcript
                            } ?: return@launch
                            channel.trySend(text)
                        }
                        delay(250)
                        if (!isActive) return@launch
                        finalize()
                    }
                    channel.consumeAsFlow().onCompletion { run.job?.cancel() }
                },
                stop = finalize,
            )
        }
    }
}

private class LabRun(
    val channel: Channel<String>,
    var transcript: String = "",
    var job: Job? = null,
)

var DependencyValues.dictation: DictationClient
    get() = this[DictationClient]
    set(value) {
        this[DictationClient] = value
    }

/// The microphone's live level while dictating, for the recording button's
/// halo. Kept outside TCA, like `VoiceAudioLevels`, so per-buffer updates
/// never become store actions.
class DictationLevels {
    private val lock = Any()
    private var value = 0f

    /// 0 (silence) to 1 (loud speech).
    val level: Float
        get() = synchronized(lock) { value }

    fun update(samples: FloatArray) {
        if (samples.isEmpty()) return
        var sumSquares = 0f
        for (sample in samples) sumSquares += sample * sample
        // The same −48…−12 dB speech window as the voice call's meter.
        val db = 20 * log10(sqrt(sumSquares / samples.size) + 1e-6f)
        set((db + 48) / 36)
    }

    fun set(level: Float) {
        synchronized(lock) { value = level.coerceIn(0f, 1f) }
    }

    companion object {
        val shared = DictationLevels()
    }
}

sealed class DictationError(message: String) : Exception(message) {
    data object microphoneDenied : DictationError("Microphone access is off for OpenCode. Enable it in Settings.")

    data object localeUnsupported : DictationError("Dictation isn’t available for your language yet.")

    data object audioFormatUnavailable : DictationError("Dictation couldn’t start an audio session.")
}

object DictationAudioSession {
    /// A call that just ended deactivates the shared session a moment later
    /// (after its hang-up tail and haptics); claiming it first keeps that from
    /// cutting off dictation mid-recording.
    fun claim() = Unit
}

private class LiveDictation {
    private var recognizer: SpeechRecognizer? = null
    private var transcripts: Channel<String>? = null
    private var generation = 0
    private var finalized = ""

    suspend fun prepare() {
        if (!available()) return
    }

    suspend fun start(): Flow<String> {
        stop()
        val started = ++generation
        if (!available()) throw DictationError.localeUnsupported
        if (!ensurePermission()) throw DictationError.microphoneDenied
        if (generation != started) throw CancellationException()
        val channel = Channel<String>(Channel.BUFFERED)
        transcripts = channel
        finalized = ""
        withContext(Dispatchers.Main.immediate) {
            if (generation != started) return@withContext
            DictationAudioSession.claim()
            begin(channel, started)
        }
        if (generation != started) {
            channel.close()
            throw CancellationException()
        }
        return channel.consumeAsFlow().onCompletion {
            if (generation == started) stop()
        }
    }

    suspend fun stop() {
        generation += 1
        val channel = transcripts
        transcripts = null
        DictationLevels.shared.set(0f)
        val recognizer = recognizer
        this.recognizer = null
        if (recognizer != null) {
            withContext(Dispatchers.Main.immediate) {
                runCatching { recognizer.stopListening() }
                recognizer.destroy()
            }
        }
        channel?.close()
    }

    private fun available(): Boolean {
        val context = appContext ?: return false
        return runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrDefault(false)
    }

    private suspend fun ensurePermission(): Boolean {
        val context = appContext ?: return false
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            return true
        }
        val activity = resumed as? ComponentActivity ?: return false
        return permissions[activity]?.ensure() == true
    }

    private fun begin(channel: Channel<String>, started: Int) {
        val context = appContext ?: run {
            channel.close(DictationError.localeUnsupported)
            return
        }
        val created = runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(context) }.getOrNull()
        if (created == null) {
            channel.close(DictationError.localeUnsupported)
            return
        }
        recognizer = created
        created.setRecognitionListener(listener(channel, started, created))
        runCatching { created.startListening(intent()) }.onFailure {
            channel.close(DictationError.audioFormatUnavailable)
        }
    }

    private fun listener(channel: Channel<String>, started: Int, created: SpeechRecognizer) = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit

        override fun onBeginningOfSpeech() = Unit

        override fun onRmsChanged(rmsdB: Float) {
            DictationLevels.shared.set((rmsdB + 2f) / 12f)
        }

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() = Unit

        override fun onError(error: Int) {
            if (generation != started) return
            val failure = failure(error) ?: run {
                runCatching { created.startListening(intent()) }
                return
            }
            channel.close(failure)
        }

        override fun onResults(results: Bundle?) {
            val text = best(results)
            if (text.isNotEmpty()) {
                finalized = join(finalized, text)
                channel.trySend(finalized)
            }
            if (generation == started) runCatching { created.startListening(intent()) }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            channel.trySend(join(finalized, best(partialResults)))
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun intent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
    }

    private fun failure(error: Int): DictationError? = when (error) {
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> DictationError.microphoneDenied
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> DictationError.localeUnsupported
        SpeechRecognizer.ERROR_AUDIO -> DictationError.audioFormatUnavailable
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT, SpeechRecognizer.ERROR_CLIENT -> null
        else -> DictationError.localeUnsupported
    }

    private fun best(results: Bundle?): String =
        results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()

    private fun join(committed: String, tail: String): String = when {
        committed.isEmpty() -> tail
        tail.isEmpty() -> committed
        else -> "$committed $tail"
    }
}

private class DictationPermission(private val activity: ComponentActivity) {
    private var waiter: CancellableContinuation<Boolean>? = null
    private val launcher = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val waiting = waiter
        waiter = null
        waiting?.resume(granted)
    }

    suspend fun ensure(): Boolean {
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            return true
        }
        return suspendCancellableCoroutine { continuation ->
            waiter = continuation
            continuation.invokeOnCancellation { if (waiter == continuation) waiter = null }
            launcher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
}

private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
private val permissions = HashMap<Activity, DictationPermission>()

@Volatile
private var installed = false

@Volatile
private var appContext: Context? = null

@Volatile
private var resumed: Activity? = null

@Volatile
private var activeStop: (suspend () -> Unit)? = null

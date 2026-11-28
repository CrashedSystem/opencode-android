package ai.opencode.feature.voice.samsung

import ai.opencode.core.log.AppLog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Process
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Samsung's AI text-to-speech, reached through the interface it exports to other apps.
 *
 * This is where a voice recorded with "나만의 음성 만들기" lives. Samsung's own TTS engine reports
 * it, but `setVoice` on the standard Android API refuses it without a matching signature; this
 * interface is the one the phone app and call screening use, and its permission is `normal`.
 *
 * Nothing here is called unless [describe] or [speak] is asked for, so a phone without the service
 * costs one failed bind, once.
 */
class SamsungTtsClient(private val context: Context) {
    private var bound: SamsungTtsProxy? = null
    private var connection: ServiceConnection? = null

    /** Connects if needed; null when this phone has no such service. */
    private suspend fun connect(): SamsungTtsProxy? {
        bound?.let { if (it.alive()) return it }
        unbind()
        val deferred = CompletableDeferred<IBinder?>()
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                if (!deferred.isCompleted) deferred.complete(service)
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                if (!deferred.isCompleted) deferred.complete(null)
            }

            override fun onBindingDied(name: ComponentName?) {
                if (!deferred.isCompleted) deferred.complete(null)
            }
        }
        val intent = Intent(SamsungTts.ACTION).setPackage(SamsungTts.PACKAGE)
        val started = runCatching { context.bindService(intent, conn, Context.BIND_AUTO_CREATE) }.getOrDefault(false)
        if (!started) {
            AppLog.voice.info { "samsung_tts_bind_failed" }
            return null
        }
        connection = conn
        val binder = withTimeoutOrNull(BIND_TIMEOUT.inWholeMilliseconds) { deferred.await() }
        if (binder == null) {
            AppLog.voice.info { "samsung_tts_no_service" }
            unbind()
            return null
        }
        return SamsungTtsProxy(binder).also { bound = it }
    }

    private fun unbind() {
        connection?.let { runCatching { context.unbindService(it) } }
        connection = null
        bound = null
    }

    /**
     * What this phone offers, for the log: whether the service answers, how many voices it has, and
     * which of them are personal recordings and whether they demand a signature.
     */
    suspend fun describe(): String = withContext(Dispatchers.IO) {
        val proxy = connect() ?: return@withContext "samsung-tts: unavailable"
        val caller = callerName()
        val uid = Process.myUid()
        val locales = runCatching { proxy.getSupportLocales(caller, uid) }.getOrDefault(emptyList())
        val voices = runCatching { proxy.getVoices(caller, uid) }.getOrDefault(emptyList())
        val personal = voices.filter { it.isPersonal }
        val lines = buildList {
            add("samsung-tts: ${voices.size} voices, locales=${locales.joinToString(",") { it.take(12) }}")
            for (voice in voices) {
                add(
                    "  ${voice.name} | tag=${voice.tag.take(16)} | ${voice.locale?.toLanguageTag()}" +
                        " | personal=${voice.isPersonal} | needsAuth=${voice.requiresAuthorization}" +
                        " | status=${voice.personalStatus} | pkg=${voice.packageName}",
                )
            }
            if (personal.isEmpty()) add("  no personal voice reported")
        }
        lines.joinToString("\n")
    }

    /**
     * Reads [text] with [voice], or the service's own choice when null. Returns the code the
     * service answered with; 0 accepted it.
     */
    suspend fun speak(text: String, voice: SamsungVoice?, rate: Float): Int = withContext(Dispatchers.IO) {
        val proxy = connect() ?: return@withContext SAMSUNG_UNAVAILABLE
        val caller = callerName()
        val uid = Process.myUid()
        val voices = runCatching { proxy.getVoices(caller, uid) }.getOrDefault(emptyList())
        val chosen = voice ?: voices.firstOrNull { it.isPersonal } ?: voices.firstOrNull() ?: return@withContext SAMSUNG_NO_VOICE
        // The synthesizer is per caller, not per voice, so it is created before the chosen voice is
        // set; a personal one goes through the custom-voice entry point.
        val created = if (chosen.isPersonal) {
            proxy.createCustomVoiceSynthesizer(caller, uid, NoopInit)
        } else {
            proxy.createSynthesizer(caller, uid, NoopInit)
        }
        if (created < 0) {
            AppLog.voice.error { "samsung_tts_synth_failed $created" }
            return@withContext created
        }
        val set = proxy.setVoice(caller, uid, chosen)
        if (set != SamsungTts.SUCCESS) {
            AppLog.voice.error { "samsung_tts_set_voice_failed $set" }
            return@withContext set
        }
        val done = CompletableDeferred<Int>()
        val callback = object : SamsungSynthesisCallback.Stub() {
            override fun onStart(utterance: String, sampleRate: Int, encoding: Int, channels: Int) = Unit
            override fun onProgress(utterance: String, audio: ByteArray, start: Int, length: Int) = Unit
            override fun onRangeStart(utterance: String, start: Int, end: Int, frame: Int) = Unit
            override fun onDone(utterance: String) {
                if (!done.isCompleted) done.complete(SamsungTts.SUCCESS)
            }

            override fun onError(utterance: String, code: Int) {
                if (!done.isCompleted) done.complete(code)
            }
        }
        val params = android.os.Bundle().apply { putFloat(SamsungTts.PARAM_SPEECH_RATE, rate) }
        val accepted = proxy.speak(
            caller = caller,
            uid = uid,
            voice = chosen,
            text = text,
            queueMode = SamsungTts.QUEUE_FLUSH,
            params = params,
            utterance = "oc-${System.nanoTime()}",
            callback = callback,
        )
        if (accepted != SamsungTts.SUCCESS) return@withContext accepted
        withTimeoutOrNull(SPEAK_TIMEOUT.inWholeMilliseconds) { done.await() } ?: SPEAK_TIMEOUT_CODE
    }

    suspend fun stop() = withContext(Dispatchers.IO) {
        val proxy = bound ?: return@withContext
        runCatching { proxy.stop(callerName(), Process.myUid()) }
        Unit
    }

    /** The service keys a synthesizer by this name and the caller's uid. */
    private fun callerName(): String = context.packageName

    private object NoopInit : SamsungInitCallback.Stub() {
        override fun onConnected(synthesizerID: Int) = Unit
        override fun onDisconnected() = Unit
        override fun onVoiceUpdated(voices: List<SamsungVoice>) = Unit
    }

    /** Whether the binder survived; a dead one is replaced on the next call. */
    private fun SamsungTtsProxy.alive(): Boolean = runCatching { getSupportLocales(callerName(), Process.myUid()) }.isSuccess

    companion object {
        const val SAMSUNG_UNAVAILABLE = -100
        const val SAMSUNG_NO_VOICE = -101
        const val SPEAK_TIMEOUT_CODE = -102
        private val BIND_TIMEOUT: Duration = 6.seconds
        private val SPEAK_TIMEOUT: Duration = 120.seconds
    }
}

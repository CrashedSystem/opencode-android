package ai.opencode.feature.voice.samsung

import android.os.Bundle
import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import android.os.Parcelable
import android.os.RemoteException
import java.util.Locale

/**
 * The AIDL surface of Samsung's AI text-to-speech, as `SamsungIntelliVoiceServices` exports it
 * under `intellivoiceservice.intent.action.BIND_TEXT_TO_SPEECH_FOR_EXTERNAL`.
 *
 * Written from the interface the service actually implements rather than a published SDK, because
 * Samsung ships none: the classes live in `/system/priv-app/SamsungIntelliVoiceServices` under
 * `com.samsung.android.sivs.ai.sdkcommon.tts`. The values are copied from that APK, so a Samsung
 * update that changes them shows as a failing call, not as silently wrong speech.
 */
object SamsungTts {
    const val ACTION = "intellivoiceservice.intent.action.BIND_TEXT_TO_SPEECH_FOR_EXTERNAL"
    const val PACKAGE = "com.samsung.android.intellivoiceservice"

    /** Declared by that service; `normal`, so a manifest line grants it. */
    const val PERMISSION = "com.samsung.android.intellivoiceservice.ai.tts.permission.BIND_TEXT_TO_SPEECH_FOR_EXTERNAL"

    const val SUCCESS = 0
    const val LANG_AVAILABLE = 0
    const val LANG_COUNTRY_AVAILABLE = 1
    const val LANG_COUNTRY_VAR_AVAILABLE = 2
    const val LANG_NOT_SUPPORTED = -1

    /** `speak` with this flushes what is queued; the other adds to it. */
    const val QUEUE_FLUSH = 0
    const val QUEUE_ADD = 1

    const val ERROR_NOT_AUTHORIZED = -34
    const val ERROR_CUSTOM_VOICE_NOT_AVAILABLE = -50
    const val ERROR_CUSTOM_VOICE_NOT_INSTALLED = -52

    const val PARAM_SPEECH_RATE = "PARAM_KEY_SPEECH_RATE"
    const val PARAM_PITCH = "PARAM_KEY_PITCH"
}

/** One of the service's voices. `features` carries the flags that decide whether it can be used. */
data class SamsungVoice(
    val tag: String,
    val locale: Locale?,
    val features: Bundle?,
) : Parcelable {
    val name: String get() = features?.getString("KEY_FEATURE_STRING_VOICE_NAME") ?: tag
    val packageName: String? get() = features?.getString("KEY_FEATURE_STRING_PACKAGE_NAME")

    /** A voice trained from the owner's recordings ("내 음성"). */
    val isPersonal: Boolean get() = features?.getBoolean("KEY_FEATURE_BOOL_IS_PERSONAL_TTS", false) == true

    /** The flag `setVoice` refuses on without a matching signature. */
    val requiresAuthorization: Boolean
        get() = features?.getBoolean("KEY_FEATURE_BOOL_REQUIRES_AUTHORIZATION", false) == true

    val personalStatus: String? get() = features?.getString("KEY_FEATURE_STRING_PERSONAL_TTS_STATUS")

    override fun describeContents() = 0

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(tag)
        parcel.writeSerializable(locale)
        parcel.writeBundle(features)
    }

    companion object {
        @JvmField
        val CREATOR = object : Parcelable.Creator<SamsungVoice> {
            override fun createFromParcel(parcel: Parcel) = SamsungVoice(
                tag = parcel.readString().orEmpty(),
                locale = parcel.readSerializable(java.util.Locale::class.java.classLoader, Locale::class.java) as? Locale,
                features = parcel.readBundle(SamsungVoice::class.java.classLoader),
            )

            override fun newArray(size: Int) = arrayOfNulls<SamsungVoice>(size)
        }
    }
}

/** The callbacks the service makes while synthesizing. */
interface SamsungSynthesisCallback : IInterface {
    fun onStart(utterance: String, sampleRate: Int, encoding: Int, channels: Int)
    fun onProgress(utterance: String, audio: ByteArray, start: Int, length: Int)
    fun onRangeStart(utterance: String, start: Int, end: Int, frame: Int)
    fun onDone(utterance: String)
    fun onError(utterance: String, code: Int)

    abstract class Stub : android.os.Binder(), SamsungSynthesisCallback {
        override fun asBinder(): IBinder = this

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            when (code) {
                1 -> { onStart(data.readString().orEmpty(), data.readInt(), data.readInt(), data.readInt()); return true }
                2 -> {
                    val utterance = data.readString().orEmpty()
                    val bytes = data.createByteArray() ?: ByteArray(0)
                    onProgress(utterance, bytes, data.readInt(), data.readInt())
                    return true
                }
                3 -> { onRangeStart(data.readString().orEmpty(), data.readInt(), data.readInt(), data.readInt()); return true }
                4 -> { onDone(data.readString().orEmpty()); return true }
                5 -> { onError(data.readString().orEmpty(), data.readInt()); return true }
            }
            return super.onTransact(code, data, reply, flags)
        }
    }
}

/** `onConnected` comes with the synthesizer id every other call needs. */
interface SamsungInitCallback : IInterface {
    fun onConnected(synthesizerID: Int)
    fun onDisconnected()
    fun onVoiceUpdated(voices: List<SamsungVoice>)

    abstract class Stub : android.os.Binder(), SamsungInitCallback {
        override fun asBinder(): IBinder = this

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            when (code) {
                1 -> { onConnected(data.readInt()); return true }
                2 -> { onDisconnected(); return true }
                3 -> {
                    @Suppress("UNCHECKED_CAST")
                    val voices = data.readArrayList(SamsungVoice::class.java.classLoader) as? List<SamsungVoice> ?: emptyList()
                    onVoiceUpdated(voices)
                    return true
                }
            }
            return super.onTransact(code, data, reply, flags)
        }
    }
}

/**
 * `ITextToSpeechService`'s transactions, in the order the service declares them. Signatures follow
 * the AIDL the service ships; a wrong transaction here shows as a remote exception, not silence.
 */
class SamsungTtsProxy(private val remote: IBinder) {
    fun getSupportLocales(caller: String, uid: Int): List<String> =
        transact(1) { writeString(caller); writeInt(uid) }?.let { reply ->
            reply.readStringList().orEmpty()
        }.orEmpty()

    fun getVoices(caller: String, uid: Int): List<SamsungVoice> = voices(3, caller, uid, null)

    fun getVoicesWithLocale(caller: String, uid: Int, language: String): List<SamsungVoice> =
        voices(4, caller, uid, language)

    /** Returns this caller's synthesizer count; the caller name and uid are the session. */
    fun createSynthesizer(caller: String, uid: Int, callback: SamsungInitCallback): Int =
        transact(7) {
            writeString(caller)
            writeInt(uid)
            writeStrongInterface(callback)
        }?.let { it.readInt() } ?: -1

    fun releaseSynthesizer(caller: String, uid: Int): Int =
        transact(8) { writeString(caller); writeInt(uid) }?.let { it.readInt() } ?: -1

    fun setVoice(caller: String, uid: Int, voice: SamsungVoice): Int =
        transact(9) {
            writeString(caller)
            writeInt(uid)
            writeTypedObject(voice, 0)
        }?.let { it.readInt() } ?: -1

    fun speak(
        caller: String,
        uid: Int,
        voice: SamsungVoice?,
        text: String,
        queueMode: Int,
        params: Bundle?,
        utterance: String,
        callback: SamsungSynthesisCallback,
    ): Int = transact(10) {
        writeString(caller)
        writeInt(uid)
        writeTypedObject(voice, 0)
        writeCharSequence(text)
        writeInt(queueMode)
        writeTypedObject(params, 0)
        writeString(utterance)
        writeStrongInterface(callback)
    }?.let { it.readInt() } ?: -1

    fun stop(caller: String, uid: Int): Int =
        transact(13) { writeString(caller); writeInt(uid) }?.let { it.readInt() } ?: -1

    fun createCustomVoiceSynthesizer(caller: String, uid: Int, callback: SamsungInitCallback): Int =
        transact(15) {
            writeString(caller)
            writeInt(uid)
            writeStrongInterface(callback)
        }?.let { it.readInt() } ?: -1

    fun getAvailableSynthesizerCount(caller: String, uid: Int): Int =
        transact(14) { writeString(caller); writeInt(uid) }?.let { it.readInt() } ?: 0

    private fun voices(code: Int, caller: String, uid: Int, language: String?): List<SamsungVoice> =
        transact(code) {
            writeString(caller)
            writeInt(uid)
            if (language != null) writeString(language)
        }?.let { reply ->
            reply.createTypedArrayList(SamsungVoice.CREATOR).orEmpty()
        }.orEmpty()

    private inline fun transact(code: Int, write: Parcel.() -> Unit): Parcel? = try {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.write()
            if (!remote.transact(code, data, reply, 0)) {
                reply.recycle()
                null
            } else {
                // The service writes its exception, if any, before the result.
                reply.readException()
                reply
            }
        } finally {
            data.recycle()
        }
    } catch (error: RemoteException) {
        null
    }

    companion object {
        const val DESCRIPTOR = "com.samsung.android.sivs.ai.sdkcommon.tts.ITextToSpeechService"
    }
}

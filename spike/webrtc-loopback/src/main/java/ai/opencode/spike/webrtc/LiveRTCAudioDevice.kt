package ai.opencode.spike.webrtc

import android.content.Context
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ConcurrentSkipListSet
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import org.webrtc.AudioTrack
import org.webrtc.AudioTrackSink
import org.webrtc.audio.JavaAudioDeviceModule

/**
 * iOS's LiveRTCAudioDevice on LiveKit's libwebrtc build: the app's engine owns the
 * microphone and speaker, and this only moves PCM16 24 kHz mono in and out of WebRTC.
 *
 * Capture: the ADM never opens an AudioRecord (`setAudioRecordEnabled(false)`). Its
 * record thread asks for every 10 ms frame through `AudioBufferCallback`, which blocks
 * until the app has delivered that much, so the app's capture clock paces the uplink.
 *
 * Playout: the ADM's own AudioTrack writes silence (`setSpeakerMute(true)`) and only
 * paces the pull. Decoded speech reaches the app through the remote track's
 * `AudioTrackSink` in 10 ms frames and is handed on in 40 ms chunks.
 */
class LiveRTCAudioDevice(context: Context) {
    companion object {
        const val sampleRate = 24_000
        const val pullFrames = sampleRate / 100
        const val chunkFrames = pullFrames * 4
        private val stallLimitNanos = TimeUnit.MILLISECONDS.toNanos(250)
    }

    class Telemetry {
        @Volatile var framesToWebRTC = 0
        @Volatile var stalls = 0
        @Volatile var stallsAfterFirstDelivery = 0
        @Volatile var delivered = 0
        @Volatile var recordStartedAt = 0L
        @Volatile var firstDeliveryAt = 0L
        @Volatile var droppedSamples = 0
        @Volatile var recordStarts = 0
        val recordErrors: MutableList<String> = Collections.synchronizedList(mutableListOf())
        @Volatile var pulls = 0
        val pullFrameSizes = ConcurrentSkipListSet<Int>()
        val pullFrameMillis = ConcurrentSkipListSet<Int>()
        val pullFormats = ConcurrentSkipListSet<String>()
        val pullTimesNanos = ConcurrentLinkedQueue<Long>()
        @Volatile var chunks = 0
        @Volatile var ownTrackBuffers = 0
        @Volatile var ownTrackPeak = 0
    }

    val telemetry = Telemetry()

    @Volatile var onPlayout: ((ByteArray) -> Unit)? = null

    private val lock = ReentrantLock()
    private val arrived = lock.newCondition()
    private val ring = ByteArray(sampleRate * 2)
    private var readAt = 0
    private var buffered = 0
    private var recording = false
    private var shutDown = false
    private val chunk = ByteArrayOutputStream(chunkFrames * 2)

    val module: JavaAudioDeviceModule = JavaAudioDeviceModule.builder(context)
        .setInputSampleRate(sampleRate)
        .setOutputSampleRate(sampleRate)
        .setUseHardwareAcousticEchoCanceler(false)
        .setUseHardwareNoiseSuppressor(false)
        .setEnableVolumeLogger(false)
        .setAudioRecordStateCallback(object : JavaAudioDeviceModule.AudioRecordStateCallback {
            override fun onWebRtcAudioRecordStart() {
                telemetry.recordStarts += 1
                telemetry.recordStartedAt = System.nanoTime()
                lock.withLock { recording = !shutDown }
            }

            override fun onWebRtcAudioRecordStop() {
                lock.withLock { recording = false }
            }
        })
        .setAudioRecordErrorCallback(object : JavaAudioDeviceModule.AudioRecordErrorCallback {
            override fun onWebRtcAudioRecordInitError(errorMessage: String) {
                telemetry.recordErrors += "init: $errorMessage"
            }

            override fun onWebRtcAudioRecordStartError(
                errorCode: JavaAudioDeviceModule.AudioRecordStartErrorCode,
                errorMessage: String,
            ) {
                telemetry.recordErrors += "start: $errorMessage"
            }

            override fun onWebRtcAudioRecordError(errorMessage: String) {
                telemetry.recordErrors += errorMessage
            }
        })
        .setAudioBufferCallback { buffer, _, _, _, _, captureTimeNs ->
            takeFrame(buffer)
            captureTimeNs
        }
        .setPlaybackSamplesReadyCallback { samples ->
            telemetry.ownTrackBuffers += 1
            val data = samples.data
            var peak = telemetry.ownTrackPeak
            for (index in 0 until data.size / 2) {
                val sample = (data[index * 2].toInt() and 0xFF) or (data[index * 2 + 1].toInt() shl 8)
                peak = maxOf(peak, kotlin.math.abs(sample.toShort().toInt()))
            }
            telemetry.ownTrackPeak = peak
        }
        .createAudioDeviceModule()
        .also {
            it.setAudioRecordEnabled(false)
            it.setSpeakerMute(true)
        }

    private val halfRate = HalfRate()

    /** The sink hears the mixer's rate (48 kHz for Opus), not the ADM's 24 kHz. */
    private val sink = AudioTrackSink { data, bitsPerSample, rate, channels, frames, _ ->
        telemetry.pulls += 1
        telemetry.pullFrameSizes += frames
        telemetry.pullFrameMillis += frames * 1_000 / rate
        telemetry.pullFormats += "$bitsPerSample-bit $rate Hz ${channels}ch"
        telemetry.pullTimesNanos += System.nanoTime()
        val input = ShortArray(frames)
        data.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(input)
        val pcm = when (rate) {
            sampleRate -> input
            sampleRate * 2 -> halfRate.process(input)
            else -> error("unexpected playout rate $rate")
        }
        for (sample in pcm) {
            chunk.write(sample.toInt() and 0xFF)
            chunk.write(sample.toInt() shr 8 and 0xFF)
        }
        if (chunk.size() >= chunkFrames * 2) {
            telemetry.chunks += 1
            onPlayout?.invoke(chunk.toByteArray())
            chunk.reset()
        }
    }

    /** PCM16 mono 24 kHz from the app's capture, in any chunk size. */
    fun deliver(pcm: ByteArray) = lock.withLock {
        if (!recording || shutDown) {
            telemetry.droppedSamples += pcm.size / 2
            return@withLock
        }
        if (telemetry.delivered == 0) telemetry.firstDeliveryAt = System.nanoTime()
        telemetry.delivered += 1
        write(pcm)
        arrived.signalAll()
    }

    fun play(remote: AudioTrack) = remote.addSink(sink)

    fun shutDown() = lock.withLock {
        shutDown = true
        recording = false
        onPlayout = null
        arrived.signalAll()
    }

    private fun takeFrame(buffer: ByteBuffer) = lock.withLock {
        val need = buffer.capacity()
        var left = stallLimitNanos
        while (buffered < need && !shutDown && left > 0) left = arrived.awaitNanos(left)
        buffer.clear()
        if (buffered < need) {
            telemetry.stalls += 1
            if (telemetry.delivered > 0) telemetry.stallsAfterFirstDelivery += 1
            buffer.put(ByteArray(need))
            return@withLock
        }
        var remaining = need
        while (remaining > 0) {
            val run = minOf(remaining, ring.size - readAt)
            buffer.put(ring, readAt, run)
            readAt = (readAt + run) % ring.size
            remaining -= run
        }
        buffered -= need
        telemetry.framesToWebRTC += 1
    }

    private fun write(pcm: ByteArray) {
        val overflow = buffered + pcm.size - ring.size
        if (overflow > 0) {
            readAt = (readAt + overflow) % ring.size
            buffered -= overflow
            telemetry.droppedSamples += overflow / 2
        }
        var writeAt = (readAt + buffered) % ring.size
        var offset = 0
        while (offset < pcm.size) {
            val run = minOf(pcm.size - offset, ring.size - writeAt)
            System.arraycopy(pcm, offset, ring, writeAt, run)
            writeAt = (writeAt + run) % ring.size
            offset += run
        }
        buffered += pcm.size
    }
}

/** 48 kHz to 24 kHz: a 63-tap Blackman-windowed sinc low-pass at 11 kHz, keeping every other sample. */
private class HalfRate {
    private val taps = DoubleArray(63) { index ->
        val center = index - 31
        val cutoff = 11_000.0 / 48_000.0
        val sinc = if (center == 0) 2 * cutoff else kotlin.math.sin(2 * Math.PI * cutoff * center) / (Math.PI * center)
        val window = 0.42 - 0.5 * kotlin.math.cos(2 * Math.PI * index / 62) + 0.08 * kotlin.math.cos(4 * Math.PI * index / 62)
        sinc * window
    }.let { taps -> taps.map { it / taps.sum() }.toDoubleArray() }
    private var history = ShortArray(taps.size - 1)

    fun process(input: ShortArray): ShortArray {
        val signal = history + input
        val output = ShortArray(input.size / 2) { index ->
            val end = history.size + index * 2 + 1
            var sum = 0.0
            for (tap in taps.indices) sum += taps[tap] * signal[end - tap]
            sum.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        history = signal.copyOfRange(signal.size - history.size, signal.size)
        return output
    }
}

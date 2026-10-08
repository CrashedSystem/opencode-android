package ai.opencode.spike.webrtc

import android.content.Context
import android.media.AudioManager
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RTCStatsReport
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription

/**
 * Two in-process peer connections set up the way iOS's VoiceRTCSession sets up its
 * one: no ICE servers, gather once, ICE-TCP on, WARP field trials, a pre-negotiated
 * event channel with id 4, and an audio track with WebRTC's processing off. The
 * caller's audio is an app-supplied 440 Hz tone; the callee's decoded audio is
 * checked for it.
 */
class Loopback(
    private val context: Context,
    private val tcpOnly: Boolean,
    private val processing: Boolean,
    private val seconds: Int,
    private val captureChunkMillis: Int,
    private val verbose: Boolean,
    private val log: (String) -> Unit,
) {
    companion object {
        const val dataChannelID = 4
        const val fieldTrials = "WebRTC-ForceDtls13/Enabled/WebRTC-Sctp-Snap/Enabled/WebRTC-IceHandshakeDtls/Enabled/"
        const val toneHz = 440.0
        const val toneAmplitude = 0.25
        const val silentFrames = LiveRTCAudioDevice.sampleRate
        private const val rate = LiveRTCAudioDevice.sampleRate
    }

    private class Heard(val atNanos: Long, val share: Double, val dbfs: Double)

    private val captureChunkFrames = rate * captureChunkMillis / 1_000
    private val appTrack = android.media.AudioTrack.Builder()
        .setAudioAttributes(
            android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        .setAudioFormat(
            android.media.AudioFormat.Builder()
                .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(rate)
                .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO)
                .build(),
        )
        .setBufferSizeInBytes(rate * 2 / 5)
        .build()
    @Volatile private var appTrackWritten = 0
    @Volatile private var appTrackShort = 0

    private val heard = ConcurrentLinkedQueue<Heard>()
    @Volatile private var toneDeliveredAt = 0L
    @Volatile private var callerDevice: LiveRTCAudioDevice? = null
    @Volatile private var generating = true

    fun run(): Boolean {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setFieldTrials(fieldTrials)
                .createInitializationOptions(),
        )
        if (verbose) org.webrtc.Logging.enableLogToDebugOutput(org.webrtc.Logging.Severity.LS_INFO)
        val caller = Peer("caller", sends = true, networkMonitor = true)
        val callee = Peer("callee", sends = false, networkMonitor = false)
        appTrack.setVolume(0f)
        appTrack.play()
        callee.device.onPlayout = { pcm ->
            heard += measure(pcm)
            val written = appTrack.write(pcm, 0, pcm.size, android.media.AudioTrack.WRITE_NON_BLOCKING)
            appTrackWritten += maxOf(0, written) / 2
            if (written < pcm.size) appTrackShort += 1
        }
        try {
            val offer = caller.offer()
            val answer = callee.answer(if (tcpOnly) withoutUdp(offer) else offer)
            caller.accept(if (tcpOnly) withoutUdp(answer) else answer)
            caller.channelOpen.get(10, TimeUnit.SECONDS)
            callee.channelOpen.get(10, TimeUnit.SECONDS)
            log("event channel open: caller id ${caller.channel.id()} '${caller.channel.label()}', callee id ${callee.channel.id()}")

            val generator = Thread(::generate, "tone").apply { start() }
            val sentAt = System.nanoTime()
            caller.send("""{"type":"session.update","session":{"instructions":"loopback"}}""")
            val toCallee = callee.messages.poll(5, TimeUnit.SECONDS)
            val toCaller = caller.messages.poll(5, TimeUnit.SECONDS)
            val roundTrip = (System.nanoTime() - sentAt) / 1e6

            Thread.sleep(seconds * 1_000L)
            val recordings = context.getSystemService(AudioManager::class.java).activeRecordingConfigurations.size
            val callerStats = caller.stats()
            val calleeStats = callee.stats()
            val stallsWhileFed = caller.device.telemetry.stallsAfterFirstDelivery
            generating = false
            generator.join()

            return report(
                offer = offer,
                answer = answer,
                caller = caller,
                callee = callee,
                toCallee = toCallee,
                toCaller = toCaller,
                roundTripMillis = roundTrip,
                recordings = recordings,
                callerStats = callerStats,
                calleeStats = calleeStats,
                stallsWhileFed = stallsWhileFed,
            )
        } finally {
            generating = false
            caller.close()
            callee.close()
            appTrack.release()
        }
    }

    /** The app's capture: chunks of `captureChunkMillis` at real-time pace, a second of silence, then the tone. */
    private fun generate() {
        val start = System.nanoTime()
        var produced = 0
        while (generating) {
            val pcm = ByteArray(captureChunkFrames * 2)
            for (index in 0 until captureChunkFrames) {
                val frame = produced + index
                val value = if (frame < silentFrames) 0.0 else toneAmplitude * sin(2 * PI * toneHz * frame / rate)
                val sample = (value * Short.MAX_VALUE).roundToInt()
                pcm[index * 2] = sample.toByte()
                pcm[index * 2 + 1] = (sample shr 8).toByte()
            }
            if (toneDeliveredAt == 0L && produced + captureChunkFrames > silentFrames) toneDeliveredAt = System.nanoTime()
            callerDevice?.deliver(pcm)
            produced += captureChunkFrames
            val due = start + produced * 1_000_000_000L / rate
            val wait = due - System.nanoTime()
            if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
        }
    }

    /** 440 Hz share of the chunk's energy (Goertzel at exactly 440 Hz) and its level. */
    private fun measure(pcm: ByteArray): Heard {
        val n = pcm.size / 2
        val coefficient = 2 * cos(2 * PI * toneHz / rate)
        var s1 = 0.0
        var s2 = 0.0
        var energy = 0.0
        for (index in 0 until n) {
            val x = ((pcm[index * 2].toInt() and 0xFF) or (pcm[index * 2 + 1].toInt() shl 8)).toShort() / 32768.0
            energy += x * x
            val s0 = x + coefficient * s1 - s2
            s2 = s1
            s1 = s0
        }
        val power = s1 * s1 + s2 * s2 - coefficient * s1 * s2
        val share = if (energy > 0) 2 * power / (n * energy) else 0.0
        return Heard(System.nanoTime(), share, 10 * log10(energy / n + 1e-12))
    }

    private fun report(
        offer: String,
        answer: String,
        caller: Peer,
        callee: Peer,
        toCallee: String?,
        toCaller: String?,
        roundTripMillis: Double,
        recordings: Int,
        callerStats: RTCStatsReport,
        calleeStats: RTCStatsReport,
        stallsWhileFed: Int,
    ): Boolean {
        val sentDbfs = 20 * log10(toneAmplitude / sqrt(2.0))
        val chunks = heard.toList()
        val first = chunks.firstOrNull { it.share > 0.5 && it.dbfs > -40 }
        val steady = first?.let { arrival -> chunks.filter { it.atNanos > arrival.atNanos + 1_000_000_000L } }.orEmpty()
        val share = steady.map { it.share }.average()
        val level = steady.map { it.dbfs }.average()
        val levelSpread = (steady.maxOfOrNull { it.dbfs } ?: 0.0) - (steady.minOfOrNull { it.dbfs } ?: 0.0)
        val latency = first?.let { (it.atNanos - toneDeliveredAt) / 1e6 }

        val pull = callee.device.telemetry
        val pullTimes = pull.pullTimesNanos.toList()
        val window = pullTimes.filter { it > (pullTimes.lastOrNull() ?: 0L) - 3_000_000_000L }
        val pullRate = if (window.size < 2) 0.0 else (window.size - 1) / ((window.last() - window.first()) / 1e9)
        val capture = caller.device.telemetry

        val link = LinkSample.from(calleeStats)
        val callerLink = LinkSample.from(callerStats)
        val transport = calleeStats.statsMap.values.firstOrNull { it.type == "transport" }?.members.orEmpty()
        val inbound = calleeStats.statsMap.values.firstOrNull { it.type == "inbound-rtp" && it.members["kind"] == "audio" }?.members.orEmpty()
        val jitterBufferMillis = inbound["jitterBufferDelay"].let { it as? Number }?.toDouble()?.let { delay ->
            (inbound["jitterBufferEmittedCount"] as? Number)?.toDouble()?.takeIf { it > 0 }?.let { delay / it * 1_000 }
        }
        val wholePullRate = if (pullTimes.size < 2) 0.0 else (pullTimes.size - 1) / ((pullTimes.last() - pullTimes.first()) / 1e9)

        val toneOk = steady.isNotEmpty() && share > 0.9 && abs(level - sentDbfs) < 3
        val pullOk = pull.pullFrameMillis == setOf(10) && pullRate in 95.0..105.0 && pull.chunks > 0
        val captureOk = capture.framesToWebRTC > 0 && stallsWhileFed == 0 && capture.recordErrors.isEmpty() && recordings == 0
        val channelOk = caller.channel.id() == dataChannelID && callee.channel.id() == dataChannelID &&
            toCallee?.contains("session.update") == true && toCaller?.contains("session.started") == true
        val pathOk = link.path == (if (tcpOnly) "tcp" else "udp") && callerLink.path == link.path
        val warpOk = offer.contains("a=sctp-init:") && offer.contains("goog-sped-v1") && transport["tlsVersion"] == "FEFC"

        log("mode: ${if (tcpOnly) "ICE-TCP only (UDP candidates stripped)" else "UDP and TCP candidates"}, WebRTC audio processing ${if (processing) "ON" else "off"}")
        log("tone: sent ${"%.1f".format(sentDbfs)} dBFS; first heard ${latency?.let { "%.0f ms".format(it) } ?: "never"} after the app delivered it")
        log("tone steady (${steady.size} × 40 ms chunks): 440 Hz share ${"%.4f".format(share)}, level ${"%.2f".format(level)} dBFS, spread ${"%.2f".format(levelSpread)} dB")
        log("pull: ${pull.pulls} sink frames of ${pull.pullFrameSizes} frames (${pull.pullFrameMillis} ms), ${pull.pullFormats}, ${"%.1f".format(pullRate)}/s over the last 3 s; ${pull.chunks} chunks of 40 ms at 24 kHz handed on")
        log("pull rate over the whole call ${"%.2f".format(wholePullRate)}/s; NetEq: concealedSamples ${inbound["concealedSamples"]} (silent ${inbound["silentConcealedSamples"]}), concealmentEvents ${inbound["concealmentEvents"]}, insertedForDeceleration ${inbound["insertedSamplesForDeceleration"]}, removedForAcceleration ${inbound["removedSamplesForAcceleration"]}, totalSamplesReceived ${inbound["totalSamplesReceived"]}, mean jitter buffer delay ${jitterBufferMillis?.let { "%.0f ms".format(it) }}")
        log("app's own AudioTrack (24 kHz, volume 0): ${appTrackWritten} frames written, head at ${appTrack.playbackHeadPosition}, underruns ${appTrack.underrunCount}, short writes $appTrackShort")
        log("WebRTC's own AudioTrack: ${pull.ownTrackBuffers} buffers written, peak |sample| ${pull.ownTrackPeak} (muted)")
        log("capture: ${capture.delivered} app chunks of $captureChunkMillis ms delivered, ${capture.framesToWebRTC} × 10 ms frames taken from the app FIFO; WebRTC started recording ${"%.0f".format((capture.firstDeliveryAt - capture.recordStartedAt) / 1e6)} ms before the first chunk; stalls ${capture.stalls} (while the app was delivering $stallsWhileFed); ${capture.droppedSamples} samples dropped; record starts ${capture.recordStarts}, record errors ${capture.recordErrors}; active AudioRecord configurations during the call: $recordings")
        log("event channel: caller→callee $toCallee; callee→caller $toCaller; round trip ${"%.1f".format(roundTripMillis)} ms")
        log("offer (phone): sctp-init (SNAP) ${offer.contains("a=sctp-init:")}, goog-sped-v1 (SPED) ${offer.contains("goog-sped-v1")}, candidates udp ${candidates(offer, "udp")} tcp ${candidates(offer, "tcp")}, opus ${offer.contains("opus/48000/2")}")
        log("phone's TCP candidates: ${offer.lines().filter { it.startsWith("a=candidate:") && it.split(" ")[2].equals("tcp", ignoreCase = true) }.map { it.split(" ").let { parts -> parts[4].take(6) + "…:" + parts[5] + " " + parts.getOrElse(parts.indexOf("tcptype") + 1) { "?" } } }}")
        log("answer (service): sctp-init ${answer.contains("a=sctp-init:")}, goog-sped-v1 ${answer.contains("goog-sped-v1")}, candidates udp ${candidates(answer, "udp")} tcp ${candidates(answer, "tcp")}")
        log("transport: tlsVersion ${transport["tlsVersion"]} dtlsCipher ${transport["dtlsCipher"]} srtpCipher ${transport["srtpCipher"]} dtlsState ${transport["dtlsState"]}")
        log("phone's selected pair: ${selectedPair(callerStats)}")
        log("callee link: $link")
        log("caller link: $callerLink")
        log("checks: tone $toneOk, pull $pullOk, capture $captureOk, channel $channelOk, path $pathOk, warp $warpOk")
        val pass = toneOk && pullOk && captureOk && channelOk && pathOk && warpOk
        log(if (pass) "PASS" else "FAIL")
        return pass
    }

    private fun selectedPair(report: RTCStatsReport): String {
        val stats = report.statsMap
        val pairID = stats.values.firstOrNull { it.type == "transport" }?.members?.get("selectedCandidatePairId") as? String
        val pair = stats[pairID]?.members ?: return "none"
        fun describe(id: Any?) = stats[id as? String]?.members?.let {
            "${it["candidateType"]} ${it["protocol"]}${it["tcpType"]?.let { type -> "/$type" } ?: ""} ${(it["address"] as? String)?.take(7)}…:${it["port"]}"
        }
        return "local ${describe(pair["localCandidateId"])} → remote ${describe(pair["remoteCandidateId"])}"
    }

    private fun candidates(sdp: String, transport: String) =
        sdp.lines().count { it.startsWith("a=candidate:") && it.split(" ")[2].equals(transport, ignoreCase = true) }

    private fun withoutUdp(sdp: String) =
        sdp.split("\r\n").filterNot { it.startsWith("a=candidate:") && it.split(" ")[2].equals("udp", ignoreCase = true) }
            .joinToString("\r\n")

    /**
     * The callee plays the voice service: without Android's network monitor its TCP
     * socket binds to the interface's IP, so it can offer a passive TCP candidate the
     * way the service offers one on 443. The caller is the phone.
     */
    private inner class Peer(val name: String, sends: Boolean, networkMonitor: Boolean) : PeerConnection.Observer {
        val device = LiveRTCAudioDevice(context)
        private val factory = PeerConnectionFactory.builder()
            .setOptions(
                PeerConnectionFactory.Options().apply {
                    networkIgnoreMask = PeerConnectionFactory.Options.ADAPTER_TYPE_LOOPBACK
                    disableNetworkMonitor = !networkMonitor
                },
            )
            .setAudioDeviceModule(device.module)
            .createPeerConnectionFactory()
        private val gathered = CompletableFuture<Unit>()
        val channelOpen = CompletableFuture<Unit>()
        val messages = LinkedBlockingQueue<String>()
        val connection: PeerConnection
        val channel: DataChannel
        private val source = factory.createAudioSource(
            MediaConstraints().apply {
                if (!processing) {
                    for (key in listOf("googEchoCancellation", "googAutoGainControl", "googNoiseSuppression", "googHighpassFilter")) {
                        mandatory += MediaConstraints.KeyValuePair(key, "false")
                    }
                }
            },
        )
        private val track = factory.createAudioTrack("microphone", source)

        init {
            val config = PeerConnection.RTCConfiguration(emptyList()).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
                rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
                continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
                tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
            }
            connection = factory.createPeerConnection(config, this)!!
            if (sends) {
                connection.addTrack(track, listOf("microphone"))
                callerDevice = device
            }
            channel = connection.createDataChannel(
                "oai-events",
                DataChannel.Init().apply {
                    negotiated = true
                    id = dataChannelID
                    ordered = true
                },
            )
            channel.registerObserver(object : DataChannel.Observer {
                override fun onBufferedAmountChange(previousAmount: Long) {}

                override fun onStateChange() {
                    if (channel.state() == DataChannel.State.OPEN) channelOpen.complete(Unit)
                }

                override fun onMessage(buffer: DataChannel.Buffer) {
                    val bytes = ByteArray(buffer.data.remaining())
                    buffer.data.get(bytes)
                    val text = String(bytes)
                    messages += text
                    if (text.contains("session.update")) send("""{"type":"session.started"}""")
                }
            })
        }

        fun send(json: String) {
            check(channel.send(DataChannel.Buffer(ByteBuffer.wrap(json.toByteArray()), false)))
        }

        fun offer(): String {
            val offer = sdp { connection.createOffer(it, MediaConstraints().apply { mandatory += MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true") }) }!!
            sdp { connection.setLocalDescription(it, offer) }
            gathered.get(5, TimeUnit.SECONDS)
            return connection.localDescription.description
        }

        fun answer(offer: String): String {
            sdp { connection.setRemoteDescription(it, SessionDescription(SessionDescription.Type.OFFER, offer)) }
            val answer = sdp { connection.createAnswer(it, MediaConstraints()) }!!
            sdp { connection.setLocalDescription(it, answer) }
            gathered.get(5, TimeUnit.SECONDS)
            return connection.localDescription.description
        }

        fun accept(answer: String) {
            sdp { connection.setRemoteDescription(it, SessionDescription(SessionDescription.Type.ANSWER, answer)) }
        }

        fun stats(): RTCStatsReport {
            val report = CompletableFuture<RTCStatsReport>()
            connection.getStats { report.complete(it) }
            return report.get(5, TimeUnit.SECONDS)
        }

        fun close() {
            device.shutDown()
            runCatching { channel.unregisterObserver() }
            runCatching { channel.close() }
            runCatching { channel.dispose() }
            runCatching { connection.dispose() }
            runCatching { track.dispose() }
            runCatching { source.dispose() }
            runCatching { factory.dispose() }
            runCatching { device.module.release() }
        }

        private fun sdp(start: (SdpObserver) -> Unit): SessionDescription? {
            val done = CompletableFuture<SessionDescription?>()
            start(object : SdpObserver {
                override fun onCreateSuccess(description: SessionDescription) {
                    done.complete(description)
                }

                override fun onSetSuccess() {
                    done.complete(null)
                }

                override fun onCreateFailure(error: String) {
                    done.completeExceptionally(IllegalStateException("$name create: $error"))
                }

                override fun onSetFailure(error: String) {
                    done.completeExceptionally(IllegalStateException("$name set: $error"))
                }
            })
            return done.get(5, TimeUnit.SECONDS)
        }

        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
            if (state == PeerConnection.IceGatheringState.COMPLETE) gathered.complete(Unit)
        }

        override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
            log("$name connection $state")
        }

        override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {
            (receiver.track() as? AudioTrack)?.let { device.play(it) }
        }

        override fun onSignalingChange(state: PeerConnection.SignalingState) {}
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {}
        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onIceCandidate(candidate: IceCandidate) {}
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
        override fun onAddStream(stream: MediaStream) {}
        override fun onRemoveStream(stream: MediaStream) {}
        override fun onDataChannel(channel: DataChannel) {}
        override fun onRenegotiationNeeded() {}
    }
}

/** iOS's LiveRTCLinkSample, read from the same standard stats. */
data class LinkSample(
    val path: String? = null,
    val roundTrip: Double? = null,
    val packetsReceived: Double = 0.0,
    val packetsLost: Double = 0.0,
    val samplesReceived: Double = 0.0,
    val samplesConcealed: Double = 0.0,
    val jitter: Double? = null,
    val outboundLoss: Double? = null,
) {
    companion object {
        fun from(report: RTCStatsReport): LinkSample {
            val stats = report.statsMap
            fun number(values: Map<String, Any>, key: String) = (values[key] as? Number)?.toDouble()
            val selectedPair = stats.values.lastOrNull { it.type == "transport" }?.members?.get("selectedCandidatePairId") as? String
            var sample = LinkSample()
            for (stat in stats.values) {
                val values = stat.members
                sample = when {
                    stat.type == "inbound-rtp" && values["kind"] == "audio" -> sample.copy(
                        packetsReceived = number(values, "packetsReceived") ?: 0.0,
                        packetsLost = maxOf(0.0, number(values, "packetsLost") ?: 0.0),
                        samplesReceived = number(values, "totalSamplesReceived") ?: 0.0,
                        samplesConcealed = number(values, "concealedSamples") ?: 0.0,
                        jitter = number(values, "jitter"),
                    )
                    stat.type == "remote-inbound-rtp" -> sample.copy(
                        outboundLoss = number(values, "fractionLost"),
                        roundTrip = sample.roundTrip ?: number(values, "roundTripTime"),
                    )
                    stat.type == "candidate-pair" && stat.id == selectedPair -> sample.copy(
                        roundTrip = number(values, "currentRoundTripTime") ?: sample.roundTrip,
                        path = (values["localCandidateId"] as? String)?.let { stats[it]?.members?.get("protocol") as? String },
                    )
                    else -> sample
                }
            }
            return sample
        }
    }
}

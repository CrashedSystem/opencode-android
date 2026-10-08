@file:PortedFrom("OpenCodeTests/AgentRevealAndOrbModelTests.swift", "f843672")

package ai.opencode.mobilecode

import ai.opencode.mobilecode.tca.expect
import java.util.UUID
import kotlin.math.abs
import kotlin.math.sign
import org.junit.Test

class AgentRevealAndOrbModelTests {
    // MARK: Inner glow

    @Test
    fun onlyAgentsMessagesReveal() {
        fun message(text: String, source: OpenCodePromptSource? = null): TranscriptUserMessage =
            TranscriptUserMessage(id = "msg", text = text, images = emptyList(), files = emptyList(), pending = null, source = source)
        val voice = VoiceHandoff.format(instruction = "check the build", transcriptDelta = listOf("user: check the build"))
        expect(message(voice).isFromAgent)
        expect(message("Rerun the tests", source = OpenCodePromptSource.session(id = "ses_other")).isFromAgent)
        expect(message("Audit the spacing", source = OpenCodePromptSource.parentAgent).isFromAgent)
        expect(!message("Typed or dictated on this phone").isFromAgent)

        val entry = TranscriptEntry.userGroup(
            items = listOf(OpenCodeTranscriptItem.userText(id = "msg_forwarded", text = "Rerun the tests", source = OpenCodePromptSource.session(id = "ses_other"))),
            startsTurn = true,
            rawID = "msg_forwarded",
        )
        expect(TranscriptUserMessage(entry)?.isFromAgent == true)
    }

    @Test
    fun revealRisesDecaysAndEndsOnThePlainBubble() {
        val peak = AgentMessageReveal.frame(at = AgentMessageReveal.rise)
        expect(peak.light > 0.99)
        expect(AgentMessageReveal.frame(at = 0.0).body == 0.0)
        expect(AgentMessageReveal.frame(at = 0.4).light < 0.5)
        expect(abs(AgentMessageReveal.duration - 1.39) < 0.02)
        expect(AgentMessageReveal.frame(at = AgentMessageReveal.duration) == AgentMessageReveal.Frame.rest)
        // The light eases to exactly nothing before the reveal ends.
        expect(AgentMessageReveal.frame(at = AgentMessageReveal.duration - 0.001).light < 0.001)
    }

    @Test
    fun reduceMotionRevealOnlyFades() {
        var t = 0.0
        while (t < AgentMessageReveal.duration) {
            val frame = AgentMessageReveal.frame(at = t, reduceMotion = true)
            expect(frame.light == 0.0)
            expect(frame.body == AgentMessageReveal.frame(at = t).body)
            t += 0.05
        }
    }

    @Test
    fun arrivalsAreRecordedOnce() {
        val reveal = TranscriptAgentMessageReveal()
        expect(reveal.arrive("msg", at = 10))
        expect(!reveal.arrive("msg", at = 20))
        expect(reveal.arrival(of = "msg") == 10L)
        expect(reveal.arrival(of = "other") == null)
    }

    // MARK: Status orb

    @Test
    fun finalStatesSettleAndStop() {
        for (activity in listOf(AgentActivity.done, AgentActivity.failed)) {
            val gyro = ActivityGyro(AgentActivity.thinking)
            gyro.set(activity)
            for (i in 0 until 600) {
                if (gyro.isStatic) break
                gyro.step(1.0 / 60)
            }
            expect(gyro.isStatic, "$activity comes to rest")
        }
    }

    @Test
    fun movingStatesKeepMovingAndReduceMotionHoldsStill() {
        for (activity in AgentActivity.entries.filter { !it.isFinal }) {
            val moving = ActivityGyro(activity)
            repeat(240) { moving.step(1.0 / 60) }
            expect(!moving.isStatic, "$activity keeps moving")

            val still = ActivityGyro(AgentActivity.idle, reduceMotion = true)
            still.set(activity)
            for (i in 0 until 240) {
                if (still.isStatic) break
                still.step(1.0 / 60)
            }
            expect(still.isStatic, "$activity holds a still pose under Reduce Motion")
        }
    }

    @Test
    fun turningBandNeverDoublesBack() {
        // Thinking's band spins; entering Waiting, the chase must keep turning the same way.
        val gyro = ActivityGyro(AgentActivity.thinking)
        repeat(60) { gyro.step(1.0 / 60) }
        val direction = sign(gyro.strokes[0][GyroAngleKey.theta].vel)
        gyro.set(AgentActivity.waiting)
        var last = gyro.strokes[0][GyroAngleKey.theta].value
        repeat(120) {
            gyro.step(1.0 / 60)
            val value = gyro.strokes[0][GyroAngleKey.theta].value
            if (direction > 0) expect(value >= last - 1e-6) else expect(value <= last + 1e-6)
            last = value
        }
    }

    @Test
    fun eachStateDrawsItsAccent() {
        val colored = mapOf(
            AgentActivity.waiting to ActivityAccent.orange,
            AgentActivity.failed to ActivityAccent.red,
            AgentActivity.done to ActivityAccent.green,
            AgentActivity.handoff to ActivityAccent.blue,
        )
        for (activity in AgentActivity.entries) {
            val gyro = ActivityGyro(activity)
            val field = gyro.fill(GyroCell.seven)
            expect(field.dots.size == GyroCell.seven.size)
            for (accent in ActivityAccent.entries) {
                val weight = field.weights[accent] ?: 0.0
                expect(abs(weight - if (colored[activity] == accent) 1.0 else 0.0) < 0.01, "$activity $accent")
            }
        }
    }

    @Test
    fun subagentStatesComeFromAppStateAndTheLedger() {
        val now = AgentActivityLedger.now()
        expect(SubagentActivity(sessionID = "s", phase = SubagentActivity.Phase.failed).activity(at = now) == AgentActivity.failed)
        expect(SubagentActivity(sessionID = "s", phase = SubagentActivity.Phase.done).activity(at = now) == AgentActivity.done)
        expect(SubagentActivity(sessionID = null, phase = SubagentActivity.Phase.spawning).activity(at = now) == AgentActivity.handoff)
        expect(SubagentActivity(sessionID = "s", phase = SubagentActivity.Phase.running, needsInput = true).activity(at = now) == AgentActivity.waiting)

        val ledger = AgentActivityLedger.shared
        val id = "ses_orb_${UUID.randomUUID()}"
        val subagent = SubagentActivity(sessionID = id, phase = SubagentActivity.Phase.running)
        ledger.record(OpenCodeServerEvent.executionStarted(sessionID = id), at = now)
        expect(subagent.activity(at = now + 0.5) == AgentActivity.startup)
        expect(subagent.activity(at = now + 2) == AgentActivity.thinking)
        ledger.record(OpenCodeServerEvent.contentBatch(sessionID = id, events = listOf(OpenCodeLiveContentEvent.toolCalled(messageID = "m", toolID = "t", input = OpenCodeJSONValue.`null`))), at = now + 3)
        expect(subagent.activity(at = now + 3.5) == AgentActivity.tool)
        expect(subagent.counts.tool == 1)
        ledger.record(OpenCodeServerEvent.contentBatch(sessionID = id, events = listOf(OpenCodeLiveContentEvent.textDelta(messageID = "m", ordinal = 0, delta = "hi"))), at = now + 6)
        expect(subagent.activity(at = now + 6.2) == AgentActivity.streaming)
        expect(subagent.activity(at = now + 15) == AgentActivity.longWork)
    }

    @Test
    fun subagentRunningBeforeTheStreamConnectedStillReachesLongWork() {
        val now = AgentActivityLedger.now()
        val id = "ses_orb_${UUID.randomUUID()}"
        val subagent = SubagentActivity(sessionID = id, phase = SubagentActivity.Phase.running)
        // Its execution started before the stream connected: only content arrives.
        AgentActivityLedger.shared.record(OpenCodeServerEvent.contentBatch(sessionID = id, events = listOf(OpenCodeLiveContentEvent.reasoningDelta(messageID = "m", ordinal = 0, delta = "…"))), at = now)
        expect(subagent.activity(at = now + 1) == AgentActivity.thinking)
        expect(subagent.activity(at = now + 9) == AgentActivity.longWork)
    }
}

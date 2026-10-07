@file:PortedFrom("OpenCodeTests/VoiceHandoffParseTests.swift", "0bda024", partial = "the cases that read voice delegations back for the transcript; the ones that need the voice call (VoiceWorkClient.formatRecentMessages, VoiceInstructions.build) are ignored")

package ai.opencode.mobilecode

import ai.opencode.mobilecode.tca.expect
import org.junit.Ignore
import org.junit.Test

class VoiceHandoffParseTests {
    @Test
    fun readsBackWhatFormatWrote() {
        val prompt = VoiceHandoff.format(instruction = "Check why the upload test is flaky.", transcriptDelta = listOf("user: the upload test", "assistant: On it."))
        expect(
            VoiceHandoff.parse(prompt) == VoiceHandoff.Delegation(
                instruction = "Check why the upload test is flaky.",
                transcriptDelta = listOf("user: the upload test", "assistant: On it."),
            ),
        )
    }

    @Test
    fun roundTripsTheVoiceAgentsFraming() {
        val prompt = VoiceHandoff.format(
            instruction = "um can you check why the upload test fails on staging",
            transcriptDelta = listOf("user: um can you check why the upload test fails on staging", "assistant: Checking the upload test."),
            context = "Session \"Uploads\" in project \"app\".",
            framing = " Checking the upload test. ",
        )
        val delegation = VoiceHandoff.parse(prompt)
        expect(
            delegation == VoiceHandoff.Delegation(
                instruction = "um can you check why the upload test fails on staging",
                transcriptDelta = listOf("user: um can you check why the upload test fails on staging", "assistant: Checking the upload test."),
                context = "Session \"Uploads\" in project \"app\".",
                framing = "Checking the upload test.",
            ),
        )
        expect(delegation?.displayText == "Checking the upload test.")
    }

    @Test
    fun withoutFramingTheSessionShowsTheUsersWords() {
        val prompt = VoiceHandoff.format(instruction = "Open a PR.", transcriptDelta = emptyList(), framing = "  ")
        expect(!prompt.contains("\"voice\""))
        expect(VoiceHandoff.parse(prompt)?.framing == null)
        expect(VoiceHandoff.parse(prompt)?.displayText == "Open a PR.")
    }

    @Test
    fun theHeaderSaysTheRequestIsTheUsersOwnWords() {
        val header = VoiceHandoff.header
        expect(header.contains("Instruction is the user's own spoken words since the previous delegation"))
        expect(header.contains("recognition errors and disfluencies"))
        expect(header.contains("Voice, when present, is how the voice agent framed the request."))
        expect(header.contains("Transcript delta is the surrounding voice conversation, both sides, for context"))
        expect(!header.contains("cleaned interpretation"))
    }

    @Test
    fun aContinuedDelegationIsShortAndStillAVoiceMessage() {
        val prompt = VoiceHandoff.format(
            instruction = "and the retry test",
            transcriptDelta = listOf("user: and the retry test"),
            framing = "Checking the retry test.",
            header = VoiceHandoff.Header.continued,
        )
        expect(prompt.startsWith(VoiceHandoff.continuedHeader + "\n{"))
        expect(!prompt.contains("recognition errors"), "The explanation goes only with the first")
        expect(
            VoiceHandoff.parse(prompt) == VoiceHandoff.Delegation(
                instruction = "and the retry test",
                transcriptDelta = listOf("user: and the retry test"),
                framing = "Checking the retry test.",
            ),
        )
        val message = TranscriptUserMessage(id = "msg_2", text = prompt, images = emptyList(), files = emptyList(), pending = null)
        expect(message.voiceDelegation?.displayText == "Checking the retry test.")
        // VoiceWorkClient.formatRecentMessages waits for voice calls (Voice/VoiceWorkClient.kt).
    }

    @Test
    fun promptsFromBeforeTheFramingStillRender() {
        val prompt = "Voice delegation. Instruction is the cleaned interpretation of the user's request.\n{\n  \"instruction\" : \"Check why the upload test keeps failing on CI.\",\n  \"transcript_delta\" : [\n    \"user: hey can you figure out why the upload test keeps failing\"\n  ]\n}"
        val message = TranscriptUserMessage(id = "msg_old", text = prompt, images = emptyList(), files = emptyList(), pending = null)
        expect(message.voiceDelegation?.framing == null)
        expect(message.voiceDelegation?.displayText == "Check why the upload test keeps failing on CI.")
    }

    @Test
    fun aDelegationProjectsToAVoiceMessageWhetherPendingOrDelivered() {
        val prompt = VoiceHandoff.format(instruction = "check the upload test", transcriptDelta = listOf("user: check the upload test"), framing = "Checking the upload test.")
        val delivered = TranscriptUserMessage(id = "msg_1", text = prompt, images = emptyList(), files = emptyList(), pending = null)
        expect(delivered.voiceDelegation?.displayText == "Checking the upload test.")
        expect(delivered.voiceDelegation?.instruction == "check the upload test")
        val steering = TranscriptUserMessage(queued = OpenCodeQueuedInput(input = OpenCodePendingInput(id = "msg_2", text = prompt, delivery = OpenCodeDelivery.steer)))
        expect(steering?.voiceDelegation?.displayText == "Checking the upload test.")
        expect(steering?.pending == OpenCodeDelivery.steer)
        expect(TranscriptUserMessage(id = "msg_3", text = "Check the upload test", images = emptyList(), files = emptyList(), pending = null).voiceDelegation == null)
    }

    @Ignore("VoiceWorkClient.formatRecentMessages waits for voice calls (Voice/VoiceWorkClient.kt)")
    @Test
    fun recentMessagesGiveTheVoiceAgentTheWordsNotThePrompt() {
    }

    @Test
    fun toleratesARewordedHeaderAndAMissingDelta() {
        val prompt = "Voice delegation. Some newer wording.\n{\"instruction\": \"  Open a PR.  \"}"
        expect(VoiceHandoff.parse(prompt) == VoiceHandoff.Delegation(instruction = "Open a PR.", transcriptDelta = emptyList()))
    }

    @Test
    fun malformedPromptsStayAsTheyAre() {
        expect(VoiceHandoff.parse("Voice delegation. Header only") == null)
        expect(VoiceHandoff.parse("Voice delegation. Header\n{\"instruction\": ") == null)
        expect(VoiceHandoff.parse("Voice delegation. Header\n{\"transcript_delta\": []}") == null)
        expect(VoiceHandoff.parse("Voice delegation. Header\n{\"instruction\": \"   \"}") == null)
    }

    @Test
    fun plainTextAboutVoiceDelegationIsNotOne() {
        expect(VoiceHandoff.parse("How does voice delegation work?\n{\"instruction\": \"x\"}") == null)
        expect(VoiceHandoff.parse("Can you explain the Voice delegation. header format?") == null)
        expect(VoiceHandoff.parse("Voice delegation (continued) is what?\n{\"instruction\": \"x\"}") == null)
    }

    @Ignore("VoiceWorkClient.formatRecentMessages and VoiceInstructions.build wait for voice calls (Voice/VoiceWorkClient.kt, Voice/VoiceTranscript.kt)")
    @Test
    fun recentMessagesExtractUserWordsFromStoredVoiceDelegationPrompts() {
    }
}

class VoiceFramingTests {
    @Test
    fun anAcknowledgementThatNamesTheTaskIsTheFraming() {
        for (ack in listOf(
            "Checking the upload test.", "Running unit tests.", "Checking the README.", "Renaming fetchUser.",
            "Checking the session drawer.", "Check why the upload test fails on staging",
        )) {
            expect(VoiceHandoff.framing(ack) == ack, ack)
        }
    }

    /// What GPT-Live said before it was asked to name the task, from the live timing runs.
    @Test
    fun aVagueAcknowledgementFallsBackToTheUsersWords() {
        for (ack in listOf(
            "On it.", "Sure, I'll take a look.", "Alright, I'll get on it.", "Okay, I'll look into it.",
            "Okay, I'll get right on it.", "I'll take a look.", "Sure, I'll see what's there.", "Checking that now.",
            "Looking that up now.", "Working on that.", "Sure", "  ", "",
        )) {
            expect(VoiceHandoff.framing(ack) == null, ack)
        }
        expect(VoiceHandoff.framing(null) == null)
        expect(VoiceHandoff.framing("Sure, I’ll take a look.") == null, "Curly apostrophes too")
    }
}

class VoiceSteeringInstructionsTests {
    @Ignore("VoiceInstructions.build waits for voice calls (Voice/VoiceTranscript.kt)")
    @Test
    fun runningWorkNeverBlocksAFollowUpDelegation() {
    }

    @Ignore("VoiceInstructions.build waits for voice calls (Voice/VoiceTranscript.kt)")
    @Test
    fun personalMessagesActLikeASecretaryForBothEngines() {
    }

    @Ignore("VoiceInstructions.build waits for voice calls (Voice/VoiceTranscript.kt)")
    @Test
    fun gptLiveAcknowledgesADelegationByNamingTheTask() {
    }

    @Test
    fun theSessionAnswersAFollowUpPromptly() {
        val prompt = VoiceHandoff.format(instruction = "How's it going?", transcriptDelta = emptyList())
        expect(prompt.contains("a clear status update, the finished result if you have enough, or the precise blocker or input you need"))
        expect(VoiceHandoff.parse(prompt)?.instruction == "How's it going?")
    }

    @Ignore("VoiceInstructions.build waits for voice calls (Voice/VoiceTranscript.kt)")
    @Test
    fun modelIdentityNamesTheActiveVoiceEngineAndAvoidsGuessing() {
    }

    @Ignore("VoiceInstructions.build waits for voice calls (Voice/VoiceTranscript.kt)")
    @Test
    fun capabilityDiscoveryDelegatesUnknownCapabilitiesWithoutAskingUserToPaste() {
    }

    @Ignore("VoiceInstructions.build waits for voice calls (Voice/VoiceTranscript.kt)")
    @Test
    fun fragmentsWaitForCompleteUtteranceAndNeverGuess() {
    }

    @Ignore("VoiceInstructions.build waits for voice calls (Voice/VoiceTranscript.kt)")
    @Test
    fun clarificationsStatusChecksRepeatedReadsAndAmbiguousWritesHaveDistinctRules() {
    }
}

package ai.opencode.feature.session

import ai.opencode.core.dependencies.UuidClient
import ai.opencode.core.files.ComposerStagedFile
import ai.opencode.core.sharing.SharedKeys
import ai.opencode.core.sharing.shared
import ai.opencode.feature.composer.ComposerFileAttachment
import ai.opencode.feature.composer.ComposerLineGrowth
import ai.opencode.feature.composer.ComposerTextInputTunables
import ai.opencode.feature.composer.DictationClient
import ai.opencode.feature.composer.DictationFeature
import ai.opencode.feature.session.SessionFeature.Action
import ai.opencode.feature.session.SessionFeature.State
import ai.opencode.feature.session.SessionTestSupport.Harness
import ai.opencode.feature.session.SessionTestSupport.sessionTest
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeDelivery
import ai.opencode.server.model.OpenCodePendingRequests
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.OpenCodeTranscriptItem
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlin.math.abs

/**
 * Port of iOS `SessionFeatureTests+Composer.swift`.
 *
 * Not ported (UIKit-only): `composerTextViewReturnKeyTypeHardwareShortcutsAndPaste` probes
 * `ImagePasteTextView`'s `returnKeyType`, `UIKeyCommand`s and `UITextView` paste handling; the
 * Compose composer's key handling has no such view to host in a JVM test.
 *
 * The line-growth tests drive `ImagePasteTextView` frame by frame; here they drive the same rules
 * through [ComposerLineGrowth], which `ComposerTextInput`'s layout follows (heights in px: one
 * line [LINE] tall under a [INSET] top inset; a hold is iOS `contentOffset.y`).
 * `aComposingLineLandsAtOnceAndASendsClearCommitsTheComposition` (60dddffa) is not ported: its
 * first half (no line hold while an IME composes) is n/a on Android (see [ComposerLineGrowth]),
 * and its second half (a send's clear commits the composition) is `TextFieldState.edit`'s own
 * behaviour, which a JVM test can't drive without the IME's input connection.
 */
class SessionFeatureTestsComposer {
    private val server = OpenCodeServer(url = "http://127.0.0.1:9", username = "opencode", password = "pw")
    private val session = OpenCodeSession(id = "ses_1", serverTitle = "Chat", projectID = "proj_1", directory = "/tmp/demo", updatedAt = Instant.EPOCH)
    private val sentID = FailedSendsSupport.sentID

    private fun Harness.sessionState(): State = state { State.session(server = server, session = session, cachedItems = emptyList()) }

    private fun DependencyBuilder.sendClient() {
        this[UuidClient] = UuidClient.incrementing()
        this[OpenCodeClient] = this[OpenCodeClient].copy(
            sendPrompt = { _, _, _, _, _, _, _ -> },
            waitForIdle = { _, _ -> },
            getSession = { _, _ -> session },
            transcript = { _, _, _ -> emptyList() },
            familyPendingRequests = { _, _, _ -> OpenCodePendingRequests(permissions = emptyList()) },
            pendingInputs = { _, _ -> emptyList() },
        )
    }

    private fun State.sent(text: String): State = copy(
        optimisticPromptIDs = setOf(sentID),
        items = listOf(OpenCodeTranscriptItem.UserText(id = sentID, text = text)),
    ).withDraft("")

    @Test fun returnKeySubmitsInsteadOfInsertingNewline() = sessionTest { h ->
        h.state { shared(SharedKeys.returnKeySends) }.set(true)
        val state = h.sessionState().copy(hasRenderedFirstContentFrame = true).withDraft("Ship it\n")
        val store = h.store(state) { sendClient() }
        store.exhaustive = false

        store.send(Action.ReturnKeyTapped) { it.withDraft("Ship it") }
        store.receive(Action.SendTapped) { it.sent("Ship it") }
    }

    @Test fun returnKeyInsertsNewlineWhenReturnKeySendsIsOffAndSendButtonSubmits() = sessionTest { h ->
        assertFalse("Return inserts a newline without opting into a setting", h.state { shared(SharedKeys.returnKeySends) }.value)
        val state = h.sessionState().copy(hasRenderedFirstContentFrame = true).withDraft("Line one\n")
        val store = h.store(state) { sendClient() }
        store.exhaustive = false

        // With Return Key Sends off, Return leaves the newline in the draft.
        store.send(Action.ReturnKeyTapped)
        assertEquals("Line one\n", store.state.draft)
        assertTrue(store.state.items.isEmpty())

        store.send(Action.Binding(BindingAction.Draft("Line one\nLine two"))) { it.withDraft("Line one\nLine two") }
        store.send(Action.SendTapped) { it.sent("Line one\nLine two") }
    }

    @Test fun commandReturnAlwaysSubmitsRegardlessOfReturnKeySendsSetting() {
        for (sendsOnReturn in listOf(true, false)) commandReturnAlwaysSubmitsRegardlessOfReturnKeySendsSetting(sendsOnReturn)
    }

    private fun commandReturnAlwaysSubmitsRegardlessOfReturnKeySendsSetting(sendsOnReturn: Boolean) = sessionTest { h ->
        h.state { shared(SharedKeys.returnKeySends) }.set(sendsOnReturn)
        val state = h.sessionState().copy(hasRenderedFirstContentFrame = true).withDraft("First line\nSecond line")
        val store = h.store(state) { sendClient() }
        store.exhaustive = false

        store.send(Action.CommandReturnTapped)
        store.receive(Action.SendTapped) { it.sent("First line\nSecond line") }

        // Empty draft: ⌘↩ is a no-op.
        store.send(Action.CommandReturnTapped)
        assertEquals("sendsOnReturn=$sendsOnReturn", 1, store.state.items.size)
    }

    @Test fun commandReturnDoesNothingDuringDictationOrPendingUploads() = sessionTest { h ->
        h.state { shared(SharedKeys.returnKeySends) }.set(true)
        val state = h.sessionState().withDraft("Existing draft")
            .withDictationPhase(DictationFeature.Phase.Recording)
            .withDictationHold(DictationFeature.Hold(text = "Dictated words", isLocked = false))
        val store = h.store(state)

        // ⌘↩ while hold-to-dictate is active does nothing and leaves recording intact.
        store.send(Action.CommandReturnTapped)
        assertEquals(DictationFeature.Phase.Recording, store.state.dictationPhase)
        assertEquals("Dictated words", store.state.dictationHold?.text)
        assertTrue(store.state.items.isEmpty())

        // ⇧↩ during dictation is ignored so it never swallows the next Return after dictation ends.
        store.send(Action.NonSendNewlineInserted("Existing draft\n"))
        assertNull(store.state.nonSendNewlineDraft)

        val afterDictationState = h.sessionState().copy(hasRenderedFirstContentFrame = true).withDraft("Existing draft")
            .withDictationPhase(DictationFeature.Phase.Recording)
            .withDictationHold(DictationFeature.Hold(text = "Dictated words", isLocked = false))
        val afterDictationStore = h.store(afterDictationState) { sendClient() }
        afterDictationStore.exhaustive = false
        afterDictationStore.send(Action.NonSendNewlineInserted("Existing draft\n"))
        afterDictationStore.send(Action.micPressCancelled)
        afterDictationStore.send(Action.Binding(BindingAction.Draft("Existing draft\n")))
        afterDictationStore.send(Action.ReturnKeyTapped)
        afterDictationStore.receive(Action.SendTapped)
        assertEquals(listOf(OpenCodeTranscriptItem.UserText(id = sentID, text = "Existing draft")), afterDictationStore.state.items)

        // ⌘↩ while waiting for uploads or with a failed attachment does nothing.
        val stagedFile = ComposerStagedFile(
            id = UUID(0, 1),
            name = "clip.mov",
            mime = "video/quicktime",
            size = 100,
            url = File("/nonexistent/clip.mov"),
        )
        val uploadState = h.sessionState().withDraft("Ready text")
            .withPendingFiles(listOf(ComposerFileAttachment(stagedFile)))
            .withSendAfterUploads(OpenCodeDelivery.Steer)
        val uploadStore = h.store(uploadState)
        uploadStore.send(Action.CommandReturnTapped)
        assertTrue(uploadStore.state.items.isEmpty())

        val failedAttachment = ComposerFileAttachment(stagedFile).copy(status = ComposerFileAttachment.Status.Failed("Couldn’t upload"))
        val failedFileState = h.sessionState().withDraft("Ready text").withPendingFiles(listOf(failedAttachment))
        val failedFileStore = h.store(failedFileState)
        failedFileStore.send(Action.CommandReturnTapped)
        assertTrue(failedFileStore.state.items.isEmpty())
    }

    /**
     * A typed line grows the field on `ComposerTunables.lineGrowth`, its text held to the field's
     * bottom frame by frame so the caret's line stays put; Reduce Motion takes the new height at once.
     */
    @Test fun aTypedLineRidesTheLineSpringHeldToTheFieldsBottom() {
        val one = INSET + LINE
        val two = INSET + 2 * LINE
        val spring = ComposerLineGrowth.typedLineSpring("One", "One\nTwo", returnKeySends = false, reduceMotion = false)
        assertEquals(ComposerTextInputTunables.lineGrowth, spring)
        // The spring takes the frame through every height in between; the caret is on the new line.
        for (height in listOf(one, (one + two) / 2, two - 2)) {
            val hold = ComposerLineGrowth.holdOffset(two, height, caretLineTop = LINE)
            assertTrue("held to the bottom at $height", abs(hold - (two - height)) < 0.5f)
        }
        assertTrue(abs(ComposerLineGrowth.holdOffset(two, two, caretLineTop = LINE)) < 0.5f)

        // Deleting the line shrinks it the same way.
        assertEquals(ComposerTextInputTunables.lineGrowth, ComposerLineGrowth.typedLineSpring("One\nTwo", "One", returnKeySends = false, reduceMotion = false))
        assertTrue(abs(ComposerLineGrowth.holdOffset(one, two, caretLineTop = 0f) - (one - two)) < 0.5f)

        // Reduce Motion: the new height at once.
        val reduced = ComposerLineGrowth.typedLineSpring("One", "One\nTwo", returnKeySends = false, reduceMotion = true)
        assertNull(reduced)
        // A Return that sends never animates the line it would add.
        assertNull(ComposerLineGrowth.typedLineSpring("One", "One\n", returnKeySends = true, reduceMotion = false))
    }

    /**
     * Growing for a word wrapped off an earlier line keeps the caret's line below the field's top
     * edge: the hold follows the selection, not the bottom.
     */
    @Test fun lineGrowthKeepsAnEarlierCaretLineInView() {
        val two = INSET + 2 * LINE
        val three = INSET + 3 * LINE
        // Typing at the start of the first line wraps its last word; the caret stays on line one.
        val caretLineTop = 0f
        for (height in listOf(two, (two + three) / 2, three - 2)) {
            val hold = ComposerLineGrowth.holdOffset(three, height, caretLineTop)
            // Its line's top no nearer the top edge than its padding.
            assertTrue("caret's line in view at $height", INSET + caretLineTop - hold >= INSET - 0.5f)
        }

        // A caret on the last line still holds to the bottom.
        val four = INSET + 4 * LINE
        val height = (three + four) / 2
        assertTrue(abs(ComposerLineGrowth.holdOffset(four, height, caretLineTop = 3 * LINE) - (four - height)) < 0.5f)
    }

    @Test fun dictationStreamsIntoDraftAfterTypedText() = sessionTest { h ->
        val state = h.sessionState().withDraft("Fix")
        val feed = Channel<String>(Channel.UNLIMITED)
        val store = h.store(state) {
            this[DictationClient] = DictationClient(
                start = { feed },
                // Stopping finalizes: the engine delivers its last transcript, then
                // the stream ends — mirroring finalizeAndFinishThroughEndOfInput.
                stop = {
                    feed.trySend("the spinner ring!")
                    feed.close()
                },
            )
        }
        store.exhaustive = false

        store.send(Action.micTapped) { it.withDictationPhase(DictationFeature.Phase.Preparing) }
        store.receive(Action.dictationReady) { it.withDictationPhase(DictationFeature.Phase.Recording) }
        feed.trySend("the spinner")
        store.receive(Action.dictationUpdated("the spinner")) { it.withDraft("Fix the spinner") }
        feed.trySend("the spinner ring")
        store.receive(Action.dictationUpdated("the spinner ring")) { it.withDraft("Fix the spinner ring") }
        // An intentional stop keeps the stream alive: the finalized transcript
        // (often the only accurate one) still lands in the draft.
        store.send(Action.micTapped) { it.withDictationPhase(DictationFeature.Phase.Finishing(send = null)) }
        store.receive(Action.dictationUpdated("the spinner ring!")) { it.withDraft("Fix the spinner ring!") }
        store.receive(Action.dictationStopped(errorMessage = null)) { it.withDictationPhase(DictationFeature.Phase.Idle) }
        // Late results after teardown must not clobber the draft.
        store.send(Action.dictationUpdated("ghost"))
        assertEquals("Fix the spinner ring!", store.state.draft)
    }

    @Test fun micRetapWhileFinishingIsIgnored() = sessionTest { h ->
        val feed = Channel<String>(Channel.UNLIMITED)
        val store = h.store(h.sessionState()) {
            this[DictationClient] = DictationClient(
                start = { feed },
                // Engine finalizes slowly; the final transcript arrives later.
                stop = {},
            )
        }
        store.exhaustive = false

        store.send(Action.micTapped) { it.withDictationPhase(DictationFeature.Phase.Preparing) }
        store.receive(Action.dictationReady) { it.withDictationPhase(DictationFeature.Phase.Recording) }
        store.send(Action.micTapped) { it.withDictationPhase(DictationFeature.Phase.Finishing(send = null)) }
        // A double-tap on stop must not restart dictation mid-teardown.
        store.send(Action.micTapped)
        assertFalse(store.state.isDictating)

        feed.trySend("final words")
        store.receive(Action.dictationUpdated("final words")) { it.withDraft("final words") }
        feed.close()
        store.receive(Action.dictationStopped(errorMessage = null)) { it.withDictationPhase(DictationFeature.Phase.Idle) }
    }

    /**
     * Send Report? waits out dictation and a call on its bar, then returns:
     * hiding it never touches the pending report.
     */
    @Test fun sendingWhileDictatingStopsTheMic() = sessionTest { h ->
        val state = h.sessionState().copy(hasRenderedFirstContentFrame = true).withDraft("Deploy")
        val feed = Channel<String>(Channel.UNLIMITED)
        val store = h.store(state) {
            sendClient()
            this[DictationClient] = DictationClient(start = { feed }, stop = { feed.close() })
        }
        store.exhaustive = false

        store.send(Action.micTapped) { it.withDictationPhase(DictationFeature.Phase.Preparing) }
        store.send(Action.SendTapped) {
            it.copy(optimisticPromptIDs = setOf(sentID)).withDictationPhase(DictationFeature.Phase.Idle).withDraft("")
        }
        assertEquals(listOf(OpenCodeTranscriptItem.UserText(id = sentID, text = "Deploy")), store.state.items)
    }

    private companion object {
        /** A line of body text, and the expanded field's top inset (px). */
        const val LINE = 22f
        const val INSET = 16f
    }
}

private typealias DependencyBuilder = ai.opencode.core.dependencies.DependencyValues.Builder

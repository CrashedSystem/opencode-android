@file:PortedFrom("OpenCode/WhatsNew/WhatsNewEntries.swift", "7a54834")

package ai.opencode.mobilecode

import ai.opencode.mobilecode.tca.Identifiable
import java.time.LocalDate

/// One change in What’s New. IDs are permanent: a seen ID never shows as new
/// again, so reword an entry freely but give a new feature a new ID.
data class WhatsNewEntry(
    override val id: String,
    /// The day it shipped; the sheet groups entries by it.
    val day: LocalDate,
    /// Title Case, a few words.
    val title: String,
    /// One or two short sentences.
    val summary: String,
    /// A concrete thing to try.
    val example: String,
    val illustration: Illustration,
) : Identifiable<String> {
    enum class Illustration {
        liveActivity,
        captions,
        problemReport,
        camera,
        talkOver,
        callButton,
        catchUp,
        voiceMessage,
    }

    constructor(id: String, day: String, title: String, summary: String, example: String, illustration: Illustration) : this(
        id = id,
        day = day.split("-").mapNotNull { it.toIntOrNull() }.let { parts -> LocalDate.of(parts[0], parts[1], parts[2]) },
        title = title,
        summary = summary,
        example = example,
        illustration = illustration,
    )

    companion object {
        /// Newest first. Add an entry for each user-facing feature you ship.
        val all: List<WhatsNewEntry> =
            listOf(
                WhatsNewEntry(
                    id = "voice-manages-sessions",
                    day = "2026-10-07",
                    title = "Voice Gets Around",
                    summary = "On a call, ask for any session: Voice opens it, starts or steers work there, and tells you when it’s done. Switching sessions no longer cuts Voice off mid-sentence.",
                    example = "On a call, say “open my upload fixes session and check the tests”",
                    illustration = Illustration.voiceMessage,
                ),
                WhatsNewEntry(
                    id = "voice-messages",
                    day = "2026-10-06",
                    title = "Voice Messages",
                    summary = "What the voice agent asks the session on your behalf shows in its own words, marked Voice above the message. Your exact words still go to the session.",
                    example = "Ask for something on a call, then look at the session",
                    illustration = Illustration.voiceMessage,
                ),
                WhatsNewEntry(
                    id = "catch-up-shortcuts",
                    day = "2026-10-06",
                    title = "Catch Up Shortcuts",
                    summary = "Hold Catch Up in the drawer to mark every unread session as read; sessions waiting on you stay. In Catch Up, tap a session’s title to open it.",
                    example = "Touch and hold Catch Up in the drawer",
                    illustration = Illustration.catchUp,
                ),
                WhatsNewEntry(
                    id = "voice-gpt-live",
                    day = "2026-10-06",
                    title = "GPT-Live Voice",
                    summary = "Calls use GPT-Live, which listens while it talks and keeps the conversation going while your session works. Your own words go to the session. Realtime is still in Advanced settings.",
                    example = "Say “and the retry test too” while it’s working",
                    illustration = Illustration.talkOver,
                ),
                WhatsNewEntry(
                    id = "voice-live-activity",
                    day = "2026-10-06",
                    title = "Calls on the Lock Screen",
                    summary = "A voice call shows on the Lock Screen and in the Dynamic Island with its state and a timer. Mute and End work without opening the app.",
                    example = "Start a call, then lock your phone",
                    illustration = Illustration.liveActivity,
                ),
                WhatsNewEntry(
                    id = "voice-call-captions",
                    day = "2026-10-06",
                    title = "Captions in the Call Bar",
                    summary = "Tap the call bar and its glass grows up into the call’s captions, with how long the call has run in the corner.",
                    example = "Tap the call bar during a call",
                    illustration = Illustration.captions,
                ),
                WhatsNewEntry(
                    id = "problem-reports",
                    day = "2026-10-06",
                    title = "Report a Problem",
                    summary = "In TestFlight builds, shake the phone to send a screenshot and the recent log to a Phone Reports session in the project you pick in Settings.",
                    example = "Shake your phone, then tap Send",
                    illustration = Illustration.problemReport,
                ),
                WhatsNewEntry(
                    id = "camera-controls",
                    day = "2026-10-06",
                    title = "Camera Controls",
                    summary = "The attachment camera has lens buttons, pinch to zoom, tap to focus, and flash.",
                    example = "Tap +, then Camera, and pinch to zoom",
                    illustration = Illustration.camera,
                ),
                WhatsNewEntry(
                    id = "voice-calls-keep-going",
                    day = "2026-10-06",
                    title = "Calls That Keep Going",
                    summary = "Long calls ride out dropped connections and the hour limit. Talk over the agent to stop it, and ask about work that’s running to steer it.",
                    example = "Say “how’s it going?” while the agent works",
                    illustration = Illustration.talkOver,
                ),
                WhatsNewEntry(
                    id = "voice-calls-from-composer",
                    day = "2026-10-05",
                    title = "Voice Calls",
                    summary = "With Voice Mode on in Advanced settings, an empty composer offers a call where Send sits, in a new session too. The call bar’s dot scope draws your voice warm and the agent’s cool.",
                    example = "Tap the waveform and say “fix the upload test”",
                    illustration = Illustration.callButton,
                ),
            )
    }
}

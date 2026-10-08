@file:PortedFrom("OpenCode/WhatsNew/WhatsNewEntries.swift", "16b40f7", partial = "all but the Steadier Voice Calls entry (9e1aad3)")

package ai.opencode.mobilecode

import ai.opencode.mobilecode.tca.Identifiable
import java.time.LocalDate
import java.util.Locale

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
        dictationBar,
        attachmentMenu,
        mediaDismiss,
        statusOrb,
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
                    id = "tool-images-whole",
                    day = "2026-10-08",
                    title = "Whole Images Inline",
                    summary = "Images the agent reads now show whole in the session, in rows that fill the width, so charts and screenshots read without opening them. Nothing is cropped or hidden behind a +N.",
                    example = "Ask the agent to read a few screenshots",
                    illustration = Illustration.mediaDismiss,
                ),
                WhatsNewEntry(
                    id = "status-orb-and-arrival-glow",
                    day = "2026-10-08",
                    title = "Status Orb",
                    summary = "A subagent’s card and the background-work capsule show a small orb that changes with what it’s doing: thinking, running tools, writing, waiting for you, done, or failed. Messages an agent sends you light up as they arrive.",
                    example = "Ask the agent to start a subagent and watch its card",
                    illustration = Illustration.statusOrb,
                ),
                WhatsNewEntry(
                    id = "media-drag-to-dismiss",
                    day = "2026-10-07",
                    title = "Drag Down to Close",
                    summary = "Images and videos open out of the transcript and follow your finger when you drag them down, shrinking back into place like in Photos.",
                    example = "Open an image the agent read, then drag it down",
                    illustration = Illustration.mediaDismiss,
                ),
                WhatsNewEntry(
                    id = "attachment-menu-hold",
                    day = "2026-10-07",
                    title = "Hold + to Attach",
                    summary = "The + menu now grows out of the composer, and the + turns into a × that closes it. Touch and hold +, slide to Camera, Photos, or Files, and let go to choose.",
                    example = "Hold +, slide up to Photos, and let go",
                    illustration = Illustration.attachmentMenu,
                ),
                WhatsNewEntry(
                    id = "dictation-send",
                    day = "2026-10-07",
                    title = "Send from Dictation",
                    summary = "Tap the mic and the recording bar can send what you said straight away, or Stop puts it in your message to review. Holding the mic, slide up to lock or left to cancel, then let go.",
                    example = "Tap the mic, say a sentence, and tap Send",
                    illustration = Illustration.dictationBar,
                ),
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

/// The entries that shipped on one day: one card in the list.
data class WhatsNewDay(
    val day: LocalDate,
    val entries: List<WhatsNewEntry>,
) : Identifiable<String> {
    /// `yyyy-MM-dd`, unique per day.
    override val id: String
        get() = String.format(Locale.ROOT, "%04d-%02d-%02d", day.year, day.monthValue, day.dayOfMonth)

    companion object {
        /// Groups entries by day in the order each day first appears, so a day
        /// never yields two cards even if its entries aren’t adjacent.
        fun group(entries: List<WhatsNewEntry>): List<WhatsNewDay> {
            val days = mutableListOf<WhatsNewDay>()
            for (entry in entries) {
                val index = days.indexOfFirst { it.day == entry.day }
                if (index >= 0) {
                    days[index] = days[index].copy(entries = days[index].entries + entry)
                } else {
                    days += WhatsNewDay(day = entry.day, entries = listOf(entry))
                }
            }
            return days
        }
    }
}

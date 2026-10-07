package ai.opencode.mobilecode

import ai.opencode.mobilecode.system.LocalGlassBlurEnabled
import ai.opencode.mobilecode.system.SystemTheme
import ai.opencode.mobilecode.tca.Effect
import ai.opencode.mobilecode.tca.Reduce
import ai.opencode.mobilecode.tca.Store
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/// The session screen with a scripted stream, for frame-timing runs on a
/// device: `dumpsys gfxinfo` before and after the stream.
///
///     adb shell am start -n ai.opencode.mobilecode/.SessionLabActivity \
///       --ei stream-ms 20000 --ei start-delay-ms 4000 --ez lab-scroll-trace true --ez glass-blur true
///
/// Batches arrive every `batch-ms` (50) with a few tokens each, like the
/// event batcher's flushes, and are parsed with the production markdown
/// parser as the reducer's projection does. Logs go to the `SessionLab` tag.
class SessionLabActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        TranscriptScrollTrace.isEnabled = intent.getBooleanExtra("lab-scroll-trace", false)
        val streamMs = intent.getIntExtra("stream-ms", 20_000).toLong()
        val startDelayMs = intent.getIntExtra("start-delay-ms", 4_000).toLong()
        val batchMs = intent.getIntExtra("batch-ms", 50).toLong()
        val tokensPerBatch = intent.getIntExtra("tokens-per-batch", 3)
        val glassBlur = intent.getBooleanExtra("glass-blur", true)
        val base = conversation()
        val store = Store(initialState = { state(base + reply("")) }) {
            // Stands in for the reducer's stream projection: the transcript it accepted.
            Reduce<SessionFeature.State, SessionFeature.Action> { action ->
                if (action is SessionFeature.Action.acceptedTranscriptUpdated) state.items = action.items
                Effect.none
            }
        }
        setContent {
            SystemTheme {
                CompositionLocalProvider(LocalGlassBlurEnabled provides glassBlur) {
                    SessionView(store, listContext = SessionListContext(project = project, isPinned = false))
                }
            }
        }
        lifecycleScope.launch {
            delay(startDelayMs)
            Log.i(TAG, "stream started")
            val start = SystemClock.uptimeMillis()
            val cache = OpenCodeMarkdownBlock.InlineCache()
            var blocks = emptyList<OpenCodeMarkdownBlock>()
            var text = ""
            var tokens = 0
            var batches = 0
            while (SystemClock.uptimeMillis() - start < streamMs) {
                repeat(tokensPerBatch) { text += token(tokens++) }
                blocks = OpenCodeMarkdownBlock.blocks(from = text, inlineCache = cache, reusing = blocks)
                store.send(
                    SessionFeature.Action.acceptedTranscriptUpdated(
                        session.id,
                        base + OpenCodeTranscriptItem.assistantMarkdown(id = "msg_lab_stream-text-0", blocks = blocks),
                    ),
                )
                batches += 1
                delay(batchMs)
            }
            Log.i(TAG, "stream finished after ${SystemClock.uptimeMillis() - start} ms: $batches batches, $tokens tokens, ${text.length} characters; trace ${TranscriptScrollTrace.latest}")
        }
    }

    private val project = OpenCodeProject(id = "prj_lab", name = "Session lab", directory = "/fixture/approval-playground")
    private val session = OpenCodeSession(
        id = "ses_approval_root",
        title = "Session rendering lab",
        projectID = "prj_lab",
        directory = "/fixture/approval-playground",
        updatedAt = 0,
    )

    private fun state(items: List<OpenCodeTranscriptItem>) =
        SessionFeature.State(OpenCodeServer(url = "http://127.0.0.1:9", username = "", password = ""), session, cachedItems = items, isRunning = true).apply {
            earlierHistory = SessionFeature.State.EarlierHistory.exhausted
            visibleLimit = 1_000
        }

    private fun reply(text: String) =
        OpenCodeTranscriptItem.assistantMarkdown(id = "msg_lab_stream-text-0", blocks = OpenCodeMarkdownBlock.blocks(from = text))

    private fun conversation(): List<OpenCodeTranscriptItem> = (1..12).flatMap { turn ->
        listOf(
            OpenCodeTranscriptItem.userText(id = "msg_lab_user_$turn", text = "Review detail $turn: what happens when the conversation gets longer?"),
            OpenCodeTranscriptItem.assistantMarkdown(
                id = "msg_lab_reply_$turn-text-0",
                blocks = OpenCodeMarkdownBlock.blocks(
                    from = "### Detail $turn\n\nI found the layout issue in `SessionView.swift`. The transcript and composer share the available height, so the keyboard is a useful stress test.\n\n- Keep earlier messages readable.\n- Leave the composer accessible.",
                ),
            ),
        )
    }

    private fun token(index: Int): String = when {
        index % 120 == 119 -> "\n\n```swift\nlet glide = spring.step(toward: bottom) // one frame\n```\n\n"
        index % 40 == 39 -> "\n\n"
        else -> WORDS[index % WORDS.size]
    }

    private companion object {
        const val TAG = "SessionLab"
        val WORDS = listOf(" The", " follower", " glides", " to", " the", " live", " bottom", " as", " each", " batch", " lands,", " and", " the", " **reader**", " keeps", " `reading`.")
    }
}

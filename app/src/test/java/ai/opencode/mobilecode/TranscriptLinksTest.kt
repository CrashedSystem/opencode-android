package ai.opencode.mobilecode

import ai.opencode.mobilecode.system.SystemTheme
import ai.opencode.mobilecode.tca.Effect
import ai.opencode.mobilecode.tca.Reduce
import ai.opencode.mobilecode.tca.Store
import ai.opencode.mobilecode.tca.TestDependencies
import android.app.Application
import android.content.Intent
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/// Inline links in a justified reply open like the iOS transcript's: a file
/// path in the file viewer, a web address in the browser.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w440dp-h956dp-xxhdpi")
class TranscriptLinksTest {
    @get:Rule
    val dependencies = TestDependencies()

    @get:Rule
    val compose = createComposeRule()

    private val reply = "The fix is in `OpenCode/SessionView.swift:12`, and the routing notes are on " +
        "[the OpenCode site](https://opencode.ai/docs), so the phone opens both from this paragraph."

    @Test
    fun tappingAFileLinkOpensTheFileAndAWebLinkOpensTheBrowser() {
        val opened = mutableListOf<AgentFileReference>()
        val item = SessionViewFixture.assistant("msg_links", reply)
        val store = Store(initialState = { SessionViewFixture.state(listOf(item)) }) {
            Reduce<SessionFeature.State, SessionFeature.Action> { _ -> Effect.none }
        }
        compose.setContent {
            SystemTheme {
                TranscriptEntryView(
                    entry = TranscriptEntry.single(item),
                    store = store,
                    onPreviewImage = {},
                    onOpenFile = { opened += it },
                    disclosure = TranscriptDisclosureState(),
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
        val spans = layout("SessionView.swift").layoutInput.text.spanStyles
        assertTrue(
            "the paragraph is set by the justified renderer",
            spans.any { it.item.letterSpacing != TextUnit.Unspecified || it.item.textGeometricTransform != null },
        )
        assertTrue("links are drawn in the link color only, as on iOS", spans.none { it.item.textDecoration == TextDecoration.Underline })

        tap("SessionView.swift")
        assertEquals(listOf(AgentFileReference(path = "OpenCode/SessionView.swift", line = 12)), opened)
        assertNull("a file link stays in the app", startedActivity())

        tap("the OpenCode site")
        val intent = startedActivity()
        assertEquals(Intent.ACTION_VIEW, intent?.action)
        assertEquals("https://opencode.ai/docs", intent?.dataString)
        assertEquals("a web link does not open the file viewer", 1, opened.size)
    }

    private fun layout(fragment: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNode(hasText(fragment, substring = true)).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.single()
    }

    private fun tap(fragment: String) {
        val layout = layout(fragment)
        val start = layout.layoutInput.text.text.indexOf(fragment)
        val target = layout.getBoundingBox(start + fragment.length / 2).center
        compose.onNode(hasText(fragment, substring = true)).performTouchInput { click(target) }
        compose.waitForIdle()
    }

    private fun startedActivity(): Intent? =
        shadowOf(ApplicationProvider.getApplicationContext<Application>()).nextStartedActivity
}

package ai.opencode.mobilecode

import ai.opencode.mobilecode.system.LocalGlassBlurEnabled
import ai.opencode.mobilecode.system.SystemColors
import ai.opencode.mobilecode.system.SystemTheme
import ai.opencode.mobilecode.tca.Effect
import ai.opencode.mobilecode.tca.Reduce
import ai.opencode.mobilecode.tca.Store
import ai.opencode.mobilecode.tca.TestDependencies
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import java.util.Locale
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/// The session screen in the iOS reference states (`docs/port/reference/ios`),
/// at the reference's 440 × 956 points and 3x. Each test drives `SessionView`
/// from a prepared state with a reducer that ignores every action.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w440dp-h956dp-xxhdpi")
class SessionViewScreenshotTest {
    @get:Rule
    val dependencies = TestDependencies()

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun locale() {
        Locale.setDefault(Locale.US)
    }

    @Test fun draftLight() = draft("light")

    @Test @Config(qualifiers = "+night")
    fun draftDark() = draft("dark")

    @Test fun transcriptMarkdownLight() = transcriptMarkdown("light")

    @Test @Config(qualifiers = "+night")
    fun transcriptMarkdownDark() = transcriptMarkdown("dark")

    @Test fun toolsCollapsedLight() = tools("light", expanded = false)

    @Test @Config(qualifiers = "+night")
    fun toolsCollapsedDark() = tools("dark", expanded = false)

    @Test fun toolsExpandedLight() = tools("light", expanded = true)

    @Test @Config(qualifiers = "+night")
    fun toolsExpandedDark() = tools("dark", expanded = true)

    @Test fun subagentLight() = subagent("light")

    @Test @Config(qualifiers = "+night")
    fun subagentDark() = subagent("dark")

    @Test fun imagesLight() = images("light")

    @Test @Config(qualifiers = "+night")
    fun imagesDark() = images("dark")

    @Test fun runningLight() = running("light")

    @Test @Config(qualifiers = "+night")
    fun runningDark() = running("dark")

    @Test fun streamingLight() = streaming("light")

    @Test @Config(qualifiers = "+night")
    fun streamingDark() = streaming("dark")

    @Test fun composerTextLight() = composerText("light")

    @Test @Config(qualifiers = "+night")
    fun composerTextDark() = composerText("dark")

    @Test fun composerAttachmentsLight() = composerAttachments("light")

    @Test @Config(qualifiers = "+night")
    fun composerAttachmentsDark() = composerAttachments("dark")

    @Test fun steerMenuLight() = steerMenu("light")

    @Test @Config(qualifiers = "+night")
    fun steerMenuDark() = steerMenu("dark")

    @Test fun permissionLight() = permission("light")

    @Test @Config(qualifiers = "+night")
    fun permissionDark() = permission("dark")

    @Test fun questionLight() = question("light")

    @Test @Config(qualifiers = "+night")
    fun questionDark() = question("dark")

    @Test fun headerContextLight() = headerContext("light")

    @Test @Config(qualifiers = "+night")
    fun headerContextDark() = headerContext("dark")

    @Test fun modelPickerLight() = modelPicker("light")

    @Test @Config(qualifiers = "+night")
    fun modelPickerDark() = modelPicker("dark")

    @Test fun backgroundWorkLight() = backgroundWork("light")

    @Test @Config(qualifiers = "+night")
    fun backgroundWorkDark() = backgroundWork("dark")

    @Test fun errorBannerLight() = errorBanner("light")

    @Test @Config(qualifiers = "+night")
    fun errorBannerDark() = errorBanner("dark")

    @Test fun composerEmptyLight() = composerEmpty("light")

    @Test @Config(qualifiers = "+night")
    fun composerEmptyDark() = composerEmpty("dark")

    private fun draft(appearance: String) = capture(
        "06-draft",
        appearance,
        context = SessionListContext(elsewhere = DrawerBadge.unread),
    ) {
        SessionFeature.State(
            SessionViewFixture.server,
            draftProject = SessionViewFixture.project,
            projects = listOf(SessionViewFixture.project),
            inheritedModel = OpenCodeModelRef("fixture-model", "fixture"),
        ).apply { models = listOf(SessionViewFixture.model.copy(name = "fixture-model")) }
    }

    private fun transcriptMarkdown(appearance: String) = capture("07-transcript-markdown", appearance, interact = {
        compose.onNodeWithTag("transcript-list").performScrollToIndex(0)
    }) {
        SessionViewFixture.state(listOf(SessionViewFixture.user(), SessionViewFixture.assistant("msg_lab_typography", TranscriptFixture.typography)))
    }

    private fun tools(appearance: String, expanded: Boolean) = capture(
        if (expanded) "12-tools-expanded" else "11-tools-collapsed",
        appearance,
        interact = { if (expanded) compose.onNodeWithContentDescription("Used 1 Shell").performClick() },
    ) {
        SessionViewFixture.state(SessionViewFixture.markdownItems + SessionViewFixture.shell(OpenCodeToolCall.Status.completed))
    }

    private fun subagent(appearance: String) = capture("13-subagent", appearance, interact = {
        compose.onNodeWithContentDescription("Used 1 Subagent").performClick()
    }) {
        SessionViewFixture.state(SessionViewFixture.intro + SessionViewFixture.subagent(), isRunning = true).apply {
            familySessions = listOf(SessionViewFixture.session, SessionViewFixture.child)
            activeFamilySessionIDs = setOf(SessionViewFixture.session.id, SessionViewFixture.child.id)
        }
    }

    private fun images(appearance: String) = capture("14-images", appearance) {
        SessionViewFixture.state(SessionViewFixture.intro + SessionViewFixture.readImages(4))
    }

    private fun running(appearance: String) = capture("15-running", appearance) {
        SessionViewFixture.state(SessionViewFixture.markdownItems + SessionViewFixture.shell(OpenCodeToolCall.Status.running), isRunning = true)
    }

    private fun streaming(appearance: String) = capture("16-streaming", appearance) {
        SessionViewFixture.state(SessionViewFixture.longConversation(streamUpdates = 8), isRunning = true)
    }

    private fun composerText(appearance: String) = capture("17-composer-text", appearance, interact = {
        compose.onNodeWithContentDescription("Message").performClick()
    }) {
        SessionViewFixture.state(SessionViewFixture.intro).apply { draft = "Check the composer with a short note" }
    }

    private fun composerAttachments(appearance: String) = capture("18-composer-attachments", appearance) {
        SessionViewFixture.state(SessionViewFixture.intro).apply {
            val image = SessionViewFixture.readImages(1).call.imageAttachments.first()
            pendingAttachments = listOf(OpenCodeOutgoingAttachment(id = "00000000-0000-0000-0000-000000000001", data = image.data, mime = "image/png"))
        }
    }

    private fun steerMenu(appearance: String) = capture("19-composer-steer-menu", appearance, interact = {
        // Hold past the long-press timeout on the paused clock, then lift.
        compose.onNodeWithTag("Send message").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithTag("Send message").performTouchInput { up() }
    }) {
        SessionViewFixture.state(SessionViewFixture.markdownItems + SessionViewFixture.shell(OpenCodeToolCall.Status.running), isRunning = true).apply {
            draft = "Steer this"
            familySessions = listOf(SessionViewFixture.session, SessionViewFixture.child)
            activeFamilySessionIDs = setOf(SessionViewFixture.session.id, SessionViewFixture.child.id)
        }
    }

    private fun permission(appearance: String) = capture("20-permission", appearance, context = SessionViewFixture.approvalContext) {
        SessionViewFixture.state(SessionViewFixture.approvalItems, session = SessionViewFixture.approvalSession).apply {
            familySessions = listOf(SessionViewFixture.approvalSession, SessionViewFixture.child)
            permissionRequests = listOf(SessionViewFixture.permission)
            selectedRequestID = SessionViewFixture.permission.id
            activePicker = SessionFeature.State.ActivePicker.requests
        }
    }

    private fun question(appearance: String) = capture("21-question", appearance, context = SessionViewFixture.approvalContext) {
        SessionViewFixture.state(SessionViewFixture.approvalItems, session = SessionViewFixture.approvalSession).apply {
            familySessions = listOf(SessionViewFixture.approvalSession, SessionViewFixture.child)
            formRequests = listOf(SessionViewFixture.question)
            selectedRequestID = SessionViewFixture.question.id
            activePicker = SessionFeature.State.ActivePicker.requests
        }
    }

    private fun headerContext(appearance: String) = capture("22-header-context", appearance) {
        SessionViewFixture.state(SessionViewFixture.markdownItems).apply {
            contextUsage = OpenCodeContextUsage(tokens = 340_000, model = SessionViewFixture.model.ref)
        }
    }

    private fun modelPicker(appearance: String) = capture("25-model-picker", appearance) {
        SessionViewFixture.state(SessionViewFixture.intro).apply { activePicker = SessionFeature.State.ActivePicker.model }
    }

    private fun backgroundWork(appearance: String) = capture(
        "27-background-work",
        appearance,
        context = SessionListContext(project = SessionViewFixture.gauntletProject, isPinned = false, elsewhere = DrawerBadge.unread),
        interact = { compose.onNodeWithTag("background-activity").performClick() },
    ) {
        val shellTool = SessionViewFixture.shell(OpenCodeToolCall.Status.completed)
        SessionViewFixture.state(
            listOf(
                SessionViewFixture.user(text = "Polish the native feature tour."),
                shellTool,
                SessionViewFixture.assistant("msg_tour", SessionViewFixture.tourReply),
            ),
            session = SessionViewFixture.gauntletSession,
        ).apply {
            familySessions = listOf(SessionViewFixture.gauntletSession, SessionViewFixture.gauntletChild)
            activeFamilySessionIDs = setOf(SessionViewFixture.gauntletChild.id)
            shells = listOf(
                OpenCodeShell(
                    id = "sh_gauntlet_1",
                    command = "bun dev --port 3000",
                    sessionID = SessionViewFixture.gauntletSession.id,
                    isUserCommand = true,
                    startedAt = System.currentTimeMillis() - 15_000,
                ),
            )
        }
    }

    private fun errorBanner(appearance: String) = capture("33-error-banner", appearance, context = SessionViewFixture.approvalContext) {
        SessionViewFixture.state(SessionViewFixture.approvalItems, session = SessionViewFixture.approvalSession).apply {
            requestLoadError = "The server returned HTTP 503."
        }
    }

    private fun composerEmpty(appearance: String) = capture("35-composer-empty", appearance) {
        SessionViewFixture.state(SessionViewFixture.intro)
    }

    @OptIn(ExperimentalRoborazziApi::class)
    private fun capture(
        name: String,
        appearance: String,
        context: SessionListContext = SessionViewFixture.context,
        interact: () -> Unit = {},
        state: () -> SessionFeature.State,
    ) {
        val store = Store(initialState = state) {
            Reduce<SessionFeature.State, SessionFeature.Action> { _ -> Effect.none }
        }
        compose.mainClock.autoAdvance = false
        compose.setContent {
            SystemTheme {
                CompositionLocalProvider(LocalGlassBlurEnabled provides false) {
                    // The iOS captures keep their 62 pt status bar and 34 pt home indicator.
                    Box(Modifier.fillMaxSize().background(SystemColors.systemBackground).padding(top = 62.dp, bottom = 34.dp)) {
                        SessionView(store, listContext = context)
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_200)
        interact()
        compose.mainClock.advanceTimeBy(1_200)
        compose.waitForIdle()
        captureScreenRoboImage("src/test/snapshots/session-view/$name.$appearance.png")
    }
}

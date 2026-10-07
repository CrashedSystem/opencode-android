package ai.opencode.mobilecode

import ai.opencode.mobilecode.system.LocalGlassBlurEnabled
import ai.opencode.mobilecode.system.SystemColors
import ai.opencode.mobilecode.system.SystemSheet
import ai.opencode.mobilecode.system.SystemTheme
import ai.opencode.mobilecode.tca.Store
import ai.opencode.mobilecode.tca.TestDependencies
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
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

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w440dp-h956dp-xxhdpi")
class SessionChildrenScreenshotTest {
    @get:Rule
    val dependencies = TestDependencies()

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun locale() {
        Locale.setDefault(Locale.US)
    }

    @Test
    fun sessionSheetLight() = captureSheet("light")

    @Test
    @Config(qualifiers = "+night")
    fun sessionSheetDark() = captureSheet("dark")

    @Test
    fun sessionMenuLight() = captureMenu("light")

    @Test
    @Config(qualifiers = "+night")
    fun sessionMenuDark() = captureMenu("dark")

    @Test
    fun shellOutputLight() = captureShell("light")

    @Test
    @Config(qualifiers = "+night")
    fun shellOutputDark() = captureShell("dark")

    @OptIn(ExperimentalRoborazziApi::class)
    private fun captureSheet(appearance: String) {
        val store = Store(initialState = { SessionSheetFeature.State(server, session.id) }) { SessionSheetFeature() }
        compose.setContent {
            SystemTheme {
                CompositionLocalProvider(LocalGlassBlurEnabled provides false) {
                    Box(Modifier.fillMaxSize().background(SystemColors.systemBackground))
                    SystemSheet(onDismissRequest = {}) {
                        SessionSheetView(
                            store = store,
                            session = session,
                            context = context,
                            currentModelName = "Fixture model",
                            contextUsage = OpenCodeContextUsage(340_000, OpenCodeModelRef("fixture", "fixture")),
                            contextFraction = 0.85,
                            contextLimit = 400_000,
                            sessionCost = 1.42,
                            onModelButtonTapped = {},
                            modifier = Modifier.fillMaxWidth().height(640.dp),
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
        captureScreenRoboImage("src/test/snapshots/session/SessionSheetView.$appearance.png")
    }

    @OptIn(ExperimentalRoborazziApi::class)
    private fun captureMenu(appearance: String) {
        compose.setContent {
            SystemTheme {
                CompositionLocalProvider(LocalGlassBlurEnabled provides false) {
                    Box(Modifier.fillMaxSize().background(SystemColors.systemBackground).padding(top = 48.dp, end = 16.dp), contentAlignment = Alignment.TopEnd) {
                        SessionMenuButton(
                            title = session.title,
                            sessionID = session.id,
                            context = context,
                            contextFraction = 0.85,
                            contextUsage = OpenCodeContextUsage(340_000, OpenCodeModelRef("fixture", "fixture")),
                            contextLimit = 400_000,
                            onAction = {},
                            onSessionInfo = {},
                        )
                    }
                }
            }
        }
        compose.onNodeWithTag("session-menu").performClick()
        compose.waitForIdle()
        captureScreenRoboImage("src/test/snapshots/session/SessionMenu.$appearance.png")
    }

    @OptIn(ExperimentalRoborazziApi::class)
    private fun captureShell(appearance: String) {
        val startedAt = System.currentTimeMillis() - 19_000
        val shell = OpenCodeShell(id = "sh_gauntlet_1", command = "bun dev --port 3000", status = OpenCodeShell.Status.running, startedAt = startedAt)
        val store = Store(
            initialState = {
                ShellOutputFeature.State(server, "/fixture", shell).apply {
                    output = shellLog
                    endCursor = shellLog.toByteArray().size.toLong()
                }
            },
            withDependencies = {
                it.openCodeClient = testOpenCodeClient(
                    shell = { _, _, _ -> shell },
                    shellOutput = { _, _, _, _, _ -> OpenCodeShellOutputPage("", shellLog.toByteArray().size, shellLog.toByteArray().size) },
                )
            },
        ) { ShellOutputFeature() }
        compose.setContent {
            SystemTheme {
                CompositionLocalProvider(LocalGlassBlurEnabled provides false) {
                    Box(Modifier.fillMaxSize().background(SystemColors.systemBackground))
                    SystemSheet(onDismissRequest = {}) {
                        ShellOutputView(store, Modifier.fillMaxWidth().height(420.dp))
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
        captureScreenRoboImage("src/test/snapshots/session/ShellOutputView.$appearance.png")
    }

    private val server = OpenCodeServer(url = "http://127.0.0.1:9", username = "", password = "")
    private val session = OpenCodeSession(
        id = "ses_lab",
        title = "Session rendering lab",
        projectID = "prj",
        directory = "/fixture/approval-playground",
        updatedAt = 0,
        cost = 1.42,
    )
    private val context = SessionListContext(
        project = OpenCodeProject(id = "prj", name = "Session lab", directory = "/fixture/approval-playground"),
        isPinned = false,
        isUnread = false,
    )
    private val shellLog = """
        [0115]  →  Local:   http://localhost:3000/
        [0116]  ✓ Compiled /session in 304 ms (216 modules)
        [0117]  GET /api/session 200 in 341ms
        [0118]  ○ Watching for file changes in ./OpenCode...
        [0119]  warn - Fast Refresh had to perform a full reload while editing SessionView.swift
        [0120]  →  Local:   http://localhost:3000/
        [0121]  ✓ Compiled /session in 89 ms (181 modules)
        [0122]  GET /api/session 200 in 126ms
        [0123]  ○ Watching for file changes in ./OpenCode...
        [0124]  warn - Fast Refresh had to perform a full reload while editing SessionView.swift
        [0125]  →  Local:   http://localhost:3000/
        [0126]  ✓ Compiled /session in 274 ms (186 modules)
        [0127]  GET /api/session 200 in 311ms
    """.trimIndent()
}

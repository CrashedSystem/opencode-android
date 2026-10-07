package ai.opencode.mobilecode

import ai.opencode.mobilecode.system.SystemTheme
import ai.opencode.mobilecode.tca.DateGenerator
import ai.opencode.mobilecode.tca.ImmediateClock
import ai.opencode.mobilecode.tca.Store
import ai.opencode.mobilecode.tca.TestDependencies
import ai.opencode.mobilecode.tca.UUIDGenerator
import ai.opencode.mobilecode.tca.continuousClock
import ai.opencode.mobilecode.tca.date
import ai.opencode.mobilecode.tca.expect
import ai.opencode.mobilecode.tca.uuid
import java.time.Instant
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlinx.coroutines.flow.flow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ProblemReportSettingsRowsTest {
    @get:Rule
    val dependencies = TestDependencies()

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun choosingADestinationReplacesNoneAndReportProblemSendsThere() {
        val server = OpenCodeServer(url = "http://127.0.0.1:4096", username = "opencode", password = "")
        val project = OpenCodeProject(id = "mobile", name = "mobile", directory = "/Users/kit/mobile")
        val created = mutableListOf<String>()
        val prompts = mutableListOf<String>()
        val client = OpenCodeClient.testValue
        client.createSession = { _, directory ->
            synchronized(created) { created += directory }
            OpenCodeSession(id = "ses_report_1", title = "New session", projectID = project.id, directory = directory, updatedAt = 0)
        }
        client.sendPrompt = { _, _, text, _, _, _, _ -> synchronized(prompts) { prompts += text } }
        client.uploadFile = { _, _, _, name -> flow { emit(OpenCodeUploadEvent.finished("/tmp/$name")) } }
        client.renameSession = { _, _, _ -> }
        client.switchModel = { _, _, _ -> }
        val store = Store(
            initialState = {
                ProblemReportsFeature.State().apply {
                    this.server = server
                    projects = listOf(project, OpenCodeProject(id = "other", name = "other", directory = "/Users/kit/other"))
                }
            },
            withDependencies = {
                it.openCodeClient = client
                it.uuid = UUIDGenerator.incrementing()
                it.date = DateGenerator.constant(Instant.ofEpochSecond(2_000))
                it.continuousClock = ImmediateClock()
            },
        ) { ProblemReportsFeature() }

        compose.setContent {
            SystemTheme {
                problemReports(store) {
                    ProblemReportSettingsRows(store)
                }
            }
        }
        compose.onNodeWithText("None").assertExists()
        compose.onNodeWithTag("send-reports-to").assert(hasStateDescription("None")).performClick()
        compose.onNodeWithTag("report-project-mobile").assertExists().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("report-project-mobile").assertDoesNotExist()
        compose.onNodeWithText("None").assertDoesNotExist()
        compose.onNodeWithText("mobile").assertExists()
        compose.onNodeWithTag("send-reports-to").assert(hasStateDescription("mobile"))
        expect(store.state.destination?.projectID == "mobile")
        expect(store.state.destination?.directory == "/Users/kit/mobile")

        expect(ProblemReportsFeature.isAvailable)
        compose.onNodeWithText("Report Problem").performClick()
        waitUntil({ "composer=${store.state.composer}" }) { store.state.composer?.isReady == true }
        compose.onNodeWithTag("problem-report-send").performClick()
        waitUntil({ "created=$created prompts=$prompts composer=${store.state.composer}" }) {
            synchronized(prompts) { prompts.isNotEmpty() }
        }
        expect(synchronized(created) { created.toList() } == listOf("/Users/kit/mobile"))
        expect(store.state.destination?.projectID == "mobile")
        expect(store.state.composer?.isPickingProject != true)
    }

    private fun waitUntil(message: () -> String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!condition()) {
            compose.mainClock.advanceTimeBy(200)
            compose.waitForIdle()
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            check(System.nanoTime() <= deadline) { "timed out waiting for the report destination: ${message()}" }
            Thread.sleep(20)
        }
    }
}

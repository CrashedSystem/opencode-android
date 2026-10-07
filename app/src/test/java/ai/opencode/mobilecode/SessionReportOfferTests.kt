package ai.opencode.mobilecode

import ai.opencode.mobilecode.system.SystemTheme
import ai.opencode.mobilecode.tca.Effect
import ai.opencode.mobilecode.tca.Reduce
import ai.opencode.mobilecode.tca.Store
import ai.opencode.mobilecode.tca.TestDependencies
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import java.util.UUID
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w440dp-h956dp-xxhdpi")
class SessionReportOfferTests {
    @get:Rule
    val dependencies = TestDependencies()

    @get:Rule
    val compose = createComposeRule()

    @After
    fun clearHub() {
        ProblemReportHub.shared.store = null
    }

    @Test
    fun showsTheOfferInThePillRow() {
        offer()
        compose.setContent {
            SystemTheme { SessionView(session()) }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("problem-report-offer").assertExists()
    }

    @Test
    fun hidesTheOfferWhileCatchingUp() {
        offer()
        compose.setContent {
            SystemTheme { SessionView(session(), onCatchUpDone = {}) }
        }
        compose.waitForIdle()
        compose.onAllNodesWithTag("problem-report-offer").assertCountEquals(0)
    }

    private fun offer() {
        ProblemReportHub.shared.store = Store(
            initialState = {
                ProblemReportsFeature.State().apply {
                    offer = ProblemReportsFeature.State.Offer(UUID.randomUUID(), ProblemReportTrigger.crash)
                }
            },
        ) {
            Reduce<ProblemReportsFeature.State, ProblemReportsFeature.Action> { _ -> Effect.none }
        }
    }

    private fun session() = Store(initialState = { SessionViewFixture.state(SessionViewFixture.intro) }) {
        Reduce<SessionFeature.State, SessionFeature.Action> { _ -> Effect.none }
    }
}

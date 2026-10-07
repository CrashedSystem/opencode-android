package ai.opencode.mobilecode

import ai.opencode.mobilecode.system.LocalGlassBlurEnabled
import ai.opencode.mobilecode.system.SystemTheme
import ai.opencode.mobilecode.tca.ImmediateClock
import ai.opencode.mobilecode.tca.Store
import ai.opencode.mobilecode.tca.TestDependencies
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/// Home in the iOS reference states (`04-drawer`, `05-drawer-search`,
/// `29-catch-up`, `31-settings`) on the reference device's 440 × 956 point
/// screen at 3x, from the gauntlet fixture.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w440dp-h956dp-xxhdpi")
class HomeViewScreenshotTest {
    @get:Rule
    val dependencies = TestDependencies()

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun drawerLight() = capture("drawer.light") { openDrawer() }

    /// `+night` reaches the sheets' own windows, which `ForcedAppearance` does not.
    @Test
    @Config(qualifiers = "+night")
    fun drawerDark() = capture("drawer.dark") { openDrawer() }

    @Test
    fun drawerSearchLight() = capture("drawer-search.light") { searchDrawer() }

    @Test
    @Config(qualifiers = "+night")
    fun drawerSearchDark() = capture("drawer-search.dark") { searchDrawer() }

    @Test
    fun catchUpLight() = capture("catch-up.light") { it.send(HomeFeature.Action.catchUpTapped) }

    @Test
    @Config(qualifiers = "+night")
    fun catchUpDark() = capture("catch-up.dark") { it.send(HomeFeature.Action.catchUpTapped) }

    @Test
    fun settingsLight() = capture("settings.light", port = 4110) { openSettings(it) }

    @Test
    @Config(qualifiers = "+night")
    fun settingsDark() = capture("settings.dark", port = 4110) { openSettings(it) }

    private fun openDrawer() {
        compose.onNodeWithContentDescription("Open sessions").performClick()
        settle()
    }

    private fun searchDrawer() {
        openDrawer()
        compose.onNodeWithContentDescription("Search sessions", useUnmergedTree = true).performTextInput("composer")
        settle()
    }

    private fun openSettings(store: Store<HomeFeature.State, HomeFeature.Action>) {
        store.send(HomeFeature.Action.sessionTapped(HomeLabFixture.asking))
        settle()
        store.send(HomeFeature.Action.settingsTapped)
        settle(2_000)
    }

    /// The ring and the glyph never stop, so the clock moves by hand.
    private fun settle(milliseconds: Long = 1_000) {
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(milliseconds)
        compose.waitForIdle()
    }

    @OptIn(ExperimentalRoborazziApi::class)
    private fun capture(name: String, port: Int = 4120, act: (Store<HomeFeature.State, HomeFeature.Action>) -> Unit) {
        val store = HomeLabFixture.store(server = HomeLabFixture.server(port), clock = ImmediateClock())
        compose.mainClock.autoAdvance = false
        compose.setContent {
            SystemTheme {
                CompositionLocalProvider(LocalGlassBlurEnabled provides false) {
                    HomeView(store)
                }
            }
        }
        settle()
        act(store)
        settle()
        captureScreenRoboImage("src/test/snapshots/home/HomeView.$name.png")
    }
}

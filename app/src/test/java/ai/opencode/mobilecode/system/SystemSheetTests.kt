package ai.opencode.mobilecode.system

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w440dp-h956dp-xxhdpi")
class SystemSheetTests {
    @get:Rule
    val compose = createComposeRule()

    /// A sheet without detents opens at full height on iOS, so the server form's
    /// password field and Connect button stay above the keyboard.
    @Test
    fun largeSheetOpensFullyExpanded() {
        val content = presentSheet(SheetDetent.Large)
        assertEquals("the whole sheet is on screen", 956f, content.bottom.value, 1f)
    }

    @Test
    fun mediumSheetOpensAtHalfHeight() {
        val content = presentSheet(SheetDetent.Medium)
        assertTrue("stops at half height, at ${content.top}", content.top.value >= 956f / 2)
    }

    /// iOS lifts a medium sheet above the keyboard, so a question's Send Answers
    /// stays tappable while its answer field has focus.
    @Test
    fun keyboardLiftsAMediumSheetAboveIt() {
        presentSheet(SheetDetent.Medium)
        val keyboard = 300f
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, (keyboard * 3).toInt()))
            .setVisible(WindowInsetsCompat.Type.ime(), true)
            .build()
        compose.runOnUiThread { ViewCompat.dispatchApplyWindowInsets(ShadowDialog.getLatestDialog().window!!.decorView, insets) }
        compose.waitForIdle()

        val content = compose.onNodeWithTag("sheet-content", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertEquals("the sheet ends where the keyboard starts", 956f - keyboard, content.bottom.value, 1f)
    }

    private fun presentSheet(detent: SheetDetent): DpRect {
        compose.setContent {
            SystemSheet(onDismissRequest = {}, initialDetent = detent) {
                Box(Modifier.fillMaxWidth().height(600.dp).testTag("sheet-content"))
            }
        }
        compose.waitForIdle()
        return compose.onNodeWithTag("sheet-content", useUnmergedTree = true).getUnclippedBoundsInRoot()
    }
}

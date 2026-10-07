@file:PortedFrom("OpenCodeTests/AttachmentMenuPressTests.swift", "32afd1e")

package ai.opencode.mobilecode

import ai.opencode.mobilecode.tca.TestDependencies
import ai.opencode.mobilecode.tca.expect
import ai.opencode.mobilecode.tca.withDependencies
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Rule
import org.junit.Test

/// The + button's touch: hold to open, slide over rows, release to choose,
/// and the haptics each step plays (`.recording` from `HapticsAuditTests`).
class AttachmentMenuPressTests {
    @get:Rule
    val dependencies = TestDependencies()

    private val button = Rect(20f, 800f, 64f, 844f)
    private val rows: Map<AttachmentMenuItem, Rect> = mapOf(
        AttachmentMenuItem.camera to Rect(20f, 600f, 220f, 648f),
        AttachmentMenuItem.photos to Rect(20f, 648f, 220f, 696f),
        AttachmentMenuItem.files to Rect(20f, 696f, 220f, 744f),
        AttachmentMenuItem.paste to Rect(20f, 744f, 220f, 792f),
    )
    private val onButton: Offset get() = button.center

    private fun point(item: AttachmentMenuItem): Offset = rows.getValue(item).center

    private fun press(log: MutableList<String>): AttachmentMenuPress =
        withDependencies({ it.haptics = HapticsClient.recording(log) }) { AttachmentMenuPress() }

    @Test
    fun aTapTogglesAndOpeningPlaysOneLightImpact() {
        val log = mutableListOf<String>()
        val press = press(log)
        press.began(isOpen = false)
        expect(press.ended(at = onButton, button = button, rows = rows) == AttachmentMenuPress.Outcome.toggle)
        press.menuOpened()
        expect(log == listOf("impact"))

        press.began(isOpen = true)
        expect(press.ended(at = onButton, button = button, rows = rows) == AttachmentMenuPress.Outcome.toggle, "The × closes it")
        expect(log == listOf("impact"), "Closing plays nothing")
    }

    @Test
    fun holdingOpensOnceAndSlidingTicksOncePerRow() {
        val log = mutableListOf<String>()
        val press = press(log)
        press.began(isOpen = false)
        expect(press.highlighted == null)
        // Before the hold elapses, sliding highlights nothing.
        press.moved(to = point(AttachmentMenuItem.files), button = button, rows = rows)
        expect(press.highlighted == null)
        expect(press.holdElapsed())
        expect(!press.holdElapsed(), "A touch opens the menu once")
        press.menuOpened()

        press.moved(to = point(AttachmentMenuItem.files), button = button, rows = rows)
        press.moved(to = Offset(60f, 700f), button = button, rows = rows)
        press.moved(to = point(AttachmentMenuItem.photos), button = button, rows = rows)
        press.moved(to = Offset(100f, 660f), button = button, rows = rows)
        press.moved(to = point(AttachmentMenuItem.camera), button = button, rows = rows)
        expect(press.highlighted == AttachmentMenuItem.camera)
        expect(log == listOf("impact", "selection", "selection", "selection"))

        expect(press.ended(at = point(AttachmentMenuItem.camera), button = button, rows = rows) == AttachmentMenuPress.Outcome.choose(AttachmentMenuItem.camera))
        expect(log == listOf("impact", "selection", "selection", "selection", "impact"))
        expect(press.highlighted == null)
    }

    @Test
    fun releasingOnFilesChoosesIt() {
        val log = mutableListOf<String>()
        val press = press(log)
        press.began(isOpen = false)
        press.holdElapsed()
        press.moved(to = point(AttachmentMenuItem.files), button = button, rows = rows)
        expect(press.ended(at = point(AttachmentMenuItem.files), button = button, rows = rows) == AttachmentMenuPress.Outcome.choose(AttachmentMenuItem.files))
        expect(log == listOf("selection", "impact"))
    }

    @Test
    fun pasteIsNeverChosenByDrag() {
        val log = mutableListOf<String>()
        val press = press(log)
        press.began(isOpen = false)
        press.holdElapsed()
        press.moved(to = point(AttachmentMenuItem.paste), button = button, rows = rows)
        expect(press.highlighted == null)
        expect(press.ended(at = point(AttachmentMenuItem.paste), button = button, rows = rows) == AttachmentMenuPress.Outcome.none)
        expect(log.isEmpty())
    }

    @Test
    fun releasingBackOnTheCloseButtonCancels() {
        val log = mutableListOf<String>()
        val press = press(log)
        press.began(isOpen = false)
        press.holdElapsed()
        press.moved(to = point(AttachmentMenuItem.photos), button = button, rows = rows)
        press.moved(to = onButton, button = button, rows = rows)
        expect(press.highlighted == null)
        expect(press.ended(at = onButton, button = button, rows = rows) == AttachmentMenuPress.Outcome.close)
        expect(log == listOf("selection"))
    }

    @Test
    fun heldOpenAndReleasedInPlaceOrAwayLeavesTheMenuOpen() {
        val log = mutableListOf<String>()
        val press = press(log)
        press.began(isOpen = false)
        press.holdElapsed()
        press.moved(to = Offset(onButton.x + 3, onButton.y - 2), button = button, rows = rows)
        expect(press.ended(at = onButton, button = button, rows = rows) == AttachmentMenuPress.Outcome.none)

        press.began(isOpen = false)
        press.holdElapsed()
        press.moved(to = Offset(300f, 300f), button = button, rows = rows)
        expect(press.ended(at = Offset(300f, 300f), button = button, rows = rows) == AttachmentMenuPress.Outcome.none)
        expect(log.isEmpty())
    }

    @Test
    fun slidingFromTheOpenMenusCloseButtonChoosesARow() {
        val log = mutableListOf<String>()
        val press = press(log)
        press.began(isOpen = true)
        expect(!press.holdElapsed(), "An open menu doesn't reopen")
        press.moved(to = point(AttachmentMenuItem.photos), button = button, rows = rows)
        expect(press.ended(at = point(AttachmentMenuItem.photos), button = button, rows = rows) == AttachmentMenuPress.Outcome.choose(AttachmentMenuItem.photos))
        expect(log == listOf("selection", "impact"))
    }

    /// With the photo card up, the button passes no rows: sliding from the ×
    /// across the card ticks nothing and chooses nothing.
    @Test
    fun withoutRowsSlidingPlaysAndChoosesNothing() {
        val log = mutableListOf<String>()
        val press = press(log)
        press.began(isOpen = true)
        press.moved(to = point(AttachmentMenuItem.photos), button = button, rows = emptyMap())
        press.moved(to = point(AttachmentMenuItem.camera), button = button, rows = emptyMap())
        expect(press.highlighted == null)
        expect(press.ended(at = point(AttachmentMenuItem.camera), button = button, rows = emptyMap()) == AttachmentMenuPress.Outcome.none)
        expect(log.isEmpty())
    }

    @Test
    fun aCancelledTouchChoosesNothing() {
        val log = mutableListOf<String>()
        val press = press(log)
        press.began(isOpen = false)
        press.holdElapsed()
        press.moved(to = point(AttachmentMenuItem.files), button = button, rows = rows)
        press.cancelled()
        expect(press.highlighted == null)
        expect(press.ended(at = point(AttachmentMenuItem.files), button = button, rows = rows) == AttachmentMenuPress.Outcome.none)
        expect(log == listOf("selection"))
    }
}

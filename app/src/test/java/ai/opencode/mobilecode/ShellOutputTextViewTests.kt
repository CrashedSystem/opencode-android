package ai.opencode.mobilecode

import android.widget.TextView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.activity.ComponentActivity
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/// A running shell's output tails the bottom while the reader follows it, as on iOS.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w440dp-h956dp-xxhdpi")
class ShellOutputTextViewTests {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun followsTheTailAsTheSheetGrows() {
        val output = (1..200).joinToString("\n") { "[%04d] Compiled /session in 49 ms".format(it) }
        var height by mutableStateOf(120.dp)
        val following = mutableListOf<Boolean>()
        compose.setContent {
            Box(Modifier.fillMaxWidth().height(height)) {
                ShellOutputTextView(output, 0, isFollowing = true, onFollowingChanged = { following += it }, jumpRequest = 0)
            }
        }
        compose.waitForIdle()
        height = 400.dp
        compose.waitForIdle()

        val view = textView()
        assertEquals("the last line is in view", view.layout.height - view.height + view.paddingBottom, view.scrollY)
        assertEquals("nothing but the reader stops following", emptyList<Boolean>(), following)
    }

    @Test
    fun followsTheTailAsOutputArrives() {
        val lines = (1..200).map { "[%04d] Compiled /session in 49 ms".format(it) }
        var output by mutableStateOf(lines.take(100).joinToString("\n"))
        compose.setContent {
            Box(Modifier.fillMaxWidth().height(400.dp)) {
                ShellOutputTextView(output, 0, isFollowing = true, onFollowingChanged = {}, jumpRequest = 0)
            }
        }
        compose.waitForIdle()
        for (count in 120..200 step 20) {
            output = lines.take(count).joinToString("\n")
            compose.waitForIdle()
            compose.activity.window.decorView.viewTreeObserver.dispatchOnPreDraw()
            compose.waitForIdle()
        }

        val view = textView()
        assertEquals("the newest line is in view", view.layout.height - view.height + view.paddingBottom, view.scrollY)
    }

    @Test
    fun logLinesUseTheReferencePitch() {
        val output = (1..8).joinToString("\n") { "[%04d] Compiled /session in 49 ms".format(it) }
        compose.setContent {
            Box(Modifier.fillMaxWidth().height(200.dp)) {
                ShellOutputTextView(output, 0, isFollowing = true, onFollowingChanged = {}, jumpRequest = 0)
            }
        }
        compose.waitForIdle()
        val view = textView()
        val pitch = view.layout.getLineTop(1) - view.layout.getLineTop(0)
        assertEquals(44, pitch)
    }

    private fun textView(): TextView {
        fun find(view: android.view.View): TextView? = when (view) {
            is TextView -> view
            is android.view.ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { find(view.getChildAt(it)) }
            else -> null
        }
        return find(compose.activity.window.decorView)!!
    }
}

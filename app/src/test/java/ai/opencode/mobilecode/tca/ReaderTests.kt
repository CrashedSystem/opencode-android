package ai.opencode.mobilecode.tca

import ai.opencode.mobilecode.system.ButtonRole
import ai.opencode.mobilecode.system.LocalDismiss
import ai.opencode.mobilecode.system.SystemButton
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ReaderTests {
    @get:Rule
    val dependencies = TestDependencies()

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun presentedShowsTheChildStoreAndItsDismissSendsDismiss() {
        val store = Store(initialState = Modal()) { ModalFeature() }
        compose.setContent {
            store.presented(Modal::child, ModalAction.child) { child ->
                val dismiss = LocalDismiss.current
                Text("count ${child.state.count}")
                SystemButton("Increment") { child.send(ChildAction.increment) }
                SystemButton("Close") { dismiss() }
            }
        }
        compose.onNodeWithText("Increment").performClick()
        compose.onNodeWithText("count 1").assertExists()
        compose.onNodeWithText("Close").performClick()
        compose.waitForIdle()
        assertNull(store.state.child)
        assertEquals(1, store.state.observed)
        compose.onNodeWithText("count 1").assertDoesNotExist()
    }

    @Test
    fun alertRunsTheTappedButtonAfterClearingItsBinding() {
        val store = Store(initialState = Form()) { FormFeature() }
        val tapped = mutableListOf<String>()
        compose.setContent {
            alert(
                title = "Delete Session?",
                isPresented = store.binding(Form::isOn, FormAction::binding),
                message = "This removes it for every client.",
                buttons =
                    listOf(
                        AlertButton("Delete", role = ButtonRole.destructive) { tapped += "delete" },
                        AlertButton("Cancel", role = ButtonRole.cancel) { tapped += "cancel" },
                    ),
            )
        }
        compose.onNodeWithText("Delete Session?").assertDoesNotExist()
        compose.runOnIdle { store.send(FormAction.binding(BindingAction.set(Form::isOn, true))) }
        compose.onNodeWithText("This removes it for every client.").assertExists()
        compose.onNodeWithText("Delete").performClick()
        compose.waitForIdle()
        assertEquals(listOf("delete"), tapped)
        assertEquals(false, store.state.isOn)
        compose.onNodeWithText("Delete Session?").assertDoesNotExist()
    }

    @Test
    fun observeRecomposesOnlyWhenTheDerivedValueChanges() {
        val store = Store(initialState = Form()) { FormFeature() }
        var compositions = 0
        compose.setContent {
            val hasDraft = store.observe { draft.isNotEmpty() }
            SideEffect { compositions += 1 }
            Text(if (hasDraft) "has draft" else "empty")
        }
        compose.waitForIdle()
        listOf("H", "He", "Hel").forEach { draft ->
            compose.runOnIdle { store.send(FormAction.binding(BindingAction.set(Form::draft, draft))) }
        }
        compose.onNodeWithText("has draft").assertExists()
        assertEquals(2, compositions)
    }
}

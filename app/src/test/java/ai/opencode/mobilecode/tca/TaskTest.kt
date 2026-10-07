package ai.opencode.mobilecode.tca

import android.os.Bundle
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TaskTest {
    @Test
    fun aTaskRunsOnAppearAndOnANewIDButNotAfterAConfigurationChange() {
        val store = Store(initialState = Visit()) { VisitFeature() }
        TaskActivity.content = { VisitView(store) }
        val activity = Robolectric.buildActivity(TaskActivity::class.java).setup()
        idle()
        assertEquals(listOf("task a"), store.state.log)

        activity.recreate()
        idle()
        assertEquals(listOf("task a"), store.state.log)

        store.send(VisitAction.select("b"))
        idle()
        assertEquals(listOf("task a", "select b", "task b"), store.state.log)

        store.send(VisitAction.hide)
        idle()
        store.send(VisitAction.show)
        idle()
        assertEquals(listOf("task a", "select b", "task b", "hide", "show", "task b"), store.state.log)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
}

class TaskActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { content() }
    }

    companion object {
        var content: @Composable () -> Unit = {}
    }
}

class Visit : ObservableState() {
    var selected by StoreVar("a")
    var isShown by StoreVar(true)
    var log by StoreVar(listOf<String>())
}

sealed interface VisitAction {
    data class task(val id: String) : VisitAction

    data class select(val id: String) : VisitAction

    data object hide : VisitAction

    data object show : VisitAction
}

class VisitFeature : Reducer<Visit, VisitAction> {
    override val body: Reducer<Visit, VisitAction> =
        Reduce { action ->
            when (action) {
                is VisitAction.task -> state.log += "task ${action.id}"
                is VisitAction.select -> {
                    state.selected = action.id
                    state.log += "select ${action.id}"
                }
                VisitAction.hide -> {
                    state.isShown = false
                    state.log += "hide"
                }
                VisitAction.show -> {
                    state.isShown = true
                    state.log += "show"
                }
            }
            Effect.none
        }
}

@Composable
private fun VisitView(store: Store<Visit, VisitAction>) {
    if (!store.state.isShown) return
    store.task(id = store.state.selected) { store.send(VisitAction.task(store.state.selected)) }
    Text(store.state.selected)
}

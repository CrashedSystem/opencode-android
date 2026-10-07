package ai.opencode.mobilecode.tca

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RecompositionTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun tokenDeltasRecomposeOnlyTheirRow() {
        val store = Store(initialState = { Stream() }) { StreamFeature() }
        val compositions = HashMap<String, Int>()
        compose.setContent { StreamScreen(store, compositions) }
        compose.waitForIdle()
        val before = compositions.toMap()
        assertEquals(mapOf("drawer" to 1, "transcript" to 1, "row r1" to 1, "row r2" to 1, "row r3" to 1, "composer" to 1), before)

        repeat(120) { token ->
            compose.runOnIdle { store.send(StreamAction.delta("r2", "t$token ")) }
            compose.waitForIdle()
        }

        val text = (0 until 120).joinToString("") { "t$it " }
        assertEquals(text, store.state.items["r2"]?.liveText)
        compose.onNodeWithText(text).assertExists()
        assertEquals(before + ("row r2" to 121), compositions)
    }
}

class Stream : ObservableState() {
    val items = IdentifiedArray(listOf(StreamRow("r1", "Hello"), StreamRow("r2", ""), StreamRow("r3", "Later")))
    var draft by StoreVar("")
    var sessions by StoreVar(listOf("Fix the upload test", "Port the kit"))
}

class StreamRow(override val id: String, text: String) : ObservableState(), Identifiable<String> {
    var liveText by StoreVar(text)
}

sealed interface StreamAction : BindableAction<Stream> {
    data class binding(override val binding: BindingAction<Stream>) : StreamAction

    data class delta(val id: String, val text: String) : StreamAction
}

class StreamFeature : Reducer<Stream, StreamAction> {
    override val body: Reducer<Stream, StreamAction> =
        combine(
            BindingReducer(),
            Reduce { action ->
                when (action) {
                    is StreamAction.binding -> Effect.none
                    is StreamAction.delta -> {
                        state.items[action.id]?.liveText += action.text
                        Effect.none
                    }
                }
            },
        )
}

@Composable
private fun StreamScreen(store: Store<Stream, StreamAction>, compositions: HashMap<String, Int>) {
    Column(Modifier.fillMaxSize()) {
        DrawerRow(store, compositions)
        Transcript(store, compositions, Modifier.weight(1f))
        Composer(store, compositions)
    }
}

@Composable
private fun DrawerRow(store: Store<Stream, StreamAction>, compositions: HashMap<String, Int>) {
    SideEffect { compositions.merge("drawer", 1, Int::plus) }
    Text(store.state.sessions.first())
}

@Composable
private fun Transcript(store: Store<Stream, StreamAction>, compositions: HashMap<String, Int>, modifier: Modifier) {
    SideEffect { compositions.merge("transcript", 1, Int::plus) }
    LazyColumn(modifier) {
        items(store.state.items.elements, key = { it.id }) { row -> TranscriptRow(row, compositions) }
    }
}

@Composable
private fun TranscriptRow(row: StreamRow, compositions: HashMap<String, Int>) {
    SideEffect { compositions.merge("row ${row.id}", 1, Int::plus) }
    Text(row.liveText)
}

@Composable
private fun Composer(store: Store<Stream, StreamAction>, compositions: HashMap<String, Int>) {
    SideEffect { compositions.merge("composer", 1, Int::plus) }
    val draft = store.binding(Stream::draft, StreamAction::binding)
    BasicTextField(value = draft.wrappedValue, onValueChange = { draft.wrappedValue = it })
}

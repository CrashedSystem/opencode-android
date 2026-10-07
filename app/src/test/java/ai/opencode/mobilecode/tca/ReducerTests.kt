package ai.opencode.mobilecode.tca

import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test

class ReducerTests {
    @get:Rule
    val dependencies = TestDependencies()

    private val clock = TestClock()

    @Test
    fun scopeRunsWhereItIsDeclared() {
        val store = TestStore(initialState = { Always() }) { AlwaysFeature() }
        store.send(AlwaysAction.child(ChildAction.increment)) {
            it.child.count = 1
            it.observed = 1
        }
    }

    @Test
    fun dismissLetsTheParentReadTheChildThenClearsItAndCancelsItsEffects() {
        val store = TestStore(initialState = { Modal() }, withDependencies = { it.continuousClock = clock }) { ModalFeature() }
        store.send(ModalAction.child(PresentationAction.presented(ChildAction.increment))) { it.child!!.count = 1 }
        store.send(ModalAction.child(PresentationAction.presented(ChildAction.start(1))))
        clock.advance(by = 100.milliseconds)
        store.send(ModalAction.child(PresentationAction.dismiss)) {
            it.child = null
            it.observed = 1
        }
        clock.advance(by = 5.seconds)
    }

    @Test
    fun aBindingIsMatchedByItsField() {
        val store = TestStore(initialState = { Form() }) { FormFeature() }
        store.send(FormAction.binding(BindingAction.set(Form::draft, "Hel"))) {
            it.draft = "Hel"
            it.draftEdits = 1
            it.transitions = listOf(" -> Hel")
        }
        store.send(FormAction.binding(BindingAction.set(Form::isOn, true))) { it.isOn = true }
    }

    @Test
    fun onChangeRunsAfterTheBindingAndOnlyWhenTheValueChanged() {
        val store = TestStore(initialState = { Form() }) { FormFeature() }
        store.send(FormAction.binding(BindingAction.set(Form::draft, "Hello"))) {
            it.draft = "Hello"
            it.draftEdits = 1
            it.transitions = listOf(" -> Hello")
        }
        store.send(FormAction.binding(BindingAction.set(Form::draft, "Hello"))) { it.draftEdits = 2 }
    }

    @Test
    fun bindingActionsSentByEffectsCompareByFieldAndValue() {
        val store = TestStore(initialState = { Form() }) { FormFeature() }
        store.send(FormAction.typeForMe)
        store.receive(FormAction.binding(BindingAction.set(Form::draft, "typed"))) {
            it.draft = "typed"
            it.draftEdits = 1
            it.transitions = listOf(" -> typed")
        }
    }

    @Test
    fun aViewCannotWriteStateTheStoreOwns() {
        val store = TestStore(initialState = { Child() }) { ChildFeature() }
        store.send(ChildAction.increment) { it.count = 1 }
        val failure = assertThrows(IllegalStateException::class.java) { store.state.count = 5 }
        assertEquals("Wrote Child.count outside the reducer. Send an action.", failure.message)
        assertEquals(1, store.state.count)
    }

    @Test
    fun aTestCanSetUpStateBeforeAStoreOwnsIt() {
        val child = Child()
        child.count = 3
        val store = TestStore(initialState = { child }) { ChildFeature() }
        store.send(ChildAction.increment) { it.count = 4 }
    }

    @Test
    fun aPlainVarInAStateIsRejected() {
        val failure = assertThrows(IllegalStateException::class.java) { TestStore(initialState = { Leaky() }) { Reduce<Leaky, Unit> { Effect.none } } }
        assertEquals(
            "Leaky.count is a plain var. Declare it `by StoreVar(...)` so the store owns its writes.",
            failure.message,
        )
    }

    @Test
    fun identifiedArraySubscriptWritesTheDeltaToThatRow() {
        val store = TestStore(initialState = { Transcript() }) { TranscriptFeature() }
        store.send(TranscriptAction.delta("r1", "Hel")) { it.items.append(Row("r1").apply { liveText = "Hel" }) }
        store.send(TranscriptAction.delta("r1", "lo")) { it.items["r1"]!!.liveText = "Hello" }
        store.send(TranscriptAction.delta("r2", "Hi")) { it.items.append(Row("r2").apply { liveText = "Hi" }) }
        assertEquals(listOf("r1", "r2"), store.state.items.ids)
        assertEquals("Hello", store.state.items["r1"]?.liveText)
        assertThrows(IllegalStateException::class.java) { store.state.items.remove("r1") }
        assertThrows(IllegalStateException::class.java) { store.state.items["r1"]!!.liveText = "x" }
    }

    @Test
    fun identifiedArrayFollowsSwiftAppendAndSubscriptRules() {
        val array = IdentifiedArray<String, Row>()
        array.append(Row("a").apply { liveText = "first" })
        array.append(Row("a").apply { liveText = "duplicate" })
        array["b"] = Row("b")
        array.insert(Row("c"), at = 0)
        assertEquals(listOf("c", "a", "b"), array.ids)
        assertEquals("first", array["a"]?.liveText)
        array["a"] = null
        assertEquals(listOf("c", "b"), array.ids)
        assertNull(array["a"])
        assertThrows(IllegalArgumentException::class.java) { array["c"] = Row("d") }
    }

    @Test
    fun scopedStoresSendThroughTheParentAndReadTheChild() {
        val store = parentStore()
        val child = store.store.scope(Parent::child, ParentAction.child)
        child!!.send(ChildAction.increment)
        assertEquals(1, child.state.count)
        assertEquals(1, store.state.observed)
        assertEquals(child, store.store.scope(Parent::child, ParentAction.child))
        store.store.send(ParentAction.replace(null))
        assertNull(store.store.scope(Parent::child, ParentAction.child))
        assertEquals(1, child.state.count)
    }

    private fun parentStore() = LiveStore(Parent(), ParentFeature())
}

/** A store on the test thread with no exhaustive checks, for testing scoped stores the way views use them. */
class LiveStore<S, A>(initialState: S, reducer: Reducer<S, A>) {
    val store: Store<S, A> =
        Store(
            StoreRuntime.create(initialState, reducer, DependencyValues.current, kotlinx.coroutines.Dispatchers.Unconfined, { true }) { throw it },
            { initialState },
            { it },
            emptyList(),
        )

    val state: S get() = store.state
}

class Always : ObservableState() {
    var child by StoreVar(Child())
    var observed by StoreVar(0)
}

sealed interface AlwaysAction {
    data class child(val action: ChildAction) : AlwaysAction {
        companion object : Case<AlwaysAction, ChildAction>({ child(it) }, { (it as? child)?.action })
    }
}

class AlwaysFeature : Reducer<Always, AlwaysAction> {
    override val body: Reducer<Always, AlwaysAction> =
        combine(
            Scope(state = Always::child, action = AlwaysAction.child) { ChildFeature() },
            Reduce { _ ->
                state.observed = state.child.count
                Effect.none
            },
        )
}

class Modal : ObservableState() {
    var child by Presents(Child())
    var observed by StoreVar(-1)
}

sealed interface ModalAction {
    data class child(val action: PresentationAction<ChildAction>) : ModalAction {
        companion object : Case<ModalAction, PresentationAction<ChildAction>>({ child(it) }, { (it as? child)?.action })
    }
}

class ModalFeature : Reducer<Modal, ModalAction> {
    override val body: Reducer<Modal, ModalAction> =
        Reduce<Modal, ModalAction> { action ->
            if (action is ModalAction.child && action.action == PresentationAction.dismiss) state.observed = state.child!!.count
            Effect.none
        }
            .ifLet(Modal::child, ModalAction.child) { ChildFeature() }
}

class Form : ObservableState() {
    var draft by StoreVar("")
    var isOn by StoreVar(false)
    var draftEdits by StoreVar(0)
    var transitions by StoreVar(listOf<String>())
}

sealed interface FormAction : BindableAction<Form> {
    data class binding(override val binding: BindingAction<Form>) : FormAction

    data object typeForMe : FormAction
}

class FormFeature : Reducer<Form, FormAction> {
    override val body: Reducer<Form, FormAction> =
        combine(
            BindingReducer<Form, FormAction>()
                .onChange(of = Form::draft) { oldValue, newValue ->
                    Reduce { _ ->
                        state.transitions += "$oldValue -> $newValue"
                        Effect.none
                    }
                },
            Reduce { action ->
                when (action) {
                    is FormAction.binding if action.binding.keyPath == Form::draft -> {
                        state.draftEdits += 1
                        Effect.none
                    }
                    is FormAction.binding -> Effect.none
                    FormAction.typeForMe -> Effect.send(FormAction.binding(BindingAction.set(Form::draft, "typed")))
                }
            },
        )
}

class Leaky : ObservableState() {
    var count = 0
}

class Row(override val id: String) : ObservableState(), Identifiable<String> {
    var liveText by StoreVar("")
}

class Transcript : ObservableState() {
    val items = IdentifiedArray<String, Row>()
}

sealed interface TranscriptAction {
    data class delta(val id: String, val text: String) : TranscriptAction
}

class TranscriptFeature : Reducer<Transcript, TranscriptAction> {
    override val body: Reducer<Transcript, TranscriptAction> =
        Reduce { action ->
            when (action) {
                is TranscriptAction.delta -> {
                    if (state.items[action.id] == null) state.items.append(Row(action.id))
                    state.items[action.id]?.liveText += action.text
                    Effect.none
                }
            }
        }
}

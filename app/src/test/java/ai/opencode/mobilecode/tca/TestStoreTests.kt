package ai.opencode.mobilecode.tca

import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.Description
import org.junit.runners.model.Statement

class TestStoreTests {
    @get:Rule
    val dependencies = TestDependencies()

    @Test
    fun anUnassertedStateChangeFailsWithADiff() {
        val store = TestStore(initialState = { Child() }) { ChildFeature() }
        val failure = assertThrows(AssertionError::class.java) { store.send(ChildAction.increment) }
        assertEquals(
            """
            A state change does not match expectation after increment:
            (Expected: −, Actual: +)
              Child(
                id: a
            −   count: 0
            +   count: 1
                completions: []
              )
            """.trimIndent(),
            failure.message,
        )
    }

    @Test
    fun anAssertionOfAChangeThatDidNotHappenFails() {
        val store = TestStore(initialState = { Child() }) { ChildFeature() }
        assertThrows(AssertionError::class.java) { store.send(ChildAction.stop) { it.count = 1 } }
    }

    @Test
    fun anUnassertedSharedWriteFails() {
        val store = TestStore(initialState = { Toggle() }) { ToggleFeature() }
        val failure = assertThrows(AssertionError::class.java) { store.send(ToggleAction.flip) }
        assertTrue(failure.message!!.contains("−   isOn: false\n+   isOn: true"))
        store.send(ToggleAction.flip) { it::isOn.withLock { value = false } }
    }

    @Test
    fun aSharedWriteIsVisibleToEveryHolderWithoutAnAction() {
        val store = TestStore(initialState = { Toggle() }) { ToggleFeature() }
        val elsewhere = Toggle()
        store.send(ToggleAction.flip) { it::isOn.withLock { value = true } }
        expect(elsewhere.isOn, "another state holding the key sees the write")
        var isOn by Shared(SharedKey.toggle)
        expect(isOn)
        isOn = false
        expect(!store.state.isOn)
        store.assert { it::isOn.withLock { value = false } }
    }

    @Test
    fun stateShowsAnEffectsActionOnlyOnceItIsReceived() {
        val store = TestStore(initialState = { Log() }) { LogFeature() }
        store.send(LogAction.ordered)
        val afterSend = store.state
        assertEquals(emptyList<String>(), afterSend.entries)
        store.receive(LogAction.append("sent")) { it.entries = listOf("sent") }
        assertEquals(listOf("sent"), store.state.entries)
        store.receive(LogAction.append("run")) { it.entries = listOf("sent", "run") }
        assertEquals(listOf("sent", "run"), store.state.entries)
        assertEquals(emptyList<String>(), afterSend.entries)
    }

    @Test
    fun receivingByCaseIgnoresThePayload() {
        val store = TestStore(initialState = { Log() }) { LogFeature() }
        store.send(LogAction.ordered)
        store.receive<LogAction.append> { it.entries = listOf("sent") }
        store.receive({ it is LogAction.append && it.value == "run" }) { it.entries = listOf("sent", "run") }
    }

    @Test
    fun sendingWithUnreceivedActionsFails() {
        val store = TestStore(initialState = { Log() }) { LogFeature() }
        store.send(LogAction.ordered)
        val failure = assertThrows(AssertionError::class.java) { store.send(LogAction.ordered) }
        assertEquals("Must handle 2 received action(s) before sending an action:\n  append(value=sent)\n  append(value=run)", failure.message)
        store.skipReceivedActions()
    }

    @Test
    fun nonExhaustiveStoresCheckOnlyWhatTheTestAsserts() {
        val clock = TestClock()
        val store = TestStore(initialState = { Parent() }, withDependencies = { it.continuousClock = clock }) { ParentFeature() }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)
        store.send(ParentAction.child(ChildAction.increment)) { it.observed = 1 }
        store.send(ParentAction.child(ChildAction.start(7)))
        clock.advance(by = 1.seconds)
        store.send(ParentAction.child(ChildAction.increment))
        assertEquals(listOf(7), store.state.child!!.completions)
    }

    @Test
    fun nonExhaustiveAssertionsStartFromTheStateAfterTheAction() {
        val store = TestStore(initialState = { Parent() }) { ParentFeature() }
        store.exhaustivity = Exhaustivity.off
        val failure = assertThrows(AssertionError::class.java) { store.send(ParentAction.replace("b")) { it.child?.count = 5 } }
        assertTrue(failure.message!!.startsWith("An asserted value does not match after replace(id=b)"))
        store.send(ParentAction.replace("c")) { it.child?.count = 0 }
    }

    @Test
    fun aSharedWriteAnEffectMakesBetweenActionsMustBeAsserted() {
        val store = TestStore(initialState = { Toggle() }) { QuietToggleFeature() }
        store.send(QuietToggleAction.flipLater)
        store.finish()
        assertThrows(AssertionError::class.java) { store.send(QuietToggleAction.nothing) }
        store.send(QuietToggleAction.nothing) { it::isOn.withLock { value = true } }
    }

    @Test
    fun anUnassertedSharedWriteFromAnEffectFailsWhenTheTestEnds() {
        val failure =
            runRule {
                val store = TestStore(initialState = { Toggle() }) { QuietToggleFeature() }
                store.send(QuietToggleAction.flipLater)
                store.finish()
            }
        assertEquals("Shared state changed after the last assertion without being asserted: appStorage:toggle", failure?.message)
    }

    @Test
    fun aStoreWithAnUnreceivedActionFailsWhenTheTestEnds() {
        val failure = runRule { TestStore(initialState = { Log() }) { LogFeature() }.send(LogAction.ordered) }
        assertEquals("The store received 2 unasserted action(s):\n  append(value=sent)\n  append(value=run)", failure?.message)
    }

    @Test
    fun aStoreWithARunningEffectFailsWhenTheTestEnds() {
        val failure =
            runRule {
                val clock = TestClock()
                TestStore(initialState = { Child() }, withDependencies = { it.continuousClock = clock }) { ChildFeature() }
                    .send(ChildAction.start(1))
            }
        assertEquals("1 effect(s) still running at the end of the test. Finish or cancel them before it ends.", failure?.message)
    }

    @Test
    fun finishWaitsForEffectsThatDoRealAsynchronousWork() {
        val store = TestStore(initialState = { Log() }) { RealTimeLogFeature() }
        store.send(LogAction.ordered)
        store.receive(LogAction.append("woke")) { it.entries = listOf("woke") }
        store.finish()
    }

    private fun runRule(body: () -> Unit): Throwable? =
        runCatching {
            TestDependencies().apply(
                object : Statement() {
                    override fun evaluate() = body()
                },
                Description.EMPTY,
            ).evaluate()
        }.exceptionOrNull()
}

val SharedKey.Companion.toggle: SharedKey<Boolean>
    get() = SharedKey.appStorage("toggle", default = false)

class Toggle : ObservableState() {
    var isOn by Shared(SharedKey.toggle)
}

sealed interface ToggleAction {
    data object flip : ToggleAction
}

class ToggleFeature : Reducer<Toggle, ToggleAction> {
    override val body: Reducer<Toggle, ToggleAction> =
        Reduce { action ->
            when (action) {
                ToggleAction.flip -> {
                    state::isOn.withLock { value = !value }
                    Effect.none
                }
            }
        }
}

sealed interface QuietToggleAction {
    data object flipLater : QuietToggleAction

    data object nothing : QuietToggleAction
}

/** Flips the shared toggle from an effect without sending an action. */
class QuietToggleFeature : Reducer<Toggle, QuietToggleAction> {
    override val body: Reducer<Toggle, QuietToggleAction> =
        Reduce { action ->
            when (action) {
                QuietToggleAction.flipLater ->
                    Effect.run { _ ->
                        var isOn by Shared(SharedKey.toggle)
                        isOn = !isOn
                    }
                QuietToggleAction.nothing -> Effect.none
            }
        }
}

/** Sleeps on the wall clock, which a `TestClock` never advances, so the store must wait in real time. */
class RealTimeLogFeature : Reducer<Log, LogAction> {
    private val wallClock by Dependency(DependencyValues::wallClock)

    override val body: Reducer<Log, LogAction> =
        Reduce { action ->
            when (action) {
                LogAction.ordered ->
                    Effect.run { send ->
                        wallClock.sleep(50.milliseconds)
                        send(LogAction.append("woke"))
                    }
                is LogAction.append -> {
                    state.entries += action.value
                    Effect.none
                }
                else -> Effect.none
            }
        }
}

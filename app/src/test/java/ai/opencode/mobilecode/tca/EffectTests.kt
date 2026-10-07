package ai.opencode.mobilecode.tca

import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test

class EffectTests {
    @get:Rule
    val dependencies = TestDependencies()

    private val clock = TestClock()

    private fun parentStore() =
        TestStore(initialState = { Parent() }, withDependencies = { it.continuousClock = clock }) { ParentFeature() }

    @Test
    fun ifLetRunsTheChildBeforeTheParent() {
        val store = parentStore()
        store.send(ParentAction.child(ChildAction.increment)) {
            it.child!!.count = 1
            it.observed = 1
        }
    }

    @Test
    fun replacingTheChildWithAnotherIdentityCancelsEvenItsUnlabelledEffects() {
        val store = parentStore()
        store.send(ParentAction.child(ChildAction.start(1, labelled = false)))
        clock.advance(by = 100.milliseconds)
        store.send(ParentAction.replace("b")) { it.child = Child("b") }
        store.send(ParentAction.child(ChildAction.start(2, labelled = false)))
        clock.advance(by = 1.seconds)
        store.receive(ParentAction.child(ChildAction.finished(2))) { it.child!!.completions = listOf(2) }
    }

    @Test
    fun keepingTheChildIdentityKeepsItsEffects() {
        val store = parentStore()
        store.send(ParentAction.child(ChildAction.start(4)))
        clock.advance(by = 100.milliseconds)
        store.send(ParentAction.replace("a")) { it.child = Child("a") }
        clock.advance(by = 1.seconds)
        store.receive(ParentAction.child(ChildAction.finished(4))) { it.child!!.completions = listOf(4) }
    }

    @Test
    fun clearingOneChildLeavesItsSiblingRunning() {
        val store = parentStore()
        store.send(ParentAction.child(ChildAction.start(1)))
        store.send(ParentAction.other(ChildAction.start(3)))
        clock.advance(by = 100.milliseconds)
        store.send(ParentAction.replace(null)) { it.child = null }
        clock.advance(by = 1.seconds)
        store.receive(ParentAction.other(ChildAction.finished(3))) { it.other!!.completions = listOf(3) }
    }

    @Test
    fun replacingAMiddleFeatureCancelsItsGrandchildsEffects() {
        val store = TestStore(initialState = { Grand() }, withDependencies = { it.continuousClock = clock }) { GrandFeature() }
        store.send(leafStart(1))
        clock.advance(by = 100.milliseconds)
        store.send(GrandAction.replaceMiddle("two")) { it.middle = Middle("two") }
        clock.advance(by = 5.seconds)
        store.send(leafStart(2))
        clock.advance(by = 1.seconds)
        store.receive(leafFinished(2)) { it.middle!!.child!!.completions = listOf(2) }
    }

    @Test
    fun anAncestorCancelsADescendantsIDTwoLevelsDown() {
        val store = TestStore(initialState = { Grand() }, withDependencies = { it.continuousClock = clock }) { GrandFeature() }
        store.send(leafStart(1))
        clock.advance(by = 100.milliseconds)
        store.send(GrandAction.cancelTimers)
        clock.advance(by = 5.seconds)
        store.send(leafStart(5))
        clock.advance(by = 1.seconds)
        store.receive(leafFinished(5)) { it.middle!!.child!!.completions = listOf(5) }
    }

    @Test
    fun cancelInFlightReplacesTheRunningTimer() {
        val store = TestStore(initialState = { Child() }, withDependencies = { it.continuousClock = clock }) { ChildFeature() }
        store.send(ChildAction.start(1))
        clock.advance(by = 500.milliseconds)
        store.send(ChildAction.start(2))
        clock.advance(by = 500.milliseconds)
        expect(store.state.completions.isEmpty(), "the first timer was cancelled at 500 ms")
        clock.advance(by = 500.milliseconds)
        store.receive(ChildAction.finished(2)) { it.completions = listOf(2) }
    }

    @Test
    fun aCancelledEffectCannotSendThroughNonCancellableClientCode() {
        val store = TestStore(initialState = { Child() }, withDependencies = { it.continuousClock = clock }) { ChildFeature() }
        store.send(ChildAction.nonCooperative)
        clock.advance(by = 100.milliseconds)
        store.send(ChildAction.stop)
        clock.advance(by = 5.seconds)
        store.send(ChildAction.start(6))
        clock.advance(by = 1.seconds)
        store.receive(ChildAction.finished(6)) { it.completions = listOf(6) }
    }

    @Test
    fun mergedEffectsRunConcurrentlyEvenWhenCancellable() {
        val store = TestStore(initialState = { Log() }, withDependencies = { it.continuousClock = clock }) { LogFeature() }
        store.send(LogAction.merged)
        clock.advance(by = 1.seconds)
        store.receive(LogAction.append("a")) { it.entries = listOf("a") }
        store.receive(LogAction.append("b")) { it.entries = listOf("a", "b") }
        assertEquals(1.seconds, clock.now)
    }

    @Test
    fun aSentActionArrivesBeforeARunEffectStarts() {
        val store = TestStore(initialState = { Log() }, withDependencies = { it.continuousClock = clock }) { LogFeature() }
        store.send(LogAction.ordered)
        store.receive(LogAction.append("sent")) { it.entries = listOf("sent") }
        store.receive(LogAction.append("run")) { it.entries = listOf("sent", "run") }
    }

    @Test
    fun cancellingTheProducerLeavesTheEffectOfItsResponseRunning() {
        val store = TestStore(initialState = { Log() }, withDependencies = { it.continuousClock = clock }) { LogFeature() }
        store.send(LogAction.stream)
        store.receive(LogAction.response("a")) { it.entries = listOf("a") }
        store.send(LogAction.stopStream)
        clock.advance(by = 1.seconds)
        store.receive(LogAction.append("settled a")) { it.entries = listOf("a", "settled a") }
        clock.advance(by = 10.seconds)
    }

    @Test
    fun cancellingASendsTaskStopsTheEffectsOfActionsItsEffectsSent() {
        val store = TestStore(initialState = { Log() }, withDependencies = { it.continuousClock = clock }) { LogFeature() }
        val task = store.send(LogAction.stream)
        store.receive(LogAction.response("a")) { it.entries = listOf("a") }
        task.cancel()
        expect(task.isCancelled)
        clock.advance(by = 20.seconds)
    }

    @Test
    fun anEffectThatThrowsFailsTheTest() {
        val store = TestStore(initialState = { Log() }) { LogFeature() }
        val failure = assertThrows(AssertionError::class.java) { store.send(LogAction.explode) }
        assertEquals("boom", failure.cause?.message)
    }

    @Test
    fun aCatchHandlerTurnsTheErrorIntoAnAction() {
        val store = TestStore(initialState = { Log() }) { LogFeature() }
        store.send(LogAction.explodeAndCatch)
        store.receive(LogAction.append("caught boom")) { it.entries = listOf("caught boom") }
    }

    @Test
    fun readingStateInsideAnEffectThrows() {
        val store = TestStore(initialState = { Log() }) { LogFeature() }
        store.send(LogAction.readStateInEffect)
        store.receive(LogAction.append("Read Log.entries from an effect. Copy the value into a local before Effect.run.")) {
            it.entries = listOf("Read Log.entries from an effect. Copy the value into a local before Effect.run.")
        }
    }

    @Test
    fun aDependencyWithoutATestValueFailsClosed() {
        val store = TestStore(initialState = { Child() }) { ChildFeature() }
        val failure = assertThrows(AssertionError::class.java) { store.send(ChildAction.start(1)) }
        assertEquals(UnimplementedDependency::class.java, failure.cause?.javaClass)
        store.skipInFlightEffects()
    }
}

class Log : ObservableState() {
    var entries by StoreVar(listOf<String>())
}

sealed interface LogAction {
    data object merged : LogAction

    data object ordered : LogAction

    data object stream : LogAction

    data object stopStream : LogAction

    data class response(val value: String) : LogAction

    data class append(val value: String) : LogAction

    data object explode : LogAction

    data object explodeAndCatch : LogAction

    data object readStateInEffect : LogAction
}

enum class LogCancelID {
    merged,
    stream,
}

class LogFeature : Reducer<Log, LogAction> {
    private val clock by Dependency(DependencyValues::continuousClock)

    override val body: Reducer<Log, LogAction> =
        Reduce { action ->
            when (action) {
                LogAction.merged ->
                    Effect.merge(
                        Effect.run { send ->
                            clock.sleep(1.seconds)
                            send(LogAction.append("a"))
                        },
                        Effect.run { send ->
                            clock.sleep(1.seconds)
                            send(LogAction.append("b"))
                        },
                    ).cancellable(LogCancelID.merged)
                LogAction.ordered ->
                    Effect.merge(
                        Effect.run { send -> send(LogAction.append("run")) },
                        Effect.send(LogAction.append("sent")),
                    )
                LogAction.stream ->
                    Effect.run { send ->
                        send(LogAction.response("a"))
                        clock.sleep(10.seconds)
                        send(LogAction.response("never"))
                    }.cancellable(LogCancelID.stream)
                LogAction.stopStream -> Effect.cancel(LogCancelID.stream)
                is LogAction.response -> {
                    state.entries += action.value
                    Effect.run { send ->
                        clock.sleep(1.seconds)
                        send(LogAction.append("settled ${action.value}"))
                    }
                }
                is LogAction.append -> {
                    state.entries += action.value
                    Effect.none
                }
                LogAction.explode -> Effect.run { throw IllegalStateException("boom") }
                LogAction.explodeAndCatch ->
                    Effect.run(catch = { error, send -> send(LogAction.append("caught ${error.message}")) }) {
                        throw IllegalStateException("boom")
                    }
                LogAction.readStateInEffect -> {
                    val captured = state
                    Effect.run { send ->
                        val message = runCatching { captured.entries }.exceptionOrNull()?.message ?: "read succeeded"
                        send(LogAction.append(message))
                    }
                }
            }
        }
}

package ai.opencode.mobilecode.tca

import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test

class GuardTests {
    @get:Rule
    val dependencies = TestDependencies()

    private val clock = TestClock()

    @Test
    fun aStoreOwnsItsWholeStateFromTheStart() {
        val store = TestStore(initialState = { Parent() }) { ParentFeature() }
        val failure = assertThrows(IllegalStateException::class.java) { store.state.child!!.count = 5 }
        assertEquals("Wrote Child.count outside the reducer. Send an action.", failure.message)
    }

    @Test
    fun aChildAssignedInTheReducerJoinsTheStore() {
        val store = TestStore(initialState = { Parent() }) { ParentFeature() }
        store.send(ParentAction.replace("b")) { it.child = Child("b") }
        assertThrows(IllegalStateException::class.java) { store.state.child!!.count = 5 }
    }

    @Test
    fun printingStateInsideAnEffectThrows() {
        val store = TestStore(initialState = { Cleanup() }, withDependencies = { it.continuousClock = clock }) { CleanupFeature() }
        store.send(CleanupAction.describe)
        store.receive(CleanupAction.described("Read Cleanup from an effect. Copy the values into locals before Effect.run."))
    }

    @Test
    fun anEffectStillCleaningUpAfterCancellationCountsAsRunning() {
        val store = TestStore(initialState = { Cleanup() }, withDependencies = { it.continuousClock = clock }) { CleanupFeature() }
        store.send(CleanupAction.start)
        store.send(CleanupAction.stop)
        assertThrows(AssertionError::class.java) { store.finish(timeout = 50.milliseconds) }
        clock.advance(by = 1.seconds)
        store.finish()
    }
}

class Cleanup : ObservableState() {
    var count by StoreVar(0)
}

sealed interface CleanupAction {
    data object start : CleanupAction

    data object stop : CleanupAction

    data object describe : CleanupAction

    data class described(val message: String) : CleanupAction
}

enum class CleanupID {
    work,
}

class CleanupFeature : Reducer<Cleanup, CleanupAction> {
    private val clock by Dependency(DependencyValues::continuousClock)

    override val body: Reducer<Cleanup, CleanupAction> =
        Reduce { action ->
            when (action) {
                CleanupAction.start ->
                    Effect.run<CleanupAction> { _ ->
                        try {
                            clock.sleep(10.seconds)
                        } finally {
                            withContext(NonCancellable) { clock.sleep(1.seconds) }
                        }
                    }.cancellable(CleanupID.work)
                CleanupAction.stop -> Effect.cancel(CleanupID.work)
                CleanupAction.describe -> {
                    val captured = state
                    Effect.run { send ->
                        send(CleanupAction.described(runCatching { captured.toString() }.exceptionOrNull()?.message ?: "printed"))
                    }
                }
                is CleanupAction.described -> Effect.none
            }
        }
}

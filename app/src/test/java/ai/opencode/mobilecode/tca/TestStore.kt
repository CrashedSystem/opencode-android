package ai.opencode.mobilecode.tca

import java.util.IdentityHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.suspendCancellableCoroutine
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/** `#expect(condition, "comment")` */
fun expect(condition: Boolean, comment: String? = null) {
    if (!condition) throw AssertionError(comment ?: "Expectation failed")
}

/**
 * `TestStore(initialState: State()) { Feature() } withDependencies: { … }`.
 *
 * Exhaustive by default. Each `send` and `receive` compares the whole state, shared values included,
 * against the state before that action as changed by the trailing closure, so a change the test does
 * not assert fails. Actions sent by effects must be received in order. When the test ends,
 * [TestDependencies] fails a store with unreceived actions or effects still running.
 */
class TestStore<S, A>(
    initialState: () -> S,
    withDependencies: (DependencyValues) -> Unit = {},
    reducer: () -> Reducer<S, A>,
) {
    var exhaustivity: Exhaustivity = Exhaustivity.on

    /** How long `receive` and `finish` wait for an effect doing real asynchronous work. */
    var timeout: Duration = 1.seconds

    val state: S

    private val context = checkNotNull(TestDependencies.current.get()) {
        "A TestStore needs `@get:Rule val dependencies = TestDependencies()` in the test class."
    }
    private val loop = context.loop
    private val dependencies = context.values.child().also(withDependencies)
    private val runtime: StoreRuntime
    private val received = ArrayDeque<Reduction<A>>()
    private val errors = mutableListOf<Throwable>()
    private var sent: Reduction<A>? = null
    private var asserted: Map<Cell<*>, Any?> = emptyMap()

    private class Reduction<A>(val action: A, val before: Map<Cell<*>, Any?>, val after: Map<Cell<*>, Any?>)

    init {
        state = StoreContext.run(null, dependencies) { initialState() }
        runtime =
            StoreRuntime.create(
                initialState = state,
                reducer = StoreContext.run(null, dependencies, reducer),
                dependencies = dependencies,
                dispatcher = loop.dispatcher,
                isStoreThread = { Thread.currentThread() === loop.thread },
                onEffectError = { errors += it },
            )
        runtime.observer =
            object : ReductionObserver {
                private var before: Map<Cell<*>, Any?> = emptyMap()

                override fun willReduce(action: Any?, fromEffect: Boolean) {
                    before = capture()
                }

                @Suppress("UNCHECKED_CAST")
                override fun didReduce(action: Any?, fromEffect: Boolean) {
                    val reduction = Reduction(action as A, before, capture())
                    if (fromEffect) received.addLast(reduction) else sent = reduction
                }
            }
        asserted = capture()
        context.register(this)
    }

    /** `await store.send(.action) { $0.field = value }` */
    fun send(action: A, assert: ((S) -> Unit)? = null): TestStoreTask {
        if (exhaustivity == Exhaustivity.on && received.isNotEmpty()) {
            fail("Must handle ${received.size} received action(s) before sending an action:\n${describe(received)}")
        }
        received.clear()
        val task = runtime.send(action)
        val reduction = checkNotNull(sent)
        sent = null
        assertReduction(reduction, assert)
        runEffects()
        return TestStoreTask(task, this)
    }

    /** `await store.receive(.action) { $0.field = value }` */
    fun receive(action: A, assert: ((S) -> Unit)? = null) = receive("$action", { it == action }, assert)

    /** `await store.receive(\.response) { … }`, matching the case and ignoring its payload. */
    inline fun <reified C : A> receive(noinline assert: ((S) -> Unit)? = null) =
        receive(C::class.java.name.substringAfterLast('.').replace('$', '.'), { it is C }, assert)

    /** `await store.receive(\.child.response) { … }` with a predicate for a nested case. */
    fun receive(matching: (A) -> Boolean, assert: ((S) -> Unit)? = null) = receive("a matching action", matching, assert)

    @PublishedApi
    internal fun receive(description: String, matching: (A) -> Boolean, assert: ((S) -> Unit)?) {
        val reduction = awaitReceived(description, matching)
        if (!matching(reduction.action)) fail("Received an unexpected action:\n  ${reduction.action}\nExpected:\n  $description")
        assertReduction(reduction, assert)
        runEffects()
    }

    /** `store.assert { $0.field = value }`, checked against the current state. */
    fun assert(assert: (S) -> Unit) {
        runEffects()
        val now = capture()
        assertReduction(Reduction(null, now, now), assert, "assert")
    }

    /** `await store.finish()`: waits for every effect to finish. A non-exhaustive store stops waiting quietly, as TCA's does. */
    fun finish(timeout: Duration = this.timeout) {
        if (!await(timeout) { runtime.inFlightCount == 0 } && exhaustivity == Exhaustivity.on) {
            fail("Expected effects to finish, but ${runtime.inFlightCount} are still running after $timeout.")
        }
    }

    /** `await store.skipReceivedActions()` */
    fun skipReceivedActions() {
        runEffects()
        received.clear()
        asserted = capture()
    }

    /** `await store.skipInFlightEffects()`: cancels every running effect. */
    fun skipInFlightEffects() {
        runtime.cancelAll()
        runEffects()
    }

    internal fun runEffects() {
        loop.runUntilIdle()
        if (errors.isNotEmpty()) {
            val error = errors.removeAt(0)
            throw AssertionError("An effect threw: $error", error)
        }
    }

    internal fun await(timeout: Duration, done: () -> Boolean): Boolean {
        val deadline = TimeSource.Monotonic.markNow() + timeout
        while (true) {
            runEffects()
            if (done()) return true
            if (deadline.hasPassedNow()) return false
            loop.awaitWork(-deadline.elapsedNow())
        }
    }

    internal fun completed() {
        runEffects()
        if (exhaustivity != Exhaustivity.on) return
        if (received.isNotEmpty()) fail("The store received ${received.size} unasserted action(s):\n${describe(received)}")
        if (runtime.inFlightCount > 0) {
            fail("${runtime.inFlightCount} effect(s) still running at the end of the test. Finish or cancel them before it ends.")
        }
        val changed = asserted.filter { (cell, value) -> cell.isShared && cell.live != value }.keys
        if (changed.isNotEmpty()) {
            fail("Shared state changed after the last assertion without being asserted: ${changed.joinToString { it.label }}")
        }
    }

    internal fun close() {
        runtime.cancelAll()
        loop.runUntilIdle()
        runtime.close()
    }

    private fun awaitReceived(description: String, matching: (A) -> Boolean): Reduction<A> {
        val deadline = TimeSource.Monotonic.markNow() + timeout
        while (true) {
            runEffects()
            if (exhaustivity != Exhaustivity.on) {
                while (received.isNotEmpty() && !matching(received.first().action)) received.removeFirst()
            }
            received.removeFirstOrNull()?.let { return it }
            if (deadline.hasPassedNow() || !loop.awaitWork(-deadline.elapsedNow())) {
                runEffects()
                received.removeFirstOrNull()?.let { return it }
                fail("Expected to receive $description, but received none after $timeout.")
            }
        }
    }

    private fun capture(): Map<Cell<*>, Any?> {
        val cells = IdentityHashMap<Cell<*>, Any?>()
        StateGraph.normalize(state) { cell -> cell.live.also { cells[cell] = it } }
        return cells
    }

    /**
     * An exhaustive assertion starts from the state before the action, with shared values as the last
     * assertion left them, so a shared write made between actions has to be asserted too. A
     * non-exhaustive one starts from the state after the action, as TCA's does.
     */
    private fun assertReduction(reduction: Reduction<*>, assert: ((S) -> Unit)?, label: String = "${reduction.action}") {
        val exhaustive = exhaustivity == Exhaustivity.on
        val seed =
            if (exhaustive) {
                IdentityHashMap(reduction.before).also { base ->
                    for ((cell, value) in asserted) if (cell.isShared && base.containsKey(cell)) base[cell] = value
                }
            } else {
                reduction.after
            }
        val overlay = Overlay(seed)
        if (assert != null) {
            val previous = Overlay.current.get()
            Overlay.current.set(overlay)
            try {
                StoreContext.run(null, dependencies) { assert(state) }
            } finally {
                Overlay.current.set(previous)
            }
        }
        val expectedRead: (Cell<*>) -> Any? = overlay::read
        val actualRead: (Cell<*>) -> Any? = { cell -> if (reduction.after.containsKey(cell)) reduction.after[cell] else cell.live }
        if (exhaustive) {
            val expected = StateGraph.normalize(state, expectedRead)
            val actual = StateGraph.normalize(state, actualRead)
            if (expected != actual) fail("A state change does not match expectation after $label:\n${diff(expected, actual)}")
        } else {
            for (cell in overlay.written) {
                if (!reduction.after.containsKey(cell)) continue
                val expected = StateGraph.normalize(overlay.read(cell), expectedRead)
                val actual = StateGraph.normalize(reduction.after[cell], actualRead)
                if (expected != actual) fail("An asserted value does not match after $label:\n${diff(expected, actual)}")
            }
        }
        asserted = reduction.after
    }

    private fun describe(reductions: Collection<Reduction<A>>) = reductions.joinToString("\n") { "  ${it.action}" }
}

/** The task of one `send`. `await task.cancel()` stops every effect that action started. */
class TestStoreTask internal constructor(private val task: StoreTask, private val store: TestStore<*, *>) {
    val isCancelled: Boolean
        get() = task.isCancelled

    fun cancel() {
        task.cancel()
        store.runEffects()
    }

    fun finish(timeout: Duration = store.timeout) {
        if (!store.await(timeout) { !task.isActive }) fail("Expected the task's effects to finish within $timeout.")
    }
}

/** `store.exhaustivity = .on`, `.off`, or `.off(showSkippedAssertions: false)` */
sealed interface Exhaustivity {
    data object on : Exhaustivity

    data class off(val showSkippedAssertions: Boolean = true) : Exhaustivity {
        companion object : Exhaustivity
    }
}

/**
 * `TestClock()`. A sleep waits until the test advances past it, and the woken effects run before
 * `advance` returns. Install it with `withDependencies = { it.continuousClock = clock }`.
 */
class TestClock : Clock {
    private class Sleeper(val deadline: Duration, val order: Long, val continuation: CancellableContinuation<Unit>)

    private val loop: TestMainLoop
        get() =
            checkNotNull(TestDependencies.current.get()) {
                "A TestClock needs `@get:Rule val dependencies = TestDependencies()` in the test class."
            }.loop
    private val sleepers = mutableListOf<Sleeper>()
    private var order = 0L

    var now: Duration = Duration.ZERO
        private set

    override suspend fun sleep(duration: Duration) {
        if (!duration.isPositive()) return
        suspendCancellableCoroutine { continuation ->
            val sleeper = synchronized(sleepers) { Sleeper(now + duration, order++, continuation).also(sleepers::add) }
            continuation.invokeOnCancellation { synchronized(sleepers) { sleepers.remove(sleeper) } }
        }
    }

    /** `await clock.advance(by: .seconds(1))` */
    fun advance(by: Duration = Duration.ZERO) {
        val target = now + by
        loop.runUntilIdle()
        while (true) {
            val next =
                synchronized(sleepers) {
                    sleepers
                        .filter { it.deadline <= target }
                        .minWithOrNull(compareBy<Sleeper>({ it.deadline }, { it.order }))
                        ?.also(sleepers::remove)
                } ?: break
            now = next.deadline
            next.continuation.resume(Unit)
            loop.runUntilIdle()
        }
        now = target
    }

    /** `await clock.run()`: advances until no sleep is waiting. */
    fun run() {
        while (true) {
            val last = synchronized(sleepers) { sleepers.maxOfOrNull { it.deadline } } ?: return
            advance(by = last - now)
        }
    }
}

/** `ImmediateClock()`. Every sleep returns at once. */
class ImmediateClock : Clock {
    override suspend fun sleep(duration: Duration) = Unit
}

/**
 * Installs test dependencies on the test thread for each test: `isTesting`, fail-closed clients, and
 * shared storage that starts empty. It checks every `TestStore` the test created when the test ends,
 * the way a Swift `TestStore` checks itself when it deinitializes.
 */
class TestDependencies : TestRule {
    internal val values = DependencyValues.test()
    internal lateinit var loop: TestMainLoop
    private val stores = mutableListOf<TestStore<*, *>>()

    internal fun register(store: TestStore<*, *>) {
        stores += store
    }

    override fun apply(base: Statement, description: Description): Statement =
        object : Statement() {
            override fun evaluate() {
                loop = TestMainLoop()
                val previous = current.get()
                val previousDependencies = StoreContext.dependencies.get()
                current.set(this@TestDependencies)
                StoreContext.dependencies.set(values)
                try {
                    base.evaluate()
                    stores.forEach { it.completed() }
                } finally {
                    stores.forEach { it.close() }
                    StoreContext.dependencies.set(previousDependencies)
                    current.set(previous)
                }
            }
        }

    internal companion object {
        val current = ThreadLocal<TestDependencies?>()
    }
}

/** The test's main thread. Effects run on it, one at a time, whenever the test store or clock lets them. */
internal class TestMainLoop {
    val thread: Thread = Thread.currentThread()
    private val queue = LinkedBlockingQueue<Runnable>()

    val dispatcher =
        object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                queue.put(block)
            }
        }

    fun runUntilIdle() {
        while (true) (queue.poll() ?: return).run()
    }

    fun awaitWork(timeout: Duration): Boolean {
        val task = queue.poll(timeout.inWholeMilliseconds.coerceAtLeast(1), TimeUnit.MILLISECONDS) ?: return false
        task.run()
        return true
    }
}

private fun fail(message: String): Nothing = throw AssertionError(message)

private fun diff(expected: Any?, actual: Any?): String {
    val old = StateGraph.render(expected)
    val new = StateGraph.render(actual)
    val lengths = Array(old.size + 1) { IntArray(new.size + 1) }
    for (i in old.indices.reversed()) {
        for (j in new.indices.reversed()) {
            lengths[i][j] = if (old[i] == new[j]) lengths[i + 1][j + 1] + 1 else maxOf(lengths[i + 1][j], lengths[i][j + 1])
        }
    }
    val lines = mutableListOf("(Expected: −, Actual: +)")
    var i = 0
    var j = 0
    while (i < old.size || j < new.size) {
        when {
            i < old.size && j < new.size && old[i] == new[j] -> lines += "  ${old[i++]}".also { j++ }
            i < old.size && (j == new.size || lengths[i + 1][j] >= lengths[i][j + 1]) -> lines += "− ${old[i++]}"
            else -> lines += "+ ${new[j++]}"
        }
    }
    return lines.joinToString("\n")
}

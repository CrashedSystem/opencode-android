package ai.opencode.mobilecode.tca

import androidx.compose.runtime.SnapshotMutationPolicy
import androidx.compose.runtime.mutableStateOf
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty
import kotlinx.coroutines.ThreadContextElement

/**
 * The base of every feature `State`, the Kotlin form of `@ObservableState struct State`.
 *
 * A stored property is a `StoreVar`, a `Shared`, an `IdentifiedArray`, or an immutable `val`.
 * A plain `var` fails the first time the state is compared or snapshotted. Equality is
 * structural, like the synthesized `Equatable` of the Swift struct.
 */
abstract class ObservableState {
    internal var attached = false

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ObservableState || other.javaClass != javaClass) return false
        checkReadable()
        other.checkReadable()
        return StateGraph.normalize(this, Cell<*>::read) == StateGraph.normalize(other, Cell<*>::read)
    }

    override fun hashCode(): Int {
        checkReadable()
        return StateGraph.normalize(this, Cell<*>::read).hashCode()
    }

    override fun toString(): String {
        checkReadable()
        return StateGraph.render(StateGraph.normalize(this, Cell<*>::read)).joinToString("\n")
    }

    private fun checkReadable() {
        if (Overlay.current.get() == null && StoreContext.phase.get() == Phase.Effect) {
            check(!attached) { "Read ${typeName(javaClass)} from an effect. Copy the values into locals before Effect.run." }
        }
    }
}

/**
 * A stored property of a state, `var draft by StoreVar("")`.
 *
 * Once a store owns the state, only the reducer writes it. A write from a view or an effect
 * throws, and so does a read from an effect: copy the value into a local before `Effect.run`.
 * A state assigned in the reducer joins the store's graph, children included.
 * Compose observes each `StoreVar` on its own, so a token written to one row invalidates
 * only the composables that read that row.
 */
class StoreVar<T>(initial: T) : ReadWriteProperty<ObservableState, T> {
    internal val cell = Cell(initial)

    override fun getValue(thisRef: ObservableState, property: KProperty<*>): T {
        if (Overlay.current.get() == null && StoreContext.phase.get() == Phase.Effect) {
            check(!thisRef.attached) { readFromEffect(thisRef, property.name) }
        }
        return cell.read()
    }

    override fun setValue(thisRef: ObservableState, property: KProperty<*>, value: T) {
        if (Overlay.current.get() == null && thisRef.attached) {
            check(StoreContext.phase.get() == Phase.Reduce) {
                "Wrote ${typeName(thisRef.javaClass)}.${property.name} outside the reducer. Send an action."
            }
            attachGraph(value)
        }
        cell.write(value)
    }
}

/** `@Presents var destination: Destination.State?`, an optional child presented by `ifLet`. */
@Suppress("FunctionName")
fun <T : Any> Presents(initial: T? = null): StoreVar<T?> = StoreVar(initial)

internal enum class Phase {
    Reduce,
    Effect,
}

internal object StoreContext {
    val phase = ThreadLocal<Phase?>()
    val dependencies = ThreadLocal<DependencyValues?>()

    inline fun <R> run(phase: Phase?, dependencies: DependencyValues?, block: () -> R): R {
        val previousPhase = this.phase.get()
        val previousDependencies = this.dependencies.get()
        this.phase.set(phase)
        this.dependencies.set(dependencies)
        try {
            return block()
        } finally {
            this.phase.set(previousPhase)
            this.dependencies.set(previousDependencies)
        }
    }
}

/** Runs an effect's coroutine with the store's dependencies and the effect phase, on any thread. */
internal class EffectContext(private val dependencies: DependencyValues) : ThreadContextElement<EffectContext.Saved> {
    class Saved(val phase: Phase?, val dependencies: DependencyValues?)

    companion object Key : CoroutineContext.Key<EffectContext>

    override val key: CoroutineContext.Key<*> get() = Key

    override fun updateThreadContext(context: CoroutineContext): Saved {
        val saved = Saved(StoreContext.phase.get(), StoreContext.dependencies.get())
        StoreContext.phase.set(Phase.Effect)
        StoreContext.dependencies.set(dependencies)
        return saved
    }

    override fun restoreThreadContext(context: CoroutineContext, oldState: Saved) {
        StoreContext.phase.set(oldState.phase)
        StoreContext.dependencies.set(oldState.dependencies)
    }
}

/** One observable slot. Reads and writes go to the expectation overlay while a `TestStore` asserts. */
internal open class Cell<T>(initial: T) {
    @Suppress("UNCHECKED_CAST")
    private val state = mutableStateOf(initial, StoreEquality as SnapshotMutationPolicy<T>)

    val live: T get() = state.value

    /** Names the cell in a test failure. A shared cell names its key. */
    open val label: String get() = "a state value"

    open val isShared: Boolean get() = false

    @Suppress("UNCHECKED_CAST")
    fun read(): T {
        val overlay = Overlay.current.get() ?: return state.value
        return overlay.read(this) as T
    }

    fun write(value: T) {
        val overlay = Overlay.current.get()
        if (overlay != null) {
            overlay.write(this, value)
            return
        }
        val changed = !StoreEquality.equivalent(state.value, value)
        state.value = value
        if (changed) didWrite(value)
    }

    protected open fun didWrite(value: T) {}
}

/** Values compare by `==`. States and collections compare by identity, so replacing one always lands. */
internal object StoreEquality : SnapshotMutationPolicy<Any?> {
    override fun equivalent(a: Any?, b: Any?): Boolean =
        a === b ||
            a !is ObservableState &&
            a !is IdentifiedArray<*, *> &&
            a !is Collection<*> &&
            a !is Map<*, *> &&
            a == b
}

/** The copy an expected-state closure mutates: every cell's value before the action, plus the closure's writes. */
internal class Overlay(seed: Map<Cell<*>, Any?>) {
    private val values = IdentityHashMap<Cell<*>, Any?>(seed)
    val written: MutableSet<Cell<*>> = Collections.newSetFromMap(IdentityHashMap())

    fun read(cell: Cell<*>): Any? = if (values.containsKey(cell)) values[cell] else cell.live

    fun write(cell: Cell<*>, value: Any?) {
        values[cell] = value
        written.add(cell)
    }

    companion object {
        val current = ThreadLocal<Overlay?>()
    }
}

/** Marks a value and every state reachable from it as owned by a store. Views and effects can no longer write them. */
internal fun attachGraph(value: Any?) {
    when (value) {
        is ObservableState -> {
            if (value.attached) return
            value.attached = true
            StateGraph.values(value, Cell<*>::live).forEach { (_, field) -> attachGraph(field) }
        }
        is IdentifiedArray<*, *> -> {
            if (value.attached) return
            value.attached = true
            value.cell.live.elements.forEach(::attachGraph)
        }
        is Collection<*> -> value.forEach(::attachGraph)
        is Map<*, *> -> value.values.forEach(::attachGraph)
    }
}

private fun readFromEffect(state: Any, name: String) =
    "Read ${typeName(state.javaClass)}.$name from an effect. Copy the value into a local before Effect.run."

internal fun typeName(type: Class<*>): String = type.name.substringAfterLast('.').replace('$', '.')

/** A state's stored properties, found from its class layout, and a value tree for comparing and printing. */
internal object StateGraph {
    data class Node(val type: Class<*>, val fields: List<Pair<String, Any?>>)

    data class Elements(val elements: List<Any?>)

    private val layouts = ConcurrentHashMap<Class<*>, List<Field>>()

    fun fields(type: Class<*>): List<Field> =
        layouts.getOrPut(type) {
            generateSequence<Class<*>>(type) { it.superclass }
                .takeWhile { it != ObservableState::class.java }
                .toList()
                .asReversed()
                .flatMap { owner -> owner.declaredFields.filter { !Modifier.isStatic(it.modifiers) && !it.isSynthetic } }
                .onEach { field ->
                    check(Modifier.isFinal(field.modifiers)) {
                        "${typeName(type)}.${field.name} is a plain var. Declare it `by StoreVar(...)` so the store owns its writes."
                    }
                    field.isAccessible = true
                }
        }

    fun values(state: ObservableState, read: (Cell<*>) -> Any?): List<Pair<String, Any?>> =
        fields(state.javaClass).map { field ->
            field.name.removeSuffix("\$delegate") to
                when (val raw = field.get(state)) {
                    is StoreVar<*> -> read(raw.cell)
                    is Shared<*> -> read(raw.cell)
                    else -> raw
                }
        }

    /** A value tree of [value]. Reading through a recording [read] also snapshots every reachable cell. */
    fun normalize(value: Any?, read: (Cell<*>) -> Any?): Any? =
        when (value) {
            is ObservableState -> Node(value.javaClass, values(value, read).map { (name, field) -> name to normalize(field, read) })
            is IdentifiedArray<*, *> -> Elements((read(value.cell) as IdentifiedStorage<*, *>).elements.map { normalize(it, read) })
            is List<*> -> value.map { normalize(it, read) }
            is Set<*> -> value.mapTo(LinkedHashSet()) { normalize(it, read) }
            is Map<*, *> -> value.entries.associate { normalize(it.key, read) to normalize(it.value, read) }
            else -> value
        }

    fun render(value: Any?, indent: String = ""): List<String> =
        when (value) {
            is Node ->
                listOf("${typeName(value.type)}(") +
                    value.fields.flatMap { (name, field) -> prefixFirst(render(field, "$indent  "), "$indent  $name: ") } +
                    listOf("$indent)")
            is Elements -> renderList(value.elements, "IdentifiedArray[", indent)
            is List<*> -> if (value.none { it is Node || it is Elements }) listOf(value.toString()) else renderList(value, "[", indent)
            else -> listOf(value.toString())
        }

    private fun renderList(values: List<Any?>, open: String, indent: String): List<String> =
        if (values.isEmpty()) {
            listOf("${open}]")
        } else {
            listOf(open) + values.flatMap { prefixFirst(render(it, "$indent  "), "$indent  ") } + listOf("$indent]")
        }

    private fun prefixFirst(lines: List<String>, prefix: String): List<String> =
        lines.mapIndexed { index, line -> if (index == 0) prefix + line else line }
}

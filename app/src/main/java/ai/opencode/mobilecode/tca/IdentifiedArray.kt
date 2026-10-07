package ai.opencode.mobilecode.tca

/** `Identifiable` */
interface Identifiable<out ID> {
    val id: ID
}

/**
 * `IdentifiedArrayOf<Element>`: ordered elements with lookup by ID. A state holds it as a `val`.
 *
 * Its contents change only in the reducer. An element can itself be a state, so
 * `state.items[id]?.liveText += delta` writes that one row's `StoreVar` and leaves the array alone:
 * only the composable that reads the row recomposes. Adding or removing rows invalidates readers
 * of [elements].
 */
class IdentifiedArray<ID, E : Identifiable<ID>>(elements: List<E> = emptyList()) : Iterable<E> {
    internal val cell = Cell(IdentifiedStorage.of(elements))
    internal var attached = false

    val elements: List<E>
        get() = read().elements

    val ids: List<ID>
        get() = read().elements.map { it.id }

    val count: Int
        get() = read().elements.size

    fun isEmpty(): Boolean = count == 0

    override fun iterator(): Iterator<E> = elements.iterator()

    fun index(id: ID): Int? = read().index[id]

    /** `items[id: id]` */
    operator fun get(id: ID): E? {
        val storage = read()
        return storage.index[id]?.let(storage.elements::get)
    }

    /** `items[id: id] = element`. Replaces in place, appends when absent, removes on null. */
    operator fun set(id: ID, element: E?) {
        require(element == null || element.id == id) { "items[id: $id] cannot hold an element whose id is ${element?.id}" }
        mutate { storage ->
            val index = storage.index[id]
            when {
                element == null && index == null -> null
                element == null -> storage.elements.filterIndexed { position, _ -> position != index }
                index == null -> storage.elements + element
                else -> storage.elements.toMutableList().also { it[index] = element }
            }
        }
    }

    /** `append(_:)`. An element whose id is already present is not appended, as in Swift. */
    fun append(element: E) {
        mutate { storage -> if (storage.index.containsKey(element.id)) null else storage.elements + element }
    }

    fun insert(element: E, at: Int) {
        mutate { storage ->
            if (storage.index.containsKey(element.id)) null else storage.elements.toMutableList().also { it.add(at, element) }
        }
    }

    /** `remove(id:)` */
    fun remove(id: ID): E? {
        val removed = read().let { storage -> storage.index[id]?.let(storage.elements::get) } ?: return null
        mutate { storage -> storage.elements.filter { it.id != id } }
        return removed
    }

    fun removeAll(where: (E) -> Boolean) {
        mutate { storage -> storage.elements.filterNot(where).takeIf { it.size != storage.elements.size } }
    }

    fun removeAll() {
        mutate { storage -> if (storage.elements.isEmpty()) null else emptyList() }
    }

    override fun equals(other: Any?) = other is IdentifiedArray<*, *> && other.elements == elements

    override fun hashCode() = elements.hashCode()

    override fun toString() = "IdentifiedArray$elements"

    private fun read(): IdentifiedStorage<ID, E> {
        if (Overlay.current.get() == null && StoreContext.phase.get() == Phase.Effect) {
            check(!attached) { "Read an IdentifiedArray from an effect. Copy the elements into a local before Effect.run." }
        }
        return cell.read()
    }

    private inline fun mutate(change: (IdentifiedStorage<ID, E>) -> List<E>?) {
        val owned = Overlay.current.get() == null && attached
        if (owned) check(StoreContext.phase.get() == Phase.Reduce) { "Mutated an IdentifiedArray outside the reducer. Send an action." }
        val next = change(cell.read()) ?: return
        if (owned) next.forEach(::attachGraph)
        cell.write(IdentifiedStorage.of(next))
    }
}

internal class IdentifiedStorage<ID, E : Identifiable<ID>> private constructor(
    val elements: List<E>,
    val index: Map<ID, Int>,
) {
    companion object {
        fun <ID, E : Identifiable<ID>> of(elements: List<E>): IdentifiedStorage<ID, E> {
            val index = HashMap<ID, Int>(elements.size)
            elements.forEachIndexed { position, element ->
                require(index.put(element.id, position) == null) { "Duplicate id ${element.id} in an IdentifiedArray" }
            }
            return IdentifiedStorage(elements.toList(), index)
        }
    }
}

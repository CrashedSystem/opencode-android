package ai.opencode.mobilecode.tca

import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty
import kotlin.time.Duration
import kotlinx.coroutines.delay

/**
 * `DependencyKey`. A client's companion object implements it, the way a Swift client conforms:
 * `companion object : DependencyKey<HapticsClient> { override val liveValue get() = HapticsClient(…) }`.
 *
 * Declare both values with `get() =`. Each root container builds its value once, so a test double
 * is shared within one test and never across tests. A key with no test value fails closed in tests.
 */
interface DependencyKey<T> {
    val liveValue: T

    val testValue: T
        get() = throw UnimplementedDependency(typeName(javaClass).removeSuffix(".Companion"))
}

class UnimplementedDependency(name: String) :
    IllegalStateException("$name has no test value. Override it in TestStore(withDependencies = { … }).")

/**
 * `DependencyValues`. Read and override values through the extension property each client declares,
 * `var DependencyValues.haptics: HapticsClient`. A child container overrides some values and finds the
 * rest in its parent, so a `TestStore` and its test body share defaults such as shared storage.
 */
class DependencyValues private constructor(val isTesting: Boolean, private val parent: DependencyValues?) {
    private val lock: Any = parent?.lock ?: Any()
    private val overrides = HashMap<DependencyKey<*>, Any?>()
    private val defaults = HashMap<DependencyKey<*>, Any?>()

    @Suppress("UNCHECKED_CAST")
    operator fun <T> get(key: DependencyKey<T>): T = synchronized(lock) { lookup(key) as T }

    operator fun <T> set(key: DependencyKey<T>, value: T) {
        synchronized(lock) { overrides[key] = value }
    }

    internal fun child(): DependencyValues = DependencyValues(isTesting, this)

    private fun lookup(key: DependencyKey<*>): Any? {
        if (overrides.containsKey(key)) return overrides[key]
        if (parent != null) return parent.lookup(key)
        if (!defaults.containsKey(key)) defaults[key] = if (isTesting) key.testValue else key.liveValue
        return defaults[key]
    }

    companion object {
        /** The process's live values, used outside any store or test. */
        val live = DependencyValues(isTesting = false, parent = null)

        internal fun test() = DependencyValues(isTesting = true, parent = null)

        /** The values of the store, effect, or test running on this thread. */
        val current: DependencyValues
            get() = StoreContext.dependencies.get() ?: live
    }
}

/** `@Dependency(\.continuousClock) var clock` is `val clock by Dependency(DependencyValues::continuousClock)`. */
class Dependency<T>(private val keyPath: (DependencyValues) -> T) : ReadOnlyProperty<Any?, T> {
    override fun getValue(thisRef: Any?, property: KProperty<*>): T = keyPath(DependencyValues.current)
}

/** `withDependencies { $0.x = y } operation: { … }` */
fun <R> withDependencies(update: (DependencyValues) -> Unit, operation: () -> R): R {
    val values = DependencyValues.current.child().also(update)
    return StoreContext.run(StoreContext.phase.get(), values, operation)
}

/** `isTesting` */
val isTesting: Boolean
    get() = DependencyValues.current.isTesting

/** `any Clock<Duration>`. `try await clock.sleep(for: .seconds(1))` is `clock.sleep(1.seconds)`. */
fun interface Clock {
    suspend fun sleep(duration: Duration)
}

class UnimplementedClock(private val name: String) : Clock {
    override suspend fun sleep(duration: Duration): Unit = throw UnimplementedDependency(name)
}

object ContinuousClockKey : DependencyKey<Clock> {
    override val liveValue: Clock
        get() = Clock { delay(it) }

    override val testValue: Clock
        get() = UnimplementedClock("continuousClock")
}

/** `@Dependency(\.continuousClock)`. Tests install a `TestClock` and advance it. */
var DependencyValues.continuousClock: Clock
    get() = this[ContinuousClockKey]
    set(value) {
        this[ContinuousClockKey] = value
    }

object WallClockKey : DependencyKey<Clock> {
    override val liveValue: Clock
        get() = Clock { delay(it) }

    override val testValue: Clock
        get() = Clock { delay(it) }
}

/** iOS's raw `Task.sleep(for:)`. It sleeps in real time in tests too, so advancing a `TestClock` never moves it. */
var DependencyValues.wallClock: Clock
    get() = this[WallClockKey]
    set(value) {
        this[WallClockKey] = value
    }

/** `DateGenerator`. `@Dependency(\.date.now) var now` is `val now by Dependency { it.date.now }`. */
class DateGenerator(private val generate: () -> Instant) {
    val now: Instant
        get() = generate()

    companion object : DependencyKey<DateGenerator> {
        fun constant(now: Instant) = DateGenerator { now }

        override val liveValue: DateGenerator
            get() = DateGenerator { Instant.now() }

        override val testValue: DateGenerator
            get() = DateGenerator { throw UnimplementedDependency("date") }
    }
}

var DependencyValues.date: DateGenerator
    get() = this[DateGenerator]
    set(value) {
        this[DateGenerator] = value
    }

/** `UUIDGenerator`. `@Dependency(\.uuid) var uuid` is `val uuid by Dependency { it.uuid }`, called as `uuid()`. */
class UUIDGenerator(private val generate: () -> UUID) {
    operator fun invoke(): UUID = generate()

    companion object : DependencyKey<UUIDGenerator> {
        fun constant(uuid: UUID) = UUIDGenerator { uuid }

        /** `.incrementing`: 00000000-0000-0000-0000-000000000000, then …0001, and so on. */
        fun incrementing(): UUIDGenerator {
            val next = AtomicLong()
            return UUIDGenerator { UUID(0, next.getAndIncrement()) }
        }

        override val liveValue: UUIDGenerator
            get() = UUIDGenerator(UUID::randomUUID)

        override val testValue: UUIDGenerator
            get() = UUIDGenerator { throw UnimplementedDependency("uuid") }
    }
}

var DependencyValues.uuid: UUIDGenerator
    get() = this[UUIDGenerator]
    set(value) {
        this[UUIDGenerator] = value
    }

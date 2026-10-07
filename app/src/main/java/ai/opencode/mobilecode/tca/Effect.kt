package ai.opencode.mobilecode.tca

/** Sends an action into the store from an effect, `await send(.action)`. Sends from a cancelled effect are dropped. */
fun interface Send<in A> {
    suspend operator fun invoke(action: A)
}

/**
 * The receiver of an effect body. Its `state` hides the reducer's `state`, so reading reducer state
 * inside `Effect.run` does not compile. Copy the value into a local first, as Swift requires.
 */
class EffectScope internal constructor() {
    @Deprecated("Effects cannot read reducer state. Copy the value into a local before Effect.run.", level = DeprecationLevel.ERROR)
    val state: Nothing
        get() = error("Effects cannot read reducer state.")

    internal companion object {
        val shared = EffectScope()
    }
}

/** The work a reducer returns, `Effect<Action>`. */
sealed interface Effect<out A> {
    companion object {
        /** `.none` */
        val none: Effect<Nothing> = None

        /** `.send(action)`, delivered synchronously after the current action, before any `.run` starts. */
        fun <A> send(action: A): Effect<A> = SendAction(action)

        /** `.run { send in … } catch: { error, send in … }`. The body runs on the store's main thread. */
        fun <A> run(
            catch: (suspend EffectScope.(error: Throwable, send: Send<A>) -> Unit)? = null,
            operation: suspend EffectScope.(send: Send<A>) -> Unit,
        ): Effect<A> = Run(operation, catch)

        /** `.merge(…)`. Merged effects run concurrently. */
        fun <A> merge(vararg effects: Effect<A>): Effect<A> = merge(effects.asList())

        fun <A> merge(effects: List<Effect<A>>): Effect<A> {
            val work = effects.filter { it !== None }
            return when (work.size) {
                0 -> None
                1 -> work.single()
                else -> Merge(work)
            }
        }

        /** `.cancel(id:)`. Cancels effects with this ID started here or in any descendant feature. */
        fun cancel(id: Any): Effect<Nothing> = Cancel(id)
    }
}

/** `.cancellable(id:cancelInFlight:)` */
fun <A> Effect<A>.cancellable(id: Any, cancelInFlight: Boolean = false): Effect<A> =
    if (this === None) this else Cancellable(this, id, cancelInFlight)

fun <A, B> Effect<A>.map(transform: (A) -> B): Effect<B> =
    when (this) {
        None -> None
        is SendAction -> SendAction(transform(action))
        is Run ->
            Run(
                { send -> operation(this, Send { send(transform(it)) }) },
                catch?.let { handler -> { error, send -> handler(this, error, Send { send(transform(it)) }) } },
            )
        is Merge -> Merge(effects.map { it.map(transform) })
        is Cancellable -> Cancellable(effect.map(transform), id, cancelInFlight)
        is Cancel -> this
        is InChild -> InChild(segment, effect.map(transform))
        is CancelChild -> this
    }

internal data object None : Effect<Nothing>

internal class SendAction<out A>(val action: A) : Effect<A>

internal class Run<out A>(
    val operation: suspend EffectScope.(Send<A>) -> Unit,
    val catch: (suspend EffectScope.(Throwable, Send<A>) -> Unit)?,
) : Effect<A>

internal class Merge<out A>(val effects: List<Effect<A>>) : Effect<A>

internal class Cancellable<out A>(val effect: Effect<A>, val id: Any, val cancelInFlight: Boolean) : Effect<A>

internal class Cancel(val id: Any) : Effect<Nothing>

/** Runs [effect] one level deeper in the navigation path, under a child's identity. */
internal class InChild<out A>(val segment: Segment, val effect: Effect<A>) : Effect<A>

/** Cancels every effect running under a child's identity, at any depth. */
internal class CancelChild(val segment: Segment) : Effect<Nothing>

/** One level of a navigation path: the state property that holds a child and the child's identity. */
internal data class Segment(val property: Any, val identity: Any?)

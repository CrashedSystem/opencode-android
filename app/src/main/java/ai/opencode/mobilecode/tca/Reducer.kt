package ai.opencode.mobilecode.tca

import kotlin.experimental.ExperimentalTypeInference
import kotlin.reflect.KMutableProperty1

/**
 * `Reducer`. A feature overrides [body], `var body: some ReducerOf<Self>`. The kit's own
 * reducers override [reduce]. A reducer mutates [state] in place and returns the work to run.
 */
interface Reducer<S, A> {
    val body: Reducer<S, A>
        get() = throw NotImplementedError("${javaClass.name} overrides neither body nor reduce")

    fun reduce(state: S, action: A): Effect<A> = body.reduce(state, action)
}

class ReduceScope<S> internal constructor(val state: S)

/** `Reduce { state, action in … }`. `state` is a receiver property so that `Effect.run` can hide it. */
@Suppress("FunctionName")
fun <S, A> Reduce(reducer: ReduceScope<S>.(action: A) -> Effect<A>): Reducer<S, A> =
    object : Reducer<S, A> {
        override fun reduce(state: S, action: A): Effect<A> = ReduceScope(state).reducer(action)
    }

/** The reducers of a `body`, run in order, their effects merged in order. */
fun <S, A> combine(vararg reducers: Reducer<S, A>): Reducer<S, A> =
    object : Reducer<S, A> {
        override fun reduce(state: S, action: A): Effect<A> = Effect.merge(reducers.map { it.reduce(state, action) })
    }

/**
 * A case path into an action, `\.child`. A case that carries a child feature's actions declares one
 * as its companion object, so call sites read `Scope(state = State::child, action = Action.child)`.
 */
open class Case<A, C>(private val embedding: (C) -> A, private val extracting: (A) -> C?) {
    fun embed(value: C): A = embedding(value)

    fun extract(action: A): C? = extracting(action)
}

/** `\.child.presented` */
val <A, C> Case<A, PresentationAction<C>>.presented: Case<A, C>
    get() = PresentedCase(this)

private class PresentedCase<A, C>(private val base: Case<A, PresentationAction<C>>) :
    Case<A, C>(
        { base.embed(PresentationAction.presented(it)) },
        { (base.extract(it) as? PresentationAction.presented<C>)?.action },
    ) {
    override fun equals(other: Any?) = other is PresentedCase<*, *> && other.base === base

    override fun hashCode() = base.hashCode()
}

/** `PresentationAction`, the action of a `@Presents` child. */
sealed interface PresentationAction<out A> {
    data class presented<out A>(val action: A) : PresentationAction<A>

    data object dismiss : PresentationAction<Nothing>
}

/** `Scope(state:action:child:)`. Runs where it is declared. It never cancels: the child is always there. */
@Suppress("FunctionName")
fun <S, A, CS, CA> Scope(
    state: KMutableProperty1<S, CS>,
    action: Case<A, CA>,
    child: () -> Reducer<CS, CA>,
): Reducer<S, A> {
    val toChildState = state
    val toChildAction = action
    val childReducer = child()
    return object : Reducer<S, A> {
        override fun reduce(state: S, action: A): Effect<A> {
            val childAction = toChildAction.extract(action) ?: return Effect.none
            return childReducer.reduce(toChildState.get(state), childAction).map(toChildAction::embed)
        }
    }
}

/**
 * `.ifLet(\.child, action: \.child) { Child() }` for a plain optional child. The child reduces its
 * actions before the parent, so the parent sees the child's mutations. When the parent clears the
 * child or swaps in one with another `Identifiable.id`, every effect the child started is cancelled,
 * at any depth below it.
 */
@OptIn(ExperimentalTypeInference::class)
@OverloadResolutionByLambdaReturnType
fun <S, A, CS : Any, CA> Reducer<S, A>.ifLet(
    state: KMutableProperty1<S, CS?>,
    action: Case<A, CA>,
    child: () -> Reducer<CS, CA>,
): Reducer<S, A> {
    val parent = this
    val toChildState = state
    val toChildAction = action
    val childReducer = child()
    return object : Reducer<S, A> {
        override fun reduce(state: S, action: A): Effect<A> {
            val childEffects = reduceChild(state, action)
            val childBefore = toChildState.get(state)
            val idBefore = childBefore?.let(::identityOf)
            val parentEffects = parent.reduce(state, action)
            val childAfter = toChildState.get(state)
            val cancel =
                if (childBefore != null && (childAfter == null || identityOf(childAfter) != idBefore)) {
                    CancelChild(Segment(toChildState, idBefore))
                } else {
                    Effect.none
                }
            return Effect.merge(childEffects, parentEffects, cancel)
        }

        private fun reduceChild(state: S, action: A): Effect<A> {
            val childAction = toChildAction.extract(action) ?: return Effect.none
            val childState = toChildState.get(state) ?: return Effect.none
            val segment = Segment(toChildState, identityOf(childState))
            return InChild(segment, childReducer.reduce(childState, childAction).map(toChildAction::embed))
        }
    }
}

/**
 * `.ifLet(\.$destination, action: \.destination) { Destination() }` for a `@Presents` child.
 * `.presented` actions reach the child first. On `.dismiss` the parent reduces first, so it can
 * still read the child, then the child is cleared unless the parent already replaced it.
 */
@JvmName("ifLetPresents")
@OptIn(ExperimentalTypeInference::class)
@OverloadResolutionByLambdaReturnType
fun <S, A, CS : Any, CA> Reducer<S, A>.ifLet(
    state: KMutableProperty1<S, CS?>,
    action: Case<A, PresentationAction<CA>>,
    child: () -> Reducer<CS, CA>,
): Reducer<S, A> {
    val parent = this
    val toChildState = state
    val toPresentationAction = action
    val childReducer = child()
    return object : Reducer<S, A> {
        override fun reduce(state: S, action: A): Effect<A> {
            val childBefore = toChildState.get(state)
            val idBefore = childBefore?.let(::identityOf)
            val presentation = toPresentationAction.extract(action)
            val childEffects =
                if (presentation is PresentationAction.presented && childBefore != null) {
                    InChild(
                        Segment(toChildState, idBefore),
                        childReducer.reduce(childBefore, presentation.action).map {
                            toPresentationAction.embed(PresentationAction.presented(it))
                        },
                    )
                } else {
                    Effect.none
                }
            val parentEffects = parent.reduce(state, action)
            if (presentation == PresentationAction.dismiss && childBefore != null) {
                val current = toChildState.get(state)
                if (current != null && identityOf(current) == idBefore) toChildState.set(state, null)
            }
            val childAfter = toChildState.get(state)
            val cancel =
                if (childBefore != null && (childAfter == null || identityOf(childAfter) != idBefore)) {
                    CancelChild(Segment(toChildState, idBefore))
                } else {
                    Effect.none
                }
            return Effect.merge(childEffects, parentEffects, cancel)
        }
    }
}

/** `.onChange(of: \.value) { oldValue, newValue in Reduce { … } }`. Runs only when the selected value changed. */
fun <S, A, V> Reducer<S, A>.onChange(
    of: (S) -> V,
    reducer: (oldValue: V, newValue: V) -> Reducer<S, A>,
): Reducer<S, A> {
    val upstream = this
    return object : Reducer<S, A> {
        override fun reduce(state: S, action: A): Effect<A> {
            val oldValue = of(state)
            require(oldValue !is ObservableState && oldValue !is IdentifiedArray<*, *>) {
                "onChange(of:) must select an immutable value. A feature state is the same object before and after."
            }
            val effects = upstream.reduce(state, action)
            val newValue = of(state)
            if (oldValue == newValue) return effects
            return Effect.merge(effects, reducer(oldValue, newValue).reduce(state, action))
        }
    }
}

/**
 * `BindingAction<State>`. Matchable by field: `action.binding.keyPath == State::draft`
 * is Swift's `case .binding(\.draft)`.
 */
class BindingAction<S> private constructor(
    val keyPath: KMutableProperty1<S, *>,
    private val value: Any?,
    private val assign: (S) -> Unit,
) {
    internal fun applyTo(state: S) = assign(state)

    override fun equals(other: Any?) = other is BindingAction<*> && other.keyPath == keyPath && other.value == value

    override fun hashCode() = 31 * keyPath.hashCode() + value.hashCode()

    override fun toString() = "BindingAction.set(${keyPath.name}, $value)"

    companion object {
        /** `.set(\.field, value)` */
        fun <S, V> set(keyPath: KMutableProperty1<S, V>, value: V): BindingAction<S> =
            BindingAction(keyPath, value) { keyPath.set(it, value) }
    }
}

/** `Action: BindableAction`. The `binding` case overrides [binding] with its payload. */
interface BindableAction<S> {
    val binding: BindingAction<S>?
        get() = null
}

/** `BindingReducer()`. Writes the bound field before the reducers after it run. */
class BindingReducer<S, A : BindableAction<S>> : Reducer<S, A> {
    override fun reduce(state: S, action: A): Effect<A> {
        action.binding?.applyTo(state)
        return Effect.none
    }
}

internal fun identityOf(child: Any): Any? = (child as? Identifiable<*>)?.id

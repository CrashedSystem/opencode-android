package ai.opencode.mobilecode.tca

import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

class Child(override val id: String = "a") : ObservableState(), Identifiable<String> {
    var count by StoreVar(0)
    var completions by StoreVar(listOf<Int>())
}

sealed interface ChildAction {
    data object increment : ChildAction

    data class start(val value: Int, val labelled: Boolean = true) : ChildAction

    data class finished(val value: Int) : ChildAction

    data object stop : ChildAction

    data object nonCooperative : ChildAction
}

enum class ChildCancelID {
    timer,
}

class ChildFeature : Reducer<Child, ChildAction> {
    private val clock by Dependency(DependencyValues::continuousClock)

    override val body: Reducer<Child, ChildAction> =
        Reduce { action ->
            when (action) {
                ChildAction.increment -> {
                    state.count += 1
                    Effect.none
                }
                is ChildAction.start -> {
                    val work =
                        Effect.run { send ->
                            clock.sleep(1.seconds)
                            send(ChildAction.finished(action.value))
                        }
                    if (action.labelled) work.cancellable(ChildCancelID.timer, cancelInFlight = true) else work
                }
                is ChildAction.finished -> {
                    state.completions += action.value
                    Effect.none
                }
                ChildAction.stop -> Effect.cancel(ChildCancelID.timer)
                ChildAction.nonCooperative ->
                    Effect.run { send ->
                        try {
                            clock.sleep(1.seconds)
                        } catch (cancellation: CancellationException) {
                            withContext(NonCancellable) { send(ChildAction.finished(99)) }
                        }
                    }.cancellable(ChildCancelID.timer, cancelInFlight = true)
            }
        }
}

class Parent : ObservableState() {
    var child by StoreVar<Child?>(Child("a"))
    var other by StoreVar<Child?>(Child("other"))
    var observed by StoreVar(-1)
}

sealed interface ParentAction {
    data class child(val action: ChildAction) : ParentAction {
        companion object : Case<ParentAction, ChildAction>({ child(it) }, { (it as? child)?.action })
    }

    data class other(val action: ChildAction) : ParentAction {
        companion object : Case<ParentAction, ChildAction>({ other(it) }, { (it as? other)?.action })
    }

    data class replace(val id: String?) : ParentAction

    data object cancelFromAncestor : ParentAction
}

class ParentFeature : Reducer<Parent, ParentAction> {
    override val body: Reducer<Parent, ParentAction> =
        Reduce<Parent, ParentAction> { action ->
            when (action) {
                is ParentAction.child -> {
                    if (action.action == ChildAction.increment) state.observed = state.child!!.count
                    Effect.none
                }
                is ParentAction.other -> Effect.none
                is ParentAction.replace -> {
                    state.child = action.id?.let(::Child)
                    Effect.none
                }
                ParentAction.cancelFromAncestor -> Effect.cancel(ChildCancelID.timer)
            }
        }
            .ifLet(Parent::child, ParentAction.child) { ChildFeature() }
            .ifLet(Parent::other, ParentAction.other) { ChildFeature() }
}

/** Three levels: a grandparent holds an optional parent, which holds an optional child that runs timers. */
class Middle(override val id: String) : ObservableState(), Identifiable<String> {
    var child by StoreVar<Child?>(Child("leaf"))
}

sealed interface MiddleAction {
    data class child(val action: ChildAction) : MiddleAction {
        companion object : Case<MiddleAction, ChildAction>({ child(it) }, { (it as? child)?.action })
    }
}

class MiddleFeature : Reducer<Middle, MiddleAction> {
    override val body: Reducer<Middle, MiddleAction> =
        Reduce<Middle, MiddleAction> { Effect.none }
            .ifLet(Middle::child, MiddleAction.child) { ChildFeature() }
}

class Grand : ObservableState() {
    var middle by StoreVar<Middle?>(Middle("one"))
}

sealed interface GrandAction {
    data class middle(val action: MiddleAction) : GrandAction {
        companion object : Case<GrandAction, MiddleAction>({ middle(it) }, { (it as? middle)?.action })
    }

    data class replaceMiddle(val id: String) : GrandAction

    data object cancelTimers : GrandAction
}

class GrandFeature : Reducer<Grand, GrandAction> {
    override val body: Reducer<Grand, GrandAction> =
        Reduce<Grand, GrandAction> { action ->
            when (action) {
                is GrandAction.middle -> Effect.none
                is GrandAction.replaceMiddle -> {
                    state.middle = Middle(action.id)
                    Effect.none
                }
                GrandAction.cancelTimers -> Effect.cancel(ChildCancelID.timer)
            }
        }
            .ifLet(Grand::middle, GrandAction.middle) { MiddleFeature() }
}

fun leafStart(value: Int) = GrandAction.middle(MiddleAction.child(ChildAction.start(value)))

fun leafFinished(value: Int) = GrandAction.middle(MiddleAction.child(ChildAction.finished(value)))

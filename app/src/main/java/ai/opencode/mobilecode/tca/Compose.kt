@file:Suppress("ComposableNaming")

package ai.opencode.mobilecode.tca

import ai.opencode.mobilecode.system.ButtonRole
import ai.opencode.mobilecode.system.SystemAlert
import ai.opencode.mobilecode.system.SystemSheet
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import java.util.UUID
import kotlin.reflect.KMutableProperty1

/**
 * A value derived from state, `store.observe { showsButton }`. The caller recomposes only when the
 * derived value changes. Reading `store.state.field` directly also observes, one field at a time.
 */
@Composable
fun <S, A, T> Store<S, A>.observe(selector: S.() -> T): T {
    val currentSelector = rememberUpdatedState(selector)
    val derived = remember(this) { derivedStateOf { currentSelector.value(state) } }
    return derived.value
}

/**
 * `.task(id:) { store.send(.task) }`. Runs [action] when the view appears and again when [id] changes.
 *
 * An activity rebuilt by a configuration change does not run it again, as a SwiftUI view that stayed
 * on screen would not. A view that leaves and comes back runs it again. Leaving cancels nothing:
 * effects belong to the store and end with their feature, not with the view.
 */
@Composable
fun <S, A> Store<S, A>.task(id: Any? = Unit, action: () -> Unit) {
    val slot = rememberSaveable { UUID.randomUUID().toString() }
    val activity = LocalActivity.current
    val currentAction = rememberUpdatedState(action)
    val tasks = runtime.viewTasks
    LaunchedEffect(slot, id) {
        if (!tasks.containsKey(slot) || tasks[slot] != id) {
            tasks[slot] = id
            currentAction.value()
        }
    }
    DisposableEffect(slot) {
        onDispose { if (activity?.isChangingConfigurations != true) tasks.remove(slot) }
    }
}

/** `.onAppear { store.send(.viewed) }` */
@Composable
fun <S, A> Store<S, A>.onAppear(action: () -> Unit) = task(Unit, action)

/** SwiftUI's `Binding`. */
@Stable
open class Binding<V>(private val getter: () -> V, private val setter: (V) -> Unit) {
    var wrappedValue: V
        get() = getter()
        set(value) = setter(value)
}

/** `$store.field` is `store.binding(State::field, Action::binding)`. Setting it sends a typed `BindingAction`. */
fun <S, A, V> Store<S, A>.binding(keyPath: KMutableProperty1<S, V>, action: (BindingAction<S>) -> A): Binding<V> =
    StoreBinding(this, keyPath, action)

private class StoreBinding<S, A, V>(
    private val store: Store<S, A>,
    private val keyPath: KMutableProperty1<S, V>,
    private val action: (BindingAction<S>) -> A,
) : Binding<V>({ keyPath.get(store.state) }, { store.send(action(BindingAction.set(keyPath, it))) }) {
    override fun equals(other: Any?) =
        other is StoreBinding<*, *, *> && other.store == store && other.keyPath == keyPath && other.action == action

    override fun hashCode() = 31 * (31 * store.hashCode() + keyPath.hashCode()) + action.hashCode()
}

/** `.sheet(isPresented: $store.isPresented) { … }`. Medium and large detents, like the app's sheets. */
@Composable
fun sheet(isPresented: Binding<Boolean>, content: @Composable () -> Unit) {
    if (!isPresented.wrappedValue) return
    SystemSheet(onDismissRequest = { isPresented.wrappedValue = false }) { content() }
}

/**
 * `.sheet(item: $store.scope(\.child, action: \.child)) { childStore in … }` for a `@Presents` child.
 * A child with another identity gets a new sheet, so its remembered state and tasks start fresh.
 */
@Composable
fun <S, A, CS : Any, CA> Store<S, A>.presented(
    state: KMutableProperty1<S, CS?>,
    action: Case<A, PresentationAction<CA>>,
    content: @Composable (Store<CS, CA>) -> Unit,
) {
    val child = scope(state, action.presented) ?: return
    key(identityOf(child.state)) {
        SystemSheet(onDismissRequest = { send(action.embed(PresentationAction.dismiss)) }) { content(child) }
    }
}

/** A button of an `alert`, `Button("Delete", role: .destructive) { … }`. */
class AlertButton(val title: String, val role: ButtonRole? = null, val action: () -> Unit = {})

/** `.alert(title, isPresented:) { buttons } message: { Text(message) }` */
@Composable
fun alert(
    title: String,
    isPresented: Binding<Boolean>,
    message: String? = null,
    buttons: List<AlertButton> = listOf(AlertButton("OK")),
) {
    if (!isPresented.wrappedValue) return
    val cancel = buttons.firstOrNull { it.role == ButtonRole.cancel }
    val confirm = buttons.lastOrNull { it.role != ButtonRole.cancel } ?: cancel ?: return
    SystemAlert(
        onDismissRequest = {
            isPresented.wrappedValue = false
            cancel?.action?.invoke()
        },
        title = title,
        text = message,
        confirm = confirm.title,
        onConfirm = {
            isPresented.wrappedValue = false
            confirm.action()
        },
        dismiss = cancel?.takeIf { it !== confirm }?.title,
        destructive = confirm.role == ButtonRole.destructive,
    )
}

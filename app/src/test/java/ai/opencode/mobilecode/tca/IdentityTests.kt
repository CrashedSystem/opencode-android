package ai.opencode.mobilecode.tca

import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import org.junit.Rule
import org.junit.Test

class IdentityTests {
    @get:Rule
    val dependencies = TestDependencies()

    private val clock = TestClock()

    private fun store() = TestStore(initialState = { Holder() }, withDependencies = { it.continuousClock = clock }) { HolderFeature() }

    @Test
    fun replacingAChildThatIsNotIdentifiableKeepsItsEffects() {
        val store = store()
        store.send(HolderAction.sheet(SheetAction.start))
        clock.advance(by = 100.milliseconds)
        store.send(HolderAction.replace) { it.sheet = Sheet() }
        clock.advance(by = 1.seconds)
        store.receive(HolderAction.sheet(SheetAction.finished)) { it.sheet!!.finished = 1 }
    }

    @Test
    fun clearingAChildThatIsNotIdentifiableCancelsItsEffects() {
        val store = store()
        store.send(HolderAction.sheet(SheetAction.start))
        clock.advance(by = 100.milliseconds)
        store.send(HolderAction.clear) { it.sheet = null }
        clock.advance(by = 5.seconds)
    }
}

class Sheet : ObservableState() {
    var finished by StoreVar(0)
}

sealed interface SheetAction {
    data object start : SheetAction

    data object finished : SheetAction
}

class SheetFeature : Reducer<Sheet, SheetAction> {
    private val clock by Dependency(DependencyValues::continuousClock)

    override val body: Reducer<Sheet, SheetAction> =
        Reduce { action ->
            when (action) {
                SheetAction.start ->
                    Effect.run { send ->
                        clock.sleep(1.seconds)
                        send(SheetAction.finished)
                    }
                SheetAction.finished -> {
                    state.finished += 1
                    Effect.none
                }
            }
        }
}

class Holder : ObservableState() {
    var sheet by StoreVar<Sheet?>(Sheet())
}

sealed interface HolderAction {
    data class sheet(val action: SheetAction) : HolderAction {
        companion object : Case<HolderAction, SheetAction>({ sheet(it) }, { (it as? sheet)?.action })
    }

    data object replace : HolderAction

    data object clear : HolderAction
}

class HolderFeature : Reducer<Holder, HolderAction> {
    override val body: Reducer<Holder, HolderAction> =
        Reduce<Holder, HolderAction> { action ->
            when (action) {
                is HolderAction.sheet -> Unit
                HolderAction.replace -> state.sheet = Sheet()
                HolderAction.clear -> state.sheet = null
            }
            Effect.none
        }
            .ifLet(Holder::sheet, HolderAction.sheet) { SheetFeature() }
}

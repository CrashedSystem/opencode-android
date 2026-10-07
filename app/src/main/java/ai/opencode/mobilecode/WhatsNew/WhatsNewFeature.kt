@file:PortedFrom("OpenCode/WhatsNew/WhatsNewFeature.swift", "553a407")

package ai.opencode.mobilecode

import ai.opencode.mobilecode.tca.BindableAction
import ai.opencode.mobilecode.tca.BindingAction
import ai.opencode.mobilecode.tca.BindingReducer
import ai.opencode.mobilecode.tca.Effect
import ai.opencode.mobilecode.tca.ObservableState
import ai.opencode.mobilecode.tca.Reduce
import ai.opencode.mobilecode.tca.Reducer
import ai.opencode.mobilecode.tca.Shared
import ai.opencode.mobilecode.tca.SharedKey
import ai.opencode.mobilecode.tca.StorageDirectory
import ai.opencode.mobilecode.tca.StoreVar
import ai.opencode.mobilecode.tca.appStorage
import ai.opencode.mobilecode.tca.combine
import ai.opencode.mobilecode.tca.fileStorage
import ai.opencode.mobilecode.tca.withLock

/// What’s New: a drawer button while some entries are unseen, the sheet it
/// opens, and Settings’ switch for the button.
class WhatsNewFeature : Reducer<WhatsNewFeature.State, WhatsNewFeature.Action> {
    class State(entries: List<WhatsNewEntry> = WhatsNewEntry.all) : ObservableState() {
        var entries by StoreVar(entries)
        var seenIDs by Shared(SharedKey.whatsNewSeenIDs)
        var isButtonEnabled by Shared(SharedKey.whatsNewButtonEnabled)
        var isPresented by StoreVar(false)

        init {
            if (BuildConfig.DEBUG) {
                WhatsNewFeature.applyLabLaunchState()
            }
        }

        val unseenEntries: List<WhatsNewEntry> get() = entries.filter { !seenIDs.contains(it.id) }
        val showsButton: Boolean get() = isButtonEnabled && !unseenEntries.isEmpty()

        fun markAllSeen() {
            val ids = entries.map { it.id }.toSet()
            if (seenIDs.containsAll(ids)) return
            ::seenIDs.withLock { value += ids }
        }
    }

    sealed interface Action : BindableAction<State> {
        data class binding(override val binding: BindingAction<State>) : Action

        data object buttonTapped : Action

        data object hideTapped : Action

        data class buttonEnabledChanged(val enabled: Boolean) : Action

        /// The list appeared, in the sheet or in Settings.
        data object viewed : Action
    }

    override val body: Reducer<State, Action> =
        combine(
            BindingReducer(),
            Reduce { action ->
                when (action) {
                    is Action.binding -> Effect.none
                    Action.buttonTapped -> {
                        state.isPresented = true
                        state.markAllSeen()
                        Effect.none
                    }
                    Action.hideTapped -> {
                        state::isButtonEnabled.withLock { value = false }
                        Effect.none
                    }
                    is Action.buttonEnabledChanged -> {
                        state::isButtonEnabled.withLock { value = action.enabled }
                        Effect.none
                    }
                    Action.viewed -> {
                        state.markAllSeen()
                        Effect.none
                    }
                }
            },
        )

    companion object {
        /// UI tests launch with every entry seen so other suites' drawers stay as
        /// they were; `OPENCODE_LAB_WHATS_NEW=unseen` launches with all of them new.
        private val labLaunchState: Unit by lazy {
            val environment = System.getenv()
            if (environment["OPENCODE_LAB_SERVER_URL"] == null) return@lazy
            var seenIDs by Shared(SharedKey.whatsNewSeenIDs)
            var isButtonEnabled by Shared(SharedKey.whatsNewButtonEnabled)
            val unseen = environment["OPENCODE_LAB_WHATS_NEW"] == "unseen"
            seenIDs = if (unseen) emptySet() else WhatsNewEntry.all.map { it.id }.toSet()
            isButtonEnabled = true
        }

        fun applyLabLaunchState() = labLaunchState
    }
}

/// What’s New entries already shown, by ID.
val SharedKey.Companion.whatsNewSeenIDs: SharedKey<Set<String>>
    get() = SharedKey.fileStorage(StorageDirectory.documents.appending(component = "whats-new-seen.json"), default = emptySet())

/// Settings → Show What’s New. Hiding the button turns it off.
val SharedKey.Companion.whatsNewButtonEnabled: SharedKey<Boolean>
    get() = SharedKey.appStorage("whats-new-button-enabled", default = true)

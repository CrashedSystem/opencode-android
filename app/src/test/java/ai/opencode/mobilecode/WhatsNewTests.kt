@file:PortedFrom("OpenCodeTests/WhatsNewTests.swift", "a9bc920")

package ai.opencode.mobilecode

import ai.opencode.mobilecode.WhatsNewFeature.Action
import ai.opencode.mobilecode.tca.Exhaustivity
import ai.opencode.mobilecode.tca.Shared
import ai.opencode.mobilecode.tca.SharedKey
import ai.opencode.mobilecode.tca.TestDependencies
import ai.opencode.mobilecode.tca.TestStore
import ai.opencode.mobilecode.tca.expect
import ai.opencode.mobilecode.tca.withLock
import org.junit.Rule
import org.junit.Test

class WhatsNewTests {
    @get:Rule
    val dependencies = TestDependencies()

    fun entry(id: String, day: String = "2026-10-06"): WhatsNewEntry =
        WhatsNewEntry(id = id, day = day, title = id, summary = "", example = "", illustration = WhatsNewEntry.Illustration.callButton)

    @Test
    fun unseenEntriesShowTheButton() {
        var seenIDs by Shared(SharedKey.whatsNewSeenIDs)
        seenIDs = setOf("a")
        val state = WhatsNewFeature.State(entries = listOf(entry("b"), entry("a")))
        expect(state.unseenEntries.map { it.id } == listOf("b"))
        expect(state.showsButton)

        seenIDs = setOf("a", "b")
        expect(state.unseenEntries.isEmpty())
        expect(!state.showsButton)
    }

    @Test
    fun openingMarksEverythingSeen() {
        val store = TestStore(initialState = { WhatsNewFeature.State(entries = listOf(entry("b"), entry("a"))) }) {
            WhatsNewFeature()
        }
        expect(store.state.showsButton)

        store.send(Action.buttonTapped) {
            it.isPresented = true
            it::seenIDs.withLock { value = setOf("a", "b") }
        }
        expect(!store.state.showsButton)
    }

    @Test
    fun viewingFromSettingsMarksEverythingSeen() {
        val store = TestStore(initialState = { WhatsNewFeature.State(entries = listOf(entry("a"))) }) {
            WhatsNewFeature()
        }
        store.send(Action.viewed) {
            it::seenIDs.withLock { value = setOf("a") }
        }
        expect(!store.state.isPresented)
    }

    @Test
    fun hidingTurnsTheButtonOffUntilSettingsBringsItBack() {
        val store = TestStore(initialState = { WhatsNewFeature.State(entries = listOf(entry("a"))) }) {
            WhatsNewFeature()
        }
        store.send(Action.hideTapped) {
            it::isButtonEnabled.withLock { value = false }
        }
        expect(!store.state.showsButton)
        expect(store.state.unseenEntries.map { it.id } == listOf("a"), "Hiding doesn’t mark anything seen")

        store.send(Action.buttonEnabledChanged(true)) {
            it::isButtonEnabled.withLock { value = true }
        }
        expect(store.state.showsButton)
    }

    @Test
    fun anUpdatesNewEntriesBringTheButtonBack() {
        val store = TestStore(initialState = { WhatsNewFeature.State(entries = listOf(entry("a"))) }) {
            WhatsNewFeature()
        }
        store.send(Action.buttonTapped) {
            it.isPresented = true
            it::seenIDs.withLock { value = setOf("a") }
        }

        // The next version ships with one more entry.
        val updated = WhatsNewFeature.State(entries = listOf(entry("b", day = "2026-10-20"), entry("a")))
        expect(updated.unseenEntries.map { it.id } == listOf("b"))
        expect(updated.showsButton)
    }

    @Test
    fun homeOpensWhatsNewFromItsButton() {
        val server = OpenCodeServer(url = "http://localhost:9", username = "", password = "")
        val home = HomeFeature.State(server = server, snapshot = OpenCodeHomeSnapshot(projects = emptyList(), sessions = emptyList(), activeSessionIDs = emptySet()))
        home.whatsNew = WhatsNewFeature.State(entries = listOf(entry("a")))
        val store = TestStore(initialState = { home }) { HomeFeature() }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)

        store.send(HomeFeature.Action.whatsNew(Action.buttonTapped))
        expect(store.state.whatsNew.isPresented)
        expect(!store.state.whatsNew.showsButton)
    }

    @Test
    fun eachDayIsOneCardHoldingExactlyItsEntries() {
        val entries = listOf(
            entry("c", day = "2026-10-07"), entry("b"), entry("a"),
            entry("z", day = "2026-10-05"), entry("late", day = "2026-10-07"),
        )
        val days = WhatsNewDay.group(entries)
        expect(days.map { it.id } == listOf("2026-10-07", "2026-10-06", "2026-10-05"))
        expect(days.map { day -> day.entries.map { it.id } } == listOf(listOf("c", "late"), listOf("b", "a"), listOf("z")))
    }

    @Test
    fun shippedEntriesGroupIntoUniqueDays() {
        val days = WhatsNewDay.group(WhatsNewEntry.all)
        expect(days.map { it.id }.toSet().size == days.size)
        expect(days.flatMap { it.entries } == WhatsNewEntry.all, "Every entry appears once, in its card, in order")
    }

    @Test
    fun shippedEntriesAreWellFormed() {
        val entries = WhatsNewEntry.all
        expect(entries.size >= 4)
        expect(entries.map { it.id }.toSet().size == entries.size, "IDs are unique")
        val dates = entries.map { it.day }
        expect(dates.size == entries.size)
        expect(dates == dates.sortedDescending(), "Newest first")
        for (entry in entries) {
            expect(!entry.title.isEmpty() && !entry.summary.isEmpty() && !entry.example.isEmpty())
        }
    }
}

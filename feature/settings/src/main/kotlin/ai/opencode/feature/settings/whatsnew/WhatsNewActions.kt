package ai.opencode.feature.settings.whatsnew

import ai.opencode.core.experiments.ExperimentalFeature
import ai.opencode.feature.settings.ConnectionStatus
import ai.opencode.feature.settings.SettingsFeature
import ai.opencode.feature.voice.VoiceRoutePreferences
import ai.opencode.server.OpenCodeServer

/**
 * Port of iOS `Settings/WhatsNew/WhatsNewActions.swift`.
 *
 * iOS's `whatsNewSettingsPage(_:over:)` / `WhatsNewSettingsPage` (a Settings page pushed over the
 * entry's page on What's New's own stack) fold into Settings' stack on Android:
 * [SettingsFeature] pushes `settingsPage()` for an `open` action, and Voice's page for a call
 * before Voice is set up.
 */

/** The page's button: what it does, in Title Case. */
fun WhatsNewEntry.Destination.title(voiceAvailable: Boolean): String = when (this) {
    WhatsNewEntry.Destination.NewSession -> "New Session"
    is WhatsNewEntry.Destination.Prompt -> "Try It"
    WhatsNewEntry.Destination.ModelList -> "Choose a Model"
    WhatsNewEntry.Destination.Call -> if (voiceAvailable) "Start a Call" else "Set Up Voice"
    is WhatsNewEntry.Destination.Open ->
        if (page == WhatsNewEntry.SettingsDestination.Servers) "Manage Servers" else "Open ${page.title}"
}

/** What the button does where its title alone doesn't say. */
val WhatsNewEntry.Destination.hint: String?
    get() = if (this is WhatsNewEntry.Destination.Prompt) "Open a new session with the prompt typed in" else null

val WhatsNewEntry.SettingsDestination.title: String
    get() = when (this) {
        WhatsNewEntry.SettingsDestination.Themes -> "Themes"
        WhatsNewEntry.SettingsDestination.Sessions -> "Sessions"
        WhatsNewEntry.SettingsDestination.Voice -> "Voice"
        WhatsNewEntry.SettingsDestination.Notifications -> "Notifications"
        WhatsNewEntry.SettingsDestination.Servers -> "Servers"
        WhatsNewEntry.SettingsDestination.Experimental -> "Experimental"
        is WhatsNewEntry.SettingsDestination.Experiment -> ExperimentalFeature.fromRawValue(id)?.title ?: "Experimental"
        WhatsNewEntry.SettingsDestination.Developer -> "Developer"
    }

/**
 * Whether the action can go now: a new session needs the server, so offline the page stays.
 */
fun SettingsFeature.State.canTryWhatsNew(destination: WhatsNewEntry.Destination): Boolean = when (destination) {
    WhatsNewEntry.Destination.NewSession, is WhatsNewEntry.Destination.Prompt ->
        connectionStatus != ConnectionStatus.disconnected
    else -> true
}

/** Notifications are hidden, not disabled, where the build has none. */
fun SettingsFeature.State.offersWhatsNew(destination: WhatsNewEntry.Destination): Boolean =
    destination != WhatsNewEntry.Destination.notifications || notificationPermission != null

/**
 * Whether a call can start now (iOS `VoiceRoutePreferences.isAvailable(server:)`: Voice Mode on
 * and `VoiceRoute.resolve` not unavailable, the broker resolved from the Voice Server choice).
 * False until Home hands Settings its server.
 */
fun whatsNewVoiceAvailable(server: OpenCodeServer?): Boolean =
    server != null && VoiceRoutePreferences().isAvailable(server)

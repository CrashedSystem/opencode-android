package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Destination
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.SettingsDestination
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.sessionLiveActivity: WhatsNewEntry
    get() = smaller(
        id = "session-live-activity",
        day = "2026-10-24",
        title = "Session Live Updates",
        symbol = "platter.filled.top.iphone",
        summary = "Turn on Session Live Updates in Settings › Experimental › Drawer to follow a run you send in a live notification, on the lock screen too, until it’s done or needs your input.",
        tryIt = Destination.Open(SettingsDestination.Experiment("session-live-activity")),
    )

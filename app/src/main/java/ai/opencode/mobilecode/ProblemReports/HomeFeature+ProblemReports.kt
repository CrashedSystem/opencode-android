@file:PortedFrom("OpenCode/ProblemReports/HomeFeature+ProblemReports.swift", "553a407", partial = "lastUsedModel without the Phone Reports exclusion; the rest lands when ProblemReportsFeature merges")

package ai.opencode.mobilecode

/// The newest session's model, never Phone Reports': reports are the app
/// talking, not you, and it may sit on the server's default.
val HomeFeature.State.lastUsedModel: OpenCodeModelRef?
    get() = sessions.firstOrNull { it.model != null }?.model

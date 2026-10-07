package ai.opencode.mobilecode

/**
 * Claims the iOS file this Kotlin file mirrors, at the upstream commit it was last synced against.
 * `scripts/ios-sync.py` reads these markers.
 */
@Target(AnnotationTarget.FILE)
@Retention(AnnotationRetention.SOURCE)
@Repeatable
annotation class PortedFrom(val path: String, val commit: String)

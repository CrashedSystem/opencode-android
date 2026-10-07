package ai.opencode.mobilecode

/**
 * Names the iOS source file this Kotlin file mirrors and the upstream commit it was last synced
 * against. `scripts/ios-sync.py` reads these markers to map iOS changes onto Kotlin files.
 */
@Target(AnnotationTarget.FILE)
@Retention(AnnotationRetention.SOURCE)
@Repeatable
annotation class PortedFrom(val path: String, val commit: String)

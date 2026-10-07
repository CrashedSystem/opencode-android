package ai.opencode.mobilecode

/**
 * Names the iOS source file this Kotlin file mirrors and the upstream commit it was last synced
 * against. `scripts/ios-sync.py` reads these markers to map iOS changes onto Kotlin files.
 * A non-empty [partial] says which part of the iOS file is ported so far; the sync gate keeps
 * counting the file as unported until the marker drops it.
 */
@Target(AnnotationTarget.FILE)
@Retention(AnnotationRetention.SOURCE)
@Repeatable
annotation class PortedFrom(val path: String, val commit: String, val partial: String = "")

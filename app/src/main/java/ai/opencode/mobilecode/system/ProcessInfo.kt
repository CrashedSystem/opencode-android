package ai.opencode.mobilecode.system

import android.os.Bundle

/// Foundation's `ProcessInfo.processInfo`, the launch environment and
/// arguments the lab hooks read. iOS UI tests set them on the launch; a debug
/// `MainActivity` takes the same names from its launch intent's extras before
/// the root store exists, and release builds never fill them. An extra named
/// `OPENCODE_…` is an environment value. An extra whose name starts with `-`
/// is a launch argument: `--ez -lab-pasteboard-image true` passes the flag,
/// and `--es -lab-interface-style dark` passes it with its value.
class ProcessInfo(
    val environment: Map<String, String> = emptyMap(),
    val arguments: List<String> = emptyList(),
) {
    /// The value after `-key`, which Foundation's argument domain reads as the
    /// user default named `key`.
    fun argumentValue(key: String): String? =
        arguments.indexOf("-$key").takeIf { it >= 0 }?.let { arguments.getOrNull(it + 1) }

    companion object {
        var processInfo = ProcessInfo()

        @Suppress("DEPRECATION")
        fun launch(extras: Bundle?): ProcessInfo {
            val values = extras?.keySet().orEmpty().sorted().associateWith { extras?.get(it) }
            return ProcessInfo(
                environment = values.filterKeys { it.startsWith("OPENCODE_") }.mapValues { it.value.toString() },
                arguments = values.filterKeys { it.startsWith("-") }.flatMap { (key, value) ->
                    when (value) {
                        true -> listOf(key)
                        is String -> listOf(key, value)
                        else -> emptyList()
                    }
                },
            )
        }
    }
}

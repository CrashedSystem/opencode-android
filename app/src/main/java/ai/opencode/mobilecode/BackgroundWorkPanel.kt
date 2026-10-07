@file:PortedFrom("OpenCode/BackgroundWorkPanel.swift", "553a407", partial = "ShellElapsedTime")

package ai.opencode.mobilecode

import ai.opencode.mobilecode.system.TextStyles
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.delay

/// Running time as a clock (`0:42`, `12:03`, `1:02:03`), ticking each second.
@Composable
fun ShellElapsedTime(since: Long, until: Long? = null, modifier: Modifier = Modifier) {
    if (until != null) {
        ElapsedLabel(since, until, modifier)
        return
    }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(since) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    ElapsedLabel(since, now, modifier)
}

@Composable
private fun ElapsedLabel(since: Long, at: Long, modifier: Modifier) {
    val seconds = maxOf(0, ((at - since) / 1_000).toInt())
    Text(
        elapsedClock(seconds),
        modifier.semantics { contentDescription = elapsedAccessibility(seconds) },
        style = TextStyles.subheadline,
    )
}

internal fun elapsedClock(seconds: Int): String {
    val hours = seconds / 3_600
    val minutes = (seconds % 3_600) / 60
    val rest = seconds % 60
    if (hours > 0) return "%d:%02d:%02d".format(hours, minutes, rest)
    return "%d:%02d".format(minutes, rest)
}

private fun elapsedAccessibility(seconds: Int): String {
    val hours = seconds / 3_600
    val minutes = (seconds % 3_600) / 60
    val rest = seconds % 60
    return "$hours hours, $minutes minutes, $rest seconds"
}

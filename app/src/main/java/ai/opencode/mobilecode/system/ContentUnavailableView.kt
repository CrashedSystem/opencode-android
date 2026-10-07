package ai.opencode.mobilecode.system

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** SwiftUI's `ContentUnavailableView(title, systemImage:, description:)`, with optional actions below. */
@Composable
fun ContentUnavailableView(
    title: String,
    systemImage: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    actions: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SystemImage(systemImage, null, Modifier.padding(bottom = 6.dp).symbolSize(40), tint = SystemColors.secondaryLabel)
        Text(
            title,
            Modifier.semantics { heading() },
            style = TextStyles.title2.bold,
            color = SystemColors.label,
            textAlign = TextAlign.Center,
        )
        if (description != null) {
            Text(description, style = TextStyles.subheadline, color = SystemColors.secondaryLabel, textAlign = TextAlign.Center)
        }
        if (actions != null) {
            Column(Modifier.padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) { actions() }
        }
    }
}

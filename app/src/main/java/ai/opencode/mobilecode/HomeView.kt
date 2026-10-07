@file:PortedFrom("OpenCode/HomeView.swift", "553a407", partial = "DrawerShortcutLabel")

package ai.opencode.mobilecode

import ai.opencode.mobilecode.system.SystemColors
import ai.opencode.mobilecode.system.SystemImage
import ai.opencode.mobilecode.system.TextStyles
import ai.opencode.mobilecode.system.symbolSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/// A drawer shortcut row in the session rows’ style: a tinted symbol, the
/// title, and an accessory in the session indicators’ column.
@Composable
fun DrawerShortcutLabel(
    title: String,
    systemImage: String,
    modifier: Modifier = Modifier,
    accessory: @Composable () -> Unit,
) {
    val symbolWidth = with(LocalDensity.current) { 20.sp.toDp() }

    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Leading-aligned so each symbol starts on the session titles’ edge.
        Box(Modifier.width(symbolWidth), contentAlignment = Alignment.CenterStart) {
            SystemImage(systemImage, contentDescription = null, modifier = Modifier.symbolSize(TextStyles.body), tint = SystemColors.tint)
        }
        Text(title, style = TextStyles.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.weight(1f))
        Box(Modifier.widthIn(min = 18.dp), contentAlignment = Alignment.Center) {
            accessory()
        }
    }
}

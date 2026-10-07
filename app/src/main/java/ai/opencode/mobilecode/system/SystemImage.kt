package ai.opencode.mobilecode.system

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

@Composable
fun SystemImage(
    name: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) {
    val id = SymbolNames.drawable(name)
    if (id == null) {
        Box(modifier.size(24.dp), contentAlignment = Alignment.Center) {
            Text(name, color = SystemColors.systemRed, style = TextStyles.caption2, maxLines = 2)
        }
        return
    }
    Icon(
        painter = painterResource(id),
        contentDescription = contentDescription,
        modifier = modifier,
        tint = tint,
    )
}

@file:PortedFrom("OpenCode/AttachmentPicker.swift", "553a407")

package ai.opencode.mobilecode

import ai.opencode.mobilecode.system.SystemColors
import ai.opencode.mobilecode.system.SystemImage
import ai.opencode.mobilecode.system.TextStyles
import ai.opencode.mobilecode.system.medium
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

object AttachmentPickerButton {
    const val maxPhotoSelection = 4
}

@Composable
fun AttachmentPickerButton(
    isLoading: Boolean,
    anchors: AttachmentMenuAnchors,
    lift: Float = 0f,
    action: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .size(44.dp)
            .attachmentMenuAnchor(anchors, { anchors.button = it }, lift)
            .clickable(enabled = !isLoading, onClick = action)
            .semantics { contentDescription = if (isLoading) "Adding attachment" else "Add attachment" },
        contentAlignment = Alignment.Center,
    ) {
        if (isLoading) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = SystemColors.label, strokeWidth = 2.dp)
        } else {
            SystemImage("plus", contentDescription = null, tint = SystemColors.label, modifier = Modifier.size(20.dp))
        }
    }
}

class AttachmentMenuAnchors {
    var button: Rect = Rect.Zero
    var composer: Rect = Rect.Zero

    companion object {
        const val space = "attachment-menu"
    }
}

fun Modifier.attachmentMenuAnchor(
    anchors: AttachmentMenuAnchors,
    assign: (Rect) -> Unit,
    lift: Float = 0f,
): Modifier = onGloballyPositioned { coordinates ->
    val frame = coordinates.boundsInRoot()
    assign(frame.copy(top = frame.top + lift, bottom = frame.bottom + lift))
}

@Composable
fun StripPhotoLocation(content: @Composable () -> Unit) {
    content()
}

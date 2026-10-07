package ai.opencode.mobilecode.system

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

enum class SheetDetent { Medium, Large }

data class SystemMenuItem(
    val title: String,
    val systemImage: String? = null,
    val destructive: Boolean = false,
    val onClick: () -> Unit,
)

data class SystemMenuSection(val items: List<SystemMenuItem>)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SystemSheet(
    onDismissRequest: () -> Unit,
    initialDetent: SheetDetent = SheetDetent.Medium,
    content: @Composable ColumnScope.() -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    LaunchedEffect(initialDetent) {
        when (initialDetent) {
            SheetDetent.Medium -> sheetState.partialExpand()
            SheetDetent.Large -> sheetState.expand()
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = SystemColors.systemBackground,
        contentColor = SystemColors.label,
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        Column(Modifier.fillMaxWidth(), content = content)
    }
}

@Composable
fun SystemMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    sections: List<SystemMenuSection>,
    modifier: Modifier = Modifier,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
    ) {
        SystemMenuSections(sections)
    }
}

@Composable
fun SystemMenuSections(sections: List<SystemMenuSection>) {
    sections.forEachIndexed { index, section ->
        if (index > 0) HorizontalDivider(color = SystemColors.separator)
        section.items.forEach { item ->
            val color = if (item.destructive) SystemColors.systemRed else SystemColors.label
            DropdownMenuItem(
                text = { Text(item.title, color = color, style = TextStyles.body) },
                leadingIcon = item.systemImage?.let { name ->
                    { SystemImage(name, contentDescription = null, tint = color) }
                },
                onClick = item.onClick,
            )
        }
    }
}

@Composable
fun SystemContextMenu(
    sections: List<SystemMenuSection>,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(
        modifier.combinedClickable(
            onClick = { onClick?.invoke() },
            onLongClick = { expanded = true },
        ),
    ) {
        content()
        SystemMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            sections = sections,
        )
    }
}

@Composable
fun SystemAlert(
    onDismissRequest: () -> Unit,
    title: String,
    confirm: String,
    onConfirm: () -> Unit,
    text: String? = null,
    dismiss: String? = null,
    destructive: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(title, style = TextStyles.headline) },
        text = text?.let { message ->
            { Text(message, style = TextStyles.body, color = SystemColors.secondaryLabel) }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirm, color = if (destructive) SystemColors.systemRed else SystemColors.systemBlue)
            }
        },
        dismissButton = dismiss?.let { label ->
            {
                TextButton(onClick = onDismissRequest) {
                    Text(label, color = SystemColors.systemBlue)
                }
            }
        },
        containerColor = SystemColors.secondarySystemGroupedBackground,
        titleContentColor = SystemColors.label,
        textContentColor = SystemColors.secondaryLabel,
    )
}

@Composable
fun SystemConfirmation(
    onDismissRequest: () -> Unit,
    title: String,
    confirm: String,
    onConfirm: () -> Unit,
    text: String? = null,
    dismiss: String = "Cancel",
) {
    SystemAlert(
        onDismissRequest = onDismissRequest,
        title = title,
        text = text,
        confirm = confirm,
        onConfirm = onConfirm,
        dismiss = dismiss,
        destructive = true,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeToDeleteRow(
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value != SwipeToDismissBoxValue.EndToStart) return@rememberSwipeToDismissBoxState false
            onDelete()
            true
        },
    )
    SwipeToDismissBox(
        state = state,
        modifier = modifier,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(SystemColors.systemRed)
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                SystemImage("trash", contentDescription = "Delete", tint = Color.White)
            }
        },
        content = content,
    )
}

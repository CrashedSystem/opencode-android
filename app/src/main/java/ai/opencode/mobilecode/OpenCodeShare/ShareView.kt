@file:PortedFrom("OpenCodeShare/ShareView.swift", "553a407")

package ai.opencode.mobilecode

import ai.opencode.mobilecode.system.GroupedList
import ai.opencode.mobilecode.system.NavigationStack
import ai.opencode.mobilecode.system.NavigationTitle
import ai.opencode.mobilecode.system.SystemButton
import ai.opencode.mobilecode.system.SystemColors
import ai.opencode.mobilecode.system.SystemImage
import ai.opencode.mobilecode.system.TextStyles
import ai.opencode.mobilecode.system.TitleDisplayMode
import ai.opencode.mobilecode.system.Toolbar
import ai.opencode.mobilecode.system.ToolbarItem
import ai.opencode.mobilecode.system.ToolbarItemPlacement
import ai.opencode.mobilecode.system.symbolSize
import ai.opencode.mobilecode.tca.Binding
import ai.opencode.mobilecode.tca.alert
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun ShareView(
    model: ShareModel,
    onCancel: () -> Unit,
    onAdded: () -> Unit,
) {
    NavigationStack(Modifier.fillMaxSize().background(SystemColors.systemGroupedBackground)) {
        val targets = model.targets
        if (targets != null) {
            form(model, targets, Modifier.weight(1f))
        } else {
            Column(
                Modifier.fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                SystemImage("desktopcomputer", contentDescription = null, modifier = Modifier.symbolSize(48), tint = SystemColors.secondaryLabel)
                Spacer(Modifier.size(12.dp))
                Text("Connect to a Server First", style = TextStyles.title2, color = SystemColors.label)
                Spacer(Modifier.size(6.dp))
                Text(
                    "Open OpenCode and connect to your computer, then share again.",
                    style = TextStyles.body,
                    color = SystemColors.secondaryLabel,
                )
            }
        }
        NavigationTitle("Add to OpenCode", displayMode = TitleDisplayMode.inline)
        Toolbar {
            ToolbarItem(placement = ToolbarItemPlacement.cancellationAction) {
                SystemButton("Cancel", action = onCancel)
            }
            if (targets != null) {
                ToolbarItem(placement = ToolbarItemPlacement.confirmationAction) {
                    Text(
                        "Add",
                        Modifier
                            .alpha(if (model.canAdd) 1f else 0.35f)
                            .clickable(enabled = model.canAdd, role = Role.Button) {
                                if (model.save()) onAdded()
                            },
                        style = TextStyles.headline,
                        color = SystemColors.systemBlue,
                    )
                }
            }
        }
        alert(
            "Couldn’t Add to OpenCode",
            isPresented = Binding(
                getter = { model.saveError != null },
                setter = { shown -> if (!shown) model.saveError = null },
            ),
            message = model.saveError ?: "",
            buttons = listOf(ai.opencode.mobilecode.tca.AlertButton("OK") { model.saveError = null }),
        )
    }
}

@Composable
private fun form(model: ShareModel, targets: ShareTargets, modifier: Modifier = Modifier) {
    GroupedList(modifier) {
        Section(
            footer = {
                Column {
                    if (model.skippedImageCount > 0) {
                        Text(
                            if (model.skippedImageCount == 1) {
                                "1 image couldn’t be added"
                            } else {
                                "${model.skippedImageCount} images couldn’t be added"
                            },
                        )
                    }
                    if (model.skippedFileCount > 0) {
                        Text(
                            if (model.skippedFileCount == 1) {
                                "1 file couldn’t be added"
                            } else {
                                "${model.skippedFileCount} files couldn’t be added"
                            },
                        )
                    }
                }
            },
        ) {
            if (model.isLoading && !model.hasContent) {
                ForEach(listOf("loading"), { it }) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            Modifier.semantics { contentDescription = "Loading shared items" },
                            color = SystemColors.secondaryLabel,
                        )
                    }
                }
            }
            if (model.images.isNotEmpty()) {
                ForEach(listOf("images"), { it }) { imageStrip(model) }
            }
            ForEach(model.files, { it.id }) { file ->
                Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
                    SystemImage(
                        AttachmentFileType.symbolName(file.name, file.mime),
                        contentDescription = null,
                        modifier = Modifier.symbolSize(22),
                        tint = SystemColors.secondaryLabel,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(file.name, maxLines = 1, overflow = TextOverflow.MiddleEllipsis, style = TextStyles.body, color = SystemColors.label)
                        Text(
                            AttachmentFileType.subtitle(file.name, file.mime, file.size),
                            style = TextStyles.footnote,
                            color = SystemColors.secondaryLabel,
                        )
                    }
                }
            }
            ForEach(model.sharedText, { it }) { text ->
                Text(text, style = TextStyles.callout, color = SystemColors.secondaryLabel, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            ForEach(listOf("message"), { it }) {
                BasicTextField(
                    value = model.message,
                    onValueChange = { model.message = it },
                    textStyle = TextStyles.body.copy(color = SystemColors.label),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 22.dp),
                    decorationBox = { inner ->
                        Box {
                            if (model.message.isEmpty()) {
                                Text("Add a message", style = TextStyles.body, color = SystemColors.placeholderText)
                            }
                            inner()
                        }
                    },
                )
            }
        }
        Section(
            header = { Text(targets.serverName) },
            footer = { Text("Shared items wait in the session’s composer. Open OpenCode to send them.") },
        ) {
            ForEach(listOf(ShareModel.Destination.newSession), { "new" }) {
                destinationRow(model, ShareModel.Destination.newSession, "New Session", targets.newSessionProject?.name)
            }
        }
        if (targets.pinned.isNotEmpty()) {
            Section(header = { Text("Pinned") }) {
                ForEach(targets.pinned) { session ->
                    destinationRow(model, ShareModel.Destination.session(session), session.title, session.projectName)
                }
            }
        }
        if (targets.recents.isNotEmpty()) {
            Section(header = { Text("Recents") }) {
                ForEach(targets.recents) { session ->
                    destinationRow(model, ShareModel.Destination.session(session), session.title, session.projectName)
                }
            }
        }
    }
}

@Composable
private fun imageStrip(model: ShareModel) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        model.images.forEachIndexed { index, image ->
            Image(
                bitmap = image.preview.asImageBitmap(),
                contentDescription = "Image ${index + 1} of ${model.images.size}",
                modifier = Modifier.size(72.dp).clip(RoundedCornerShape(10.dp)),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

@Composable
private fun destinationRow(model: ShareModel, destination: ShareModel.Destination, title: String, detail: String?) {
    val isSelected = model.destination == destination
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .semantics {
                selected = isSelected
                contentDescription = title
            }
            .clickable(role = Role.Button) { model.destination = destination },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyles.body, color = SystemColors.label)
            if (!detail.isNullOrEmpty()) {
                Text(detail, style = TextStyles.subheadline, color = SystemColors.secondaryLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        SystemImage(
            "checkmark",
            contentDescription = null,
            modifier = Modifier.symbolSize(17).alpha(if (isSelected) 1f else 0f),
            tint = SystemColors.tint,
        )
    }
}

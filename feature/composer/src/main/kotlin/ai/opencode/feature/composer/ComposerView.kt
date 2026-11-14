package ai.opencode.feature.composer

import ai.opencode.core.R as CoreR
import ai.opencode.core.experiments.key
import ai.opencode.core.store.StoreOf
import ai.opencode.core.store.collectState
import ai.opencode.core.strings.localized
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.SFSymbol
import ai.opencode.core.ui.drawerPanHorizontalScroller
import ai.opencode.core.ui.openCodeTapTarget
import ai.opencode.server.model.PastedText
import androidx.compose.ui.graphics.asImageBitmap
import ai.opencode.feature.composer.modelpicker.ModelPickerEvent
import ai.opencode.feature.composer.modelpicker.OnePickerChip
import ai.opencode.feature.composer.modelpicker.OnePickerController
import ai.opencode.feature.composer.modelpicker.OnePickerSheetHost
import ai.opencode.feature.composer.plusmenu.PlusMenuGlassSurface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * The composer: a 28 dp capsule with the message field, its attachments, and the control row
 * (+, model chip, Send), with a pending request's panel growing out of it (D-31 B), the
 * attachment menu over it, and the model sheet. Built from the ports of iOS `OpenCode/Composer/`.
 *
 * Fill the session's screen with it (it draws its overlays over everything and sits the
 * capsule at the bottom, above the keyboard). The session area owns the iOS
 * `SessionView+Composer` capsule and may compose these pieces itself instead.
 */
@Composable
fun ComposerView(
    store: StoreOf<ComposerFeature.State, ComposerFeature.Action>,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(store) { store.send(ComposerFeature.Action.Task) }
    val state by store.collectState()
    val send: (ComposerFeature.Action) -> Unit = { store.send(it) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val anchors = remember { AttachmentMenuAnchors() }
    val press = remember { AttachmentMenuPress() }
    val imageImporter = remember(scope) { ComposerImageImporter(scope) }
    val fileImporter = remember(scope) { ComposerFileImporter(scope) }

    val photos = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uris = ComposerPickers.uris(result.resultCode, result.data)
        importURIs(uris, imageImporter, fileImporter, send)
    }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uris = ComposerPickers.uris(result.resultCode, result.data)
        importURIs(uris, imageImporter, fileImporter, send)
    }

    fun choose(item: AttachmentMenuItem) {
        send(ComposerFeature.Action.AttachmentMenuDismissed)
        when (item) {
            AttachmentMenuItem.Photos, AttachmentMenuItem.Camera -> photos.launch(ComposerPickers.photosIntent(includesVideos = true))
            AttachmentMenuItem.Files -> files.launch(ComposerPickers.filesIntent())
            AttachmentMenuItem.Location -> send(ComposerFeature.Action.Attachments(ComposerAttachmentsFeature.Action.LocationTapped))
            AttachmentMenuItem.Paste -> Unit
        }
    }

    Box(modifier.fillMaxSize()) {
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            state.sendError?.let { error ->
                Text(error, style = OpenCodeTextStyles.footnote, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(8.dp))
            }
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 3.dp,
                shadowElevation = 2.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .animateContentSize()
                    .attachmentMenuAnchor { anchors.composer = it }
                    .testTag("composer"),
            ) {
                Column {
                    AnimatedVisibility(state.requestDock.hasPendingRequests) {
                        RequestDockView(
                            dock = state.requestDock,
                            context = RequestDockContext(currentSessionID = state.sessionID),
                            isKeyboardUp = state.isFocused,
                            send = { send(ComposerFeature.Action.RequestDock(it)) },
                        )
                    }
                    AttachmentsRow(state, send)
                    Box {
                    if (state.draft.isEmpty()) {
                        Text(
                            state.requestDock.requestBar(state.draft)?.placeholder ?: stringResource(R.string.composer_message_placeholder),
                            style = OpenCodeTextStyles.body,
                            color = OpenCodeTheme.colors.tertiaryText,
                            modifier = Modifier.padding(start = 16.dp, top = 16.dp).clearAndSetSemantics {},
                        )
                    }
                    ComposerTextInput(
                        text = state.draft,
                        onTextChange = { send(ComposerFeature.Action.DraftChanged(it)) },
                        isFocused = state.isFocused,
                        onFocusedChange = { send(ComposerFeature.Action.FocusChanged(it)) },
                        expanded = true,
                        onImagePaste = { importURIs(it, imageImporter, fileImporter, send) },
                        onLongTextPaste = if (state.isAnsweringRequest) null else { text ->
                            send(ComposerFeature.Action.Attachments(ComposerAttachmentsFeature.Action.AttachmentAdded(PastedText.attachment(text))))
                        },
                        accessibilityLabel = state.requestDock.requestBar(state.draft)?.fieldLabel ?: stringResource(R.string.composer_message_placeholder),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    )
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AttachmentPickerButton(
                            isLoading = imageImporter.isLoading || fileImporter.isLoading || state.attachments.isLocating,
                            isOpen = state.isAttachmentMenuOpen,
                            anchors = anchors,
                            press = press,
                            rows = if (state.isAttachmentMenuOpen) anchors.rows else emptyMap(),
                            onToggle = { send(ComposerFeature.Action.PlusTapped) },
                            onChoose = ::choose,
                        )
                        if (!state.isAnsweringRequest) {
                            // One Picker's chip; this host opens the list in its sheet, never the morph.
                            val chipController = remember { OnePickerController() }
                            OnePickerChip(
                                state = state.modelPicker,
                                controller = chipController,
                                onEvent = { send(ComposerFeature.Action.ModelPicker(it)) },
                                onOpen = { send(ComposerFeature.Action.ModelChipTapped) },
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        SendButton(
                            enabled = state.requestDock.requestBar(state.draft)?.let { it.isReady && state.requestDock.respondingRequestIDs.isEmpty() } ?: state.canSend,
                            verb = sendVerb(state),
                            onClick = { send(ComposerFeature.Action.SendTapped) },
                        )
                    }
                }
            }
        }

        if (state.isAttachmentMenuOpen) {
            AttachmentMenuDismissArea(onDismiss = { send(ComposerFeature.Action.AttachmentMenuDismissed) })
        }
        val menuItems = listOf(AttachmentMenuItem.Camera, AttachmentMenuItem.Photos, AttachmentMenuItem.Files, AttachmentMenuItem.Location)
        AttachmentMenuMeasure(anchors = anchors, items = menuItems)
        PlusMenuGlassSurface(
            anchors = anchors,
            isOpen = state.isAttachmentMenuOpen,
            items = menuItems,
            highlighted = press.highlighted,
            onChoose = ::choose,
            onPaste = { send(ComposerFeature.Action.AttachmentMenuDismissed) },
            onDismiss = { send(ComposerFeature.Action.AttachmentMenuDismissed) },
            modifier = Modifier.fillMaxSize(),
        )

        if (state.isModelPickerOpen) {
            OnePickerSheetHost(
                state = state.modelPicker,
                onEvent = { send(ComposerFeature.Action.ModelPicker(it)) },
                onDismiss = { send(ComposerFeature.Action.ModelPicker(ModelPickerEvent.Dismiss)) },
                onPickModel = { model, level -> send(ComposerFeature.Action.ModelPicker(ModelPickerEvent.ModelPickedWithLevel(model, level))) },
            )
        }
    }
}

/** iOS's bar: Send (null, the arrow), or the request bar's verb while answering (Next, Deny, Dismiss). */
private fun sendVerb(state: ComposerFeature.State): String? = when (val bar = state.requestDock.requestBar(state.draft)) {
    null -> null
    is RequestBar.Decline -> bar.verb.title
    is RequestBar.Answer -> if (bar.isLast) null else localized(CoreR.string.common_next)
    is RequestBar.Choose -> if (bar.isLast) null else localized(CoreR.string.common_next)
}

@Composable
private fun SendButton(enabled: Boolean, verb: String?, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val label = verb ?: stringResource(CoreR.string.common_send)
    Box(
        Modifier
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .openCodeTapTarget()
            .semantics { contentDescription = label }
            .testTag("composer-send"),
        contentAlignment = Alignment.Center,
    ) {
        if (verb != null) {
            // The request bar's verb (Deny, Dismiss, Next) reads as a word, as iOS's bar does.
            Box(
                Modifier
                    .heightIn(min = 34.dp)
                    .clip(RoundedCornerShape(17.dp))
                    .background(if (enabled) colors.primary else colors.surfaceContainerHighest)
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = OpenCodeTextStyles.subheadline.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
                    color = if (enabled) colors.onPrimary else OpenCodeTheme.colors.secondaryText,
                )
            }
            return@Box
        }
        Box(
            Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(if (enabled) colors.primary else colors.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            OpenCodeIcon(
                SFSymbol.ArrowUp,
                contentDescription = null,
                size = 18.dp,
                tint = if (enabled) colors.onPrimary else OpenCodeTheme.colors.secondaryText,
            )
        }
    }
}

@Composable
private fun AttachmentsRow(state: ComposerFeature.State, send: (ComposerFeature.Action) -> Unit) {
    val attachments = state.attachments
    if (!attachments.hasContent && !attachments.isLocating) return
    val scroll = rememberScrollState()
    val photoDescription = stringResource(R.string.composer_attachment_photo_description)
    Row(
        Modifier
            .fillMaxWidth()
            .drawerPanHorizontalScroller(scroll)
            .horizontalScroll(scroll)
            .padding(start = 12.dp, end = 12.dp, top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        attachments.pendingAttachments.forEachIndexed { index, attachment ->
            val summary = attachment.pasted
            if (summary != null) {
                PastedTextCard(summary = summary, onOpen = {}, modifier = Modifier.clickable {
                    send(ComposerFeature.Action.Attachments(ComposerAttachmentsFeature.Action.AttachmentRemoved(index)))
                })
            } else {
                Box(
                    Modifier
                        .size(76.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .clickable { send(ComposerFeature.Action.Attachments(ComposerAttachmentsFeature.Action.AttachmentRemoved(index))) }
                        .semantics { contentDescription = attachment.name ?: photoDescription },
                    contentAlignment = Alignment.Center,
                ) {
                    AttachmentThumbnail(attachment)
                }
            }
        }
        attachments.pendingFiles.forEach { file ->
            ComposerFileChip(
                file = file,
                canRetry = true,
                onRetry = { send(ComposerFeature.Action.Attachments(ComposerAttachmentsFeature.Action.FileRetryTapped(file.id))) },
            )
        }
        if (attachments.pendingLocation != null || attachments.isLocating) {
            ComposerLocationAttachment(
                location = attachments.pendingLocation,
                isLocating = attachments.isLocating,
                gateProgress = 1f,
                onAppear = {},
                onRemove = { send(ComposerFeature.Action.Attachments(ComposerAttachmentsFeature.Action.LocationRemoved)) },
                onOpen = {},
            )
        }
    }
}

@Composable
private fun AttachmentThumbnail(attachment: ai.opencode.server.model.OpenCodeOutgoingAttachment) {
    val bitmap = remember(attachment.id) {
        if (!attachment.isImage) null
        else runCatching { android.graphics.BitmapFactory.decodeByteArray(attachment.data, 0, attachment.data.size) }.getOrNull()
    }
    if (bitmap != null) {
        androidx.compose.foundation.Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    } else {
        OpenCodeIcon(SFSymbol.Doc, contentDescription = null)
    }
}

/** Images go inline (re-encoded); everything else stages for upload (iOS `ComposerFileRouting`). */
private fun importURIs(
    uris: List<android.net.Uri>,
    images: ComposerImageImporter,
    files: ComposerFileImporter,
    send: (ComposerFeature.Action) -> Unit,
) {
    if (uris.isEmpty()) return
    val (inline, other) = uris.partition { ComposerFileRouting.isInlineImage(it) }
    if (inline.isNotEmpty()) {
        images.start(
            sources = inline.map { ComposerImageSource.ItemProvider(it) },
            onAttachment = { send(ComposerFeature.Action.Attachments(ComposerAttachmentsFeature.Action.AttachmentAdded(it))) },
        )
    }
    if (other.isNotEmpty()) {
        files.start(
            sources = other.map { ComposerFileSource.Url(it) },
            onStaged = { send(ComposerFeature.Action.Attachments(ComposerAttachmentsFeature.Action.FilesStaged(it))) },
            onFailure = {},
        )
    }
}

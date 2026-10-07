@file:PortedFrom("OpenCode/AttachmentMenu.swift", "553a407")

package ai.opencode.mobilecode

import ai.opencode.mobilecode.system.GlassSurface
import ai.opencode.mobilecode.system.LocalGlassBlurEnabled
import ai.opencode.mobilecode.system.SystemColors
import ai.opencode.mobilecode.system.SystemImage
import ai.opencode.mobilecode.system.TextStyles
import ai.opencode.mobilecode.system.glassEffect
import ai.opencode.mobilecode.system.semibold
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.ext.SdkExtensions
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class AttachmentMenuPhase { closed, menu, photos }

enum class PhotoPickerKind { embedded, system }

fun photoPickerKind(): PhotoPickerKind = if (embeddedPhotoPickerSupported()) PhotoPickerKind.embedded else PhotoPickerKind.system

fun embeddedPhotoPickerSupported(): Boolean {
    if (Build.VERSION.SDK_INT < 34) return false
    return SdkExtensions.getExtensionVersion(Build.VERSION_CODES.UPSIDE_DOWN_CAKE) >= 15
}

fun cameraAvailable(context: Context): Boolean =
    context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)

fun clipboardHasImage(context: Context): Boolean {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return false
    val description = clipboard.primaryClipDescription ?: return false
    return description.hasMimeType("image/png") || description.hasMimeType("image/jpeg") ||
        description.hasMimeType("image/gif") || description.hasMimeType("image/webp") ||
        description.hasMimeType("image/*")
}

enum class InlinePhotoFilter {
    all,
    screenshots,
    videos,
    ;

    val title: String
        get() = when (this) {
            all -> "All Photos"
            screenshots -> "Screenshots"
            videos -> "Videos"
        }

    val systemImage: String
        get() = when (this) {
            all -> "photo.on.rectangle"
            screenshots -> "camera.viewfinder"
            videos -> "video"
        }

    companion object {
        fun visible(includesVideos: Boolean): List<InlinePhotoFilter> =
            entries.filter { includesVideos || it != videos }
    }
}

@Composable
fun AttachmentMenuOverlay(
    phase: AttachmentMenuPhase,
    onPhase: (AttachmentMenuPhase) -> Unit,
    anchors: AttachmentMenuAnchors,
    selection: InlinePhotoSelection,
    includesVideos: Boolean,
    canUseCamera: Boolean,
    canAttachFiles: Boolean,
    canPaste: Boolean,
    onCamera: () -> Unit,
    onCameraCapture: (ByteArray) -> Unit,
    onFiles: () -> Unit,
    onFilesPicked: (List<Uri>) -> Unit,
    onPaste: (List<NSItemProvider>) -> Unit,
    onPhotos: (List<PhotosPickerItem>) -> Unit,
    pickerReservation: Float,
    onPickerReservation: (Float) -> Unit,
    reduceMotion: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var filter by remember { mutableStateOf(InlinePhotoFilter.all) }
    var showCamera by remember { mutableStateOf(false) }
    var size by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    val filesLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        onFilesPicked(uris)
    }
    val photosLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(AttachmentPickerButton.maxPhotoSelection),
    ) { uris ->
        val picks = uris.map { uri ->
            val type = context.contentResolver.getType(uri).orEmpty()
            PhotosPickerItem(
                itemIdentifier = uri.toString(),
                supportedContentTypes = listOf(type),
                uri = uri,
            )
        }
        selection.items = picks
        onPhotos(picks)
    }
    val frames = frames(
        width = size.width.toFloat(),
        height = size.height.toFloat(),
        anchors = anchors,
        rows = rows(canUseCamera, canAttachFiles, canPaste),
        rowHeight = with(density) { 52.dp.toPx() },
        density = density,
    )
    if (frames.reservation != pickerReservation) onPickerReservation(frames.reservation)
    Box(
        modifier
            .fillMaxSize()
            .onSizeChanged { size = it }
            .semantics { testTag = "attachment-menu" },
    ) {
        if (phase != AttachmentMenuPhase.closed) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable { onPhase(AttachmentMenuPhase.closed) }
                    .pointerInput(phase) {
                        detectVerticalDragGestures { _, drag ->
                            if (drag > 30) onPhase(AttachmentMenuPhase.closed)
                        }
                    },
            )
        }
        if (phase == AttachmentMenuPhase.photos && photoPickerKind() == PhotoPickerKind.embedded) {
            EmbeddedPhotoCard(
                selection = selection,
                filter = filter,
                includesVideos = includesVideos,
                onPhotos = onPhotos,
                modifier = Modifier.offset { IntOffset(frames.card.left.roundToInt(), frames.card.top.roundToInt()) }
                    .size(with(density) { frames.card.width.toDp() }, with(density) { frames.card.height.toDp() }),
            )
        }
        if (phase == AttachmentMenuPhase.menu) {
            val rect = frames.menu.translate(0f, dragOffset)
            MenuPanel(
                rows = rows(canUseCamera, canAttachFiles, canPaste),
                rect = rect,
                onCamera = {
                    showCamera = true
                    onCamera()
                    onPhase(AttachmentMenuPhase.closed)
                },
                onPhotos = {
                    if (photoPickerKind() == PhotoPickerKind.embedded) {
                        onPhase(AttachmentMenuPhase.photos)
                    } else {
                        val type = if (includesVideos) {
                            ActivityResultContracts.PickVisualMedia.ImageAndVideo
                        } else {
                            ActivityResultContracts.PickVisualMedia.ImageOnly
                        }
                        photosLauncher.launch(PickVisualMediaRequest(type))
                        onPhase(AttachmentMenuPhase.closed)
                    }
                },
                onFiles = {
                    onFiles()
                    filesLauncher.launch(arrayOf("*/*"))
                    onPhase(AttachmentMenuPhase.closed)
                },
                onPaste = {
                    val providers = clipboardImages(context)
                    if (providers.isNotEmpty()) onPaste(providers)
                    onPhase(AttachmentMenuPhase.closed)
                },
                onDrag = { dragOffset = it },
                onDismiss = {
                    dragOffset = 0f
                    onPhase(AttachmentMenuPhase.closed)
                },
            )
        }
        if (phase == AttachmentMenuPhase.photos) {
            PickerControls(
                filter = filter,
                includesVideos = includesVideos,
                onFilter = { filter = it },
                onBack = { onPhase(AttachmentMenuPhase.menu) },
                modifier = Modifier.offset { IntOffset(frames.card.left.roundToInt(), frames.card.top.roundToInt()) }
                    .size(with(density) { frames.card.width.toDp() }, with(density) { frames.card.height.toDp() }),
            )
        }
    }
    if (showCamera) {
        AttachmentCameraView(
            onCapture = { bytes ->
                showCamera = false
                onCameraCapture(bytes)
            },
            onBack = { showCamera = false },
        )
    }
}

private fun rows(canUseCamera: Boolean, canAttachFiles: Boolean, canPaste: Boolean): List<MenuRow> = buildList {
    if (canUseCamera) add(MenuRow.camera)
    add(MenuRow.photos)
    if (canAttachFiles) add(MenuRow.files)
    if (canPaste) add(MenuRow.paste)
}

private enum class MenuRow { camera, photos, files, paste }

private data class MenuFrames(
    val closed: Rect,
    val menu: Rect,
    val card: Rect,
    val reservation: Float,
)

private fun frames(
    width: Float,
    height: Float,
    anchors: AttachmentMenuAnchors,
    rows: List<MenuRow>,
    rowHeight: Float,
    density: androidx.compose.ui.unit.Density,
): MenuFrames {
    val button = anchors.button
    val composer = when {
        anchors.composer != Rect.Zero -> anchors.composer
        button != Rect.Zero -> button
        else -> Rect(with(density) { 16.dp.toPx() }, height - with(density) { 88.dp.toPx() }, width - with(density) { 16.dp.toPx() }, height - with(density) { 16.dp.toPx() })
    }
    val menuWidth = minOf(maxOf(width * 0.62f, with(density) { 230.dp.toPx() }), width - with(density) { 40.dp.toPx() })
    val padding = with(density) { 8.dp.toPx() }
    val menuHeight = maxOf(rows.size * rowHeight + padding * 2, composer.height)
    val menu = Rect(
        left = composer.left,
        top = maxOf(composer.bottom - menuHeight, with(density) { 8.dp.toPx() }),
        right = composer.left + menuWidth,
        bottom = maxOf(composer.bottom - menuHeight, with(density) { 8.dp.toPx() }) + minOf(menuHeight, composer.bottom - with(density) { 8.dp.toPx() }),
    )
    val cardHeight = minOf(maxOf(height * 0.5f, with(density) { 340.dp.toPx() }), height - with(density) { 220.dp.toPx() })
    val inset = with(density) { 8.dp.toPx() }
    val card = Rect(
        left = inset,
        top = height - inset - cardHeight,
        right = width - inset,
        bottom = height - inset,
    )
    val reservation = maxOf(cardHeight + inset + with(density) { 10.dp.toPx() }, 0f)
    return MenuFrames(button, menu, card, reservation)
}

private fun Rect.translate(dx: Float, dy: Float) = Rect(left + dx, top + dy, right + dx, bottom + dy)

@Composable
private fun MenuPanel(
    rows: List<MenuRow>,
    rect: Rect,
    onCamera: () -> Unit,
    onPhotos: () -> Unit,
    onFiles: () -> Unit,
    onPaste: () -> Unit,
    onDrag: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    val density = LocalDensity.current
    var drag by remember { mutableFloatStateOf(0f) }
    val shape = RoundedCornerShape(28.dp)
    val veil = if (LocalGlassBlurEnabled.current) 0.55f else 1f
    Column(
        Modifier
            .offset { IntOffset(rect.left.roundToInt(), rect.top.roundToInt()) }
            .shadow(12.dp, shape)
            .size(with(density) { rect.width.toDp() }, with(density) { rect.height.toDp() })
            .clip(shape)
            .glassEffect(shape, tint = SystemColors.systemBackground.copy(alpha = veil))
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragEnd = {
                        if (drag > 50) onDismiss() else onDrag(0f)
                        drag = 0f
                    },
                    onVerticalDrag = { _, amount ->
                        drag += amount
                        onDrag(if (drag > 0) drag else -sqrt(-drag) * 2)
                    },
                )
            }
            .padding(8.dp),
        verticalArrangement = Arrangement.Bottom,
    ) {
        rows.forEach { row ->
            when (row) {
                MenuRow.camera -> AttachmentMenuRow("Camera", "camera", onCamera)
                MenuRow.photos -> AttachmentMenuRow("Photos", "photo.on.rectangle", onPhotos)
                MenuRow.files -> AttachmentMenuRow("Files", "paperclip", onFiles)
                MenuRow.paste -> AttachmentMenuRow("Paste", "doc.on.clipboard", onPaste)
            }
        }
    }
}

@Composable
private fun AttachmentMenuRow(title: String, symbol: String, action: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = action)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(36.dp)
                .background(SystemColors.label.copy(alpha = 0.08f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            SystemImage(symbol, contentDescription = null, tint = SystemColors.label, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(title, style = TextStyles.body, color = SystemColors.label, maxLines = 1)
    }
}

@Composable
private fun PickerControls(
    filter: InlinePhotoFilter,
    includesVideos: Boolean,
    onFilter: (InlinePhotoFilter) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    Row(
        modifier.padding(14.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Box(
            Modifier
                .size(44.dp)
                .glassEffect(GlassSurface.Circle, interactive = true)
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            SystemImage("chevron.left", contentDescription = "Back", tint = SystemColors.label, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.weight(1f))
        Box {
            Row(
                Modifier
                    .height(44.dp)
                    .glassEffect(GlassSurface.Capsule, interactive = true)
                    .clickable { open = true }
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(filter.title, style = TextStyles.subheadline.semibold, color = SystemColors.label)
                Spacer(Modifier.width(6.dp))
                SystemImage("chevron.up.chevron.down", contentDescription = null, tint = SystemColors.secondaryLabel, modifier = Modifier.size(12.dp))
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                InlinePhotoFilter.visible(includesVideos).forEach { item ->
                    DropdownMenuItem(
                        text = { Text(item.title) },
                        onClick = {
                            onFilter(item)
                            open = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun EmbeddedPhotoCard(
    selection: InlinePhotoSelection,
    filter: InlinePhotoFilter,
    includesVideos: Boolean,
    onPhotos: (List<PhotosPickerItem>) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(44.dp))
            .background(SystemColors.systemBackground)
            .semantics { testTag = "inline-photo-picker" },
    ) {
        EmbeddedPhotoPickerHost(selection, filter, includesVideos, onPhotos, Modifier.fillMaxSize())
    }
}

private fun clipboardImages(context: Context): List<NSItemProvider> {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return emptyList()
    val clip = clipboard.primaryClip ?: return emptyList()
    return (0 until clip.itemCount).mapNotNull { index ->
        val item = clip.getItemAt(index)
        val uri = item.uri ?: return@mapNotNull null
        val type = context.contentResolver.getType(uri) ?: return@mapNotNull null
        if (!type.startsWith("image/")) return@mapNotNull null
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@mapNotNull null
        NSItemProvider(registeredTypeIdentifiers = listOf(type), data = bytes, canLoadImage = true)
    }
}

@Composable
internal fun AttachmentMenuSurface(
    phase: AttachmentMenuPhase = AttachmentMenuPhase.menu,
    canUseCamera: Boolean = true,
    canAttachFiles: Boolean = true,
    canPaste: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val anchors = remember { AttachmentMenuAnchors() }
    val selection = remember { InlinePhotoSelection() }
    var current by remember { mutableStateOf(phase) }
    Box(modifier) {
        AttachmentMenuOverlay(
            phase = current,
            onPhase = { current = it },
            anchors = anchors,
            selection = selection,
            includesVideos = canAttachFiles,
            canUseCamera = canUseCamera,
            canAttachFiles = canAttachFiles,
            canPaste = canPaste,
            onCamera = {},
            onCameraCapture = {},
            onFiles = {},
            onFilesPicked = {},
            onPaste = {},
            onPhotos = {},
            pickerReservation = 0f,
            onPickerReservation = {},
        )
    }
}

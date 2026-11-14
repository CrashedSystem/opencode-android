package ai.opencode.feature.media

import ai.opencode.core.R as CoreR
import ai.opencode.core.ui.OpenCodeShape
import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.haptics.Haptic
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.log.AppLog
import ai.opencode.core.motion.MotionPhase
import ai.opencode.core.motion.MotionRole
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.store.Effect
import ai.opencode.core.store.Reduced
import ai.opencode.core.store.Reducer
import ai.opencode.core.store.Store
import ai.opencode.core.store.StoreOf
import ai.opencode.core.store.cancellable
import ai.opencode.core.store.collectState
import ai.opencode.core.ui.OpenCodeIconButton
import ai.opencode.core.ui.OpenCodeIconButtonStyle
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.SFSymbol
import ai.opencode.core.files.AttachmentFileType
import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Environment
import android.provider.MediaStore
import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// MARK: - Photo save

/** iOS `PhotoSaveClientError`. */
sealed class PhotoSaveClientError : Exception() {
    /** The system refused the write (iOS: add-only Photos access denied). */
    data object Denied : PhotoSaveClientError()
}

/**
 * Saves an image to the photo library (iOS `PhotoSaveClient`, `@Dependency(\.photoSave)`).
 * Android: a MediaStore insert into Pictures, which needs no permission at this app's
 * minimum SDK (35; `WRITE_EXTERNAL_STORAGE` only mattered on Android 9 and earlier).
 */
class PhotoSaveClient(
    val saveImage: suspend (Bitmap) -> Unit = {},
) {
    companion object Key : DependencyKey<PhotoSaveClient> {
        override val liveValue: PhotoSaveClient by lazy {
            PhotoSaveClient(saveImage = { image -> saveToPictures(dependency(AppContext), image) })
        }
        override val testValue: PhotoSaveClient = PhotoSaveClient()

        private suspend fun saveToPictures(context: Context, image: Bitmap) = withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val png = image.hasAlpha()
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "IMG_$stamp.${if (png) "png" else "jpg"}")
                put(MediaStore.Images.Media.MIME_TYPE, if (png) "image/png" else "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = try {
                resolver.insert(collection, values)
            } catch (_: SecurityException) {
                throw PhotoSaveClientError.Denied
            } ?: throw PhotoSaveClientError.Denied
            try {
                resolver.openOutputStream(uri)?.use { stream ->
                    val written = image.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 95, stream)
                    check(written) { "compress failed" }
                } ?: error("no output stream")
                resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            } catch (error: Throwable) {
                runCatching { resolver.delete(uri, null, null) }
                throw error
            }
        }
    }
}

// MARK: - Share

/**
 * Shares an image through the system share sheet (iOS `ImageShareClient`,
 * `@Dependency(\.imageShare)`): written to the cache and offered with `ACTION_SEND` through
 * [MediaFileProvider].
 */
class ImageShareClient(
    val share: suspend (Bitmap, String) -> Unit = { _, _ -> },
) {
    companion object Key : DependencyKey<ImageShareClient> {
        override val liveValue: ImageShareClient by lazy {
            ImageShareClient(share = { image, label -> shareImage(dependency(AppContext), image, label) })
        }
        override val testValue: ImageShareClient = ImageShareClient()

        private suspend fun shareImage(context: Context, image: Bitmap, label: String) {
            val file = withContext(Dispatchers.IO) {
                val directory = File(context.cacheDir, MediaFileProvider.SHARE_DIRECTORY)
                directory.deleteRecursively()
                directory.mkdirs()
                val base = AttachmentFileType.sanitizedName(label.trim().ifEmpty { "Image" }).take(80)
                val file = File(directory, "$base.png")
                file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                file
            }
            val uri = FileProvider.getUriForFile(context, MediaFileProvider.authority(context), file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri(null, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(chooser)
        }
    }
}

// MARK: - Page title

/**
 * A real file or attachment name to show at the top centre when controls are visible, or
 * `null` when [TranscriptImagePreview.Page.label] is a generic accessibility placeholder.
 */
val TranscriptImagePreview.Page.displayTitle: String?
    get() {
        name?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        if (labelIsPlaceholder) return null
        val trimmed = label.trim()
        if (trimmed.isEmpty()) return null
        val lower = trimmed.lowercase()
        // Labels that arrive already written (a model's alt text) are matched as English words.
        val placeholders = setOf(
            "image",
            "photo",
            "attachment",
            "attached image",
            "tool image",
            "agent image preview",
            "diagram",
        )
        if (lower in placeholders ||
            lower.startsWith("tool image ") ||
            lower.startsWith("attached image ") ||
            lower.startsWith("image ") ||
            lower.startsWith("photo ") ||
            lower.startsWith("attachment ") ||
            lower.startsWith("diagram, ")
        ) {
            return null
        }
        return trimmed
    }

// MARK: - Feature

/** iOS `TranscriptImageViewerFeature`. */
class TranscriptImageViewerFeature : Reducer<TranscriptImageViewerFeature.State, TranscriptImageViewerFeature.Action> {
    data class State(
        val preview: TranscriptImagePreview,
        val selectedID: String = preview.id,
        val isShowingControls: Boolean = false,
        val isSaving: Boolean = false,
        val isDismissed: Boolean = false,
        /** Pages can be taken out (a composer's pending photos). */
        val allowsRemove: Boolean = false,
        /** The last page was removed: the viewer goes without flying home. */
        val isDiscarded: Boolean = false,
    ) {
        val selectedPage: TranscriptImagePreview.Page?
            get() = preview.pages.firstOrNull { it.id == selectedID } ?: preview.pages.firstOrNull()
    }

    sealed interface Action {
        data object Task : Action
        data class PageSelected(val id: String) : Action
        data class RetryPageTapped(val id: String) : Action
        data class MediaLoaded(val pageID: String, val result: MediaReferenceLoadResult) : Action
        data object ImageTapped : Action
        data object CloseTapped : Action
        data object SaveTapped : Action
        data class SaveFinished(val saved: Boolean) : Action
        data object ShareTapped : Action
        data object RemoveTapped : Action
        data class Delegate(val action: DelegateAction) : Action

        sealed interface DelegateAction {
            /** The page's media is no longer wanted. */
            data class Removed(val id: String) : DelegateAction
        }
    }

    private data class PageLoadID(val id: String)

    override fun reduce(state: State, action: Action): Reduced<State, Action> = when (action) {
        Action.Task -> loadPageIfNeeded(state.selectedID, state)

        is Action.PageSelected -> loadPageIfNeeded(action.id, state.copy(selectedID = action.id))

        is Action.RetryPageTapped -> loadPageIfNeeded(action.id, state, forceRetry = true)

        is Action.MediaLoaded -> {
            val index = state.preview.pages.indexOfFirst { it.id == action.pageID }
            if (index < 0) {
                Reduced(state)
            } else {
                val page = state.preview.pages[index]
                val updated = when (val result = action.result) {
                    is MediaReferenceLoadResult.Loaded -> {
                        val image = CachedImage.decoded(action.pageID, result.data, page.mediaHash) ?: decodeBitmap(result.data)
                        if (image != null) {
                            page.copy(image = image, isUnavailable = false, loadFailed = false)
                        } else {
                            page.copy(loadFailed = true)
                        }
                    }
                    MediaReferenceLoadResult.Unavailable -> page.copy(isUnavailable = true, loadFailed = false)
                    is MediaReferenceLoadResult.Failed -> page.copy(loadFailed = true)
                }
                Reduced(state.withPage(index, updated))
            }
        }

        Action.ImageTapped -> Reduced(state.copy(isShowingControls = !state.isShowingControls))

        Action.CloseTapped -> Reduced(state.copy(isDismissed = true))

        Action.SaveTapped -> {
            val image = state.selectedPage?.image
            if (state.isSaving || image == null) {
                Reduced(state)
            } else {
                val photoSave = dependency(PhotoSaveClient)
                Reduced(
                    state.copy(isSaving = true),
                    Effect.run { send ->
                        val saved = try {
                            photoSave.saveImage(image)
                            true
                        } catch (cancellation: kotlinx.coroutines.CancellationException) {
                            throw cancellation
                        } catch (error: Throwable) {
                            // iOS logs the NSError's domain and code; a Swift enum error's code is its case index.
                            val code = if (error is PhotoSaveClientError.Denied) 0 else -1
                            AppLog.app.error { "Photo save failed: ${error::class.java.name} code=$code" }
                            false
                        }
                        send(Action.SaveFinished(saved))
                    },
                )
            }
        }

        is Action.SaveFinished -> {
            dependency(HapticsClient).play(if (action.saved) Haptic.success else Haptic.error)
            Reduced(state.copy(isSaving = false))
        }

        Action.ShareTapped -> {
            val page = state.selectedPage
            val image = page?.image
            if (page == null || image == null) {
                Reduced(state)
            } else {
                val imageShare = dependency(ImageShareClient)
                Reduced(state, Effect.run { _ -> imageShare.share(image, page.label) })
            }
        }

        Action.RemoveTapped -> {
            val index = state.preview.pages.indexOfFirst { it.id == state.selectedID }
            if (!state.allowsRemove || state.isDiscarded || index < 0) {
                Reduced(state)
            } else {
                val pages = state.preview.pages.toMutableList()
                val removed = pages.removeAt(index)
                val next = if (pages.isEmpty()) {
                    state.copy(isDiscarded = true)
                } else {
                    state.copy(
                        preview = TranscriptImagePreview(state.preview.id, pages, state.preview.server),
                        selectedID = pages[min(index, pages.size - 1)].id,
                    )
                }
                Reduced(next, Effect.send(Action.Delegate(Action.DelegateAction.Removed(removed.id))))
            }
        }

        is Action.Delegate -> Reduced(state)
    }

    private fun loadPageIfNeeded(id: String, state: State, forceRetry: Boolean = false): Reduced<State, Action> {
        val index = state.preview.pages.indexOfFirst { it.id == id }
        if (index < 0) return Reduced(state)
        val page = state.preview.pages[index]
        val hash = page.mediaHash
        if (page.image != null || page.isUnavailable || (!forceRetry && page.loadFailed) || hash == null) return Reduced(state)
        val mediaReferences = dependency(MediaReferenceClient)
        val loading = state.withPage(index, page.copy(loadFailed = false))
        val server = state.preview.server
        if (server == null) {
            val image = mediaReferences.cachedData(hash)?.let { data -> CachedImage.decoded(id, data, hash) ?: decodeBitmap(data) }
            val resolved = if (image != null) page.copy(loadFailed = false, image = image) else page.copy(loadFailed = false, isUnavailable = true)
            return Reduced(state.withPage(index, resolved))
        }
        return Reduced(
            loading,
            Effect.run<Action> { send ->
                val result = mediaReferences.load(server, hash)
                send(Action.MediaLoaded(id, result))
            }.cancellable(PageLoadID(id), cancelInFlight = true),
        )
    }

    private fun State.withPage(index: Int, page: TranscriptImagePreview.Page): State =
        copy(preview = preview.copy(pages = preview.pages.toMutableList().also { it[index] = page }))

    private fun decodeBitmap(data: ByteArray): Bitmap? =
        if (data.isEmpty()) null else BitmapFactory.decodeByteArray(data, 0, data.size)
}

// MARK: - View

/**
 * Transcript images, full screen (iOS `TranscriptImageViewer`): swipe between them, pinch or
 * double-tap to zoom, single-tap to toggle controls, drag down to put them back. Opens
 * showing only the photo (controls visible when TalkBack is on).
 */
@Composable
fun TranscriptImageViewer(
    preview: TranscriptImagePreview,
    viewer: MediaViewerState,
) {
    val store = remember(preview.id) {
        Store(TranscriptImageViewerFeature.State(preview), TranscriptImageViewerFeature())
    }
    DisposableEffect(store) { onDispose { store.close() } }
    TranscriptImageViewer(store, viewer)
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun TranscriptImageViewer(
    store: StoreOf<TranscriptImageViewerFeature.State, TranscriptImageViewerFeature.Action>,
    viewer: MediaViewerState,
) {
    val state by store.collectState()
    val talkBack = rememberTalkBackEnabled()
    val pages = state.preview.pages

    // iOS `init`: the status bar and the image on screen, before the flight starts; again
    // once a `media:<hash>` page's image arrives.
    val hasImage = state.selectedPage?.image != null
    DisposableEffect(viewer, state.selectedID, hasImage) {
        viewer.show(sourceID = state.selectedID, image = state.selectedPage?.image)
        onDispose { }
    }
    LaunchedEffect(store) { store.send(TranscriptImageViewerFeature.Action.Task) }
    val showing = state.isShowingControls || talkBack
    DisposableEffect(viewer, showing) {
        viewer.setStatusBarHidden(!showing)
        onDispose { }
    }
    LaunchedEffect(state.isDismissed) {
        if (state.isDismissed) viewer.dismiss()
    }
    LaunchedEffect(state.isDiscarded) {
        if (state.isDiscarded) viewer.discard()
    }

    val controlsVisible = controlsAreVisible(state.isShowingControls, talkBack, viewer.chromeHidden)

    // The overlays keep to the safe area; the pages fill the screen, so a page's fitted image
    // is where the flight lands.
    Box(Modifier.fillMaxSize()) {
        val initialPage = remember { pages.indexOfFirst { it.id == state.selectedID }.coerceAtLeast(0) }
        val pagerState = rememberPagerState(initialPage = initialPage) { pages.size }
        LaunchedEffect(pagerState, pages) {
            snapshotFlow { pagerState.settledPage }.distinctUntilChanged().collect { index ->
                val id = pages.getOrNull(index)?.id ?: return@collect
                if (id != store.state.selectedID) store.send(TranscriptImageViewerFeature.Action.PageSelected(id))
            }
        }
        // A removed page hands the selection on: the pager follows it.
        LaunchedEffect(pagerState, pages, state.selectedID) {
            val target = pages.indexOfFirst { it.id == state.selectedID }
            if (target >= 0 && target != pagerState.currentPage) pagerState.scrollToPage(target)
        }
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize().testTag("image-gallery"),
            beyondViewportPageCount = 1,
            key = { pages[it].id },
        ) { index ->
            val page = pages[index]
            val selected = state.selectedID == page.id
            val image = page.image
            val tap = { store.send(TranscriptImageViewerFeature.Action.ImageTapped) }
            val placeholder = Modifier.fillMaxSize().background(Color.Black).clickable(
                interactionSource = null,
                indication = null,
                onClick = tap,
            )
            when {
                image != null -> ZoomableImagePage(
                    image = image,
                    label = page.label,
                    isSelected = selected,
                    isHidden = viewer.mediaHidden && selected,
                    onSingleTap = tap,
                    onZoomChange = { zoomed -> if (store.state.selectedID == page.id) viewer.isZoomed = zoomed },
                    onFrame = { viewer.mediaFrames[page.id] = it },
                )
                page.isUnavailable -> Box(placeholder) {
                    MediaAttachmentUnavailableView(name = page.name ?: page.displayTitle)
                }
                page.loadFailed -> Box(placeholder) {
                    MediaAttachmentUnavailableView(
                        name = page.name ?: page.displayTitle,
                        onRetry = { store.send(TranscriptImageViewerFeature.Action.RetryPageTapped(page.id)) },
                        isTransientFailure = true,
                    )
                }
                else -> Box(placeholder, contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color.White)
                }
            }
        }

        val safe = WindowInsets.systemBarsIgnoringVisibility.union(WindowInsets.displayCutout)
        // The controls swap in and out (iOS `Motion.swap`).
        val reduceMotion = rememberReduceMotion()
        val enter = fadeIn(MotionRole.Swap.animation(MotionPhase.Enter, reduceMotion))
        val exit = fadeOut(MotionRole.Swap.animation(MotionPhase.Exit, reduceMotion))
        Box(Modifier.fillMaxSize().windowInsetsPadding(safe)) {
            AnimatedVisibility(
                visible = controlsVisible,
                enter = enter,
                exit = exit,
                modifier = Modifier.align(Alignment.TopStart).padding(start = 16.dp, top = 8.dp),
            ) {
                OpenCodeIconButton(
                    symbol = SFSymbol.Xmark,
                    contentDescription = stringResource(CoreR.string.common_close),
                    onClick = { store.send(TranscriptImageViewerFeature.Action.CloseTapped) },
                    modifier = Modifier.testTag("image-gallery-close"),
                    style = OpenCodeIconButtonStyle.Circle,
                )
            }
            val title = state.selectedPage?.displayTitle
            val detail = state.selectedPage?.detail
            AnimatedVisibility(
                visible = controlsVisible && (title != null || detail != null),
                enter = enter,
                exit = exit,
                modifier = Modifier.align(Alignment.TopCenter).padding(horizontal = 76.dp).padding(top = 8.dp),
            ) {
                Column(
                    Modifier.heightIn(min = 48.dp),
                    verticalArrangement = Arrangement.spacedBy(1.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (title != null) {
                        Text(
                            text = title,
                            style = OpenCodeTextStyles.subheadline.copy(fontWeight = FontWeight.Medium),
                            color = Color.White.copy(alpha = 0.85f),
                            maxLines = 1,
                            overflow = TextOverflow.MiddleEllipsis,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.wrapContentHeight().testTag("image-gallery-title"),
                        )
                    }
                    if (detail != null) {
                        Text(
                            text = detail,
                            style = OpenCodeTextStyles.caption1.copy(fontFeatureSettings = "tnum"),
                            color = Color.White.copy(alpha = 0.6f),
                            maxLines = 1,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.testTag("image-gallery-detail"),
                        )
                    }
                }
            }
            AnimatedVisibility(
                visible = controlsVisible && state.allowsRemove,
                enter = enter,
                exit = exit,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 16.dp, bottom = 16.dp),
            ) {
                OpenCodeIconButton(
                    symbol = SFSymbol.Trash,
                    contentDescription = stringResource(CoreR.string.common_remove),
                    onClick = { store.send(TranscriptImageViewerFeature.Action.RemoveTapped) },
                    modifier = Modifier.testTag("image-gallery-remove"),
                    style = OpenCodeIconButtonStyle.Circle,
                    tint = MaterialTheme.colorScheme.error,
                    enabled = !state.isDiscarded,
                )
            }
            AnimatedVisibility(
                visible = controlsVisible,
                enter = enter,
                exit = exit,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 16.dp),
            ) {
                Surface(
                    shape = OpenCodeShape.control,
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = Color.White,
                ) {
                    Row(
                        modifier = Modifier.height(52.dp).padding(horizontal = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OpenCodeIconButton(
                            symbol = SFSymbol.SquareAndArrowDown,
                            contentDescription = stringResource(R.string.media_viewer_save_to_photos_description),
                            onClick = { store.send(TranscriptImageViewerFeature.Action.SaveTapped) },
                            modifier = Modifier.testTag("image-gallery-save"),
                            style = OpenCodeIconButtonStyle.Standard,
                            enabled = !state.isSaving && state.selectedPage?.image != null,
                        )
                        OpenCodeIconButton(
                            symbol = SFSymbol.SquareAndArrowUp,
                            contentDescription = stringResource(CoreR.string.common_share),
                            onClick = { store.send(TranscriptImageViewerFeature.Action.ShareTapped) },
                            modifier = Modifier.testTag("image-gallery-share"),
                            style = OpenCodeIconButtonStyle.Standard,
                            enabled = state.selectedPage?.image != null,
                        )
                    }
                }
            }
        }
    }
}

/** iOS `TranscriptImageViewer.controlsAreVisible`. */
fun controlsAreVisible(isShowingControls: Boolean, voiceOver: Boolean, chromeHidden: Boolean): Boolean =
    (isShowingControls || voiceOver) && !chromeHidden

/** iOS `accessibilityVoiceOverEnabled`: TalkBack (touch exploration) is on. Live-updating. */
@Composable
internal fun rememberTalkBackEnabled(): Boolean {
    val context = LocalContext.current
    val manager = remember(context) { context.getSystemService(AccessibilityManager::class.java) }
    var enabled by remember { mutableStateOf(manager?.isTouchExplorationEnabled == true) }
    DisposableEffect(manager) {
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { enabled = it }
        manager?.addTouchExplorationStateChangeListener(listener)
        onDispose { manager?.removeTouchExplorationStateChangeListener(listener) }
    }
    return enabled
}

/**
 * The media area's `FileProvider` (its own subclass so the manifest merger never clashes
 * with another module's provider). Serves only the share cache directory.
 */
class MediaFileProvider : FileProvider(R.xml.media_file_paths) {
    companion object {
        const val SHARE_DIRECTORY = "media-share"
        fun authority(context: Context): String = "${context.packageName}.media.files"
    }
}

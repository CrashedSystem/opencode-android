package ai.opencode.feature.media

import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.sharing.SharedKeys
import ai.opencode.core.sharing.shared
import ai.opencode.core.ui.AppearanceColorScheme
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.SFSymbol
import ai.opencode.core.ui.OpenCodeIconSize
import ai.opencode.core.ui.OpenCodePrimaryButton
import ai.opencode.core.ui.OpenCodeSecondaryButton
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.rememberAppAppearance
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.AgentFileReference
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.ContentFrame
import androidx.media3.ui.compose.state.rememberPlayPauseButtonState
import androidx.media3.ui.compose.state.rememberProgressStateWithTickInterval
import java.util.Locale
import kotlinx.coroutines.delay

/** A file opened from a document, resolved against that document's folder. */
data class AgentFileDestination(val reference: AgentFileReference, val directory: String?)

/**
 * Renders a Markdown document the way a reply renders (`:feature:transcript` provides it; media
 * sits below the transcript). [onOpenFile] pushes a file the document links to; web links are
 * the renderer's own. Laid out in a vertically scrolling column, so it must not scroll itself.
 */
typealias AgentFileMarkdown = @Composable (source: String, server: OpenCodeServer, directory: String?, onOpenFile: (AgentFileReference) -> Unit) -> Unit

/**
 * The app's Markdown renderer for file pages that aren't handed one (iOS `AgentFileViewer`
 * renders `MarkdownBlocksView` itself; here the renderer is `:feature:transcript`'s, provided
 * at the app root by `TranscriptMediaProviders`). Null: Markdown files open as source.
 */
val LocalAgentFileMarkdown = staticCompositionLocalOf<AgentFileMarkdown?> { null }

/**
 * Opens a file the agent mentioned (iOS `AgentFileViewer`): streams media when the server
 * supports ranges, otherwise downloads with progress, then shows the player, a rendered Markdown
 * document, the code viewer, a PDF, or offers another app. Links inside a document push further
 * pages; system back pops them.
 *
 * @param onDone closes the viewer (iOS Done: the media viewer's dismissal, else the sheet's).
 * @param isInMediaViewer the media viewer hosts it: the root page's dark room is the viewer's own
 *   (clear), which lifts as it's dragged away.
 * @param markdown renders Markdown files; without it [LocalAgentFileMarkdown] does, and without
 *   either they open as source.
 * @param onImageShown the root page decoded its image (iOS `viewer.show(sourceID:image:)`).
 * @param onZoomChange iOS `viewer.isZoomed`.
 * @param onImageFrame where the root page's image sits (iOS `viewer.mediaFrames[zoomSourceID]`).
 */
@Composable
fun AgentFileViewer(
    reference: AgentFileReference,
    server: OpenCodeServer,
    directory: String?,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    isInMediaViewer: Boolean = false,
    markdown: AgentFileMarkdown? = null,
    onImageShown: (sourceID: String, image: android.graphics.Bitmap) -> Unit = { _, _ -> },
    onZoomChange: (Boolean) -> Unit = {},
    onImageFrame: (sourceID: String, frame: Rect) -> Unit = { _, _ -> },
) {
    val models = remember(reference, server, directory) {
        mutableStateListOf(AgentFileViewerModel(reference, server, directory))
    }
    DisposableEffect(models) {
        onDispose { models.forEach(AgentFileViewerModel::release) }
    }
    MediaBackHandler(enabled = models.size > 1) {
        models.removeAt(models.lastIndex).release()
    }
    val model = models.last()
    Box(modifier.fillMaxSize().testTag("agent-file-viewer")) {
        AgentFilePage(
            model = model,
            isRoot = models.size == 1,
            isInMediaViewer = isInMediaViewer,
            onOpen = { destination -> models.add(AgentFileViewerModel(destination.reference, server, destination.directory)) },
            onBack = { if (models.size > 1) models.removeAt(models.lastIndex).release() },
            onDone = onDone,
            markdown = markdown,
            onImageShown = onImageShown,
            onZoomChange = onZoomChange,
            onImageFrame = onImageFrame,
        )
    }
}

/** One file's page (iOS `AgentFilePage`). Media plays in a dark room, like Photos. */
@Composable
fun AgentFilePage(
    model: AgentFileViewerModel,
    isRoot: Boolean,
    onOpen: (AgentFileDestination) -> Unit,
    onBack: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    isInMediaViewer: Boolean = false,
    markdown: AgentFileMarkdown? = null,
    onImageShown: (sourceID: String, image: android.graphics.Bitmap) -> Unit = { _, _ -> },
    onZoomChange: (Boolean) -> Unit = {},
    onImageFrame: (sourceID: String, frame: Rect) -> Unit = { _, _ -> },
) {
    val reference = model.reference
    val renderer = markdown ?: LocalAgentFileMarkdown.current
    if (reference.kind.isMedia) {
        // Media plays in a dark room, like Photos.
        OpenCodeTheme(theme = rememberAppAppearance().theme, appearance = AppearanceColorScheme.Dark) {
            AgentFilePageContent(model, isRoot, onOpen, onBack, onDone, modifier, isInMediaViewer, renderer, onImageShown, onZoomChange, onImageFrame)
        }
    } else {
        AgentFilePageContent(model, isRoot, onOpen, onBack, onDone, modifier, isInMediaViewer, renderer, onImageShown, onZoomChange, onImageFrame)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@Composable
private fun AgentFilePageContent(
    model: AgentFileViewerModel,
    isRoot: Boolean,
    onOpen: (AgentFileDestination) -> Unit,
    onBack: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier,
    isInMediaViewer: Boolean,
    markdown: AgentFileMarkdown?,
    onImageShown: (String, android.graphics.Bitmap) -> Unit,
    onZoomChange: (Boolean) -> Unit,
    onImageFrame: (String, Rect) -> Unit,
) {
    val reference = model.reference
    val wrapsShared = remember { shared(SharedKeys.fileViewerWrapsLines) }
    val wrapsLines by wrapsShared.flow.collectAsState()
    var showsSource by remember(model) { mutableStateOf(false) }
    val phase = model.phase
    val background = when {
        !reference.kind.isMedia -> OpenCodeTheme.colors.canvas
        isRoot && isInMediaViewer -> Color.Transparent
        else -> Color.Black
    }
    val showsTextControls = phase is AgentFileViewerModel.Phase.Ready && when (phase.content) {
        is AgentFileViewerModel.Content.Text -> true
        is AgentFileViewerModel.Content.Markdown -> showsSource || markdown == null
        else -> false
    }

    DisposableEffect(model) {
        model.open()
        onDispose { model.stop() }
    }

    val playerContent = (phase as? AgentFileViewerModel.Phase.Ready)?.content as? AgentFileViewerModel.Content.Player
    val pip = playerContent?.let { rememberAgentFilePictureInPicture(it.player, it.isAudio) }
    if (pip != null && pip.isActive) {
        // The viewer fills the window: in Picture in Picture the window is only the video.
        Box(modifier.fillMaxSize().background(Color.Black).testTag("agent-file-pip")) {
            ContentFrame(player = playerContent.player, modifier = Modifier.fillMaxSize())
        }
        return
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = background,
        topBar = {
            CenterAlignedTopAppBar(
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = background),
                title = { AgentFileTitle(reference.name, model.locationLabel) },
                navigationIcon = {
                    if (isRoot) {
                        TextButton(onClick = onDone, modifier = Modifier.testTag("agent-file-done")) { Text("Done") }
                    } else {
                        IconButton(onClick = onBack) { OpenCodeIcon(SFSymbol.ChevronBackward, contentDescription = "Back") }
                    }
                },
                actions = {
                    if (phase is AgentFileViewerModel.Phase.Ready && phase.content is AgentFileViewerModel.Content.Markdown && markdown != null) {
                        IconButton(onClick = { showsSource = !showsSource }, modifier = Modifier.testTag("agent-file-source-toggle")) {
                            OpenCodeIcon(
                                if (showsSource) "doc.richtext" else "chevron.left.forwardslash.chevron.right",
                                contentDescription = if (showsSource) "Show Preview" else "Show Source",
                            )
                        }
                    }
                    if (showsTextControls) {
                        IconButton(onClick = model::toggleWrapLines) {
                            OpenCodeIcon(
                                if (wrapsLines) "arrow.left.and.right.text.vertical" else "text.word.spacing",
                                contentDescription = if (wrapsLines) "Don’t Wrap Lines" else "Wrap Lines",
                            )
                        }
                    }
                    if (pip != null && pip.isSupported) {
                        IconButton(onClick = pip::start, modifier = Modifier.testTag("agent-file-pip-start")) {
                            OpenCodeIcon("pip.enter", contentDescription = "Start Picture in Picture")
                        }
                    }
                    if (phase is AgentFileViewerModel.Phase.Ready) ShareControl(model)
                },
            )
        },
    ) { padding ->
        // An image fills the whole screen under the bar (iOS `.ignoresSafeArea()`), so it zooms
        // in and out of the same frame the transcript's flight lands on.
        val fillsScreen = phase is AgentFileViewerModel.Phase.Ready && phase.content is AgentFileViewerModel.Content.Image
        Box(
            Modifier
                .fillMaxSize()
                .padding(top = if (fillsScreen) 0.dp else padding.calculateTopPadding()),
            contentAlignment = Alignment.Center,
        ) {
            when (phase) {
                AgentFileViewerModel.Phase.Opening -> StatusView(reference, detail = "Opening…", progress = null)
                is AgentFileViewerModel.Phase.Downloading -> StatusView(
                    reference,
                    detail = progressText(phase.received, phase.expected),
                    progress = phase.expected?.let { phase.received.toFloat() / maxOf(it, 1).toFloat() },
                )
                is AgentFileViewerModel.Phase.ConfirmLarge -> ConfirmLargeView(reference, phase.size, model::confirmDownload)
                is AgentFileViewerModel.Phase.Failed -> FailedView(phase.error, model::open)
                is AgentFileViewerModel.Phase.Ready -> ReadyContent(
                    model, phase.content, isRoot, wrapsLines, showsSource, background, markdown, onOpen,
                    onImageShown, onZoomChange, onImageFrame, pip,
                )
            }
        }
    }

    model.shareError?.let { error ->
        AlertDialog(
            onDismissRequest = { model.shareError = null },
            title = { Text("Couldn’t Share File") },
            text = { Text(error.detail) },
            confirmButton = { TextButton(onClick = { model.shareError = null }) { Text("OK") } },
        )
    }
}

@Composable
private fun AgentFileTitle(name: String, location: String?) {
    Column(
        Modifier.semantics(mergeDescendants = true) { heading() },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(name, style = OpenCodeTextStyles.headline, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
        if (location != null) {
            Text(
                location,
                style = OpenCodeTextStyles.caption1,
                color = OpenCodeTheme.colors.secondaryText,
                maxLines = 1,
                overflow = TextOverflow.StartEllipsis,
            )
        }
    }
}

/** Share for every file that opens: a streamed one downloads first, its progress in the button's place. */
@Composable
private fun ShareControl(model: AgentFileViewerModel) {
    val progress = model.shareProgress
    if (progress != null) {
        val text = progressText(progress.received, progress.expected)
        Box(Modifier.size(48.dp).testTag("agent-file-share-progress"), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                modifier = Modifier
                    .size(20.dp)
                    .semantics {
                        contentDescription = "Downloading to share"
                        stateDescription = text
                    },
                strokeWidth = 2.dp,
            )
        }
    } else {
        IconButton(onClick = model::share, modifier = Modifier.testTag("agent-file-share")) {
            OpenCodeIcon("square.and.arrow.up", contentDescription = "Share")
        }
    }
}

@Composable
private fun ReadyContent(
    model: AgentFileViewerModel,
    content: AgentFileViewerModel.Content,
    isRoot: Boolean,
    wrapsLines: Boolean,
    showsSource: Boolean,
    background: Color,
    markdown: AgentFileMarkdown?,
    onOpen: (AgentFileDestination) -> Unit,
    onImageShown: (String, android.graphics.Bitmap) -> Unit,
    onZoomChange: (Boolean) -> Unit,
    onImageFrame: (String, Rect) -> Unit,
    pip: AgentFilePictureInPicture?,
) {
    val reference = model.reference
    when (content) {
        is AgentFileViewerModel.Content.Image -> {
            LaunchedEffect(content.image) { if (isRoot) onImageShown(reference.zoomSourceID, content.image) }
            ZoomableImagePage(
                image = content.image,
                label = reference.name,
                isSelected = true,
                modifier = Modifier.fillMaxSize().testTag("agent-file-image"),
                onZoomChange = onZoomChange,
                onFrame = { frame -> if (isRoot) onImageFrame(reference.zoomSourceID, frame) },
            )
        }
        is AgentFileViewerModel.Content.Player -> AgentFilePlayer(
            player = content.player,
            isAudio = content.isAudio,
            name = reference.name,
            isClear = background == Color.Transparent,
            pip = pip,
            modifier = Modifier.testTag(if (content.isStreaming) "agent-file-player-streaming" else "agent-file-player"),
        )
        is AgentFileViewerModel.Content.QuickLook -> {
            val pdf = content.pdf
            if (pdf != null) {
                AgentPdfView(pdf, Modifier.testTag("agent-file-quick-look"))
            } else {
                ExternalDocumentView(reference, model.noAppOpens, model::openExternally, Modifier.testTag("agent-file-quick-look"))
            }
        }
        is AgentFileViewerModel.Content.Text -> AgentCodeView(content.document, wrapsLines)
        is AgentFileViewerModel.Content.Markdown -> {
            if (showsSource || markdown == null) {
                AgentCodeView(content.document, wrapsLines)
            } else {
                val fileDirectory = model.fileDirectory
                Box(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .testTag("agent-file-markdown"),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Box(
                        Modifier
                            .widthIn(max = TranscriptMetrics.readableColumnWidth)
                            .fillMaxWidth()
                            .padding(horizontal = TranscriptMetrics.contentGutter, vertical = TranscriptMetrics.turn)
                            .navigationBarsPadding(),
                    ) {
                        markdown(content.source, model.server, fileDirectory) { file -> onOpen(AgentFileDestination(file, fileDirectory)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun FileGlyph(reference: AgentFileReference) {
    OpenCodeIcon(reference.symbolName, contentDescription = null, size = 44.dp, tint = OpenCodeTheme.colors.secondaryText)
}

@Composable
private fun StatusView(reference: AgentFileReference, detail: String, progress: Float?) {
    Column(
        Modifier
            .padding(32.dp)
            .semantics(mergeDescendants = true) {
                if (progress != null) progressBarRangeInfo = ProgressBarRangeInfo(progress.coerceIn(0f, 1f), 0f..1f)
            }
            .testTag("agent-file-status"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(TranscriptMetrics.turn),
    ) {
        FileGlyph(reference)
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(TranscriptMetrics.compact)) {
            Text(reference.name, style = OpenCodeTextStyles.headline, textAlign = TextAlign.Center)
            Text(
                detail,
                style = OpenCodeTextStyles.subheadline.copy(fontFeatureSettings = "tnum"),
                color = OpenCodeTheme.colors.secondaryText,
            )
        }
        val barModifier = Modifier.widthIn(max = 240.dp).fillMaxWidth()
        if (progress != null) {
            LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = barModifier)
        } else {
            LinearProgressIndicator(modifier = barModifier)
        }
    }
}

@Composable
private fun ConfirmLargeView(reference: AgentFileReference, size: Long, onDownload: () -> Unit) {
    Column(
        Modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(TranscriptMetrics.turn),
    ) {
        FileGlyph(reference)
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(TranscriptMetrics.compact)) {
            Text(reference.name, style = OpenCodeTextStyles.headline, textAlign = TextAlign.Center)
            Text(
                "This file is ${fileSizeText(size)}. Download it to view it on this device?",
                style = OpenCodeTextStyles.subheadline,
                color = OpenCodeTheme.colors.secondaryText,
                textAlign = TextAlign.Center,
            )
        }
        OpenCodePrimaryButton(text = "Download", onClick = onDownload)
    }
}

/** iOS `ContentUnavailableView` with Try Again. */
@Composable
private fun FailedView(error: AgentFileError, onRetry: () -> Unit) {
    Column(
        Modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(TranscriptMetrics.standard),
    ) {
        OpenCodeIcon(
            if (error == AgentFileError.NotFound) "questionmark.folder" else "exclamationmark.triangle",
            contentDescription = null,
            size = OpenCodeIconSize.hero,
            tint = OpenCodeTheme.colors.secondaryText,
        )
        Text(error.title, style = OpenCodeTextStyles.title2, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
        Text(error.detail, style = OpenCodeTextStyles.subheadline, color = OpenCodeTheme.colors.secondaryText, textAlign = TextAlign.Center)
        Spacer(Modifier.size(TranscriptMetrics.compact))
        OpenCodeSecondaryButton(text = "Try Again", onClick = onRetry)
    }
}

/** A document Android can't preview here: another app opens it (iOS shows it in Quick Look). */
@Composable
private fun ExternalDocumentView(reference: AgentFileReference, noAppOpens: Boolean, onOpen: () -> Unit, modifier: Modifier) {
    Column(
        modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(TranscriptMetrics.turn),
    ) {
        FileGlyph(reference)
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(TranscriptMetrics.compact)) {
            Text(reference.name, style = OpenCodeTextStyles.headline, textAlign = TextAlign.Center)
            Text(reference.typeLabel, style = OpenCodeTextStyles.subheadline, color = OpenCodeTheme.colors.secondaryText)
        }
        OpenCodePrimaryButton(text = "Open in Another App", onClick = onOpen)
        if (noAppOpens) {
            // Android only: nothing installed opens this type (Share still saves it to Files).
            Text(
                "No app on this device can open this file. Share it to save it or send it elsewhere.",
                style = OpenCodeTextStyles.footnote,
                color = OpenCodeTheme.colors.secondaryText,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .widthIn(max = 280.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite }
                    .testTag("agent-file-no-app"),
            )
        }
    }
}

/** A PDF's pages, rendered as they scroll into view. */
@Composable
private fun AgentPdfView(document: AgentPdfDocument, modifier: Modifier) {
    BoxWithConstraints(modifier.fillMaxSize().background(OpenCodeTheme.colors.groupedCanvas)) {
        val widthPx = with(LocalDensity.current) { (maxWidth - TranscriptMetrics.standard * 2).roundToPx() }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = TranscriptMetrics.standard,
                top = TranscriptMetrics.standard,
                end = TranscriptMetrics.standard,
                bottom = TranscriptMetrics.standard + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
            ),
            verticalArrangement = Arrangement.spacedBy(TranscriptMetrics.standard),
        ) {
            items(count = document.pageCount, key = { it }) { index ->
                val bitmap by produceState<android.graphics.Bitmap?>(null, document, index, widthPx) {
                    value = document.render(index, widthPx)
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(document.pageAspects[index].coerceAtLeast(0.05f))
                        .background(Color.White)
                        .semantics { contentDescription = "Page ${index + 1} of ${document.pageCount}" },
                ) {
                    bitmap?.let {
                        Image(it.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                    }
                }
            }
        }
    }
}

/**
 * The player, with a glyph and name above the controls for audio (iOS `AgentFilePlayer`,
 * `AVPlayerViewController`). Video controls hide while playing and come back with a tap.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@Composable
private fun AgentFilePlayer(player: Player, isAudio: Boolean, name: String, isClear: Boolean, pip: AgentFilePictureInPicture?, modifier: Modifier) {
    var controlsShown by remember { mutableStateOf(true) }
    val playPause = rememberPlayPauseButtonState(player)
    // Letterboxing shows the dark room behind, so it lifts with a drag.
    Box(
        modifier
            .fillMaxSize()
            .background(if (isClear && !isAudio) Color.Transparent else Color.Black)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, enabled = !isAudio) {
                controlsShown = !controlsShown
            },
    ) {
        if (isAudio) {
            AudioArtwork(name)
        } else {
            ContentFrame(
                player = player,
                modifier = Modifier
                    .fillMaxSize()
                    .onGloballyPositioned { coordinates ->
                        // Where the letterboxed video sits: the PiP window grows out of it.
                        val pipState = pip ?: return@onGloballyPositioned
                        val bounds = coordinates.boundsInWindow()
                        val video = player.videoSize
                        pipState.videoFrame = if (video.width > 0 && video.height > 0 && bounds.width > 0f && bounds.height > 0f) {
                            val aspect = video.width * video.pixelWidthHeightRatio / video.height
                            val width = minOf(bounds.width, bounds.height * aspect)
                            val height = width / aspect
                            Rect(bounds.center.x - width / 2, bounds.center.y - height / 2, bounds.center.x + width / 2, bounds.center.y + height / 2)
                        } else {
                            bounds
                        }
                    },
            )
        }
        LaunchedEffect(controlsShown, playPause.showPlay, isAudio) {
            if (!isAudio && controlsShown && !playPause.showPlay) {
                delay(3_000)
                controlsShown = false
            }
        }
        if (controlsShown || isAudio) {
            PlayerControls(player, Modifier.align(Alignment.BottomCenter))
        }
    }
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@Composable
private fun PlayerControls(player: Player, modifier: Modifier) {
    val playPause = rememberPlayPauseButtonState(player)
    val progress = rememberProgressStateWithTickInterval(player, 250L)
    var scrubbing by remember { mutableStateOf<Float?>(null) }
    val duration = progress.durationMs.coerceAtLeast(0)
    val position = scrubbing?.toLong() ?: progress.currentPositionMs
    Column(
        modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.45f))
            .navigationBarsPadding()
            .padding(horizontal = TranscriptMetrics.contentGutter, vertical = TranscriptMetrics.clustered),
    ) {
        Slider(
            value = position.coerceIn(0, maxOf(duration, 1)).toFloat(),
            onValueChange = { scrubbing = it },
            onValueChangeFinished = {
                scrubbing?.let { player.seekTo(it.toLong()) }
                scrubbing = null
            },
            valueRange = 0f..maxOf(duration, 1).toFloat(),
            enabled = duration > 0,
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White),
            modifier = Modifier.semantics { contentDescription = "Playback position" },
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(timeText(position), style = OpenCodeTextStyles.caption1.copy(fontFeatureSettings = "tnum"), color = Color.White)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = playPause::onClick, enabled = playPause.isEnabled) {
                OpenCodeIcon(
                    if (playPause.showPlay) "play.fill" else "pause.fill",
                    contentDescription = if (playPause.showPlay) "Play" else "Pause",
                    tint = Color.White,
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                if (duration > 0) "-" + timeText(duration - position) else "--:--",
                style = OpenCodeTextStyles.caption1.copy(fontFeatureSettings = "tnum"),
                color = Color.White,
            )
        }
    }
}

@Composable
private fun AudioArtwork(name: String) {
    val reducedMotion = rememberReduceMotion()
    val pulse = if (reducedMotion) {
        1f
    } else {
        val transition = rememberInfiniteTransition(label = "waveform")
        val value by transition.animateFloat(0.45f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "waveform")
        value
    }
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp)
            .clearAndSetSemantics {},
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(TranscriptMetrics.turn, Alignment.CenterVertically),
    ) {
        OpenCodeIcon("waveform", contentDescription = null, size = 72.dp, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.alpha(pulse))
        Text(name, style = OpenCodeTextStyles.headline, color = Color.White, maxLines = 2, textAlign = TextAlign.Center)
    }
}

/** "Downloading 1.2 MB of 3.1 MB"; before the first bytes, just the size. */
fun progressText(received: Long, expected: Long?): String {
    val total = expected?.takeIf { it > 0 }?.let(::fileSizeText)
    if (received <= 0) return total?.let { "Downloading $it" } ?: "Downloading…"
    val done = fileSizeText(received)
    return total?.let { "Downloading $done of $it" } ?: "Downloading $done"
}

/** `ByteCountFormatter` `.file`: decimal units ("Zero KB", "12 bytes", "345 KB", "1.2 MB"). */
internal fun fileSizeText(bytes: Long): String {
    if (bytes == 0L) return "Zero KB"
    if (bytes < 1000) return "$bytes ${if (bytes == 1L) "byte" else "bytes"}"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes / 1000.0
    var unit = 0
    while (value >= 1000 && unit < units.lastIndex) {
        value /= 1000
        unit++
    }
    val text = if (unit == 0) Math.round(value).toString() else String.format(Locale.US, "%.1f", value).removeSuffix(".0")
    return "$text ${units[unit]}"
}

private fun timeText(ms: Long): String {
    val seconds = (ms.coerceAtLeast(0) / 1000)
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val secs = seconds % 60
    return if (hours > 0) String.format(Locale.US, "%d:%02d:%02d", hours, minutes, secs) else String.format(Locale.US, "%d:%02d", minutes, secs)
}

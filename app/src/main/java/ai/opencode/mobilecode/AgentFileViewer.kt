@file:PortedFrom("OpenCode/AgentFiles/AgentFileViewer.swift", "74bd186")

package ai.opencode.mobilecode

import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import android.view.LayoutInflater
import ai.opencode.mobilecode.system.ForcedAppearance
import ai.opencode.mobilecode.system.SystemColors
import ai.opencode.mobilecode.system.SystemImage
import ai.opencode.mobilecode.system.TextStyles
import ai.opencode.mobilecode.system.glassEffect
import ai.opencode.mobilecode.system.semibold
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import androidx.core.content.FileProvider
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import androidx.compose.ui.viewinterop.AndroidView
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AgentTextDocument(
    val lines: List<String>,
    val focusLine: Int?,
) {
    companion object {
        const val maxHighlightedCharacters = 300_000
        val proseExtensions: Set<String> = setOf("md", "markdown", "mdx", "txt", "log", "csv", "tsv", "rtf")
        val markdownExtensions: Set<String> = setOf("md", "markdown", "mdx")

        operator fun invoke(source: String, fileName: String, focusLine: Int?): AgentTextDocument {
            val lines = lines(of = source)
            val focus = focusLine?.takeIf { it in 1..maxOf(lines.size, 1) }
            return AgentTextDocument(lines, focus)
        }

        fun lines(of: String): List<String> {
            if (of.isEmpty()) return emptyList()
            val lines = of.split("\n").toMutableList()
            if (lines.size > 1 && lines.last().isEmpty()) lines.removeAt(lines.lastIndex)
            return lines
        }
    }
}

class AgentFileViewerModel(
    val reference: AgentFileReference,
    val server: OpenCodeServer,
    val directory: String?,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) {
    sealed class Content {
        data class player(val url: String, val headers: Map<String, String>, val isAudio: Boolean, val isStreaming: Boolean) : Content()

        /// Shown like a transcript image: zoomable, dragged down to dismiss.
        data class image(val image: ImageBitmap) : Content()
        data class quickLook(val url: File) : Content()
        data class text(val document: AgentTextDocument) : Content()
        data class markdown(val blocks: List<OpenCodeMarkdownBlock>, val source: AgentTextDocument) : Content()
    }

    sealed class Phase {
        data object opening : Phase()
        data class downloading(val received: Long, val expected: Long?) : Phase()
        data class confirmLarge(val size: Long) : Phase()
        data class ready(val content: Content, val localURL: File?) : Phase()
        data class failed(val error: AgentFileError) : Phase()
    }

    var phase: Phase by mutableStateOf(Phase.opening)
    private var streamLoader: StreamingFileLoader? = null
    private var probe: AgentFileProbe? = null
    private var work: Job? = null

    val target: AgentFileReference.Target? get() = reference.target(`in` = directory)

    val fileDirectory: String? get() = target?.absolutePath?.substringBeforeLast("/", "")

    val locationLabel: String?
        get() {
            val current = target ?: return null
            val parent = current.absolutePath.substringBeforeLast("/", "")
            if (directory != null && (parent == directory || parent.startsWith("$directory/"))) {
                val project = directory.substringAfterLast("/")
                return if (parent == directory) project else project + parent.removePrefix(directory)
            }
            val home = AgentFileReference.home(from = directory)
            if (home != null && (parent == home || parent.startsWith("$home/"))) return "~" + parent.removePrefix(home)
            return parent
        }

    fun open() {
        val ready = phase as? Phase.ready
        if (ready?.localURL != null) return
        work?.cancel()
        work = scope.launch { run(confirmed = false) }
    }

    fun confirmDownload() {
        work?.cancel()
        work = scope.launch { run(confirmed = true) }
    }

    fun stop() {
        work?.cancel()
        streamLoader?.invalidate()
        streamLoader = null
    }

    private suspend fun run(confirmed: Boolean) {
        val target = target
        val request = target?.let { server.fileReadRequest(it) }
        if (target == null || request == null) {
            phase = Phase.failed(AgentFileError.unreadable)
            return
        }
        try {
            if (!confirmed || probe == null) {
                phase = Phase.opening
                probe = AgentFileLoader.probe(request)
            }
            val current = probe ?: return
            if (reference.kind.isTimeBased && current.supportsRanges) {
                startStreaming(request, current.mime)
                return
            }
            if (!confirmed && current.size != null && current.size!! > AgentFileLoader.confirmationBytes) {
                phase = Phase.confirmLarge(current.size!!)
                return
            }
            phase = Phase.downloading(0, current.size)
            val destination = AgentFileLoader.cacheURL(server, target.absolutePath, reference.name)
            val url = AgentFileLoader.download(request, destination) { received, expected ->
                scope.launch { downloaded(received, expected) }
            }
            present(url)
        } catch (error: AgentFileException) {
            phase = Phase.failed(error.error)
        } catch (error: Exception) {
            phase = Phase.failed(AgentFileError.network(error.message ?: "The server sent an unexpected response."))
        }
    }

    private fun downloaded(received: Long, expected: Long?) {
        if (phase !is Phase.downloading) return
        phase = Phase.downloading(received, expected ?: probe?.size)
    }

    private fun startStreaming(request: URLRequest, mime: String?) {
        streamLoader?.invalidate()
        val loader = StreamingFileLoader(request, mime, reference.name)
        streamLoader = loader
        phase = Phase.ready(
            Content.player(request.url.absoluteString, loader.headers(), reference.kind == AgentFileReference.Kind.audio, true),
            null,
        )
    }

    private suspend fun present(url: File) {
        when (reference.kind) {
            AgentFileReference.Kind.video, AgentFileReference.Kind.audio -> {
                phase = Phase.ready(
                    Content.player(url.toURI().toString(), emptyMap(), reference.kind == AgentFileReference.Kind.audio, false),
                    url,
                )
            }
            AgentFileReference.Kind.text -> {
                val name = reference.name
                val line = reference.line
                val isMarkdown = AgentTextDocument.markdownExtensions.contains(AttachmentFileType.fileExtension(name)) && line == null
                val source = withContext(Dispatchers.IO) { AgentFileLoader.readText(url) }
                phase = if (source == null) {
                    Phase.ready(Content.quickLook(url), url)
                } else {
                    val document = AgentTextDocument(source, name, line)
                    val content = if (isMarkdown) {
                        Content.markdown(OpenCodeMarkdownBlock.blocks(source), document)
                    } else {
                        Content.text(document)
                    }
                    Phase.ready(content, url)
                }
            }
            AgentFileReference.Kind.image -> {
                // Formats Android can't decode (SVG, say) still open in Quick Look.
                val image = loadImage(url)
                phase = Phase.ready(image?.let { Content.image(it) } ?: Content.quickLook(url), url)
            }
            AgentFileReference.Kind.pdf, AgentFileReference.Kind.document ->
                phase = Phase.ready(Content.quickLook(url), url)
        }
    }
}

private suspend fun loadImage(url: File): ImageBitmap? = withContext(Dispatchers.IO) {
    runCatching { BitmapFactory.decodeFile(url.path)?.asImageBitmap()?.apply { prepareToDraw() } }.getOrNull()
}

data class AgentFileDestination(
    var reference: AgentFileReference,
    var directory: String?,
)

@Composable
fun AgentFileViewer(
    reference: AgentFileReference,
    server: OpenCodeServer,
    directory: String?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var path by remember { mutableStateOf(listOf<AgentFileDestination>()) }
    val page = path.lastOrNull()
    AgentFilePage(
        reference = page?.reference ?: reference,
        server = server,
        directory = page?.directory ?: directory,
        isRoot = path.isEmpty(),
        onOpen = { path = path + it },
        onDismiss = {
            if (path.isEmpty()) onDismiss() else path = path.dropLast(1)
        },
        modifier = modifier.semantics { testTag = "agent-file-viewer" },
    )
}

@Composable
fun AgentFilePage(
    reference: AgentFileReference,
    server: OpenCodeServer,
    directory: String?,
    isRoot: Boolean,
    onOpen: (AgentFileDestination) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    initial: AgentFileViewerModel.Phase? = null,
) {
    val model = remember(reference, server, directory) {
        AgentFileViewerModel(reference, server, directory).also { viewer ->
            if (initial != null) viewer.phase = initial
        }
    }
    DisposableEffect(model) {
        if (initial == null) model.open()
        onDispose { model.stop() }
    }
    val viewer = LocalMediaViewer.current
    // Media's dark room is the viewer's own, which lifts as it's dragged away.
    val background = when {
        !reference.kind.isMedia -> null
        isRoot && viewer != null -> Color.Transparent
        else -> Color.Black
    }
    val chrome by animateFloatAsState(
        if (viewer?.chromeHidden == true) 0f else 1f,
        tween(if (viewer?.chromeHidden == true) 150 else 200),
        label = "chrome",
    )
    // Media plays in a dark room, like Photos.
    ForcedAppearance(dark = reference.kind.isMedia || isSystemInDarkTheme()) {
        Column(modifier.fillMaxSize().background(background ?: SystemColors.systemBackground)) {
            AgentFileTitle(
                reference.name,
                model.locationLabel,
                isRoot,
                model.phase,
                onDismiss = { viewer?.dismiss() ?: onDismiss() },
                modifier = Modifier.graphicsLayer { alpha = chrome },
            )
            AgentFileBody(model, onOpen, isRoot, isClear = background == Color.Transparent, Modifier.weight(1f))
        }
    }
}

@Composable
private fun AgentFileTitle(
    name: String,
    location: String?,
    isRoot: Boolean,
    phase: AgentFileViewerModel.Phase,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth().height(52.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (isRoot) {
            TextButton(onClick = onDismiss, modifier = Modifier.semantics { testTag = "agent-file-done" }) {
                Text("Done", color = SystemColors.tint, style = TextStyles.body)
            }
        }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(name, style = TextStyles.headline, color = SystemColors.label, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            if (location != null) {
                Text(location, style = TextStyles.caption, color = SystemColors.secondaryLabel, maxLines = 1, overflow = TextOverflow.StartEllipsis)
            }
        }
        if (phase is AgentFileViewerModel.Phase.ready && phase.localURL != null) {
            val context = LocalContext.current
            TextButton(onClick = { share(context, phase.localURL) }) {
                SystemImage("square.and.arrow.up", contentDescription = "Share", tint = SystemColors.tint, modifier = Modifier.width(22.dp))
            }
        }
    }
}

@Composable
private fun AgentFileBody(
    model: AgentFileViewerModel,
    onOpen: (AgentFileDestination) -> Unit,
    isRoot: Boolean,
    isClear: Boolean,
    modifier: Modifier = Modifier,
) {
    val viewer = LocalMediaViewer.current
    when (val phase = model.phase) {
        AgentFileViewerModel.Phase.opening -> AgentFileStatus(model.reference, "Opening…", null, modifier)
        is AgentFileViewerModel.Phase.downloading -> AgentFileStatus(
            model.reference,
            progressText(phase.received, phase.expected),
            phase.expected?.let { phase.received.toFloat() / maxOf(it, 1).toFloat() },
            modifier,
        )
        is AgentFileViewerModel.Phase.confirmLarge -> Column(modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            SystemImage(model.reference.symbolName, contentDescription = null, tint = SystemColors.secondaryLabel, modifier = Modifier.height(44.dp))
            Text(model.reference.name, style = TextStyles.headline, color = SystemColors.label)
            Text(
                "This file is ${AttachmentFileType.byteCount(phase.size)}. Download it to view it on this device?",
                style = TextStyles.subheadline,
                color = SystemColors.secondaryLabel,
            )
            TextButton(onClick = { model.confirmDownload() }) { Text("Download") }
        }
        is AgentFileViewerModel.Phase.failed -> Column(modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            SystemImage(
                if (phase.error == AgentFileError.notFound) "questionmark.folder" else "exclamationmark.triangle",
                contentDescription = null,
                tint = SystemColors.secondaryLabel,
                modifier = Modifier.height(44.dp),
            )
            Text(phase.error.title, style = TextStyles.headline, color = SystemColors.label)
            Text(phase.error.detail, style = TextStyles.subheadline, color = SystemColors.secondaryLabel)
            TextButton(onClick = { model.open() }) { Text("Try Again") }
        }
        is AgentFileViewerModel.Phase.ready -> when (val content = phase.content) {
            is AgentFileViewerModel.Content.image -> {
                val reference = model.reference
                ZoomableImagePage(
                    image = content.image,
                    label = reference.name,
                    isSelected = true,
                    modifier = modifier.semantics { testTag = "agent-file-image" },
                    isHidden = isRoot && viewer?.mediaHidden == true,
                    onZoomChange = { viewer?.isZoomed = it },
                    onFrame = { frame -> if (isRoot) viewer?.mediaFrames?.set(reference.zoomSourceID, frame) },
                )
                DisposableEffect(content.image) {
                    if (isRoot) viewer?.show(sourceID = reference.zoomSourceID, image = content.image)
                    onDispose {}
                }
            }
            is AgentFileViewerModel.Content.player -> AgentFilePlayer(
                content.url,
                content.headers,
                content.isAudio,
                model.reference.name,
                content.isStreaming,
                isClear,
                modifier,
            )
            is AgentFileViewerModel.Content.quickLook -> AgentQuickLook(content.url, modifier)
            is AgentFileViewerModel.Content.text -> AgentCodeView(content.document, wrapsLines = false, modifier)
            is AgentFileViewerModel.Content.markdown -> AgentCodeView(content.source, wrapsLines = false, modifier)
        }
    }
}

@Composable
internal fun AgentCodeView(document: AgentTextDocument, wrapsLines: Boolean, modifier: Modifier = Modifier) {
    val scroll = rememberScrollState()
    val horizontal = rememberScrollState()
    val digits = maxOf(document.lines.size.toString().length, 2)
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .then(if (wrapsLines) Modifier else Modifier.horizontalScroll(horizontal))
            .padding(vertical = 12.dp)
            .semantics { testTag = "agent-file-code" },
    ) {
        document.lines.forEachIndexed { index, line ->
            val number = index + 1
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(if (number == document.focusLine) Color.Yellow.copy(alpha = 0.35f) else Color.Transparent)
                    .padding(horizontal = 12.dp, vertical = 1.dp),
            ) {
                Text(
                    number.toString(),
                    style = TextStyles.code,
                    color = SystemColors.tertiaryLabel,
                    modifier = Modifier.width((digits * 9 + 4).dp),
                    maxLines = 1,
                )
                Text(
                    line.ifEmpty { " " },
                    style = TextStyles.code.copy(fontFamily = FontFamily.Monospace),
                    color = SystemColors.label,
                    maxLines = if (wrapsLines) Int.MAX_VALUE else 1,
                )
            }
        }
    }
}

@Composable
internal fun AgentQuickLook(url: File, modifier: Modifier = Modifier) {
    val bitmap = remember(url) {
        runCatching { BitmapFactory.decodeFile(url.path)?.asImageBitmap() }.getOrNull()
    }
    Box(modifier.fillMaxSize().semantics { testTag = "agent-file-quick-look" }, contentAlignment = Alignment.Center) {
        if (bitmap != null) {
            Image(bitmap, contentDescription = url.name, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        } else {
            val context = LocalContext.current
            TextButton(onClick = { view(context, url) }) { Text("Open") }
        }
    }
}

@Composable
private fun AgentFileStatus(reference: AgentFileReference, detail: String, progress: Float?, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().padding(32.dp).semantics { testTag = "agent-file-status" },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SystemImage(reference.symbolName, contentDescription = null, tint = SystemColors.secondaryLabel, modifier = Modifier.height(44.dp))
        Text(reference.name, style = TextStyles.headline, color = SystemColors.label)
        Text(detail, style = TextStyles.subheadline, color = SystemColors.secondaryLabel)
        if (progress == null) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 16.dp))
        } else {
            LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(top = 16.dp))
        }
    }
}

@Composable
private fun AgentFilePlayer(
    url: String,
    headers: Map<String, String>,
    isAudio: Boolean,
    name: String,
    isStreaming: Boolean,
    /// Letterboxing shows the dark room behind, so it lifts with a drag.
    isClear: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val player = remember(url, headers) {
        ExoPlayer.Builder(context).build().apply {
            val item = MediaItem.fromUri(url)
            if (url.startsWith("http")) {
                val factory = DefaultHttpDataSource.Factory().setDefaultRequestProperties(headers)
                setMediaSource(ProgressiveMediaSource.Factory(factory).createMediaSource(item))
            } else {
                setMediaItem(item)
            }
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    val clear = isClear && !isAudio
    Box(
        modifier
            .fillMaxSize()
            .background(if (clear) Color.Transparent else Color.Black)
            .semantics { testTag = if (isStreaming) "agent-file-player-streaming" else "agent-file-player" },
    ) {
        AndroidView(
            factory = {
                // A TextureView, so the video scales and fades with a drag.
                (LayoutInflater.from(it).inflate(R.layout.agent_file_player, null) as PlayerView).apply {
                    this.player = player
                    if (clear) setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        if (isAudio) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                SystemImage("waveform", contentDescription = null, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.height(72.dp))
                Text(name, style = TextStyles.headline, color = Color.White)
            }
        }
    }
}

fun progressText(received: Long, expected: Long?): String {
    val total = expected?.takeIf { it > 0 }?.let { AttachmentFileType.byteCount(it) }
    if (received <= 0) return total?.let { "Downloading $it" } ?: "Downloading…"
    val done = AttachmentFileType.byteCount(received)
    return total?.let { "Downloading $done of $it" } ?: "Downloading $done"
}

private fun share(context: android.content.Context, file: File) {
    val uri = fileUri(context, file) ?: return
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = AttachmentFileType.declaredMime(file.name) ?: "application/octet-stream"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, null))
}

private fun view(context: android.content.Context, file: File) {
    val uri = fileUri(context, file) ?: return
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, AttachmentFileType.declaredMime(file.name) ?: "application/octet-stream")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(intent) }
}

private fun fileUri(context: android.content.Context, file: File): Uri? = runCatching {
    FileProvider.getUriForFile(context, "${context.packageName}.files", file)
}.getOrNull()

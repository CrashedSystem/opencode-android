@file:PortedFrom("OpenCode/ComposerFileImport.swift", "553a407")

package ai.opencode.mobilecode

import android.net.Uri
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed class ComposerFileSource {
    data class url(val url: File) : ComposerFileSource()
    data class photosPickerItem(val item: PhotosPickerItem, val id: UUID = UUID.randomUUID()) : ComposerFileSource()
}

object ComposerFileRouting {
    private val inlineImageExtensions = setOf("png", "jpeg", "jpg", "gif", "webp", "heic", "heif")

    fun isInlineImage(url: File): Boolean = AttachmentFileType.fileExtension(url.name) in inlineImageExtensions
}

class ComposerFileImporter(
    private val scope: CoroutineScope = importerScope(),
) {
    private var active = 0
    val isLoading: Boolean get() = active > 0

    fun start(
        sources: List<ComposerFileSource>,
        onStaged: (List<ComposerStagedFile>) -> Unit,
        onFailure: (Int) -> Unit,
    ) {
        if (sources.isEmpty()) return
        active += 1
        scope.launch {
            val staged = mutableListOf<ComposerStagedFile>()
            var failures = 0
            for (source in sources) {
                val file = stage(source)
                if (file != null) staged += file else failures += 1
            }
            active -= 1
            if (staged.isNotEmpty()) onStaged(staged)
            if (failures > 0) onFailure(failures)
        }
    }

    private suspend fun stage(source: ComposerFileSource): ComposerStagedFile? = when (source) {
        is ComposerFileSource.url -> withContext(Dispatchers.IO) { runCatching { ComposerFileStaging.stage(source.url) }.getOrNull() }
        is ComposerFileSource.photosPickerItem -> {
            val staged = source.item.loadMovie() ?: return null
            staged.id = source.id
            staged
        }
    }
}

fun copyUriToCache(context: android.content.Context, uri: Uri, name: String? = null): File? {
    val display = name ?: uri.lastPathSegment ?: "file"
    val folder = File(context.cacheDir, "picked-${UUID.randomUUID()}")
    folder.mkdirs()
    val destination = File(folder, AttachmentFileType.sanitizedName(display))
    return runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            destination.outputStream().use { output -> input.copyTo(output) }
        } ?: return null
        destination
    }.getOrNull()
}

@file:PortedFrom("OpenCode/ComposerImageImport.swift", "553a407")

package ai.opencode.mobilecode

import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ComposerImageTransfer(val data: ByteArray) {
    override fun equals(other: Any?): Boolean {
        val image = other as? ComposerImageTransfer ?: return false
        return data.contentEquals(image.data)
    }

    override fun hashCode(): Int = data.contentHashCode()
}

object ImageAttachmentImporter {
    const val maxSourceBytes = SharedImageEncoder.maxSourceBytes

    suspend fun process(data: ByteArray, id: UUID = UUID.randomUUID()): OpenCodeOutgoingAttachment? {
        val jpeg = withContext(Dispatchers.Default) { SharedImageEncoder.jpeg(from = data) } ?: return null
        return OpenCodeOutgoingAttachment(id = id.toString(), data = jpeg, mime = SharedImageEncoder.mime)
    }
}

class NSItemProvider(
    val registeredTypeIdentifiers: List<String> = emptyList(),
    val data: ByteArray? = null,
    val file: java.io.File? = null,
    val canLoadImage: Boolean = false,
) {
    fun hasItemConformingToTypeIdentifier(identifier: String): Boolean =
        registeredTypeIdentifiers.any { it == identifier || it.startsWith("image/") || (identifier == "public.image" && canLoadImage) }

    suspend fun loadData(): ByteArray? {
        val imageType = registeredTypeIdentifiers.firstOrNull { it.startsWith("image/") || it == "public.image" || it == "public.png" || it == "public.jpeg" }
        if (imageType != null) {
            data?.let { return it }
            file?.let { return runCatching { it.readBytes() }.getOrNull() }
        }
        if (registeredTypeIdentifiers.contains("public.file-url") || file != null) {
            file?.let { return runCatching { it.readBytes() }.getOrNull() }
        }
        if (!canLoadImage) return null
        return data
    }
}

object ComposerItemProviderLoader {
    suspend fun load(provider: NSItemProvider): ByteArray? = provider.loadData()
}

sealed class ComposerImageSource {
    data class itemProvider(val provider: NSItemProvider) : ComposerImageSource()
    data class photosPickerItem(val item: PhotosPickerItem, val id: UUID? = null) : ComposerImageSource()
    data class data(val data: ByteArray, val id: UUID? = null) : ComposerImageSource()
    data class transferable(val image: ComposerImageTransfer) : ComposerImageSource()
    data class loader(val id: UUID? = null, val load: suspend () -> ByteArray?) : ComposerImageSource()

    val attachmentID: UUID?
        get() = when (this) {
            is photosPickerItem -> id
            is data -> id
            is loader -> id
            is itemProvider, is transferable -> null
        }

    suspend fun load(): ByteArray? = when (this) {
        is itemProvider -> ComposerItemProviderLoader.load(provider)
        is photosPickerItem -> item.loadImage()
        is data -> data
        is transferable -> image.data
        is loader -> load.invoke()
    }
}

internal fun importerScope(): CoroutineScope {
    val dispatcher = try {
        Dispatchers.Main.immediate
    } catch (_: IllegalStateException) {
        Dispatchers.Default
    }
    return CoroutineScope(SupervisorJob() + dispatcher)
}

class ComposerImageImporter(
    private val scope: CoroutineScope = importerScope(),
) {
    class Result {
        var addedCount = 0
        var sawOversizedSource = false
    }

    private var task: Job? = null
    val isLoading: Boolean get() = task != null

    fun start(
        sources: List<ComposerImageSource>,
        process: suspend (ByteArray, UUID) -> OpenCodeOutgoingAttachment? = { data, id ->
            ImageAttachmentImporter.process(data, id)
        },
        onAttachment: (OpenCodeOutgoingAttachment) -> Unit,
        onCompletion: (Result) -> Unit = {},
    ) {
        cancel()
        val limited = sources.take(AttachmentPickerButton.maxPhotoSelection)
        if (limited.isEmpty()) return
        task = scope.launch {
            val mine = coroutineContext[Job]
            val result = Result()
            try {
                for (source in limited) {
                    if (!isActive) return@launch
                    val data = source.load()
                    if (!isActive) return@launch
                    if (data == null) continue
                    if (data.size > ImageAttachmentImporter.maxSourceBytes) {
                        result.sawOversizedSource = true
                        continue
                    }
                    val attachment = process(data, source.attachmentID ?: UUID.randomUUID())
                    if (!isActive) return@launch
                    if (attachment == null) continue
                    onAttachment(attachment)
                    if (!isActive) return@launch
                    result.addedCount += 1
                }
                if (!isActive) return@launch
                onCompletion(result)
            } finally {
                if (task === mine && mine?.isCancelled != true) task = null
            }
        }
    }

    fun cancel() {
        task?.cancel()
        task = null
    }
}

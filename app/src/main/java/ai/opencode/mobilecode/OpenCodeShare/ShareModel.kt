@file:PortedFrom("OpenCodeShare/ShareModel.swift", "553a407")

package ai.opencode.mobilecode

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.net.URI
import java.time.Instant
import java.util.UUID

class ShareModel(
    val targets: ShareTargets?,
    private val store: ShareHandoffStore?,
) {
    class SharedImage(
        val id: UUID,
        val jpeg: ByteArray,
        val preview: Bitmap,
    )

    sealed interface Destination {
        data class session(val session: ShareTargets.Session) : Destination
        data object newSession : Destination
    }

    var images by mutableStateOf(listOf<SharedImage>())
    var files by mutableStateOf(listOf<ComposerStagedFile>())
    /// Shared text and links, in the order they were shared.
    var sharedText by mutableStateOf(listOf<String>())
    var message by mutableStateOf("")
    var destination by mutableStateOf<Destination?>(null)
    var isLoading by mutableStateOf(true)
    var skippedImageCount by mutableStateOf(0)
    var skippedFileCount by mutableStateOf(0)
    var saveError by mutableStateOf<String?>(null)

    init {
        val remembered = targets?.let { published ->
            (published.pinned + published.recents).firstOrNull { it.id == published.lastViewedSessionID }
        }
        destination = if (targets == null) null else remembered?.let { Destination.session(it) } ?: Destination.newSession
    }

    val hasContent: Boolean
        get() = images.isNotEmpty() || files.isNotEmpty() || composedText.isNotEmpty()

    val canAdd: Boolean
        get() = !isLoading && hasContent && destination != null && store != null

    val composedText: String
        get() {
            val trimmed = message.trim()
            return (listOf(trimmed) + sharedText).filter { it.isNotEmpty() }.joinToString("\n\n")
        }

    suspend fun load(providers: List<NSItemProvider>) {
        try {
            for (provider in providers) {
                if (SharedItemLoader.isImage(provider)) {
                    if (images.size >= ShareHandoff.maximumImages) {
                        skippedImageCount += 1
                        continue
                    }
                    val image = SharedItemLoader.image(provider)
                    if (image != null) images = images + image else skippedImageCount += 1
                } else if (SharedItemLoader.fileType(provider) != null) {
                    addFile { SharedItemLoader.file(provider, SharedItemLoader.fileType(provider)!!) }
                } else if (provider.registeredTypeIdentifiers.any { SharedItemLoader.isURL(it) }) {
                    val url = SharedItemLoader.url(provider) ?: continue
                    if (url.scheme == "file") {
                        addFile { SharedItemLoader.file(copying = File(url.path), name = null) }
                    } else {
                        appendText(url.toString())
                    }
                } else if (provider.registeredTypeIdentifiers.any { SharedItemLoader.isText(it) }) {
                    SharedItemLoader.text(provider)?.let { appendText(it) }
                }
            }
        } finally {
            isLoading = false
        }
    }

    private suspend fun addFile(load: suspend () -> ComposerStagedFile?) {
        if (files.size >= ShareHandoff.maximumFiles) {
            skippedFileCount += 1
            return
        }
        val file = load()
        if (file != null) files = files + file else skippedFileCount += 1
    }

    private fun appendText(text: String) {
        val trimmed = text.trim()
        // Safari shares a page as both its link and the same text.
        if (trimmed.isEmpty() || sharedText.contains(trimmed)) return
        sharedText = sharedText + trimmed
    }

    /// Removes this extension's temporary copies; the handoff has its own.
    fun discardFiles() {
        for (file in files) ComposerFileStaging.discard(file.url)
    }

    /// Files the share for the app; returns false and sets `saveError` on failure.
    fun save(now: Instant = Instant.now()): Boolean {
        val targets = targets
        val store = store
        val destination = destination
        if (!canAdd || targets == null || store == null || destination == null) return false
        val target = when (destination) {
            is Destination.session -> ShareHandoff.Target.session(id = destination.session.id, projectID = destination.session.projectID)
            Destination.newSession -> ShareHandoff.Target.newSession(projectID = targets.newSessionProject?.id)
        }
        val handoff = ShareHandoff(
            id = UUID.randomUUID(),
            createdAt = now,
            serverID = targets.serverID,
            target = target,
            text = composedText,
            images = images.mapIndexed { index, image ->
                ShareHandoff.Image(id = image.id, fileName = "image-$index.jpg", mime = SharedImageEncoder.mime)
            },
            files = if (files.isEmpty()) {
                null
            } else {
                files.mapIndexed { index, file ->
                    ShareHandoff.File(id = file.id, fileName = "file-$index", name = file.name, mime = file.mime)
                }
            },
        )
        return try {
            store.save(handoff, images.map { it.jpeg }, files.map { it.url })
            discardFiles()
            true
        } catch (_: Exception) {
            saveError = "Try again. If it keeps happening, free up some storage on this phone."
            false
        }
    }
}

object SharedItemLoader {
    class Image(
        val jpeg: ByteArray,
        val preview: Bitmap,
    )

    fun isImage(provider: NSItemProvider): Boolean =
        provider.canLoadImage || provider.registeredTypeIdentifiers.any { isImageType(it) }

    fun isURL(type: String): Boolean = type == "public.url" || type == "public.file-url"

    fun isText(type: String): Boolean =
        type == "public.plain-text" || type == "public.text" || type == "text/plain" || type.startsWith("text/")

    /// PDFs, documents, video, audio, and other data. Links and typed text
    /// stay text unless the item is a named file (a `.md` from Files).
    fun fileType(provider: NSItemProvider): String? {
        val types = provider.registeredTypeIdentifiers
        val data = types.firstOrNull { type ->
            !isURL(type) && !isText(type) && !isImageType(type)
        }
        if (data != null) return data
        val name = provider.suggestedName
        if (!name.isNullOrEmpty() && name.substringAfterLast('.', "").isNotEmpty()) {
            return types.firstOrNull { isText(it) }
        }
        return null
    }

    /// Re-encodes inside the provider's callback, while its temporary file is
    /// still valid, so only the small JPEG ever crosses back.
    fun image(provider: NSItemProvider): ShareModel.SharedImage? {
        val jpeg = provider.file?.let { SharedImageEncoder.jpeg(contentsOf = it) }
            ?: provider.data?.let { SharedImageEncoder.jpeg(from = it) }
            ?: return null
        val preview = SharedImageEncoder.thumbnail(from = jpeg, maxPixelSize = 240) ?: return null
        return ShareModel.SharedImage(id = UUID.randomUUID(), jpeg = jpeg, preview = preview)
    }

    /// Copies the provider's file while its temporary URL is still valid.
    fun file(provider: NSItemProvider, type: String): ComposerStagedFile? {
        val suggested = provider.suggestedName
        val source = provider.file ?: provider.data?.let { bytes ->
            val temporary = File.createTempFile("share-item", null)
            temporary.writeBytes(bytes)
            temporary
        } ?: return null
        var name = source.name
        if (!suggested.isNullOrEmpty()) {
            name = if (!suggested.contains('.') && source.extension.isNotEmpty()) {
                "$suggested.${source.extension}"
            } else {
                suggested
            }
        }
        if (type.isEmpty()) return null
        return runCatching { ComposerFileStaging.stage(source, name = name) }.getOrNull()
    }

    fun file(copying: File, name: String?): ComposerStagedFile? =
        runCatching { ComposerFileStaging.stage(copying, name = name) }.getOrNull()

    fun url(provider: NSItemProvider): URI? {
        val raw = provider.data?.toString(Charsets.UTF_8) ?: provider.file?.path ?: return null
        return runCatching { URI(raw) }.getOrNull()
    }

    fun text(provider: NSItemProvider): String? = provider.data?.toString(Charsets.UTF_8)

    private fun isImageType(type: String): Boolean =
        type == "public.image" || type == "public.png" || type == "public.jpeg" || type.startsWith("image/")
}

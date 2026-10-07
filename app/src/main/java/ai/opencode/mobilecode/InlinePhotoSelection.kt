@file:PortedFrom("OpenCode/InlinePhotoSelection.swift", "553a407")

package ai.opencode.mobilecode

import android.net.Uri
import java.io.File
import java.util.UUID

class PhotosPickerItem(
    val itemIdentifier: String,
    val supportedContentTypes: List<String> = emptyList(),
    val uri: Uri? = null,
) {
    override fun equals(other: Any?): Boolean = other is PhotosPickerItem && itemIdentifier == other.itemIdentifier
    override fun hashCode(): Int = itemIdentifier.hashCode()

    suspend fun loadImage(): ByteArray? = bytes()

    suspend fun loadMovie(): ComposerStagedFile? {
        val file = localFile() ?: return null
        return runCatching { ComposerFileStaging.stage(file) }.getOrNull()
    }

    private fun bytes(): ByteArray? {
        val file = localFile() ?: return null
        return runCatching { file.readBytes() }.getOrNull()
    }

    private fun localFile(): File? {
        val current = uri ?: return null
        if (current.scheme == "file") return current.path?.let { File(it) }
        return null
    }
}

class InlinePhotoSelection {
    data class Pick(
        var item: PhotosPickerItem,
        var id: UUID,
        var isVideo: Boolean,
    )

    class Change {
        var added: List<Pick> = emptyList()
        var removed: List<UUID> = emptyList()
    }

    var items: List<PhotosPickerItem> = emptyList()

    private val ids = mutableMapOf<PhotosPickerItem, UUID>()
    private var reconciled: List<PhotosPickerItem> = emptyList()
    private val landed = mutableSetOf<UUID>()
    private val unlandedRemovals = mutableSetOf<UUID>()

    val selectedImages: List<Pick> get() = items.mapNotNull { pick(it) }.filter { !it.isVideo }

    fun reconcile(): Change {
        val change = Change()
        val current = items.toSet()
        val removed = mutableListOf<UUID>()
        for (item in reconciled) {
            if (current.contains(item)) continue
            val id = ids.remove(item) ?: continue
            if (!landed.remove(id)) unlandedRemovals.add(id)
            removed += id
        }
        val previous = reconciled.toSet()
        val added = mutableListOf<Pick>()
        for (item in items) {
            if (previous.contains(item)) continue
            val id = UUID.randomUUID()
            ids[item] = id
            added += Pick(item, id, isVideo(item))
        }
        reconciled = items
        change.added = added
        change.removed = removed
        return change
    }

    fun attachmentsChanged(present: Set<UUID>): List<UUID> {
        val selected = ids.values.toSet()
        landed.addAll(selected.intersect(present))
        val gone = ids.filter { landed.contains(it.value) && !present.contains(it.value) }.keys.toSet()
        if (gone.isNotEmpty()) items = items.filter { it !in gone }
        val orphans = unlandedRemovals.intersect(present)
        unlandedRemovals.removeAll(orphans)
        return orphans.toList()
    }

    fun drop(failed: Set<UUID>) {
        if (failed.isEmpty()) return
        items = items.filter { ids[it]?.let { id -> failed.contains(id) } != true }
    }

    private fun pick(item: PhotosPickerItem): Pick? {
        val id = ids[item] ?: return null
        return Pick(item, id, isVideo(item))
    }

    companion object {
        fun isVideo(item: PhotosPickerItem): Boolean =
            item.supportedContentTypes.any { it == "public.movie" || it.startsWith("video/") }
    }
}

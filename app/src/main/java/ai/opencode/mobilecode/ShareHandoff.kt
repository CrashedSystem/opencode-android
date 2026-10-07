@file:PortedFrom("OpenCode/ShareHandoff.swift", "553a407")

package ai.opencode.mobilecode

import ai.opencode.mobilecode.tca.Identifiable
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlin.math.max
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

// Compiled into both the app and the share extension. The extension never
// talks to a server: it re-encodes what was shared, files it in the App Group,
// and the app moves it into the chosen session's composer on next activation.

object AppGroup {
    const val identifier = "group.ai.opencode.mobilecode"

    var containerURL: File? = null
}

/// The same downscale and re-encode the composer applies to imported images.
/// Rendering a fresh JPEG from decoded pixels drops EXIF, GPS, and other
/// metadata, and keeps prompts small.
object SharedImageEncoder {
    const val maxSourceBytes = 20_000_000
    const val maxPixelSize = 1568
    const val compressionQuality = 80
    const val mime = "image/jpeg"

    fun jpeg(from: ByteArray): ByteArray? {
        if (from.size > maxSourceBytes) return null
        return jpeg(decode(from) ?: return null)
    }

    /// Decodes straight from the file so a large original is never held in
    /// memory as a whole, which matters under the extension's memory limit.
    fun jpeg(contentsOf: File): ByteArray? {
        if (contentsOf.length() > maxSourceBytes) return null
        val bytes = runCatching { contentsOf.readBytes() }.getOrNull() ?: return null
        return jpeg(from = bytes)
    }

    fun thumbnail(from: ByteArray, maxPixelSize: Int): Bitmap? = decode(from, maxPixelSize)

    private fun jpeg(bitmap: Bitmap): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, compressionQuality, out)
        if (!bitmap.isRecycled) bitmap.recycle()
        return out.toByteArray()
    }

    private fun decode(data: ByteArray, maxPixelSize: Int = SharedImageEncoder.maxPixelSize): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val sample = sampleSize(max(bounds.outWidth, bounds.outHeight), maxPixelSize)
        val decoded = BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val oriented = applyOrientation(decoded, data)
        return scale(oriented, maxPixelSize)
    }

    private fun sampleSize(longest: Int, maxPixelSize: Int): Int {
        var sample = 1
        while (longest / sample > maxPixelSize * 2) sample *= 2
        return sample
    }

    private fun scale(bitmap: Bitmap, maxPixelSize: Int): Bitmap {
        val longest = max(bitmap.width, bitmap.height)
        if (longest <= maxPixelSize) return bitmap
        val scale = maxPixelSize.toFloat() / longest
        val scaled = Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true,
        )
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    private fun applyOrientation(bitmap: Bitmap, data: ByteArray): Bitmap {
        val orientation = runCatching {
            ExifInterface(ByteArrayInputStream(data)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
            else -> return bitmap
        }
        val turned = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (turned !== bitmap) bitmap.recycle()
        return turned
    }
}

/// Sessions the share sheet can offer, published by the app for its current
/// server whenever it leaves the foreground. No credentials are included.
@Serializable
data class ShareTargets(
    var version: Int = currentVersion,
    var serverID: String,
    var serverName: String,
    var lastViewedSessionID: String? = null,
    /// Where New Session drafts start: the most recently used project.
    var newSessionProject: Project? = null,
    var pinned: List<Session> = emptyList(),
    var recents: List<Session> = emptyList(),
) {
    @Serializable
    data class Session(
        override val id: String,
        var title: String,
        var projectID: String,
        var projectName: String,
    ) : Identifiable<String>

    @Serializable
    data class Project(
        var id: String,
        var name: String,
    )

    fun write(to: File = fileURL) {
        val data = runCatching { shareJson.encodeToString(ShareTargets.serializer(), this) }.getOrNull() ?: return
        to.parentFile?.mkdirs()
        val temporary = File(to.parentFile, to.name + ".tmp")
        temporary.writeBytes(data.toByteArray())
        if (!temporary.renameTo(to)) {
            to.writeBytes(data.toByteArray())
            temporary.delete()
        }
    }

    companion object {
        const val currentVersion = 1
        const val maximumRecents = 20

        val fileURL: File
            get() = File(AppGroup.containerURL ?: File(System.getProperty("java.io.tmpdir") ?: "."), "share-targets.json")

        fun load(from: File = fileURL): ShareTargets? {
            val data = runCatching { from.readText() }.getOrNull() ?: return null
            val targets = runCatching { shareJson.decodeFromString(ShareTargets.serializer(), data) }.getOrNull() ?: return null
            if (targets.version != currentVersion) return null
            return targets
        }

        fun remove(at: File = fileURL) {
            at.delete()
        }
    }
}

/// One share-sheet submission waiting for the app.
@Serializable
data class ShareHandoff(
    var version: Int = currentVersion,
    @Serializable(with = UUIDAsStringSerializer::class) var id: UUID,
    @Serializable(with = InstantIsoSerializer::class) var createdAt: Instant,
    var serverID: String,
    @Serializable(with = ShareTargetSerializer::class) var target: Target,
    /// The optional message followed by any shared text and links.
    var text: String,
    var images: List<Image> = emptyList(),
    /// Optional so shares written before files existed still decode.
    var files: List<File>? = null,
) {
    @Serializable
    sealed interface Target {
        /// The project travels along so a deleted session can fall back to a
        /// New Session draft in the same place.
        @Serializable
        data class session(val id: String, val projectID: String) : Target

        @Serializable
        data class newSession(val projectID: String? = null) : Target
    }

    @Serializable
    data class Image(
        @Serializable(with = UUIDAsStringSerializer::class) var id: UUID,
        var fileName: String,
        var mime: String,
    )

    /// A non-image file (PDF, document, video, audio) the app uploads.
    @Serializable
    data class File(
        @Serializable(with = UUIDAsStringSerializer::class) var id: UUID,
        /// The stored name inside the share directory.
        var fileName: String,
        /// The original name, shown on the chip and kept on the server.
        var name: String,
        var mime: String,
    )

    companion object {
        const val currentVersion = 1
        const val maximumImages = 10
        const val maximumFiles = 10
    }
}

/// A file copied into the app's or share extension's temporary storage.
data class ComposerStagedFile(
    override var id: UUID,
    var name: String,
    var mime: String,
    var size: Long,
    var url: java.io.File,
) : Identifiable<UUID>

/// Where picked and shared files wait while they upload. Upload streams read
/// from these copies, so the original's security scope and lifetime don't
/// matter once staging finishes.
object ComposerFileStaging {
    var directory: java.io.File = java.io.File(System.getProperty("java.io.tmpdir"), "ComposerFiles")

    /// Copies `source` (opening its security scope when it has one) into a
    /// fresh per-file directory that keeps the original name.
    fun stage(source: java.io.File, name: String? = null, id: UUID = UUID.randomUUID()): ComposerStagedFile {
        val fileName = AttachmentFileType.sanitizedName(name ?: source.name)
        val folder = java.io.File(directory, id.toString())
        folder.mkdirs()
        val destination = java.io.File(folder, fileName)
        try {
            source.copyTo(destination, overwrite = true)
        } catch (error: Exception) {
            folder.deleteRecursively()
            throw error
        }
        return ComposerStagedFile(
            id = id,
            name = fileName,
            mime = AttachmentFileType.mime(destination, fileName),
            size = destination.length(),
            url = destination,
        )
    }

    fun discard(url: java.io.File) {
        val folder = url.parentFile ?: return
        if (folder.parentFile?.canonicalPath != directory.canonicalPath) return
        folder.deleteRecursively()
    }
}

data class ReceivedShare(
    var handoff: ShareHandoff,
    var images: List<Image>,
    var files: List<ComposerStagedFile> = emptyList(),
) {
    data class Image(
        var id: UUID,
        var data: ByteArray,
        var mime: String,
    ) {
        override fun equals(other: Any?): Boolean {
            val image = other as? Image ?: return false
            return id == image.id && data.contentEquals(image.data) && mime == image.mime
        }

        override fun hashCode(): Int = id.hashCode()
    }
}

/// File-based queue in the App Group. Each share is a directory that appears
/// atomically (staged, then renamed into place), and consuming one first
/// renames it out of the queue, so a share is delivered at most once even if
/// two activations race.
class ShareHandoffStore(val root: java.io.File) {
    fun save(handoff: ShareHandoff, images: List<ByteArray>, files: List<java.io.File> = emptyList()) {
        check(handoff.images.size == images.size)
        check((handoff.files ?: emptyList()).size == files.size)
        root.mkdirs()
        val staging = java.io.File(root, stagingPrefix + handoff.id.toString())
        staging.deleteRecursively()
        staging.mkdirs()
        try {
            for ((image, data) in handoff.images.zip(images)) {
                java.io.File(staging, image.fileName).writeBytes(data)
            }
            for ((file, source) in (handoff.files ?: emptyList()).zip(files)) {
                source.copyTo(java.io.File(staging, file.fileName), overwrite = true)
            }
            java.io.File(staging, manifestName).writeText(shareJson.encodeToString(ShareHandoff.serializer(), handoff))
            val published = java.io.File(root, handoff.id.toString())
            if (!staging.renameTo(published)) error("Couldn’t publish share ${handoff.id}")
        } catch (error: Exception) {
            staging.deleteRecursively()
            throw error
        }
    }

    /// Every deliverable share, oldest first. Expired, unreadable, and
    /// abandoned entries are deleted rather than returned. Shared files move
    /// into `filesDirectory/<id>/<name>`; without one they are dropped.
    fun consumeAll(now: Instant, filesDirectory: java.io.File? = null): List<ReceivedShare> {
        val entries = root.listFiles() ?: return emptyList()
        val shares = mutableListOf<ReceivedShare>()
        for (entry in entries) {
            val name = entry.name
            if (name.startsWith(stagingPrefix) || name.startsWith(claimedPrefix)) {
                // A staging directory the extension is still writing is moments old.
                val modified = if (entry.exists()) Instant.ofEpochMilli(entry.lastModified()) else null
                if (modified == null || now.epochSecond - modified.epochSecond > 60 * 60) entry.deleteRecursively()
                continue
            }
            if (runCatching { UUID.fromString(name) }.isFailure) continue
            val claimed = java.io.File(root, claimedPrefix + name)
            if (!entry.renameTo(claimed)) continue
            try {
                val share = read(claimed, filesDirectory)
                if (share != null && share.handoff.id.toString() == name && now.epochSecond - share.handoff.createdAt.epochSecond <= maximumAge) {
                    shares += share
                }
            } finally {
                claimed.deleteRecursively()
            }
        }
        return shares.sortedBy { it.handoff.createdAt }
    }

    companion object {
        const val maximumAge = 24 * 60 * 60L
        private const val manifestName = "share.json"
        private const val stagingPrefix = ".staging-"
        private const val claimedPrefix = ".claimed-"

        val live: ShareHandoffStore?
            get() = AppGroup.containerURL?.let { ShareHandoffStore(java.io.File(it, "Shares")) }

        private fun read(directory: java.io.File, filesDirectory: java.io.File?): ReceivedShare? {
            val data = runCatching { java.io.File(directory, manifestName).readText() }.getOrNull() ?: return null
            val handoff = runCatching { shareJson.decodeFromString(ShareHandoff.serializer(), data) }.getOrNull() ?: return null
            if (handoff.version != ShareHandoff.currentVersion) return null
            if (handoff.images.size > ShareHandoff.maximumImages) return null
            if ((handoff.files ?: emptyList()).size > ShareHandoff.maximumFiles) return null
            val images = mutableListOf<ReceivedShare.Image>()
            for (image in handoff.images) {
                // Only plain names inside this share's own directory.
                if (!isPlainName(image.fileName) || !image.mime.startsWith("image/")) continue
                val bytes = runCatching { java.io.File(directory, image.fileName).readBytes() }.getOrNull() ?: continue
                images += ReceivedShare.Image(id = image.id, data = bytes, mime = image.mime)
            }
            val files = mutableListOf<ComposerStagedFile>()
            if (filesDirectory != null) {
                for (file in handoff.files ?: emptyList()) {
                    if (!isPlainName(file.fileName)) continue
                    val name = if (isPlainName(file.name)) file.name else file.fileName
                    val source = java.io.File(directory, file.fileName)
                    val folder = java.io.File(filesDirectory, file.id.toString())
                    val destination = java.io.File(folder, name)
                    try {
                        folder.mkdirs()
                        if (!source.renameTo(destination)) source.copyTo(destination, overwrite = true)
                    } catch (_: Exception) {
                        folder.deleteRecursively()
                        continue
                    }
                    files += ComposerStagedFile(
                        id = file.id,
                        name = name,
                        mime = file.mime,
                        size = destination.length(),
                        url = destination,
                    )
                }
            }
            return ReceivedShare(handoff = handoff, images = images, files = files)
        }

        private fun isPlainName(name: String): Boolean =
            name.isNotEmpty() && !name.contains("/") && !name.startsWith(".")
    }
}

internal val shareJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

internal object ShareTargetSerializer : KSerializer<ShareHandoff.Target> {
    override val descriptor = buildClassSerialDescriptor("ShareHandoff.Target")

    override fun serialize(encoder: Encoder, value: ShareHandoff.Target) {
        val element = when (value) {
            is ShareHandoff.Target.session -> buildJsonObject {
                putJsonObject("session") {
                    put("id", value.id)
                    put("projectID", value.projectID)
                }
            }
            is ShareHandoff.Target.newSession -> buildJsonObject {
                putJsonObject("newSession") {
                    if (value.projectID == null) put("projectID", JsonNull) else put("projectID", value.projectID)
                }
            }
        }
        (encoder as JsonEncoder).encodeJsonElement(element)
    }

    override fun deserialize(decoder: Decoder): ShareHandoff.Target {
        val element = (decoder as JsonDecoder).decodeJsonElement().jsonObject
        element["session"]?.jsonObject?.let { session ->
            return ShareHandoff.Target.session(
                id = session.getValue("id").jsonPrimitive.content,
                projectID = session.getValue("projectID").jsonPrimitive.content,
            )
        }
        val created = element.getValue("newSession").jsonObject
        val project = created["projectID"]
        val projectID = if (project == null || project is JsonNull) null else project.jsonPrimitive.content
        return ShareHandoff.Target.newSession(projectID)
    }
}

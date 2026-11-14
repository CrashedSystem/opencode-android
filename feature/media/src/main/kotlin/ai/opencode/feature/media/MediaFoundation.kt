package ai.opencode.feature.media

import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.strings.localized
import ai.opencode.core.tuning.Tunable
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeImageAttachment
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.security.MessageDigest

/**
 * The transcript's complete spacing vocabulary (iOS `TranscriptMetrics` in
 * `Transcript/TranscriptEntryView.swift`, at the `TranscriptTunables` defaults it reads: the
 * Tuning Panel isn't ported). Rows own their outer rhythm; renderers only use values from this
 * scale so padding never compounds. Lives here because media renderers use it and
 * `:feature:transcript` sits above media; the transcript uses this one.
 */
object TranscriptMetrics {
    val compact: Dp = 4.dp
    val clustered: Dp = 8.dp
    /** `transcript.bubble.verticalPadding` default. */
    val bubbleVertical: Dp = 8.dp
    val standard: Dp = 12.dp
    /** `transcript.row.contentGutter` default. */
    val contentGutter: Dp = 16.dp
    val turn: Dp = 20.dp

    /** `transcript.bubble.cornerRadius` default. */
    val bubbleCornerRadius: Dp = 10.dp
    /** Content surfaces: code, tables, cards, and images (`transcript.card.cornerRadius` default). */
    val cardCornerRadius: Dp = 8.dp
    /** Controls inside sheets and cards: choices and fields. */
    val controlCornerRadius: Dp = 6.dp

    /** `transcript.bubble.maxFraction` default. */
    const val maximumUserBubbleFraction: Float = 0.82f

    /** The widest the transcript and composer grow, gutters included (tablets centre at it). */
    val readableColumnWidth: Dp = 720.dp

    /** Within a reply, paragraphs, Used N, notices and Working are 16 apart (`transcript.row.flowHalf`). */
    val flowHalf: Dp = 8.dp
    /** A user message is 20 from the reply around it (`transcript.row.turnHalf`). */
    val turnHalf: Dp = 12.dp
    /** A compaction is a context boundary: 24 from its neighbours (`transcript.row.sectionHalf`). */
    val sectionHalf: Dp = 16.dp

    /** iOS `MinimumHitRect.side` (44 pt) → Android's 48 dp minimum target. */
    val minimumHitSide: Dp = 48.dp
}

/**
 * What the full-screen photo viewer pages through (iOS `TranscriptImagePreview`, now in
 * `Media/TranscriptImageViewer.swift`). Lives in media because the viewer, the image gallery
 * and Mermaid cards build it; the transcript sits above media. A `media:<hash>` page may
 * arrive without its image: the viewer loads it from [server].
 */
data class TranscriptImagePreview(val id: String, val pages: List<Page>, val server: OpenCodeServer? = null) {
    /** [label] is the accessibility label / title; [image] the decoded original, or `null` while it loads. */
    data class Page(
        val id: String,
        val image: Bitmap?,
        val label: String,
        /** A line under the title, such as dimensions and size. */
        val detail: String? = null,
        val mediaHash: String? = null,
        val name: String? = null,
        val isUnavailable: Boolean = false,
        val loadFailed: Boolean = false,
        /** The app wrote [label] itself ("Image 1 of 3"), so it isn't a name worth showing as a title. */
        val labelIsPlaceholder: Boolean = false,
    ) {
        // iOS `Page: Equatable`: every field, and the image by identity or by its pixels.
        override fun equals(other: Any?): Boolean =
            other is Page &&
                other.id == id &&
                other.label == label &&
                other.detail == detail &&
                other.mediaHash == mediaHash &&
                other.name == name &&
                other.isUnavailable == isUnavailable &&
                other.loadFailed == loadFailed &&
                other.labelIsPlaceholder == labelIsPlaceholder &&
                (other.image === image || (other.image != null && image != null && other.image.sameAs(image)))

        override fun hashCode(): Int = id.hashCode() * 31 + label.hashCode()
    }

    constructor(id: String, image: Bitmap, label: String, server: OpenCodeServer? = null, labelIsPlaceholder: Boolean = false) :
        this(id, listOf(Page(id, image, label, labelIsPlaceholder = labelIsPlaceholder)), server)

    companion object {
        /**
         * iOS `init?(attachments:selectedID:label:server:)`: every decodable attachment, and
         * every `media:<hash>` or unavailable one (shown loading or unavailable), each named or
         * labelled "label i of n"; `null` when [selectedID] isn't among them.
         */
        fun fromAttachments(
            attachments: List<OpenCodeImageAttachment>,
            selectedID: String,
            label: String,
            server: OpenCodeServer? = null,
        ): TranscriptImagePreview? {
            val items = attachments.mapNotNull { attachment ->
                val image = CachedImage.decoded(attachment.id, attachment.data, attachment.mediaHash)
                when {
                    image != null -> attachment to image
                    attachment.mediaHash != null || attachment.isUnavailable -> attachment to null
                    else -> null
                }
            }
            if (items.none { it.first.id == selectedID }) return null
            return TranscriptImagePreview(
                selectedID,
                items.mapIndexed { index, (attachment, image) ->
                    Page(
                        id = attachment.id,
                        image = image,
                        label = attachment.name ?: localized(R.string.media_image_page_label, label, index + 1, items.size),
                        mediaHash = attachment.mediaHash,
                        name = attachment.name,
                        isUnavailable = attachment.isUnavailable || MediaReferenceResolver.isUnavailable(attachment, server),
                        labelIsPlaceholder = attachment.name == null,
                    )
                },
                server,
            )
        }

        /** Already-decoded images by ID, each labelled "label i of n" (the agent image gallery and lab). */
        fun fromImages(
            images: List<Pair<String, Bitmap?>>,
            selectedID: String,
            label: String,
        ): TranscriptImagePreview? {
            val decoded = images.mapNotNull { (id, image) -> image?.let { id to it } }
            if (decoded.none { it.first == selectedID }) return null
            return TranscriptImagePreview(
                selectedID,
                decoded.mapIndexed { index, (id, image) -> Page(id, image, localized(R.string.media_image_page_label, label, index + 1, decoded.size), labelIsPlaceholder = true) },
            )
        }
    }
}

/**
 * A hard retained-pixel bound (iOS `Core/DecodedImageCache.swift`). Originals retain their
 * resolution for zoom/export; an image larger than the budget is returned to its visible owner
 * but never retained by this cache. At most 64 entries, LRU; a memory-pressure callback
 * (`onTrimMemory` ≥ RUNNING_LOW / `onLowMemory`, Android's memory warning) purges it.
 *
 * Coil's own memory cache keeps Coil-loaded images (link artwork, site icons); this cache holds
 * the Markdown and attachment originals the app decodes itself, as iOS does.
 */
class DecodedImageCache(byteLimit: Int = DEFAULT_BYTE_LIMIT) {
    val byteLimit: Int = maxOf(0, byteLimit)

    private class Entry(val image: Bitmap, val bytes: Int)

    private val lock = Any()
    private val entries = HashMap<String, Entry>()
    private val order = ArrayList<String>()
    private var bytes = 0

    val retainedBytes: Int get() = synchronized(lock) { bytes }

    /** Purges entries and cost accounting (memory warning). */
    fun removeAll() = synchronized(lock) {
        entries.clear()
        order.clear()
        bytes = 0
    }

    fun image(source: String): Bitmap? {
        val key = key(source)
        return synchronized(lock) {
            val entry = entries[key] ?: return null
            order.remove(key)
            order.add(key)
            entry.image
        }
    }

    fun insert(image: Bitmap, source: String) {
        val key = key(source)
        val cost = pixelBytes(image)
        synchronized(lock) {
            entries.remove(key)?.let { bytes -= it.bytes }
            order.remove(key)
            if (cost <= 0 || cost > byteLimit) return
            while (order.isNotEmpty() && (bytes > byteLimit - cost || order.size >= 64)) {
                val oldest = order.removeAt(0)
                entries.remove(oldest)?.let { bytes -= it.bytes }
            }
            entries[key] = Entry(image, cost)
            order.add(key)
            bytes += cost
        }
    }

    companion object Key : DependencyKey<DecodedImageCache> {
        const val DEFAULT_BYTE_LIMIT = 48 * 1024 * 1024

        /** Markdown and attachment caches share one decoded-pixel budget (`\.transcriptImageCache`). */
        override val liveValue: DecodedImageCache by lazy { DecodedImageCache().also(::observeMemoryPressure) }
        override val testValue: DecodedImageCache get() = DecodedImageCache()

        /** `bytesPerRow × height`, saturating. */
        fun pixelBytes(image: Bitmap): Int {
            val cost = image.rowBytes.toLong() * image.height
            return if (cost > Int.MAX_VALUE) Int.MAX_VALUE else cost.toInt()
        }

        /** Data URLs must not become multi-megabyte map keys. */
        private fun key(source: String): String =
            MessageDigest.getInstance("SHA-256").digest(source.toByteArray()).joinToString("") { "%02x".format(it) }

        private fun observeMemoryPressure(cache: DecodedImageCache) {
            val context = runCatching { dependency(AppContext) }.getOrNull() ?: return
            context.registerComponentCallbacks(object : ComponentCallbacks2 {
                @Suppress("DEPRECATION")
                override fun onTrimMemory(level: Int) {
                    if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) cache.removeAll()
                }
                override fun onConfigurationChanged(newConfig: Configuration) = Unit
                @Deprecated("Deprecated in Java")
                override fun onLowMemory() = cache.removeAll()
            })
        }
    }
}

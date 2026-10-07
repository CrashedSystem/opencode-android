@file:PortedFrom("OpenCode/ShareHandoff.swift", "553a407", partial = "image encoder and composer file staging")

package ai.opencode.mobilecode

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlin.math.max

object SharedImageEncoder {
    const val maxSourceBytes = 20_000_000
    const val maxPixelSize = 1568
    const val compressionQuality = 80
    const val mime = "image/jpeg"

    fun jpeg(from: ByteArray): ByteArray? {
        if (from.size > maxSourceBytes) return null
        return jpeg(decode(from) ?: return null)
    }

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
        val scaled = Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true)
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

data class ComposerStagedFile(
    var id: UUID,
    var name: String,
    var mime: String,
    var size: Long,
    var url: File,
)

object ComposerFileStaging {
    var directory: File = File(System.getProperty("java.io.tmpdir"), "ComposerFiles")

    fun stage(source: File, name: String? = null, id: UUID = UUID.randomUUID()): ComposerStagedFile {
        val fileName = AttachmentFileType.sanitizedName(name ?: source.name)
        val folder = File(directory, id.toString())
        folder.mkdirs()
        val destination = File(folder, fileName)
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

    fun discard(url: File) {
        val folder = url.parentFile ?: return
        if (folder.parentFile?.canonicalPath != directory.canonicalPath) return
        folder.deleteRecursively()
    }
}

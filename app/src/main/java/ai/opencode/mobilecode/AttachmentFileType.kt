@file:PortedFrom("OpenCode/AttachmentFileType.swift", "553a407")

package ai.opencode.mobilecode

import java.io.File

object AttachmentFileType {
    val imageMimes: Set<String> = setOf("image/png", "image/jpeg", "image/gif", "image/webp")
    const val pdfMime = "application/pdf"

    const val maxInlineBytes: Long = 20L * 1024 * 1024

    private val imageExtensions = mapOf(
        "gif" to "image/gif",
        "jpeg" to "image/jpeg",
        "jpg" to "image/jpeg",
        "png" to "image/png",
        "webp" to "image/webp",
    )
    private val textMimes = setOf(
        "application/json",
        "application/ld+json",
        "application/toml",
        "application/x-toml",
        "application/x-yaml",
        "application/xml",
        "application/yaml",
    )
    private val declaredMimes = mapOf(
        "pdf" to pdfMime,
        "png" to "image/png",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "gif" to "image/gif",
        "webp" to "image/webp",
        "heic" to "image/heic",
        "heif" to "image/heif",
        "svg" to "image/svg+xml",
        "bmp" to "image/bmp",
        "tif" to "image/tiff",
        "tiff" to "image/tiff",
        "ico" to "image/x-icon",
        "mov" to "video/quicktime",
        "mp4" to "video/mp4",
        "m4v" to "video/x-m4v",
        "webm" to "video/webm",
        "mkv" to "video/x-matroska",
        "avi" to "video/x-msvideo",
        "mp3" to "audio/mpeg",
        "m4a" to "audio/mp4",
        "wav" to "audio/wav",
        "aac" to "audio/aac",
        "flac" to "audio/flac",
        "ogg" to "audio/ogg",
        "opus" to "audio/opus",
        "zip" to "application/zip",
        "json" to "application/json",
        "xml" to "application/xml",
        "html" to "text/html",
        "htm" to "text/html",
        "css" to "text/css",
        "js" to "text/javascript",
        "txt" to "text/plain",
        "csv" to "text/csv",
        "md" to "text/markdown",
    )
    private val archiveExtensions = setOf("zip", "tar", "gz", "tgz", "7z", "rar")
    private val spreadsheetExtensions = setOf("xls", "xlsx", "numbers", "ods")
    private val presentationExtensions = setOf("ppt", "pptx", "key", "odp")
    private val languageNames = mapOf(
        "c" to "C",
        "cjs" to "JavaScript",
        "cpp" to "C++",
        "cs" to "C#",
        "css" to "CSS",
        "dart" to "Dart",
        "go" to "Go",
        "html" to "HTML",
        "java" to "Java",
        "js" to "JavaScript",
        "json" to "JSON",
        "jsx" to "JSX",
        "kt" to "Kotlin",
        "lua" to "Lua",
        "md" to "Markdown",
        "mjs" to "JavaScript",
        "php" to "PHP",
        "py" to "Python",
        "rb" to "Ruby",
        "rs" to "Rust",
        "sh" to "Shell",
        "sql" to "SQL",
        "swift" to "Swift",
        "toml" to "TOML",
        "ts" to "TypeScript",
        "tsx" to "TSX",
        "xml" to "XML",
        "yaml" to "YAML",
        "yml" to "YAML",
        "zsh" to "Zsh",
    )

    fun declaredMime(fileName: String): String? {
        val suffix = fileExtension(fileName)
        if (suffix.isEmpty()) return null
        return declaredMimes[suffix]
    }

    fun mime(fileName: String, declared: String?, head: ByteArray): String {
        val type = declared.orEmpty().split(";", limit = 2).first().trim().lowercase()
        if (imageMimes.contains(type) || type == pdfMime) return type
        val suffix = fileExtension(fileName)
        val fallback = imageExtensions[suffix] ?: if (suffix == "pdf") pdfMime else null
        if ((type.isEmpty() || type == "application/octet-stream") && fallback != null) return fallback
        if (type.startsWith("text/") || textMimes.contains(type) || type.endsWith("+json") || type.endsWith("+xml")) {
            return "text/plain"
        }
        val binary = if (type.isEmpty()) "application/octet-stream" else type
        val bytes = head.copyOfRange(0, minOf(4096, head.size))
        if (bytes.any { it == 0.toByte() }) return binary
        val control = bytes.count { byte ->
            val value = byte.toInt() and 0xFF
            value < 9 || (value > 13 && value < 32)
        }
        if (bytes.isNotEmpty() && control.toDouble() / bytes.size > 0.3) return binary
        return "text/plain"
    }

    fun mime(of: File, fileName: String? = null): String {
        val name = fileName ?: of.name
        val declared = declaredMime(name)
        val head = runCatching { of.inputStream().use { it.readNBytes(4096) } }.getOrDefault(ByteArray(0))
        return mime(name, declared, head)
    }

    fun label(name: String, mime: String): String {
        if (mime == pdfMime) return "PDF"
        val suffix = fileExtension(name)
        if (suffix.isEmpty()) return "File"
        return languageNames[suffix] ?: suffix.uppercase()
    }

    fun subtitle(name: String, mime: String, size: Long): String = "${label(name, mime)} · ${byteCount(size)}"

    fun symbolName(name: String, mime: String): String {
        if (mime == pdfMime) return "doc.richtext"
        if (mime.startsWith("image/")) return "photo"
        if (mime.startsWith("video/")) return "film"
        if (mime.startsWith("audio/")) return "waveform"
        if (mime == "text/plain") {
            return if (languageNames[fileExtension(name)] == null) "doc.text" else "chevron.left.forwardslash.chevron.right"
        }
        val suffix = fileExtension(name)
        if (archiveExtensions.contains(suffix)) return "doc.zipper"
        if (spreadsheetExtensions.contains(suffix)) return "tablecells"
        if (presentationExtensions.contains(suffix)) return "rectangle.on.rectangle"
        return "doc"
    }

    fun sanitizedName(name: String): String {
        val cleaned = name
            .replace("/", "-")
            .replace("\\", "-")
            .trim { it.isWhitespace() || it.code < 32 }
        val trimmed = cleaned.dropWhile { it == '.' }
        return trimmed.ifEmpty { "file" }
    }

    fun fileExtension(name: String): String {
        val base = name.split("/").lastOrNull() ?: name
        val dot = base.lastIndexOf('.')
        if (dot <= 0) return ""
        return base.substring(dot + 1).lowercase()
    }

    fun byteCount(size: Long): String {
        val units = listOf("bytes" to 1.0, "KB" to 1000.0, "MB" to 1_000_000.0, "GB" to 1_000_000_000.0)
        val abs = kotlin.math.abs(size.toDouble())
        val unit = units.lastOrNull { abs >= it.second } ?: units.first()
        if (unit.first == "bytes") return "$size bytes"
        val value = size / unit.second
        val text = if (value >= 10 || value == kotlin.math.floor(value)) {
            value.toLong().toString()
        } else {
            String.format("%.1f", value)
        }
        return "$text ${unit.first}"
    }

    fun fileSize(size: Long): String {
        if (size < 1000) return "$size bytes"
        val units = listOf("KB", "MB", "GB", "TB")
        var value = size.toDouble()
        var unit = "bytes"
        for (next in units) {
            value /= 1000.0
            unit = next
            if (value < 1000) break
        }
        val digits = if (value < 10) 1 else 0
        return "%.${digits}f %s".format(value, unit)
    }
}

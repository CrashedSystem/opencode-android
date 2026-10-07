@file:PortedFrom("OpenCode/AttachmentFileType.swift", "553a407", partial = "name helpers for the client")

package ai.opencode.mobilecode

object AttachmentFileType {
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

    fun label(name: String, mime: String): String {
        if (mime == "application/pdf") return "PDF"
        val suffix = fileExtension(name)
        if (suffix.isEmpty()) return "File"
        return suffix.uppercase()
    }

    fun symbolName(name: String, mime: String): String {
        if (mime == "application/pdf") return "doc.richtext"
        if (mime.startsWith("image/")) return "photo"
        if (mime.startsWith("video/")) return "film"
        if (mime.startsWith("audio/")) return "waveform"
        return "doc"
    }

    fun subtitle(name: String, mime: String, size: Long): String = "${label(name, mime)} · ${fileSize(size)}"

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

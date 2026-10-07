@file:PortedFrom("OpenCode/AgentFiles/AgentFileReference.swift", "553a407")

package ai.opencode.mobilecode

import ai.opencode.mobilecode.system.URL
import java.net.URI
import kotlinx.serialization.Serializable

@Serializable
data class AgentFileReference(
    var path: String,
    var line: Int? = null,
) {
    val id: String get() = path

    val name: String get() = path.split("/").lastOrNull() ?: path

    val kind: Kind get() = Kind(name)

    val mime: String get() = AttachmentFileType.declaredMime(name) ?: "application/octet-stream"

    val typeLabel: String
        get() = when (kind) {
            Kind.video -> "Video · " + AttachmentFileType.fileExtension(name).uppercase()
            Kind.audio -> "Audio · " + AttachmentFileType.fileExtension(name).uppercase()
            else -> AttachmentFileType.label(name, mime)
        }

    val symbolName: String
        get() = when (kind) {
            Kind.video -> "film"
            Kind.audio -> "waveform"
            Kind.image -> "photo"
            Kind.pdf -> "doc.richtext"
            Kind.text -> AttachmentFileType.symbolName(name, "text/plain")
            Kind.document -> AttachmentFileType.symbolName(name, mime)
        }

    val hasViewableName: Boolean
        get() = !path.endsWith("/") &&
            (knownFileNames.contains(name.lowercase()) || viewableExtensions.contains(AttachmentFileType.fileExtension(name)))

    val linkURL: URL
        get() {
            val items = buildList {
                add("path=${encodeQuery(path)}")
                line?.let { add("line=$it") }
            }
            return URL("$linkScheme://open?${items.joinToString("&")}")
        }

    fun target(`in`: String?): Target? {
        var resolved = path
        if (resolved.startsWith("~/") || resolved == "~") {
            val home = home(from = `in`) ?: return null
            resolved = home + resolved.drop(1)
        }
        if (!resolved.startsWith("/")) {
            if (`in` == null || !`in`.startsWith("/")) return null
            resolved = `in` + "/" + resolved
        }
        val absolute = normalized(resolved) ?: return null
        if (absolute == "/") return null
        val directory = `in`?.let { normalized(it) }
        if (directory != null && absolute.startsWith(if (directory == "/") "/" else "$directory/")) {
            val relative = absolute.drop(if (directory == "/") 1 else directory.length + 1)
            return Target(location = directory, relativePath = relative, absolutePath = absolute)
        }
        val components = absolute.split("/").filter { it.isNotEmpty() }
        val last = components.lastOrNull() ?: return null
        val parent = "/" + components.dropLast(1).joinToString("/")
        return Target(location = parent, relativePath = last, absolutePath = absolute)
    }

    @Serializable
    enum class Kind {
        video,
        audio,
        image,
        pdf,
        text,
        document,
        ;

        val showsCard: Boolean get() = this != text
        val isTimeBased: Boolean get() = this == video || this == audio

        companion object {
            operator fun invoke(fileName: String): Kind {
                val suffix = AttachmentFileType.fileExtension(fileName)
                if (textExtensions.contains(suffix) || knownFileNames.contains(fileName.lowercase())) return text
                if (suffix.isEmpty()) return document
                if (suffix == "pdf") return pdf
                if (videoExtensions.contains(suffix)) return video
                if (audioExtensions.contains(suffix)) return audio
                if (imageExtensions.contains(suffix)) return image
                return document
            }
        }
    }

    @Serializable
    data class Target(
        var location: String,
        var relativePath: String,
        var absolutePath: String,
    )

    companion object {
        const val linkScheme = "opencode-file"

        val textExtensions: Set<String> = setOf(
            "astro", "bash", "c", "cc", "cfg", "cjs", "clj", "conf", "cpp", "cs", "css", "csv", "cts", "dart", "diff",
            "dockerfile", "env", "erl", "ex", "exs", "fish", "go", "gql", "gradle", "graphql", "h", "hpp", "hs", "htm",
            "html", "ini", "java", "jl", "js", "json", "jsonc", "jsonl", "jsx", "kt", "kts", "less", "lock", "log", "lua",
            "m", "markdown", "md", "mdx", "mjs", "ml", "mm", "mts", "nix", "patch", "php", "pl", "plist", "properties",
            "proto", "ps1", "py", "r", "rb", "rs", "sass", "scala", "scss", "sh", "sql", "svelte", "swift", "tf", "toml",
            "ts", "tsv", "tsx", "txt", "vue", "xml", "yaml", "yml", "zig", "zsh",
        )
        val mediaExtensions: Set<String> = setOf(
            "aac", "aif", "aiff", "avi", "caf", "flac", "m4a", "m4v", "mkv", "mov", "mp3", "mp4", "oga", "ogg", "ogv",
            "opus", "wav", "weba", "webm",
        )
        val documentExtensions: Set<String> = setOf(
            "bmp", "doc", "docx", "epub", "gif", "heic", "ico", "jpeg", "jpg", "key", "numbers", "pages", "pdf", "png",
            "ppt", "pptx", "rtf", "svg", "tif", "tiff", "webp", "xls", "xlsx", "zip",
        )
        val viewableExtensions = textExtensions + mediaExtensions + documentExtensions
        val knownFileNames: Set<String> = setOf(
            "brewfile", "changelog", "codeowners", "containerfile", "dockerfile", "gemfile", "justfile", "license",
            "makefile", "podfile", "procfile", "readme", "vagrantfile",
        )
        private val videoExtensions = setOf("avi", "m4v", "mkv", "mov", "mp4", "ogv", "webm")
        private val audioExtensions = setOf("aac", "aif", "aiff", "caf", "flac", "m4a", "mp3", "oga", "ogg", "opus", "wav", "weba")
        private val imageExtensions = setOf("bmp", "gif", "heic", "ico", "jpeg", "jpg", "png", "svg", "tif", "tiff", "webp")

        fun fromLink(destination: String): AgentFileReference? {
            var value = destination.trim().replace("\\", "/")
            if (value.isEmpty() || value.startsWith("#") || value.startsWith("?") || value.startsWith("//")) return null
            if (value.lowercase().startsWith("file:")) {
                val url = runCatching { URI(value) }.getOrNull() ?: return null
                if (url.host != null && url.host != "" && url.host != "localhost") return null
                value = url.path ?: return null
            } else if (Regex("""^[A-Za-z][A-Za-z0-9+.-]*:(?!\d+(?::\d+)?$)""").containsMatchIn(value)) {
                return null
            } else {
                val lineAnchor = Regex("""#L\d+(?:-L?\d+)?$""").find(value)?.value ?: ""
                value = value.split("#", limit = 2).first()
                value = value.split("?", limit = 2).first()
                value = (percentDecode(value) ?: value) + lineAnchor
            }
            if (value.isEmpty() || value.any { it.code < 32 }) return null
            return withLocation(value)
        }

        fun fromInlineCode(code: String): AgentFileReference? {
            val text = code.trim()
            if (text.length !in 2..512) return null
            if (Regex("""^[A-Za-z][A-Za-z0-9+.-]*://""").containsMatchIn(text)) return null
            if (Regex("""[\s()\[\]{}*+=<>|&^"';,$`]""").containsMatchIn(text)) return null
            if (text.startsWith("-") || text.startsWith("@")) return null
            val reference = withLocation(text) ?: return null
            if (!reference.hasViewableName) return null
            val fileName = reference.name
            if (!reference.path.contains("/") && !knownFileNames.contains(fileName.lowercase()) &&
                fileName.firstOrNull()?.isUpperCase() == true && codeLikeMemberAccess(fileName)
            ) {
                return null
            }
            return reference
        }

        fun withLocation(raw: String): AgentFileReference? {
            val lineMatch = Regex(""":(\d+)(?::\d+)?$""").find(raw)
            val anchorMatch = Regex("""#L(\d+)(?:-L?\d+)?$""").find(raw)
            val (path, line) = when {
                lineMatch != null -> raw.substring(0, lineMatch.range.first) to lineMatch.groupValues[1].toInt()
                anchorMatch != null -> raw.substring(0, anchorMatch.range.first) to anchorMatch.groupValues[1].toInt()
                else -> raw to null
            }
            if (path.isEmpty() || path == "/" || path == "~" || path == "." || path == "..") return null
            return AgentFileReference(path = path, line = line)
        }

        fun home(from: String?): String? {
            if (from == null) return null
            val parts = from.split("/").filter { it.isNotEmpty() }
            if (parts.size < 2 || parts[0] !in setOf("Users", "home")) return null
            return "/${parts[0]}/${parts[1]}"
        }

        fun normalized(path: String): String? {
            if (!path.startsWith("/")) return null
            val stack = mutableListOf<String>()
            for (component in path.split("/")) {
                if (component.isEmpty() || component == ".") continue
                if (component == "..") {
                    if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
                    continue
                }
                stack.add(component)
            }
            return "/" + stack.joinToString("/")
        }

        operator fun invoke(linkURL: URL): AgentFileReference? {
            if (linkURL.scheme != linkScheme) return null
            val query = linkURL.absoluteString.substringAfter("?", "")
            if (query.isEmpty()) return null
            val items = query.split("&").mapNotNull { part ->
                val pieces = part.split("=", limit = 2)
                if (pieces.size < 2) null else pieces[0] to percentDecode(pieces[1]).orEmpty()
            }.toMap()
            val path = items["path"]?.takeIf { it.isNotEmpty() } ?: return null
            return AgentFileReference(path = path, line = items["line"]?.toIntOrNull())
        }

        private fun codeLikeMemberAccess(name: String): Boolean =
            AttachmentFileType.fileExtension(name) in setOf("json", "map", "log", "lock", "env")

        private fun encodeQuery(value: String): String = buildString {
            for (byte in value.toByteArray(Charsets.UTF_8)) {
                val code = byte.toInt() and 0xFF
                val char = code.toChar()
                if (char.isLetterOrDigit() || char in "-._~") append(char) else append("%${"%02X".format(code)}")
            }
        }

        private fun percentDecode(value: String): String? {
            val bytes = mutableListOf<Byte>()
            var index = 0
            while (index < value.length) {
                val char = value[index]
                if (char == '%' && index + 2 < value.length) {
                    val hex = value.substring(index + 1, index + 3)
                    val code = hex.toIntOrNull(16) ?: return null
                    bytes.add(code.toByte())
                    index += 3
                } else {
                    bytes.add(char.code.toByte())
                    index += 1
                }
            }
            return bytes.toByteArray().toString(Charsets.UTF_8)
        }
    }
}

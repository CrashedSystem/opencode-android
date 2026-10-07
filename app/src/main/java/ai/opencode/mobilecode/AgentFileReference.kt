@file:PortedFrom("OpenCode/AgentFiles/AgentFileReference.swift", "553a407", partial = "withLocation and hasViewableName for the client")

package ai.opencode.mobilecode

data class AgentFileReference(
    var path: String,
    var line: Int? = null,
) {
    val name: String get() = path.split("/").lastOrNull() ?: path

    val hasViewableName: Boolean
        get() = !path.endsWith("/") &&
            (knownFileNames.contains(name.lowercase()) || viewableExtensions.contains(AttachmentFileType.fileExtension(name)))

    companion object {
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

        private val textExtensions = setOf(
            "astro", "bash", "c", "cc", "cfg", "cjs", "clj", "conf", "cpp", "cs", "css", "csv", "cts", "dart", "diff",
            "dockerfile", "env", "erl", "ex", "exs", "fish", "go", "gql", "gradle", "graphql", "h", "hpp", "hs", "htm",
            "html", "ini", "java", "jl", "js", "json", "jsonc", "jsonl", "jsx", "kt", "kts", "less", "lock", "log", "lua",
            "m", "markdown", "md", "mdx", "mjs", "ml", "mm", "mts", "nix", "patch", "php", "pl", "plist", "properties",
            "proto", "ps1", "py", "r", "rb", "rs", "sass", "scala", "scss", "sh", "sql", "svelte", "swift", "tf", "toml",
            "ts", "tsv", "tsx", "txt", "vue", "xml", "yaml", "yml", "zig", "zsh",
        )
        private val mediaExtensions = setOf(
            "aac", "aif", "aiff", "avi", "caf", "flac", "m4a", "m4v", "mkv", "mov", "mp3", "mp4", "oga", "ogg", "ogv",
            "opus", "wav", "weba", "webm",
        )
        private val documentExtensions = setOf(
            "bmp", "doc", "docx", "epub", "gif", "heic", "ico", "jpeg", "jpg", "key", "numbers", "pages", "pdf", "png",
            "ppt", "pptx", "rtf", "svg", "tif", "tiff", "webp", "xls", "xlsx", "zip",
        )
        private val viewableExtensions = textExtensions + mediaExtensions + documentExtensions
        private val knownFileNames = setOf(
            "brewfile", "changelog", "codeowners", "containerfile", "dockerfile", "gemfile", "justfile", "license",
            "makefile", "podfile", "procfile", "readme", "vagrantfile",
        )
    }
}

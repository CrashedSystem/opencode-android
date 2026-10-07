@file:PortedFrom("OpenCode/ComposerFileAttachment.swift", "553a407", partial = "file reference, prompt presentation, transcript file, upload event")

package ai.opencode.mobilecode

import kotlinx.serialization.Serializable

@Serializable
data class OpenCodeFileReference(
    var name: String,
    var mime: String,
    var path: String,
)

object OpenCodePromptPresentation {
    fun referenceLine(file: OpenCodeFileReference): String = "Attached file: `${file.path}`"

    fun modelText(text: String, files: List<OpenCodeFileReference>): String {
        val typed = if (text.trim().isEmpty()) emptyList() else listOf(text)
        return (typed + files.map { referenceLine(it) }).joinToString("\n")
    }

    fun files(metadata: Map<String, OpenCodeJSONValue>?): List<OpenCodeFileReference> {
        val items = (metadata?.get("attachments") as? OpenCodeJSONValue.array)?.values ?: return emptyList()
        return items.mapNotNull { item ->
            val objectValue = item.`object` ?: return@mapNotNull null
            val name = objectValue["name"]?.string ?: return@mapNotNull null
            val mime = objectValue["mime"]?.string ?: return@mapNotNull null
            val path = objectValue["path"]?.string ?: return@mapNotNull null
            OpenCodeFileReference(name = name, mime = mime, path = path)
        }
    }

    fun displayText(text: String, files: List<OpenCodeFileReference>): String {
        if (files.isEmpty()) return text
        val references = files.map { referenceLine(it) }.toSet()
        return text.split("\n")
            .filter { it !in references }
            .joinToString("\n")
            .trim()
    }
}

@Serializable
data class OpenCodeTranscriptFile(
    var id: String,
    var name: String,
    var mime: String,
    var path: String? = null,
) {
    val typeLabel: String get() = AttachmentFileType.label(name, mime)
    val symbolName: String get() = AttachmentFileType.symbolName(name, mime)
}

sealed class OpenCodeUploadEvent {
    data class progress(val sent: Long) : OpenCodeUploadEvent()
    data class finished(val path: String) : OpenCodeUploadEvent()
}

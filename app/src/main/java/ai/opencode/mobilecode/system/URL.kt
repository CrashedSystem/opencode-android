package ai.opencode.mobilecode.system

import java.net.URI
import kotlinx.serialization.Serializable

@Serializable
data class URL(val absoluteString: String) {
    val scheme: String?
        get() = absoluteString.substringBefore("://", "").takeIf { absoluteString.contains("://") }

    val host: String?
        get() {
            val uri = runCatching { URI(absoluteString) }.getOrNull() ?: return null
            return uri.host
        }

    val path: String
        get() = runCatching { URI(absoluteString).path }.getOrNull() ?: absoluteString.substringAfter("://").substringAfter("/")
            .let { if (absoluteString.contains("://")) "/$it" else absoluteString }

    companion object {
        fun parse(string: String): URL? = string.trim().takeIf { it.isNotEmpty() }?.let { URL(it) }
    }
}

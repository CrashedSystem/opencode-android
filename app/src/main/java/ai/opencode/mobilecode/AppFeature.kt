@file:PortedFrom("OpenCode/AppFeature.swift", "553a407", partial = "failure report and connection reduce for the client tests")

package ai.opencode.mobilecode

import java.net.URI
import java.time.Instant
import java.time.format.DateTimeFormatter

class AppFeature {
    class State {
        var serverURL: String = ""
        var username: String = "opencode"
        var password: String = ""
        var alternateURLs: List<String> = emptyList()
        var connection: Connection = Connection.idle
        var connectionGeneration: Int = 0
        var connectionAttempt: ConnectionAttempt? = null
        var isScanningPairingCode: Boolean = false
        var isEditingServer: Boolean = false

        val server: OpenCodeServer
            get() = OpenCodeServer(
                url = serverURL,
                username = username.trim().ifEmpty { "opencode" },
                password = password,
                alternateURLs = alternateURLs,
            )

        val errorMessage: String?
            get() = (connection as? Connection.failed)?.message

        val errorContext: FailureContext?
            get() = (connection as? Connection.failed)?.context

        fun errorReport(appVersion: String, build: String, generatedAt: Long): String? {
            val message = errorMessage ?: return null
            val lines = mutableListOf(
                "OpenCode connection error",
                "App: OpenCode $appVersion ($build)",
                "Report generated at: ${DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(generatedAt))}",
            )
            errorContext?.let { context ->
                lines += listOf(
                    "Phase: ${context.phase.raw}",
                    "Server: ${context.target}",
                    "Attempt: ${context.attempt}",
                )
            }
            return (lines + listOf("", "Message:", message)).joinToString("\n")
        }

        fun reduce(action: Action) {
            when (action) {
                is Action.binding -> serverURL = action.serverURL
                is Action.healthResponse -> {
                    val attempt = connectionAttempt
                    if (attempt == null || attempt.id != action.attemptID || connection != Connection.connecting) return
                    val failure = action.failure ?: return
                    connectionAttempt = null
                    connection = Connection.failed(
                        message = failure.message,
                        phase = FailureContext.Phase.connect,
                        context = FailureContext(server = attempt.server, phase = FailureContext.Phase.connect, attempt = action.attemptID),
                    )
                }
                is Action.homeResponse -> {
                    val server = (connection as? Connection.loadingHome)?.server ?: return
                    if (connectionAttempt?.id != action.attemptID) return
                    val failure = action.failure ?: return
                    connectionAttempt = null
                    connection = Connection.failed(
                        message = failure.message,
                        phase = FailureContext.Phase.loadSessions,
                        context = FailureContext(server = server, phase = FailureContext.Phase.loadSessions, attempt = action.attemptID),
                    )
                }
                is Action.pairingCodeScanned -> {
                    isScanningPairingCode = false
                    if (OpenCodePairingLink.decode(action.text) != null) return
                    connectionAttempt = null
                    connection = Connection.failed(
                        message = "That isn’t an OpenCode pairing code. Run `opencode pair` on your computer, then scan its QR code or paste the link it prints.",
                        phase = FailureContext.Phase.pair,
                    )
                }
            }
        }
    }

    sealed class Connection {
        data object idle : Connection()
        data object pairing : Connection()
        data object connecting : Connection()
        data class loadingHome(val server: OpenCodeServer) : Connection()
        data object connected : Connection()
        data class failed(
            val message: String,
            val phase: FailureContext.Phase = FailureContext.Phase.connect,
            val context: FailureContext? = null,
        ) : Connection()
        data object picking : Connection()
    }

    data class ConnectionAttempt(val id: Int, val server: OpenCodeServer)

    data class FailureContext(
        val phase: Phase,
        val target: String,
        val attempt: Int,
    ) {
        constructor(server: OpenCodeServer, phase: Phase, attempt: Int) : this(phase, safeTarget(server.url), attempt)

        enum class Phase(val raw: String) {
            pair("Pair"),
            connect("Connect"),
            loadSessions("Load sessions"),
        }

        companion object {
            private val allowed = Regex("""[A-Za-z0-9.\-:\[\]]""")

            fun safeTarget(address: String): String {
                val uri = runCatching { URI(address) }.getOrNull() ?: return invalid
                val scheme = uri.scheme?.lowercase() ?: return invalid
                if (scheme != "http" && scheme != "https") return invalid
                val host = uri.host?.takeIf { it.isNotEmpty() } ?: return invalid
                if (host.any { !allowed.matches(it.toString()) }) return invalid
                if (uri.port != -1 && uri.port !in 1..65535) return invalid
                val hostname = if (host.contains(":") && !host.startsWith("[")) "[$host]" else host
                return if (uri.port == -1) hostname else "$hostname:${uri.port}"
            }

            private const val invalid = "Unavailable (invalid address)"
        }
    }

    sealed class Action {
        data class binding(val serverURL: String) : Action()
        data class healthResponse(val attemptID: Int, val failure: OpenCodeClientError?) : Action()
        data class homeResponse(val attemptID: Int, val failure: OpenCodeClientError?) : Action()
        data class pairingCodeScanned(val text: String) : Action()
    }
}

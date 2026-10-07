@file:PortedFrom("OpenCodeTests/ConnectionErrorReportTests.swift", "553a407")

package ai.opencode.mobilecode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionErrorReportTests {
    @Test
    fun reportContainsOnlySafeTargetAndExplicitMetadata() {
        val server = OpenCodeServer(
            url = "https://url-user:url-password@box.example:4096/private/path?token=query-secret#fragment-secret",
            username = "auth-user",
            password = "auth-password",
        )
        val state = AppFeature.State()
        state.connection = AppFeature.Connection.failed(
            message = "Server returned HTTP 503.",
            phase = AppFeature.FailureContext.Phase.loadSessions,
            context = AppFeature.FailureContext(server, AppFeature.FailureContext.Phase.loadSessions, 7),
        )
        assertEquals(
            """
            OpenCode connection error
            App: OpenCode 1.2.3 (45)
            Report generated at: 1970-01-01T00:00:00Z
            Phase: Load sessions
            Server: box.example:4096
            Attempt: 7

            Message:
            Server returned HTTP 503.
            """.trimIndent(),
            state.errorReport("1.2.3", "45", 0),
        )
    }

    @Test
    fun malformedAddressesNeverFallBackToRawInput() {
        for (url in listOf(
            "not a URL with private data",
            "https://",
            "https://example.com:invalid/private?secret=value",
            "file:///private/secret",
            "https://box%0Asecret.example/private",
            "https://box%40secret.example/private",
        )) {
            val context = AppFeature.FailureContext(
                server = OpenCodeServer(url = url, username = "private", password = "secret"),
                phase = AppFeature.FailureContext.Phase.connect,
                attempt = 1,
            )
            assertEquals("Unavailable (invalid address)", context.target)
        }
    }

    @Test
    fun targetPreservesIPv6PortAndLongHostnameWithoutTruncation() {
        val ipv6 = AppFeature.FailureContext(
            server = OpenCodeServer(url = "http://[::1]:4096/private?token=secret", username = "", password = ""),
            phase = AppFeature.FailureContext.Phase.connect,
            attempt = 1,
        )
        assertEquals("[::1]:4096", ipv6.target)
        val host = "long-hostname.".repeat(12) + "example.com"
        val long = AppFeature.FailureContext(
            server = OpenCodeServer(url = "https://$host:8443/private", username = "", password = ""),
            phase = AppFeature.FailureContext.Phase.connect,
            attempt = 2,
        )
        assertEquals("$host:8443", long.target)
    }

    @Test
    fun acceptedFailureKeepsItsTargetAfterStaleResponsesAndFormEdits() {
        for (afterHealth in listOf(false, true)) {
            val selected = OpenCodeServer(
                url = "https://b.example/private?token=secret",
                username = "bob",
                password = "b-secret",
                alternateURLs = listOf("https://b-alternate.example:4096"),
            )
            val winner = selected.at("https://b-alternate.example:4096")
            val state = AppFeature.State()
            state.connectionGeneration = 2
            state.connectionAttempt = AppFeature.ConnectionAttempt(2, selected)
            state.connection = if (afterHealth) AppFeature.Connection.loadingHome(winner) else AppFeature.Connection.connecting
            state.serverURL = "https://form.example/private?token=other-secret"
            state.username = "form-user"
            state.password = "form-secret"
            val error = OpenCodeClientError(message = "Server returned HTTP 503.")
            if (afterHealth) state.reduce(AppFeature.Action.homeResponse(2, error))
            else state.reduce(AppFeature.Action.healthResponse(2, error))
            val expected = AppFeature.FailureContext(
                server = if (afterHealth) winner else selected,
                phase = if (afterHealth) AppFeature.FailureContext.Phase.loadSessions else AppFeature.FailureContext.Phase.connect,
                attempt = 2,
            )
            assertEquals(expected, state.errorContext)
            state.reduce(AppFeature.Action.healthResponse(1, null))
            state.reduce(AppFeature.Action.healthResponse(1, OpenCodeClientError(message = "Stale A health failure")))
            state.reduce(AppFeature.Action.homeResponse(1, null))
            state.reduce(AppFeature.Action.homeResponse(1, OpenCodeClientError(message = "Stale A home failure")))
            state.reduce(AppFeature.Action.binding("https://edited.example"))
            assertEquals(expected, state.errorContext)
            assertEquals(error.message, state.errorMessage)
            val report = state.errorReport("1", "2", 0)!!
            assertTrue(report.contains("Server: ${if (afterHealth) "b-alternate.example:4096" else "b.example"}"))
            assertTrue(report.contains("Attempt: 2"))
            assertFalse(report.contains("secret"))
            assertFalse(report.contains("form.example"))
            assertFalse(report.contains("edited.example"))
        }
    }

    @Test
    fun invalidPairingCodeReportsOnlyTheSafeMessage() {
        val state = AppFeature.State()
        state.reduce(AppFeature.Action.pairingCodeScanned("invalid QR containing a private password"))
        assertNull(state.errorContext)
        val report = state.errorReport("1", "2", 0)!!
        assertTrue(report.contains("That isn’t an OpenCode pairing code."))
        assertFalse(report.contains("private password"))
        assertFalse(report.contains("Server:"))
        assertFalse(report.contains("Attempt:"))
    }
}

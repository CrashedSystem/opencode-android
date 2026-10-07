@file:PortedFrom("OpenCodeTests/AppFeatureTests.swift", "8bec12f")

package ai.opencode.mobilecode

import ai.opencode.mobilecode.tca.BindingAction
import ai.opencode.mobilecode.tca.Exhaustivity
import ai.opencode.mobilecode.tca.TestClock
import ai.opencode.mobilecode.tca.TestDependencies
import ai.opencode.mobilecode.tca.TestStore
import ai.opencode.mobilecode.tca.expect
import ai.opencode.mobilecode.tca.withLock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AppFeatureTests {
    @get:Rule
    val dependencies = TestDependencies()

    @Test
    fun connectsToServer() {
        val snapshot = OpenCodeHomeSnapshot(projects = emptyList(), sessions = emptyList(), activeSessionIDs = emptySet())
        val server = OpenCodeServer(url = "http://127.0.0.1:4096", username = "opencode", password = developmentPassword)
        val initialState = AppFeature.State().apply { serverURL = server.url }
        val (store, snapshots) = gatedHomeStore(initialState)

        store.send(AppFeature.Action.connectButtonTapped) {
            it.manualEntryDraft = server
            it.connection = AppFeature.State.Connection.connecting
            it.connectionGeneration = 1
            it.connectionAttempt = AppFeature.ConnectionAttempt(1, server)
        }
        store.receive<AppFeature.Action.healthResponse> {
            it.connection = AppFeature.State.Connection.loadingHome(server)
            it::savedServer.withLock { value = server }
            it::savedServers.withLock { value = listOf(server) }
        }
        snapshots.trySend(snapshot)
        store.receive<AppFeature.Action.homeResponse> {
            it.connection = AppFeature.State.Connection.connected
            it.connectionAttempt = null
            it.manualEntryDraft = null
            it.home = HomeFeature.State(server = server, snapshot = snapshot)
            it::cachedHomeSnapshot.withLock { value = CachedHomeSnapshot(serverID = server.persistenceID, snapshot = snapshot) }
        }
    }

    @Test
    fun connectionProgressRemainsVisibleUntilHomeArrives() {
        val server = OpenCodeServer(url = "https://slow-home.example", username = "alice", password = "a")
        val snapshot = OpenCodeHomeSnapshot(projects = emptyList(), sessions = emptyList(), activeSessionIDs = emptySet())
        val (store, snapshots) = gatedHomeStore()
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)

        store.send(AppFeature.Action.serverTapped(server))
        store.receive<AppFeature.Action.healthResponse>()
        expect(store.state.home == null)
        expect(store.state.connectingServerID == server.id)

        snapshots.trySend(snapshot)
        store.receive<AppFeature.Action.homeResponse>()
        expect(store.state.home == HomeFeature.State(server = server, snapshot = snapshot))
        expect(store.state.connectingServerID == null)
        store.finish()
    }

    @Test
    fun autoConnectsToSavedServerOnLaunch() {
        val snapshot = OpenCodeHomeSnapshot(projects = emptyList(), sessions = emptyList(), activeSessionIDs = emptySet())
        val saved = OpenCodeServer(url = "http://saved.example:4096", username = "kit", password = "secret")
        val initialState = AppFeature.State().apply { this::savedServer.withLock { value = saved } }
        val (store, snapshots) = gatedHomeStore(initialState)

        store.send(AppFeature.Action.task) {
            it.serverURL = saved.url
            it.username = saved.username
            it.password = saved.password
            it.connection = AppFeature.State.Connection.connecting
            it.connectionGeneration = 1
            it.connectionAttempt = AppFeature.ConnectionAttempt(1, saved)
        }
        store.receive<AppFeature.Action.healthResponse> {
            it.connection = AppFeature.State.Connection.loadingHome(saved)
            it::savedServers.withLock { value = listOf(saved) }
        }
        snapshots.trySend(snapshot)
        store.receive<AppFeature.Action.homeResponse> {
            it.connection = AppFeature.State.Connection.connected
            it.connectionAttempt = null
            it.home = HomeFeature.State(server = saved, snapshot = snapshot)
            it::cachedHomeSnapshot.withLock { value = CachedHomeSnapshot(serverID = saved.persistenceID, snapshot = snapshot) }
        }
    }

    @Test
    fun restoresCachedHomeBeforeLaunchTaskWithoutProbing() {
        val saved = OpenCodeServer(
            url = "https://saved.example",
            username = "kit",
            password = "secret",
            alternateURLs = listOf("http://192.168.1.20:4096"),
        )
        val session = OpenCodeSession(
            id = "ses_cached",
            title = "Cached work",
            projectID = "proj_cached",
            directory = "/work",
            updatedAt = 1_000,
        )
        val snapshot = OpenCodeHomeSnapshot(
            projects = listOf(OpenCodeProject(id = "proj_cached", name = "Work", directory = "/work")),
            sessions = listOf(session),
            activeSessionIDs = setOf(session.id),
        )
        val cache = CachedHomeSnapshot(serverID = saved.persistenceID, snapshot = snapshot)
        AppFeature.State().apply {
            this::savedServer.withLock { value = saved }
            this::cachedHomeSnapshot.withLock { value = cache }
        }

        val store = TestStore(initialState = { AppFeature.State() }) { AppFeature() }
        expect(store.state.server == saved)
        expect(store.state.home?.projects == snapshot.projects)
        expect(store.state.home?.sessions == listOf(session))
        expect(store.state.home?.connectionStatus == HomeFeature.State.ConnectionStatus.reconnecting)
        expect(store.state.home?.isConnected == false)
        expect(store.state.home?.indicator(session) == HomeFeature.State.Indicator.none)
        expect(store.state.home?.initializedInboxServerIDs?.isEmpty() == true)
        expect(store.state.cachedHomeSnapshot == cache)

        store.send(AppFeature.Action.task)
        expect(store.state.home?.sessions == listOf(session))
        store.finish()
    }

    @Test
    fun launchWithoutMatchingCacheShowsEmptyReconnectingHome() {
        launchWithoutMatchingCache(hasOtherServerCache = false)
    }

    @Test
    fun launchWithAnotherServersCacheDoesNotAdoptIt() {
        launchWithoutMatchingCache(hasOtherServerCache = true)
    }

    private fun launchWithoutMatchingCache(hasOtherServerCache: Boolean) {
        val saved = OpenCodeServer(url = "https://saved.example", username = "kit", password = "saved-secret")
        val other = OpenCodeServer(url = saved.url, username = "another-user", password = "other-secret")
        val otherCache = CachedHomeSnapshot(
            serverID = other.persistenceID,
            snapshot = OpenCodeHomeSnapshot(
                projects = listOf(OpenCodeProject(id = "other-project", name = "Private work", directory = "/other")),
                sessions = listOf(
                    OpenCodeSession(
                        id = "other-session",
                        title = "Other account",
                        projectID = "other-project",
                        directory = "/other",
                        updatedAt = 1_000,
                    ),
                ),
                activeSessionIDs = setOf("other-session"),
            ),
        )
        AppFeature.State().apply {
            this::savedServer.withLock { value = saved }
            this::cachedHomeSnapshot.withLock { value = if (hasOtherServerCache) otherCache else null }
        }

        val store = TestStore(initialState = { AppFeature.State() }) { AppFeature() }
        expect(store.state.home?.server == saved)
        expect(store.state.home?.projects?.isEmpty() == true)
        expect(store.state.home?.sessions?.isEmpty() == true)
        expect(store.state.home?.activeSessionIDs?.isEmpty() == true)
        expect(store.state.home?.connectionStatus == HomeFeature.State.ConnectionStatus.reconnecting)
        expect(store.state.cachedHomeSnapshot == if (hasOtherServerCache) otherCache else null)
        store.send(AppFeature.Action.task)
        store.finish()
    }

    @Test
    fun encodedHomeCacheDoesNotPersistServerPassword() {
        val server = OpenCodeServer(url = "https://saved.example", username = "kit", password = "unique-keychain-secret")
        val session = OpenCodeSession(
            id = "ses_cached",
            title = "Cached work",
            projectID = "proj_cached",
            directory = "/work",
            updatedAt = 1_000,
        )
        val home = HomeFeature.State(server = server, snapshot = OpenCodeHomeSnapshot(emptyList(), listOf(session), emptySet()))
        home.persistSnapshot()
        val cache = checkNotNull(home.cachedHomeSnapshot)
        val encoded = protocolJson.encodeToString(CachedHomeSnapshot.serializer(), cache)
        expect(!encoded.contains(server.password))
        expect(!encoded.contains("\"password\""))
        expect(protocolJson.decodeFromString(CachedHomeSnapshot.serializer(), encoded) == cache)
        expect(cache.snapshot.sessions == listOf(session))
    }

    @Test
    fun homeCacheWrittenByEarlierBuildsStillDecodes() {
        val legacy = """{"serverID":"kit|https://saved.example","version":"1.2.3","snapshot":{"projects":[],"sessions":[],"activeSessionIDs":[]}}"""
        val cache = protocolJson.decodeFromString(CachedHomeSnapshot.serializer(), legacy)
        expect(
            cache == CachedHomeSnapshot(
                serverID = "kit|https://saved.example",
                snapshot = OpenCodeHomeSnapshot(emptyList(), emptyList(), emptySet()),
            ),
        )
    }

    @Test
    fun waitsForPairingWithoutSavedServer() {
        val store = TestStore(initialState = { AppFeature.State() }) { AppFeature() }
        store.send(AppFeature.Action.task)
        expect(!store.state.isEditingServer)
        expect(store.state.errorMessage == null)
        store.finish()

        store.send(AppFeature.Action.scanPairingCodeTapped) {
            it.isScanningPairingCode = true
            it.connection = AppFeature.State.Connection.picking
        }
        store.send(AppFeature.Action.scannerDismissed) {
            it.isScanningPairingCode = false
        }
        store.send(AppFeature.Action.addServerTapped) {
            it.isEditingServer = true
            it.password = ""
        }
        store.send(AppFeature.Action.cancelEditingTapped) {
            it.isEditingServer = false
        }
    }

    @Test
    fun reportsConnectionFailure() {
        val store = TestStore(
            initialState = { AppFeature.State() },
            withDependencies = { it.openCodeClient.health = { throw OpenCodeClientError(message = "Offline") } },
        ) { AppFeature() }
        store.send(AppFeature.Action.connectButtonTapped) {
            it.manualEntryDraft = it.server
            it.connection = AppFeature.State.Connection.connecting
            it.connectionGeneration = 1
            it.connectionAttempt = AppFeature.ConnectionAttempt(1, it.server)
        }
        store.receive<AppFeature.Action.healthResponse> {
            it.connectionAttempt = null
            it.connection = AppFeature.State.Connection.failed(
                message = "Offline",
                context = AppFeature.FailureContext(server = it.server, phase = AppFeature.FailureContext.Phase.connect, attempt = 1),
            )
        }
    }

    @Test
    fun serverBadgeReturnsToThePickerAndAnotherServerConnects() {
        val snapshot = OpenCodeHomeSnapshot(projects = emptyList(), sessions = emptyList(), activeSessionIDs = emptySet())
        val laptop = OpenCodeServer(url = "http://127.0.0.1:4096", username = "opencode", password = "a")
        val tailnet = OpenCodeServer(url = "https://box.ts.net:4096", username = "opencode", password = "b")
        val initialState = AppFeature.State().apply {
            connection = AppFeature.State.Connection.connected
            home = HomeFeature.State(server = laptop, snapshot = snapshot)
            this::savedServers.withLock { value = listOf(laptop, tailnet) }
        }
        val (store, continuation) = gatedHomeStore(initialState)

        store.send(AppFeature.Action.home(HomeFeature.Action.serverBadgeTapped)) {
            it.home = null
            it.connection = AppFeature.State.Connection.picking
            it.returnServer = laptop
        }
        // `.picking` must not auto-reconnect when the view's task re-runs.
        store.send(AppFeature.Action.task)

        store.send(AppFeature.Action.serverTapped(tailnet)) {
            it.serverURL = tailnet.url
            it.username = tailnet.username
            it.password = tailnet.password
            it.connection = AppFeature.State.Connection.connecting
            it.connectionGeneration = 1
            it.connectionAttempt = AppFeature.ConnectionAttempt(1, tailnet)
        }
        store.receive<AppFeature.Action.healthResponse> {
            it.connection = AppFeature.State.Connection.loadingHome(tailnet)
            it::savedServer.withLock { value = tailnet }
            it::savedServers.withLock { value = listOf(tailnet, laptop) }
        }
        continuation.trySend(snapshot)
        continuation.close()
        store.receive<AppFeature.Action.homeResponse> {
            it.connection = AppFeature.State.Connection.connected
            it.connectionAttempt = null
            it.returnServer = null
            it.home = HomeFeature.State(server = tailnet, snapshot = snapshot)
            it::cachedHomeSnapshot.withLock { value = CachedHomeSnapshot(serverID = tailnet.persistenceID, snapshot = snapshot) }
        }
    }

    @Test
    fun v2PairingLinkDecodesOriginAndOneTimeCode() {
        expect(
            OpenCodePairingLink.decode("  http://10.0.10.26:4196/auth/connect/Ab3_x-9Q\n") ==
                OpenCodePairingLink(origin = "http://10.0.10.26:4196", code = "Ab3_x-9Q"),
        )
        expect(OpenCodePairingLink.decode("https://box.example/auth/connect/code")?.origin == "https://box.example")
        expect(OpenCodePairingLink.decode("http://127.0.0.1:4096/auth/connect/code")?.isLoopback == true)
    }

    @Test
    fun rejectsMalformedPairingLinks() {
        for (link in listOf(
            "ftp://box.example/auth/connect/code",
            "https://box.example/auth/connect/",
            "https://box.example/auth/connect/code/extra",
            "https://box.example/connect/code",
            "https://box.example/auth/connect/code?next=/",
            "https://box.example/auth/connect/code#frag",
            "https://user:pass@box.example/auth/connect/code",
            "https://box.example/auth/connect/co%2Fde",
        )) {
            expect(OpenCodePairingLink.decode(link) == null, link)
        }
    }

    @Test
    fun pairingPayloadListsEveryAddressInOrder() {
        expect(
            OpenCodePairingLink.decode("""{"code":"Ab3_x-9Q","urls":["http://192.168.1.10:4096","http://100.64.0.3:4096"]}""") ==
                OpenCodePairingLink(
                    origin = "http://192.168.1.10:4096",
                    code = "Ab3_x-9Q",
                    alternateOrigins = listOf("http://100.64.0.3:4096"),
                ),
        )
        val duplicated = OpenCodePairingLink.decode(
            """{"code":"abc","urls":["HTTP://192.168.1.10:4096","https://box.example","http://192.168.1.10:4096/"]}""",
        )
        expect(duplicated?.origin == "http://192.168.1.10:4096")
        expect(duplicated?.candidateOrigins == listOf("http://192.168.1.10:4096", "https://box.example"))
        expect(OpenCodePairingLink.decode("""{"code":"abc","urls":["http://127.0.0.1:4096"]}""")?.isLoopback == true)
        expect(OpenCodePairingLink.decode("""{"code":"abc","urls":["http://127.0.0.1:4096","http://10.0.0.2:4096"]}""")?.isLoopback == false)
    }

    @Test
    fun rejectsMalformedPairingPayloads() {
        for (payload in listOf(
            """{"code":"ab/c","urls":["http://192.168.1.10:4096"]}""",
            """{"code":"åbc","urls":["http://192.168.1.10:4096"]}""",
            """{"code":"","urls":["http://192.168.1.10:4096"]}""",
            """{"code":"abc","urls":[]}""",
            """{"code":"abc"}""",
            """{"urls":["http://192.168.1.10:4096"]}""",
            """{"code":"abc","urls":["ftp://192.168.1.10:4096"]}""",
            """{"code":"abc","urls":["http://192.168.1.10:4096","not a url"]}""",
            """{"code":"abc","urls":["http://192.168.1.10:4096","https://box.example/api"]}""",
            """{"code":"abc","urls":["http://user:pass@192.168.1.10:4096"]}""",
            """{"code":"abc","urls":["http://192.168.1.10:4096?x=1"]}""",
            """{"code":"abc","urls":"http://192.168.1.10:4096"}""",
            """{"code":"abc","urls":["http://192.168.1.10:4096"]""",
        )) {
            expect(OpenCodePairingLink.decode(payload) == null, payload)
        }
    }

    @Test
    fun pairingPayloadRedeemsAcrossEveryAddress() {
        val snapshot = OpenCodeHomeSnapshot(projects = emptyList(), sessions = emptyList(), activeSessionIDs = emptySet())
        val link = OpenCodePairingLink(
            origin = "http://100.64.0.3:4096",
            code = "abc",
            alternateOrigins = listOf("http://192.168.1.10:4096"),
        )
        val paired = OpenCodeServer(
            url = "http://192.168.1.10:4096",
            username = "opencode",
            password = "1790000000.sig",
            alternateURLs = listOf("http://100.64.0.3:4096"),
        )
        val store = TestStore(
            initialState = { AppFeature.State() },
            withDependencies = {
                it.openCodeClient.redeemPairing = { redeemed ->
                    expect(redeemed == link)
                    paired
                }
                it.openCodeClient.health = { server ->
                    if (server.url != paired.url) throw OpenCodeClientError(message = "Unreachable")
                }
                it.openCodeClient.homeSnapshot = { snapshot }
            },
        ) { AppFeature() }
        store.exhaustivity = Exhaustivity.off
        store.send(
            AppFeature.Action.pairingCodeScanned("""{"code":"abc","urls":["http://100.64.0.3:4096","http://192.168.1.10:4096"]}"""),
        ) {
            it.connection = AppFeature.State.Connection.pairing
            it.connectionGeneration = 1
            it.connectionAttempt = AppFeature.ConnectionAttempt(
                1,
                OpenCodeServer(
                    url = "http://100.64.0.3:4096",
                    username = "opencode",
                    password = "",
                    alternateURLs = listOf("http://192.168.1.10:4096"),
                ),
            )
        }
        store.receive<AppFeature.Action.pairingRedeemed> {
            it.serverURL = paired.url
            it.password = paired.password
            it.alternateURLs = paired.alternateURLs
            it.connection = AppFeature.State.Connection.connecting
            it.connectionGeneration = 2
        }
        store.receive<AppFeature.Action.healthResponse>()
        store.receive<AppFeature.Action.homeResponse> {
            it.connection = AppFeature.State.Connection.connected
            it::savedServer.withLock { value = paired }
        }
    }

    @Test
    fun pairingLinkRedeemsATokenThenConnects() {
        val snapshot = OpenCodeHomeSnapshot(projects = emptyList(), sessions = emptyList(), activeSessionIDs = emptySet())
        val link = OpenCodePairingLink(origin = "http://10.0.10.26:4196", code = "abc")
        val paired = OpenCodeServer(
            url = link.origin,
            username = "opencode",
            password = "1790000000.sig",
            alternateURLs = listOf("http://100.84.220.87:4196"),
        )
        val store = TestStore(
            initialState = { AppFeature.State() },
            withDependencies = {
                it.openCodeClient.redeemPairing = { redeemed ->
                    expect(redeemed == link)
                    paired
                }
                it.openCodeClient.health = { server ->
                    if (server.url != paired.url) throw OpenCodeClientError(message = "Unreachable")
                }
                it.openCodeClient.homeSnapshot = { snapshot }
            },
        ) { AppFeature() }
        store.exhaustivity = Exhaustivity.off
        store.send(AppFeature.Action.pairingCodeScanned("http://10.0.10.26:4196/auth/connect/abc")) {
            it.connection = AppFeature.State.Connection.pairing
            it.connectionGeneration = 1
        }
        store.receive<AppFeature.Action.pairingRedeemed> {
            it.serverURL = paired.url
            it.password = paired.password
            it.alternateURLs = paired.alternateURLs
            it.connection = AppFeature.State.Connection.connecting
            it.connectionGeneration = 2
        }
        store.receive<AppFeature.Action.healthResponse>()
        store.receive<AppFeature.Action.homeResponse> {
            it.connection = AppFeature.State.Connection.connected
            it::savedServer.withLock { value = paired }
        }
    }

    @Test
    fun expiredPairingLinkExplainsHowToRetry() {
        val store = TestStore(
            initialState = { AppFeature.State() },
            withDependencies = {
                it.openCodeClient.redeemPairing = {
                    throw OpenCodeClientError(message = "This pairing code has expired or was already used.", statusCode = 401)
                }
            },
        ) { AppFeature() }
        store.exhaustivity = Exhaustivity.off
        store.send(AppFeature.Action.pairingCodeScanned("http://10.0.10.26:4196/auth/connect/used"))
        store.receive<AppFeature.Action.pairingRedeemed> {
            it.connectionAttempt = null
            it.connection = AppFeature.State.Connection.failed(
                message = "This pairing code has expired or was already used.",
                context = AppFeature.FailureContext(
                    server = OpenCodeServer(url = "http://10.0.10.26:4196", username = "opencode", password = ""),
                    phase = AppFeature.FailureContext.Phase.pair,
                    attempt = 1,
                ),
            )
        }
    }

    @Test
    fun loopbackPairingLinkFailsWithoutSpendingTheCode() {
        val store = TestStore(initialState = { AppFeature.State() }) { AppFeature() }
        store.exhaustivity = Exhaustivity.off
        store.send(AppFeature.Action.pairingCodeScanned("http://127.0.0.1:4096/auth/connect/abc")) {
            expect(it.errorMessage?.contains("only listens on localhost") == true)
        }
    }

    @Test
    fun lateFieldFlushAfterConnectRetriesWithTheCompletedEntry() {
        val store = TestStore(
            initialState = { AppFeature.State() },
            withDependencies = {
                it.openCodeClient.health = {
                    try {
                        delay(60.seconds)
                    } catch (_: CancellationException) {
                        throw OpenCodeClientError(message = "Cancelled")
                    }
                    throw OpenCodeClientError(message = "Cancelled")
                }
            },
        ) { AppFeature() }
        store.exhaustivity = Exhaustivity.off
        store.send(AppFeature.Action.addServerTapped) { it.isEditingServer = true }
        store.send(AppFeature.Action.binding(BindingAction.set(AppFeature.State::serverURL, "http://10.0.10.2")))
        store.send(AppFeature.Action.connectButtonTapped) {
            it.connection = AppFeature.State.Connection.connecting
            it.connectionGeneration = 1
        }
        store.send(AppFeature.Action.binding(BindingAction.set(AppFeature.State::serverURL, "http://10.0.10.26:4196"))) {
            it.connection = AppFeature.State.Connection.connecting
            it.connectionGeneration = 2
            it.connectionAttempt = AppFeature.ConnectionAttempt(
                2,
                OpenCodeServer(url = "http://10.0.10.26:4196", username = "opencode", password = ""),
            )
        }
        store.skipInFlightEffects()
    }

    @Test
    fun serverListOffersAWayBackToTheServerThatWasOpen() {
        val snapshot = OpenCodeHomeSnapshot(projects = emptyList(), sessions = emptyList(), activeSessionIDs = emptySet())
        val server = OpenCodeServer(url = "http://10.0.10.26:4196", username = "opencode", password = "pw")
        val state = AppFeature.State().apply {
            this::savedServer.withLock { value = server }
            this::savedServers.withLock { value = listOf(server) }
            home = HomeFeature.State(server = server, snapshot = snapshot)
            connection = AppFeature.State.Connection.connected
        }
        val store = TestStore(
            initialState = { state },
            withDependencies = {
                it.openCodeClient.health = {}
                it.openCodeClient.homeSnapshot = { snapshot }
            },
        ) { AppFeature() }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)

        store.send(AppFeature.Action.home(HomeFeature.Action.serverBadgeTapped)) {
            it.home = null
            it.connection = AppFeature.State.Connection.picking
            it.returnServer = server
        }
        store.send(AppFeature.Action.returnToServerTapped) {
            it.connection = AppFeature.State.Connection.connecting
        }
        store.receive<AppFeature.Action.healthResponse>()
        store.receive<AppFeature.Action.homeResponse> {
            it.connection = AppFeature.State.Connection.connected
            it.returnServer = null
        }
        expect(store.state.home?.server == server)
    }

    @Test
    fun returnToTheOpenServerReconnects() {
        val snapshot = OpenCodeHomeSnapshot(projects = emptyList(), sessions = emptyList(), activeSessionIDs = emptySet())
        val server = OpenCodeServer(url = "http://10.0.10.26:4196", username = "opencode", password = "pw")
        val state = AppFeature.State().apply {
            this::savedServer.withLock { value = server }
            this::savedServers.withLock { value = listOf(server) }
            returnServer = server
            connection = AppFeature.State.Connection.picking
            load(server)
        }
        val store = TestStore(
            initialState = { state },
            withDependencies = {
                it.openCodeClient.health = {}
                it.openCodeClient.homeSnapshot = { snapshot }
            },
        ) { AppFeature() }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)
        store.send(AppFeature.Action.returnToServerTapped) {
            it.connection = AppFeature.State.Connection.connecting
        }
        store.receive<AppFeature.Action.healthResponse>()
        store.receive<AppFeature.Action.homeResponse> {
            it.connection = AppFeature.State.Connection.connected
            it.returnServer = null
        }
        expect(store.state.home?.server == server)
    }

    @Test
    fun pairedServersAreRecognisedByTheirSessionToken() {
        expect(OpenCodeServer(url = "http://a", username = "opencode", password = "1792565443.Xr2dC8pLq0Z_vY4mN7bK1sA9eT3uW6jH5fG").usesPairingToken)
        expect(!OpenCodeServer(url = "http://a", username = "opencode", password = "onboarding-test").usesPairingToken)
    }

    @Test
    fun reopeningManualEntryRestoresTheFailedAttempt() {
        val store = TestStore(
            initialState = { AppFeature.State() },
            withDependencies = {
                it.openCodeClient.health = { throw OpenCodeClientError(message = "Could not reach the OpenCode server.") }
            },
        ) { AppFeature() }
        store.exhaustivity = Exhaustivity.off
        store.send(AppFeature.Action.addServerTapped) { it.serverURL = "" }
        store.send(AppFeature.Action.binding(BindingAction.set(AppFeature.State::serverURL, "http://my-mac.local:4096")))
        store.send(AppFeature.Action.binding(BindingAction.set(AppFeature.State::password, "secret")))
        store.send(AppFeature.Action.connectButtonTapped)
        store.receive<AppFeature.Action.healthResponse>()
        store.send(AppFeature.Action.addServerTapped) {
            it.isEditingServer = true
            it.serverURL = "http://my-mac.local:4096"
            it.password = "secret"
        }
    }

    @Test
    fun manualEntryWithoutASchemeConnectsOverHTTP() {
        val attempted = mutableListOf<String>()
        val store = TestStore(
            initialState = { AppFeature.State() },
            withDependencies = {
                it.openCodeClient.health = { server ->
                    attempted += server.url
                    throw OpenCodeClientError(message = "Could not reach the OpenCode server.")
                }
            },
        ) { AppFeature() }
        store.exhaustivity = Exhaustivity.off

        // A bare Tailscale host:port, as `opencode serve` prints it, means plain HTTP.
        store.send(AppFeature.Action.addServerTapped)
        store.send(AppFeature.Action.binding(BindingAction.set(AppFeature.State::serverURL, " 100.84.220.87:4102 ")))
        store.send(AppFeature.Action.connectButtonTapped) {
            it.serverURL = "http://100.84.220.87:4102"
        }
        store.receive<AppFeature.Action.healthResponse>()

        // An explicit scheme is kept as typed.
        store.send(AppFeature.Action.addServerTapped)
        store.send(AppFeature.Action.binding(BindingAction.set(AppFeature.State::serverURL, "https://my-mac.example:4096")))
        store.send(AppFeature.Action.connectButtonTapped) {
            it.serverURL = "https://my-mac.example:4096"
        }
        store.receive<AppFeature.Action.healthResponse>()

        expect(attempted == listOf("http://100.84.220.87:4102", "https://my-mac.example:4096"))
    }

    @Test
    fun blankUsernameConnectsAsTheDefaultUser() {
        val attempted = mutableListOf<OpenCodeServer>()
        val store = TestStore(
            initialState = { AppFeature.State() },
            withDependencies = {
                it.openCodeClient.health = { server ->
                    attempted += server
                    throw OpenCodeClientError(message = "The server rejected these credentials.", statusCode = 401)
                }
            },
        ) { AppFeature() }
        store.exhaustivity = Exhaustivity.off
        store.send(AppFeature.Action.addServerTapped)
        store.send(AppFeature.Action.binding(BindingAction.set(AppFeature.State::serverURL, "http://my-box:4096")))
        store.send(AppFeature.Action.binding(BindingAction.set(AppFeature.State::username, " ")))
        store.send(AppFeature.Action.binding(BindingAction.set(AppFeature.State::password, "secret")))
        store.send(AppFeature.Action.connectButtonTapped)
        store.receive<AppFeature.Action.healthResponse>()
        expect(attempted.map { it.username } == listOf("opencode"))
        expect(attempted.first().basicAuthorization == "Basic " + java.util.Base64.getEncoder().encodeToString("opencode:secret".toByteArray()))
    }

    @Test
    fun malformedLinkShowsAnErrorWithoutSavingCredentials() {
        val store = TestStore(initialState = { AppFeature.State() }) { AppFeature() }
        store.exhaustivity = Exhaustivity.off
        store.send(AppFeature.Action.pairingCodeScanned("file:///auth/connect/do-not-echo"))
        expect(store.state.errorMessage != null)
        expect(store.state.errorMessage?.contains("do-not-echo") == false)
        expect(store.state.savedServers.isEmpty())
        expect(store.state.connectionAttempt == null)
        store.finish()
    }

    @Test
    fun reachabilityRanksPublicThenTailscaleThenLANThenLoopback() {
        val urls = listOf(
            "http://127.0.0.1:4102",
            "http://10.0.10.15:4102",
            "http://192.168.139.3:4102",
            "http://172.28.0.1:4102",
            "http://100.84.220.87:4102",
            "https://box.ts.net:4102",
            "http://203.0.113.9:4102",
        )
        val ranked = urls.withIndex().sortedWith { left, right ->
            val ranks = OpenCodeServer.reachabilityRank(left.value).compareTo(OpenCodeServer.reachabilityRank(right.value))
            if (ranks != 0) ranks else left.index.compareTo(right.index)
        }.map { it.value }
        expect(
            ranked == listOf(
                "http://203.0.113.9:4102",
                "https://box.ts.net:4102",
                "http://100.84.220.87:4102",
                "http://10.0.10.15:4102",
                "http://192.168.139.3:4102",
                "http://172.28.0.1:4102",
                "http://127.0.0.1:4102",
            ),
        )
    }

    @Test
    fun connectFallsBackToAnAlternateAddressAndAdoptsIt() {
        val snapshot = OpenCodeHomeSnapshot(projects = emptyList(), sessions = emptyList(), activeSessionIDs = emptySet())
        val initialState = AppFeature.State().apply {
            serverURL = "https://box.ts.net:4096"
            alternateURLs = listOf("http://192.168.1.20:4096")
            password = "pw"
        }
        val store = TestStore(
            initialState = { initialState },
            withDependencies = {
                it.openCodeClient.health = { server ->
                    if (server.url != "http://192.168.1.20:4096") throw OpenCodeClientError(message = "Offline")
                }
                it.openCodeClient.homeSnapshot = { snapshot }
            },
        ) { AppFeature() }
        store.exhaustivity = Exhaustivity.off
        store.send(AppFeature.Action.connectButtonTapped)
        store.receive<AppFeature.Action.healthResponse>()
        expect(store.state.serverURL == "http://192.168.1.20:4096")
        expect(store.state.alternateURLs == listOf("https://box.ts.net:4096"))
        expect(store.state.savedServer?.url == "http://192.168.1.20:4096")
    }

    @Test
    fun savedServerKeepsOneEntryWhicheverAddressWins() {
        val tailnet = OpenCodeServer(
            url = "https://box.ts.net:4096",
            username = "opencode",
            password = "pw",
            alternateURLs = listOf("http://192.168.1.20:4096"),
        )
        val lan = tailnet.at("http://192.168.1.20:4096")
        val initialState = AppFeature.State().apply {
            load(tailnet)
            this::savedServers.withLock { value = listOf(tailnet) }
        }
        val store = TestStore(
            initialState = { initialState },
            withDependencies = {
                it.openCodeClient.health = { server ->
                    if (server.url != lan.url) throw OpenCodeClientError(message = "Offline")
                }
                it.openCodeClient.homeSnapshot = { OpenCodeHomeSnapshot(emptyList(), emptyList(), emptySet()) }
            },
        ) { AppFeature() }
        store.exhaustivity = Exhaustivity.off
        store.send(AppFeature.Action.connectButtonTapped)
        store.receive<AppFeature.Action.healthResponse>()
        expect(store.state.savedServers == listOf(lan))
        store.receive<AppFeature.Action.homeResponse>()
        store.send(AppFeature.Action.serverDeleted(tailnet))
        expect(store.state.savedServers.isEmpty())
        expect(store.state.savedServer == null)
        store.finish()
    }

    @Test
    fun reconnectingTheSameAddressWithNewCredentialsReplacesTheSavedEntry() {
        val old = OpenCodeServer(url = "http://192.168.1.20:4096", username = "opencode", password = "old")
        val renamed = OpenCodeServer(url = old.url, username = "kit", password = "new")
        val other = OpenCodeServer(url = "http://10.0.0.5:4096", username = "opencode", password = "pw")
        val initialState = AppFeature.State().apply {
            load(renamed)
            this::savedServers.withLock { value = listOf(old, other) }
        }
        val store = TestStore(
            initialState = { initialState },
            withDependencies = {
                it.openCodeClient.health = {}
                it.openCodeClient.homeSnapshot = { OpenCodeHomeSnapshot(emptyList(), emptyList(), emptySet()) }
            },
        ) { AppFeature() }
        store.exhaustivity = Exhaustivity.off
        store.send(AppFeature.Action.connectButtonTapped)
        store.receive<AppFeature.Action.healthResponse>()
        expect(store.state.savedServers == listOf(renamed, other))
        store.receive<AppFeature.Action.homeResponse>()
        store.finish()
    }

    @Test
    fun editingSavedLANAddressReplacesItOnlyAfterSessionsLoad() {
        val lan = OpenCodeServer(
            url = "http://192.168.1.20:4096",
            username = "opencode",
            password = "secret",
            alternateURLs = listOf("http://192.168.1.21:4096"),
        )
        val tailnet = OpenCodeServer(url = "https://box.tailnet.ts.net:4096", username = "opencode", password = "secret")
        val other = OpenCodeServer(url = "https://other.example", username = "other", password = "other")
        val snapshot = OpenCodeHomeSnapshot(projects = emptyList(), sessions = emptyList(), activeSessionIDs = emptySet())
        val snapshots = Channel<OpenCodeHomeSnapshot>(Channel.UNLIMITED)
        val state = AppFeature.State().apply {
            load(lan)
            this::savedServer.withLock { value = lan }
            this::savedServers.withLock { value = listOf(lan, other) }
            home = HomeFeature.State(server = lan, snapshot = snapshot)
            connection = AppFeature.State.Connection.connected
        }
        val store = TestStore(
            initialState = { state },
            withDependencies = {
                it.openCodeClient.health = { server -> expect(server == tailnet) }
                it.openCodeClient.homeSnapshot = { server ->
                    expect(server == tailnet)
                    snapshots.receiveCatching().getOrNull() ?: throw OpenCodeClientError(message = "Cancelled")
                }
            },
        ) { AppFeature() }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)

        store.send(AppFeature.Action.home(HomeFeature.Action.serverBadgeTapped))
        store.send(AppFeature.Action.editServerTapped(lan))
        expect(store.state.isEditingServer)
        expect(store.state.server == lan)
        store.send(AppFeature.Action.binding(BindingAction.set(AppFeature.State::serverURL, tailnet.url)))
        store.send(AppFeature.Action.connectButtonTapped)
        expect(store.state.connectionAttempt?.server == tailnet)
        expect(store.state.savedServers == listOf(lan, other))

        store.receive<AppFeature.Action.healthResponse>()
        expect(store.state.savedServer == lan)
        expect(store.state.savedServers == listOf(lan, other))
        snapshots.trySend(snapshot)
        snapshots.close()
        store.receive<AppFeature.Action.homeResponse>()
        expect(store.state.savedServer == tailnet)
        expect(store.state.savedServers == listOf(tailnet, other))
        expect(store.state.editingSavedServer == null)
        expect(store.state.home?.server == tailnet)
        store.finish()
    }

    @Test
    fun failedEditKeepsTheSavedServerAndCancelDoesNotChangeIt() {
        val original = OpenCodeServer(url = "http://192.168.1.20:4096", username = "opencode", password = "secret")
        val updatedURL = "https://box.tailnet.ts.net:4096"
        val state = AppFeature.State().apply {
            this::savedServer.withLock { value = original }
            this::savedServers.withLock { value = listOf(original) }
        }
        val store = TestStore(
            initialState = { state },
            withDependencies = { it.openCodeClient.health = { throw OpenCodeClientError(message = "Offline") } },
        ) { AppFeature() }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)
        store.send(AppFeature.Action.editServerTapped(original))
        store.send(AppFeature.Action.binding(BindingAction.set(AppFeature.State::serverURL, updatedURL)))
        store.send(AppFeature.Action.cancelEditingTapped)
        expect(store.state.savedServers == listOf(original))
        expect(store.state.savedServer == original)
        store.send(AppFeature.Action.editServerTapped(original))
        store.send(AppFeature.Action.binding(BindingAction.set(AppFeature.State::serverURL, updatedURL)))
        store.send(AppFeature.Action.connectButtonTapped)
        store.receive<AppFeature.Action.healthResponse>()
        expect(store.state.savedServers == listOf(original))
        expect(store.state.savedServer == original)
        store.finish()
    }

    @Test
    fun editedAddressWithUnloadedSessionsKeepsTheOriginalEntry() {
        val original = OpenCodeServer(url = "http://192.168.1.20:4096", username = "opencode", password = "secret")
        val state = AppFeature.State().apply {
            this::savedServer.withLock { value = original }
            this::savedServers.withLock { value = listOf(original) }
        }
        val store = TestStore(
            initialState = { state },
            withDependencies = {
                it.openCodeClient.health = {}
                it.openCodeClient.homeSnapshot = { throw OpenCodeClientError(message = "Sessions unavailable") }
            },
        ) { AppFeature() }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)
        store.send(AppFeature.Action.editServerTapped(original))
        store.send(AppFeature.Action.binding(BindingAction.set(AppFeature.State::serverURL, "https://box.tailnet.ts.net:4096")))
        store.send(AppFeature.Action.connectButtonTapped)
        store.receive<AppFeature.Action.healthResponse>()
        expect(store.state.savedServer == original)
        store.receive<AppFeature.Action.homeResponse>()
        expect(store.state.savedServer == original)
        expect(store.state.savedServers == listOf(original))
        expect(store.state.editingSavedServer == null)
        store.finish()
    }

    @Test
    fun connectSavesReachableTailscaleInsteadOfFasterLAN() {
        val snapshot = OpenCodeHomeSnapshot(projects = emptyList(), sessions = emptyList(), activeSessionIDs = emptySet())
        val initialState = AppFeature.State().apply {
            serverURL = "http://192.168.1.20:4102"
            alternateURLs = listOf("http://100.84.220.87:4102")
            password = "pw"
        }
        val store = TestStore(
            initialState = { initialState },
            withDependencies = {
                it.openCodeClient.health = { server ->
                    try {
                        delay(if (server.url.contains("100.84")) 100.milliseconds else 10.milliseconds)
                    } catch (_: CancellationException) {
                        throw OpenCodeClientError(message = "Cancelled")
                    }
                }
                it.openCodeClient.homeSnapshot = { snapshot }
            },
        ) { AppFeature() }
        store.exhaustivity = Exhaustivity.off
        store.send(AppFeature.Action.connectButtonTapped)
        store.receive<AppFeature.Action.healthResponse>()
        expect(store.state.serverURL == "http://100.84.220.87:4102")
        expect(store.state.savedServer?.url == "http://100.84.220.87:4102")
        expect(store.state.alternateURLs == listOf("http://192.168.1.20:4102"))
    }

    @Test
    fun connectUsesTheFastestHealthyAddress() {
        val snapshot = OpenCodeHomeSnapshot(projects = emptyList(), sessions = emptyList(), activeSessionIDs = emptySet())
        val initialState = AppFeature.State().apply {
            serverURL = "https://slow.example:4096"
            alternateURLs = listOf("https://fast.example:4096")
            password = "pw"
        }
        val store = TestStore(
            initialState = { initialState },
            withDependencies = {
                it.openCodeClient.health = { server ->
                    try {
                        delay(if (server.url.contains("slow")) 250.milliseconds else 10.milliseconds)
                    } catch (_: CancellationException) {
                        throw OpenCodeClientError(message = "Cancelled")
                    }
                }
                it.openCodeClient.homeSnapshot = { snapshot }
            },
        ) { AppFeature() }
        store.exhaustivity = Exhaustivity.off
        store.send(AppFeature.Action.connectButtonTapped)
        store.receive<AppFeature.Action.healthResponse>()
        expect(store.state.serverURL == "https://fast.example:4096")
        expect(store.state.alternateURLs == listOf("https://slow.example:4096"))
        expect(store.state.savedServer?.url == "https://fast.example:4096")
    }

    @Test
    fun lateHealthCannotReplaceTheSelectedServerOrItsCredentials() {
        val first = OpenCodeServer(url = "https://a.example", username = "alice", password = "a")
        val second = OpenCodeServer(
            url = "https://b.example",
            username = "bob",
            password = "b",
            alternateURLs = listOf("https://b-alternate.example"),
        )
        val winner = second.at("https://b-alternate.example")
        val store = suspendedConnectionStore()
        store.send(AppFeature.Action.serverTapped(first))
        val firstID = checkNotNull(store.state.connectionAttempt).id
        store.send(AppFeature.Action.serverTapped(second))
        val secondID = checkNotNull(store.state.connectionAttempt).id
        expect(firstID != secondID)
        store.send(AppFeature.Action.healthResponse(secondID, Result.success(winner)))
        store.send(AppFeature.Action.healthResponse(firstID, Result.success(first)))
        store.send(AppFeature.Action.healthResponse(firstID, Result.failure(OpenCodeClientError(message = "A went offline"))))
        expect(store.state.server == winner)
        expect(store.state.savedServer == winner)
        expect(store.state.savedServers == listOf(winner))
        expect(store.state.connection == AppFeature.State.Connection.loadingHome(winner))
        expect(store.state.connectingServerID == winner.id)
        expect(store.state.home == null)
        store.send(AppFeature.Action.cancelEditingTapped)
        store.finish()
    }

    @Test
    fun lateHomeCannotMixServersOrEndTheCurrentLoadingPhase() {
        val first = OpenCodeServer(url = "https://a.example", username = "alice", password = "a")
        val second = OpenCodeServer(url = "https://b.example", username = "bob", password = "b")
        val firstHome = OpenCodeHomeSnapshot(
            projects = listOf(OpenCodeProject(id = "a-project", name = "A only", directory = "/a")),
            sessions = emptyList(),
            activeSessionIDs = emptySet(),
        )
        val secondHome = OpenCodeHomeSnapshot(
            projects = listOf(OpenCodeProject(id = "b-project", name = "B only", directory = "/b")),
            sessions = emptyList(),
            activeSessionIDs = emptySet(),
        )
        val store = suspendedConnectionStore()
        store.send(AppFeature.Action.serverTapped(first))
        val firstID = checkNotNull(store.state.connectionAttempt).id
        store.send(AppFeature.Action.healthResponse(firstID, Result.success(first)))
        store.send(AppFeature.Action.serverTapped(second))
        val secondID = checkNotNull(store.state.connectionAttempt).id
        store.send(AppFeature.Action.healthResponse(secondID, Result.success(second)))
        store.send(AppFeature.Action.homeResponse(firstID, Result.success(firstHome)))
        store.send(AppFeature.Action.homeResponse(firstID, Result.failure(OpenCodeClientError(message = "A snapshot failed"))))
        expect(store.state.home == null)
        expect(store.state.connection == AppFeature.State.Connection.loadingHome(second))
        expect(store.state.connectionProgressLabel == "Loading sessions")
        expect(store.state.connectingServerID == second.id)
        store.send(AppFeature.Action.homeResponse(secondID, Result.success(secondHome)))
        store.send(AppFeature.Action.homeResponse(firstID, Result.success(firstHome)))
        store.send(AppFeature.Action.homeResponse(firstID, Result.failure(OpenCodeClientError(message = "A snapshot failed"))))
        expect(store.state.home == HomeFeature.State(server = second, snapshot = secondHome))
        expect(store.state.server == second)
        expect(store.state.savedServer == second)
        expect(store.state.connection == AppFeature.State.Connection.connected)
        expect(store.state.connectionProgressLabel == null)
        store.skipInFlightEffects()
    }

    @Test
    fun cancellingEditingOrScanningInvalidatesBothPhases() {
        val server = OpenCodeServer(url = "https://a.example", username = "alice", password = "a")
        val snapshot = OpenCodeHomeSnapshot(projects = emptyList(), sessions = emptyList(), activeSessionIDs = emptySet())
        val interruptions = listOf(
            AppFeature.Action.cancelEditingTapped,
            AppFeature.Action.addServerTapped,
            AppFeature.Action.editServerTapped(server),
            AppFeature.Action.scanPairingCodeTapped,
            AppFeature.Action.scannerDismissed,
            AppFeature.Action.pairingCodeScanned("not a pairing code"),
            AppFeature.Action.binding(BindingAction.set(AppFeature.State::serverURL, "https://edited.example")),
            AppFeature.Action.binding(BindingAction.set(AppFeature.State::username, "edited-user")),
            AppFeature.Action.binding(BindingAction.set(AppFeature.State::password, "edited-password")),
        )
        for (afterHealth in listOf(false, true)) {
            for (interruption in interruptions) {
                val store = suspendedConnectionStore()
                store.send(AppFeature.Action.serverTapped(server))
                val id = checkNotNull(store.state.connectionAttempt).id
                if (afterHealth) store.send(AppFeature.Action.healthResponse(id, Result.success(server)))
                store.send(interruption)
                val connection = store.state.connection
                val editedServer = store.state.server
                val savedServers = store.state.savedServers
                expect(store.state.connectionAttempt == null, "$interruption afterHealth=$afterHealth")
                store.send(AppFeature.Action.healthResponse(id, Result.success(server)))
                store.send(AppFeature.Action.healthResponse(id, Result.failure(OpenCodeClientError(message = "Late health failure"))))
                store.send(AppFeature.Action.homeResponse(id, Result.success(snapshot)))
                store.send(AppFeature.Action.homeResponse(id, Result.failure(OpenCodeClientError(message = "Late home failure"))))
                expect(store.state.connection == connection)
                expect(store.state.server == editedServer)
                expect(store.state.savedServers == savedServers)
                expect(store.state.home == null)
                expect(store.state.connectionProgressLabel == null)
                store.finish()
            }
        }
    }

    @Test
    fun retryingTheSameAddressUsesANewAttempt() {
        val original = OpenCodeServer(url = "https://a.example", username = "alice", password = "old")
        val updated = OpenCodeServer(url = original.url, username = "bob", password = "new")
        val store = suspendedConnectionStore()
        store.send(AppFeature.Action.serverTapped(original))
        val oldID = checkNotNull(store.state.connectionAttempt).id
        store.send(AppFeature.Action.serverTapped(updated))
        val newID = checkNotNull(store.state.connectionAttempt).id
        expect(oldID != newID)
        store.send(AppFeature.Action.healthResponse(oldID, Result.success(original)))
        expect(store.state.connection == AppFeature.State.Connection.connecting)
        expect(store.state.server == updated)
        expect(store.state.savedServer == null)
        store.send(AppFeature.Action.cancelEditingTapped)
        store.finish()
    }

    @Test
    fun unchangedFormBindingsDoNotCancelConnection() {
        val server = OpenCodeServer(url = "https://fixture.example", username = "fixture", password = "")
        for (afterHealth in listOf(false, true)) {
            val store = suspendedConnectionStore()
            store.send(AppFeature.Action.serverTapped(server))
            val attempt = store.state.connectionAttempt
            if (afterHealth) store.send(AppFeature.Action.healthResponse(checkNotNull(attempt).id, Result.success(server)))
            val connection = store.state.connection
            store.send(AppFeature.Action.binding(BindingAction.set(AppFeature.State::serverURL, server.url)))
            store.send(AppFeature.Action.binding(BindingAction.set(AppFeature.State::username, server.username)))
            store.send(AppFeature.Action.binding(BindingAction.set(AppFeature.State::password, server.password)))
            expect(store.state.connectionAttempt == attempt, "afterHealth=$afterHealth")
            expect(store.state.connection == connection)
            store.send(AppFeature.Action.cancelEditingTapped)
            store.finish()
        }
    }

    private fun gatedHomeStore(initialState: AppFeature.State = AppFeature.State()): Pair<TestStore<AppFeature.State, AppFeature.Action>, Channel<OpenCodeHomeSnapshot>> {
        val snapshots = Channel<OpenCodeHomeSnapshot>(Channel.UNLIMITED)
        val store = TestStore(
            initialState = { initialState },
            withDependencies = {
                it.openCodeClient.health = {}
                it.openCodeClient.homeSnapshot = {
                    snapshots.receiveCatching().getOrNull() ?: throw OpenCodeClientError(message = "Cancelled")
                }
            },
        ) { AppFeature() }
        return store to snapshots
    }

    private fun suspendedConnectionStore(): TestStore<AppFeature.State, AppFeature.Action> {
        val clock = TestClock()
        val state = AppFeature.State().apply {
            this::savedServer.withLock { value = null }
            this::savedServers.withLock { value = emptyList() }
        }
        val store = TestStore(
            initialState = { state },
            withDependencies = {
                it.openCodeClient.health = {
                    try {
                        clock.sleep(3_600.seconds)
                    } catch (_: CancellationException) {
                        throw OpenCodeClientError(message = "Cancelled")
                    }
                }
                it.openCodeClient.homeSnapshot = {
                    try {
                        clock.sleep(3_600.seconds)
                    } catch (_: CancellationException) {
                        throw OpenCodeClientError(message = "Cancelled")
                    }
                    OpenCodeHomeSnapshot(emptyList(), emptyList(), emptySet())
                }
            },
        ) { AppFeature() }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)
        return store
    }

    private val developmentPassword: String
        get() = System.getenv("OPENCODE_PASSWORD") ?: ""
}

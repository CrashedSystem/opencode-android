@file:PortedFrom("OpenCodeTests/ShareHandoffTests.swift", "553a407")

package ai.opencode.mobilecode

import ai.opencode.mobilecode.tca.Exhaustivity
import ai.opencode.mobilecode.tca.TestDependencies
import ai.opencode.mobilecode.tca.TestStore
import ai.opencode.mobilecode.tca.expect
import ai.opencode.mobilecode.tca.withLock
import android.graphics.Bitmap
import android.graphics.Color
import android.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

class ShareHandoffStoreTests {
    private val root = File(System.getProperty("java.io.tmpdir"), "share-handoff-${UUID.randomUUID()}")
    private val store: ShareHandoffStore get() = ShareHandoffStore(root)
    private val now = Instant.ofEpochSecond(1_800_000_000)

    private fun handoff(
        id: UUID = UUID.randomUUID(),
        createdAt: Instant,
        target: ShareHandoff.Target = ShareHandoff.Target.session(id = "ses_1", projectID = "proj_1"),
        images: List<ShareHandoff.Image> = emptyList(),
    ) = ShareHandoff(
        id = id,
        createdAt = createdAt,
        serverID = "server",
        target = target,
        text = "Look at this",
        images = images,
    )

    private fun entries(): List<String> = (root.list() ?: emptyArray()).sorted()

    @Test
    fun savedSharesRoundTripOldestFirstAndAreConsumedOnce() {
        try {
            val imageID = UUID.randomUUID()
            val newer = handoff(
                createdAt = now.minusSeconds(10),
                target = ShareHandoff.Target.newSession(projectID = "proj_2"),
                images = listOf(ShareHandoff.Image(id = imageID, fileName = "image-0.jpg", mime = "image/jpeg")),
            )
            val older = handoff(createdAt = now.minusSeconds(60))
            store.save(newer, images = listOf(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x01)))
            store.save(older, images = emptyList())

            val received = store.consumeAll(now = now)
            expect(received.map { it.handoff } == listOf(older, newer))
            expect(
                received[1].images == listOf(
                    ReceivedShare.Image(id = imageID, data = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x01), mime = "image/jpeg"),
                ),
            )
            expect(store.consumeAll(now = now).isEmpty())
            expect(entries().isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun expiredUnreadableAndAbandonedEntriesAreDiscarded() {
        try {
            store.save(handoff(createdAt = now.minusSeconds(ShareHandoffStore.maximumAge + 1)), images = emptyList())
            val corrupt = File(root, UUID.randomUUID().toString())
            corrupt.mkdirs()
            File(corrupt, "share.json").writeText("not json")
            val abandoned = File(root, ".staging-${UUID.randomUUID()}")
            abandoned.mkdirs()
            abandoned.setLastModified(now.minusSeconds(2 * 60 * 60).toEpochMilli())
            val inProgress = File(root, ".staging-${UUID.randomUUID()}")
            inProgress.mkdirs()
            inProgress.setLastModified(now.toEpochMilli())

            expect(store.consumeAll(now = now).isEmpty())
            // Only the extension's still-fresh write survives.
            expect(entries() == listOf(inProgress.name))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun imageNamesCannotEscapeTheirShare() {
        try {
            root.mkdirs()
            File(root, "outside.jpg").writeBytes(byteArrayOf(1))
            var share = handoff(
                createdAt = now,
                images = listOf(ShareHandoff.Image(id = UUID.randomUUID(), fileName = "inside.jpg", mime = "image/jpeg")),
            )
            store.save(share, images = listOf(byteArrayOf(2)))
            // Rewrite the manifest as a hostile writer would.
            share = share.copy(
                images = share.images + ShareHandoff.Image(id = UUID.randomUUID(), fileName = "../outside.jpg", mime = "image/jpeg"),
            )
            File(File(root, share.id.toString()), "share.json").writeText(shareJson.encodeToString(ShareHandoff.serializer(), share))

            val received = store.consumeAll(now = now).first()
            expect(received.images.map { it.data.toList() } == listOf(listOf(2.toByte())))
        } finally {
            root.deleteRecursively()
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33])
class SharedImageEncoderTests {
    /// A 3000×2000 JPEG tagged as rotated, with camera and GPS metadata.
    private fun cameraPhoto(): ByteArray {
        val bitmap = Bitmap.createBitmap(3000, 2000, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(51, 102, 204))
        val file = File.createTempFile("camera", ".jpg")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        bitmap.recycle()
        val exif = ExifInterface(file.absolutePath)
        exif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
        exif.setAttribute(ExifInterface.TAG_MAKE, "TestCam")
        exif.setAttribute(ExifInterface.TAG_MODEL, "Lens 1")
        exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE, "37/1,19/1,48/1")
        exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N")
        exif.saveAttributes()
        return file.readBytes().also { file.delete() }
    }

    @Test
    fun shareReencodingMatchesTheComposerAndStripsMetadata() {
        val original = cameraPhoto()
        val originalExif = ExifInterface(java.io.ByteArrayInputStream(original))
        expect(originalExif.getAttribute(ExifInterface.TAG_GPS_LATITUDE) != null)

        val shared = SharedImageEncoder.jpeg(from = original)!!
        val composer = runBlocking { ImageAttachmentImporter.process(original) }!!
        expect(composer.data.contentEquals(shared))
        expect(composer.mime == SharedImageEncoder.mime)

        val file = File(System.getProperty("java.io.tmpdir"), "${UUID.randomUUID()}.jpg")
        try {
            file.writeBytes(original)
            expect(SharedImageEncoder.jpeg(contentsOf = file).contentEquals(shared))
        } finally {
            file.delete()
        }

        val encoded = ExifInterface(java.io.ByteArrayInputStream(shared))
        expect(encoded.getAttribute(ExifInterface.TAG_GPS_LATITUDE) == null)
        expect(encoded.getAttribute(ExifInterface.TAG_MAKE) == null)
        val orientation = encoded.getAttribute(ExifInterface.TAG_ORIENTATION)
        expect(orientation == null || orientation == "0" || orientation == "1")
        val decoded = android.graphics.BitmapFactory.decodeByteArray(shared, 0, shared.size)
        // The rotation is applied to the pixels, and the long edge is capped.
        expect(decoded.width == 1045)
        expect(decoded.height == SharedImageEncoder.maxPixelSize)
    }

    @Test
    fun oversizedSourcesAreRejected() {
        expect(SharedImageEncoder.jpeg(from = ByteArray(SharedImageEncoder.maxSourceBytes + 1)) == null)
        expect(SharedImageEncoder.jpeg(from = "not an image".toByteArray()) == null)
    }
}

class ShareModelTests {
    @Test
    fun textAndLinksDeduplicateAndSaveIntoTheQueue() {
        kotlinx.coroutines.runBlocking {
            val root = File(System.getProperty("java.io.tmpdir"), "share-model-${UUID.randomUUID()}")
            val store = ShareHandoffStore(root)
            val targets = ShareTargets(
                serverID = "server",
                serverName = "localhost:4096",
                lastViewedSessionID = "ses_1",
                newSessionProject = ShareTargets.Project(id = "proj", name = "Work"),
                pinned = listOf(ShareTargets.Session(id = "ses_1", title = "Current", projectID = "proj", projectName = "Work")),
            )
            val model = ShareModel(targets, store)
            model.load(
                listOf(
                    NSItemProvider(registeredTypeIdentifiers = listOf("public.plain-text"), data = "Hello".toByteArray()),
                    NSItemProvider(registeredTypeIdentifiers = listOf("public.url"), data = "https://example.com".toByteArray()),
                    NSItemProvider(registeredTypeIdentifiers = listOf("public.plain-text"), data = "https://example.com".toByteArray()),
                ),
            )
            expect(model.sharedText == listOf("Hello", "https://example.com"))
            expect(model.save())
            val received = store.consumeAll(java.time.Instant.now())
            expect(received.single().handoff.text == "Hello\n\nhttps://example.com")
            expect(received.single().handoff.target == ShareHandoff.Target.session(id = "ses_1", projectID = "proj"))
            root.deleteRecursively()
        }
    }
}

// Robolectric gives the reducers' LabMarks a working android.util.Log.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ShareInboxFeatureTests {
    @get:Rule
    val dependencies = TestDependencies()

    private val server = OpenCodeServer(url = "http://localhost:4096", username = "kit", password = "secret")
    private val project = OpenCodeProject(id = "proj", name = "Work", directory = "/work")
    private val other = OpenCodeProject(id = "proj_other", name = "Other", directory = "/other")
    private val current = OpenCodeSession(id = "ses_current", title = "Current", projectID = "proj", directory = "/work", updatedAt = 30_000)
    private val target = OpenCodeSession(id = "ses_target", title = "Target", projectID = "proj_other", directory = "/other", updatedAt = 20_000)
    private val screenshot = ReceivedShare.Image(id = UUID(0, 1), data = byteArrayOf(0xFF.toByte(), 0xD8.toByte()), mime = "image/jpeg")

    private fun share(
        target: ShareHandoff.Target,
        text: String,
        images: List<ReceivedShare.Image> = emptyList(),
        at: Long,
    ): ReceivedShare = ReceivedShare(
        handoff = ShareHandoff(
            id = UUID.randomUUID(),
            createdAt = Instant.ofEpochSecond(at),
            serverID = server.persistenceID,
            target = target,
            text = text,
            images = images.map { ShareHandoff.Image(id = it.id, fileName = "x.jpg", mime = it.mime) },
        ),
        images = images,
    )

    private fun home(): HomeFeature.State {
        val state = HomeFeature.State(
            server = server,
            snapshot = OpenCodeHomeSnapshot(projects = listOf(project, other), sessions = listOf(current, target), activeSessionIDs = emptySet()),
        )
        state.transcriptCache = mapOf(current.id to emptyList(), target.id to emptyList())
        state.sessionDetail = state.makeDetail(current, cachedItems = emptyList())
        state.sessionDetail?.draft = "Typed already"
        return state
    }

    private fun makeStore(state: HomeFeature.State): TestStore<HomeFeature.State, HomeFeature.Action> {
        val store = TestStore(
            initialState = { state },
            withDependencies = { it.openCodeClient.viewSession = { _, _, _ -> } },
        ) {
            HomeFeature()
        }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)
        return store
    }

    @Test
    fun shareOpensItsSessionWithTheItemsInTheComposer() {
        val store = makeStore(home())
        store.send(
            HomeFeature.Action.sharesReceived(
                listOf(share(ShareHandoff.Target.session(id = target.id, projectID = other.id), text = "What’s wrong here?", images = listOf(screenshot), at = 100)),
            ),
        )
        store.receive<HomeFeature.Action.sessionTapped>()
        expect(store.state.sessionDetail?.session?.id == target.id)
        expect(store.state.sessionDetail?.draft == "What’s wrong here?")
        expect(
            store.state.sessionDetail?.pendingAttachments == listOf(
                OpenCodeOutgoingAttachment(id = screenshot.id.toString(), data = screenshot.data, mime = "image/jpeg"),
            ),
        )
        expect(store.state.sharedDrafts.isEmpty())
        store.finish()
    }

    @Test
    fun shareForTheOpenSessionKeepsWhatWasTyped() {
        val store = makeStore(home())
        val navigationID = store.state.sessionDetail?.navigationID
        store.send(HomeFeature.Action.sharesReceived(listOf(share(ShareHandoff.Target.session(id = current.id, projectID = project.id), text = "https://example.com", at = 100))))
        store.receive<HomeFeature.Action.sessionTapped>()
        expect(store.state.sessionDetail?.navigationID == navigationID)
        expect(store.state.sessionDetail?.draft == "Typed already\n\nhttps://example.com")
        store.finish()
    }

    @Test
    fun multipleSharesOpenTheNewestAndHoldTheRestForTheirSessions() {
        val store = makeStore(home())
        store.send(
            HomeFeature.Action.sharesReceived(
                listOf(
                    share(ShareHandoff.Target.session(id = target.id, projectID = other.id), text = "first", images = listOf(screenshot), at = 100),
                    share(ShareHandoff.Target.newSession(projectID = other.id), text = "second", at = 110),
                    share(ShareHandoff.Target.newSession(projectID = other.id), text = "third", at = 120),
                ),
            ),
        )
        expect(store.state.sessionDetail?.isDraft == true)
        expect(store.state.sessionDetail?.draftProject == other)
        expect(store.state.sessionDetail?.draft == "second\n\nthird")
        expect(store.state.sharedDrafts.keys.toList() == listOf(SharedDraftTarget.session(target.id)))

        store.send(HomeFeature.Action.sessionTapped(target.id))
        expect(store.state.sessionDetail?.draft == "first")
        expect(store.state.sessionDetail?.pendingAttachments?.map { it.id } == listOf(screenshot.id.toString()))
        expect(store.state.sharedDrafts.isEmpty())
        store.finish()
    }

    @Test
    fun shareForADeletedSessionStartsANewSessionInItsProject() {
        val store = makeStore(home())
        store.send(HomeFeature.Action.sharesReceived(listOf(share(ShareHandoff.Target.session(id = "ses_gone", projectID = other.id), text = "still useful", at = 100))))
        expect(store.state.sessionDetail?.isDraft == true)
        expect(store.state.sessionDetail?.draftProject == other)
        expect(store.state.sessionDetail?.draft == "still useful")
        store.finish()
    }

    @Test
    fun publishedTargetsFollowTheDrawerWithoutSecrets() {
        val state = home()
        state::pinnedSessionKeys.withLock { value = listOf(state.sessionKey(target.id)) }
        state.search = "no match"
        val targets = state.shareTargets
        expect(targets.serverID == server.persistenceID)
        expect(targets.serverName == "localhost:4096")
        expect(targets.lastViewedSessionID == current.id)
        expect(targets.pinned.map { it.id } == listOf(target.id))
        expect(targets.pinned.firstOrNull()?.projectName == "Other")
        expect(targets.recents.map { it.id } == listOf(current.id))
        expect(targets.newSessionProject == ShareTargets.Project(id = project.id, name = "Work"))
        val encoded = shareJson.encodeToString(ShareTargets.serializer(), targets)
        expect(!encoded.contains(server.password))
    }

    @Test
    fun appConsumesSharesOnActivationAndRoutesThemToTheOpenServer() {
        val persisted = AppFeature.State()
        persisted::savedServer.withLock { value = server }
        persisted::savedServers.withLock { value = listOf(server) }
        persisted::cachedHomeSnapshot.withLock {
            value = CachedHomeSnapshot(
                serverID = server.persistenceID,
                snapshot = OpenCodeHomeSnapshot(projects = listOf(project, other), sessions = listOf(current, target), activeSessionIDs = emptySet()),
            )
        }
        val pending = share(ShareHandoff.Target.newSession(projectID = project.id), text = "from Photos", images = listOf(screenshot), at = 100)
        val foreign = share(ShareHandoff.Target.newSession(projectID = null), text = "elsewhere", at = 90).also {
            it.handoff.serverID = "forgotten-server"
        }
        val shares = listOf(foreign, pending)
        val store = TestStore(
            initialState = { AppFeature.State() },
            withDependencies = { it.shareInbox.consume = { shares } },
        ) { AppFeature() }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)

        store.send(AppFeature.Action.pendingSharesRequested)
        store.receive<AppFeature.Action.sharesReceived>()
        store.receive({ it is AppFeature.Action.home && it.action is HomeFeature.Action.sharesReceived })
        expect(store.state.shareError != null)
        expect(store.state.pendingShares.isEmpty())
        expect(store.state.home?.sessionDetail?.isDraft == true)
        expect(store.state.home?.sessionDetail?.draft == "from Photos")
        expect(store.state.home?.sessionDetail?.pendingAttachments?.map { it.id } == listOf(screenshot.id.toString()))
        store.finish()
    }
}

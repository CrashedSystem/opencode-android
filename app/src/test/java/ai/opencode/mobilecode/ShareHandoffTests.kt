@file:PortedFrom("OpenCodeTests/ShareHandoffTests.swift", "553a407", partial = "store and image encoder; HomeFeature and AppFeature share routing waits for those features")

package ai.opencode.mobilecode

import ai.opencode.mobilecode.tca.expect
import android.graphics.Bitmap
import android.graphics.Color
import android.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Ignore
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

class ShareInboxFeatureTests {
    @Test
    @Ignore("OpenCode/HomeFeature.kt will port shareOpensItsSessionWithTheItemsInTheComposer")
    fun shareOpensItsSessionWithTheItemsInTheComposer() {}

    @Test
    @Ignore("OpenCode/HomeFeature.kt will port shareForTheOpenSessionKeepsWhatWasTyped")
    fun shareForTheOpenSessionKeepsWhatWasTyped() {}

    @Test
    @Ignore("OpenCode/HomeFeature.kt will port multipleSharesOpenTheNewestAndHoldTheRestForTheirSessions")
    fun multipleSharesOpenTheNewestAndHoldTheRestForTheirSessions() {}

    @Test
    @Ignore("OpenCode/HomeFeature.kt will port shareForADeletedSessionStartsANewSessionInItsProject")
    fun shareForADeletedSessionStartsANewSessionInItsProject() {}

    @Test
    @Ignore("OpenCode/HomeFeature.kt will port publishedTargetsFollowTheDrawerWithoutSecrets")
    fun publishedTargetsFollowTheDrawerWithoutSecrets() {}

    @Test
    @Ignore("OpenCode/AppFeature.kt will port appConsumesSharesOnActivationAndRoutesThemToTheOpenServer")
    fun appConsumesSharesOnActivationAndRoutesThemToTheOpenServer() {}
}

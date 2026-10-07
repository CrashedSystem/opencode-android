@file:PortedFrom("OpenCodeTests/ComposerItemProviderLoaderTests.swift", "553a407")

package ai.opencode.mobilecode

import java.io.File
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerItemProviderLoaderTests {
    private fun attachment(data: ByteArray, id: UUID) = OpenCodeOutgoingAttachment(id.toString(), data, "image/test")

    private suspend fun waitForImport(importer: ComposerImageImporter) {
        val start = System.currentTimeMillis()
        while (importer.isLoading && System.currentTimeMillis() - start < 2_000) delay(10)
    }

    @Test
    fun textViewRoutesImagePasteWithoutChangingText() {
        val view = ImagePasteTextView()
        view.text = "Keep this draft"
        val provider = NSItemProvider(registeredTypeIdentifiers = listOf("public.png"), data = byteArrayOf(1, 2, 3), canLoadImage = true)
        var pasted = emptyList<NSItemProvider>()
        view.imagePasteHandler = { pasted = it }
        view.paste(listOf(provider))
        assertEquals(1, pasted.size)
        assertEquals("Keep this draft", view.text)
    }

    @Test
    fun loadsFileBackedAndUIImageProviders() = runBlocking {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())
        val url = File(System.getProperty("java.io.tmpdir"), "${UUID.randomUUID()}.jpg")
        url.writeBytes(jpeg)
        try {
            val fileProvider = NSItemProvider(registeredTypeIdentifiers = listOf("public.jpeg"), file = url, canLoadImage = true)
            val fileURLProvider = NSItemProvider(registeredTypeIdentifiers = listOf("public.file-url"), file = url)
            val objectProvider = NSItemProvider(registeredTypeIdentifiers = listOf("public.image"), data = jpeg, canLoadImage = true)
            val loaded = listOf(fileProvider, fileURLProvider, objectProvider).mapNotNull { ComposerItemProviderLoader.load(it) }
            assertEquals(3, loaded.size)
            assertTrue(loaded.all { it.isNotEmpty() })
        } finally {
            url.delete()
        }
    }

    @Test
    fun imageImportPreservesMixedSourceOrder() = runBlocking {
        val provider = NSItemProvider(registeredTypeIdentifiers = listOf("public.png"), data = byteArrayOf(2), canLoadImage = true)
        val importer = ComposerImageImporter()
        val imported = mutableListOf<Byte>()
        importer.start(
            listOf(
                ComposerImageSource.data(byteArrayOf(1)),
                ComposerImageSource.itemProvider(provider),
                ComposerImageSource.loader { byteArrayOf(3) },
            ),
            process = { data, id -> attachment(data, id) },
            onAttachment = { imported += it.data[0] },
        )
        waitForImport(importer)
        assertEquals(listOf<Byte>(1, 2, 3), imported)
    }

    @Test
    fun imageImportEnforcesSelectionLimitOnce() = runBlocking {
        val importer = ComposerImageImporter()
        val imported = mutableListOf<Byte>()
        importer.start(
            (1..6).map { ComposerImageSource.data(byteArrayOf(it.toByte())) },
            process = { data, id -> attachment(data, id) },
            onAttachment = { imported += it.data[0] },
        )
        waitForImport(importer)
        assertEquals(listOf<Byte>(1, 2, 3, 4), imported)
    }

    @Test
    fun secondImageImportCancelsFirst() = runBlocking {
        val importer = ComposerImageImporter()
        val imported = mutableListOf<Byte>()
        importer.start(
            listOf(ComposerImageSource.loader {
                delay(10_000)
                byteArrayOf(1)
            }),
            process = { data, id -> attachment(data, id) },
            onAttachment = { imported += it.data[0] },
        )
        delay(30)
        importer.start(
            listOf(ComposerImageSource.data(byteArrayOf(2))),
            process = { data, id -> attachment(data, id) },
            onAttachment = { imported += it.data[0] },
        )
        waitForImport(importer)
        assertEquals(listOf<Byte>(2), imported)
    }

    @Test
    fun partialImageImportFailureKeepsSuccessfulImages() = runBlocking {
        val importer = ComposerImageImporter()
        val imported = mutableListOf<Byte>()
        importer.start(
            listOf(ComposerImageSource.data(byteArrayOf(1)), ComposerImageSource.loader { null }, ComposerImageSource.data(byteArrayOf(3))),
            process = { data, id -> attachment(data, id) },
            onAttachment = { imported += it.data[0] },
        )
        waitForImport(importer)
        assertEquals(listOf<Byte>(1, 3), imported)
    }

    @Test
    fun failedImportSettlesSoTheNextImportCanStart() = runBlocking {
        val importer = ComposerImageImporter()
        val results = mutableListOf<Int>()
        val imported = mutableListOf<Byte>()
        importer.start(listOf(ComposerImageSource.loader { null }), onAttachment = {}, onCompletion = { results += it.addedCount })
        assertTrue(importer.isLoading)
        waitForImport(importer)
        assertEquals(listOf(0), results)
        importer.start(
            listOf(ComposerImageSource.data(byteArrayOf(7))),
            process = { data, id -> attachment(data, id) },
            onAttachment = { imported += it.data[0] },
            onCompletion = { results += it.addedCount },
        )
        waitForImport(importer)
        assertEquals(listOf<Byte>(7), imported)
        assertEquals(listOf(0, 1), results)
    }

    @Test
    fun cancelledImportStopsLoadingAndNeverCompletes() = runBlocking {
        val importer = ComposerImageImporter()
        val gate = CompletableDeferred<Unit>()
        var completions = 0
        importer.start(
            listOf(ComposerImageSource.loader {
                gate.await()
                byteArrayOf(1)
            }),
            process = { data, id -> attachment(data, id) },
            onAttachment = { error("A cancelled import must not attach") },
            onCompletion = { completions += 1 },
        )
        delay(30)
        importer.cancel()
        assertFalse(importer.isLoading)
        gate.complete(Unit)
        delay(50)
        assertEquals(0, completions)
    }

    @Test
    fun attachmentCallbackCanEndTheImport() = runBlocking {
        for (supersede in listOf(false, true)) {
            val importer = ComposerImageImporter()
            var loads = 0
            var completions = 0
            val imported = mutableListOf<Byte>()
            importer.start(
                listOf(ComposerImageSource.data(byteArrayOf(1)), ComposerImageSource.loader { loads += 1; byteArrayOf(2) }),
                process = { data, id -> attachment(data, id) },
                onAttachment = { attachment ->
                    imported += attachment.data[0]
                    if (attachment.data[0] != 1.toByte()) return@start
                    if (supersede) {
                        importer.start(
                            listOf(ComposerImageSource.data(byteArrayOf(9))),
                            process = { data, id -> attachment(data, id) },
                            onAttachment = { imported += it.data[0] },
                        )
                    } else {
                        importer.cancel()
                    }
                },
                onCompletion = { completions += 1 },
            )
            waitForImport(importer)
            delay(30)
            assertEquals(0, loads)
            assertEquals(0, completions)
            assertEquals(if (supersede) listOf<Byte>(1, 9) else listOf<Byte>(1), imported)
        }
    }

    @Test
    fun composerOffersACallWhenVoiceIsSetUp() {
        val cases = listOf(
            listOf(false, false, false, false, false) to ComposerControls(ComposerTrailingControl.call, ComposerControls.MicPlacement.trailing, null),
            listOf(false, false, false, false, true) to ComposerControls(ComposerTrailingControl.call, ComposerControls.MicPlacement.leading, null),
            listOf(false, false, false, true, true) to ComposerControls(ComposerTrailingControl.send(true), ComposerControls.MicPlacement.leading, null),
            listOf(false, false, true, false, false) to ComposerControls(ComposerTrailingControl.stop, ComposerControls.MicPlacement.trailing, null),
            listOf(true, false, false, false, true) to ComposerControls(ComposerTrailingControl.mic, ComposerControls.MicPlacement.none, ComposerControls.DictationControls(false)),
        )
        for ((input, expected) in cases) {
            assertEquals(
                expected,
                ComposerControls.make(input[0], input[1], input[2], input[3], input[4], canCall = true),
            )
        }
    }

    @Test
    fun composerControlPresentation() {
        val cases = listOf(
            listOf(false, false, false, false, false) to ComposerControls(ComposerTrailingControl.mic, ComposerControls.MicPlacement.none, null),
            listOf(false, false, true, false, false) to ComposerControls(ComposerTrailingControl.stop, ComposerControls.MicPlacement.trailing, null),
            listOf(false, false, false, true, true) to ComposerControls(ComposerTrailingControl.send(true), ComposerControls.MicPlacement.leading, null),
            listOf(true, false, true, true, true) to ComposerControls(
                ComposerTrailingControl.send(true),
                ComposerControls.MicPlacement.none,
                ComposerControls.DictationControls(false),
            ),
            listOf(false, true, true, true, true) to ComposerControls(
                ComposerTrailingControl.send(true),
                ComposerControls.MicPlacement.none,
                ComposerControls.DictationControls(true),
            ),
        )
        for ((input, expected) in cases) {
            assertEquals(expected, ComposerControls.make(input[0], input[1], input[2], input[3], input[4]))
        }
    }
}

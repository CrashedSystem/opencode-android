@file:PortedFrom("OpenCodeShare/ShareViewController.swift", "553a407")

package ai.opencode.mobilecode

import ai.opencode.mobilecode.system.SystemTheme
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import java.io.File
import java.util.UUID
import kotlinx.coroutines.launch

/// The share sheet's principal class: hosts `ShareView` and reports back to
/// the extension context. Nothing is sent from here; see ShareHandoff.swift.
class ShareViewController : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installDiagnosticsStorage(applicationContext)
        val model = ShareModel(targets = ShareTargets.load(), store = ShareHandoffStore.live)
        setContent {
            SystemTheme {
                ShareView(
                    model = model,
                    onCancel = {
                        model.discardFiles()
                        finish()
                    },
                    onAdded = { finish() },
                )
            }
        }
        val providers = providers(intent)
        lifecycleScope.launch { model.load(providers) }
    }

    private fun providers(intent: Intent): List<NSItemProvider> {
        val providers = mutableListOf<NSItemProvider>()
        intent.getStringExtra(Intent.EXTRA_TEXT)?.let { text ->
            val trimmed = text.trim()
            val http = trimmed.startsWith("http://") || trimmed.startsWith("https://")
            providers += NSItemProvider(
                registeredTypeIdentifiers = listOf(if (http) "public.url" else "public.plain-text"),
                data = trimmed.toByteArray(),
            )
        }
        val streams = mutableListOf<Uri>()
        intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)?.let { streams += it }
        @Suppress("DEPRECATION")
        intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { streams += it }
        intent.clipData?.let { clip ->
            for (index in 0 until clip.itemCount) {
                clip.getItemAt(index).uri?.let { streams += it }
            }
        }
        intent.getStringExtra(Intent.EXTRA_STREAM)?.let { path ->
            val file = File(path)
            if (file.isFile) providers += fileProvider(file, intent.type)
        }
        val type = intent.type
        for (stream in streams.distinct()) {
            streamProvider(stream, type)?.let { providers += it }
        }
        return providers
    }

    private fun streamProvider(uri: Uri, intentType: String?): NSItemProvider? {
        if (uri.scheme == "file") {
            val file = File(uri.path ?: return null)
            if (!file.isFile) return null
            return fileProvider(file, intentType)
        }
        val copied = copy(uri) ?: return null
        val mime = contentResolver.getType(uri) ?: intentType
        return fileProvider(copied, mime)
    }

    private fun fileProvider(file: File, mime: String?): NSItemProvider {
        val type = mime ?: "application/octet-stream"
        val identifiers = if (type.startsWith("image/")) listOf("public.image", type) else listOf(type, "public.data")
        return NSItemProvider(
            registeredTypeIdentifiers = identifiers,
            file = file,
            canLoadImage = type.startsWith("image/"),
            suggestedName = file.name,
        )
    }

    private fun copy(uri: Uri): File? {
        val name = displayName(uri) ?: "shared"
        val destination = File(cacheDir, "share-incoming/${UUID.randomUUID()}/$name")
        destination.parentFile?.mkdirs()
        val input = contentResolver.openInputStream(uri) ?: return null
        input.use { source -> destination.outputStream().use { source.copyTo(it) } }
        return destination
    }

    private fun displayName(uri: Uri): String? {
        val cursor = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null) ?: return null
        cursor.use {
            if (!it.moveToFirst()) return null
            val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index < 0) return null
            return it.getString(index)
        }
    }
}

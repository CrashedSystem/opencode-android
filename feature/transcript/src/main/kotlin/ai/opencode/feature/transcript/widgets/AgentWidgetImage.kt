package ai.opencode.feature.transcript.widgets

import ai.opencode.core.dependencies.dependency
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeShape
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.SFSymbol
import ai.opencode.feature.media.DecodedImageCache
import ai.opencode.feature.transcript.LocalTranscriptRowContext
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeServer
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.size.Size
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A widget `image`: web images through Coil, `data:` URIs and paths relative to the session's
 * folder through the server client's Markdown image reader (the server's file endpoint), decoded
 * no larger than the box it fills and kept in the app's decoded image cache. It reserves its
 * box (`aspect`, else 4:3) before the pixels arrive.
 */
@Composable
fun AgentWidgetImage(src: String, alt: String?, aspect: Float?, modifier: Modifier = Modifier) {
    val row = LocalTranscriptRowContext.current
    val server = row.server
    val directory = row.directory
    val colors = OpenCodeTheme.colors
    val context = LocalContext.current
    val density = LocalDensity.current
    val client = remember { dependency(OpenCodeClient) }
    val decoded = remember { dependency(DecodedImageCache) }
    val ratio = aspect ?: (4f / 3f)
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .aspectRatio(ratio)
            .heightIn(max = 420.dp)
            .clip(OpenCodeShape.card)
            .background(colors.userBubble)
            .semantics { if (alt != null) contentDescription = alt },
        contentAlignment = Alignment.Center,
    ) {
        val widthPx = with(density) { maxWidth.roundToPx() }.coerceIn(64, 1440)
        val key = remember(server, directory, src, widthPx) {
            val base = server?.let { OpenCodeClient.markdownImageKey(it, directory, src) } ?: src
            "widget:$widthPx:$base"
        }
        var image by remember(key) { mutableStateOf(decoded.image(key)?.asImageBitmap()) }
        var failed by remember(key) { mutableStateOf(false) }
        LaunchedEffect(key) {
            if (image != null) return@LaunchedEffect
            val bitmap = withContext(Dispatchers.IO) { runCatching { load(context, client, server, directory, src, widthPx) }.getOrNull() }
            if (bitmap == null) {
                failed = true
            } else {
                decoded.insert(bitmap, key)
                image = bitmap.asImageBitmap()
            }
        }
        val shown: ImageBitmap? = image
        when {
            shown != null -> Image(shown, contentDescription = null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            failed -> Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OpenCodeIcon(SFSymbol.PhotoBadgeExclamationmark, contentDescription = null, tint = colors.tertiaryText)
                Text(alt ?: src.substringAfterLast('/'), style = OpenCodeTextStyles.caption1, color = colors.secondaryText, textAlign = TextAlign.Center, maxLines = 2)
            }
            else -> Box(Modifier.fillMaxSize())
        }
    }
}

private suspend fun load(context: Context, client: OpenCodeClient, server: OpenCodeServer?, directory: String?, src: String, widthPx: Int): Bitmap? {
    if (src.startsWith("http://", ignoreCase = true) || src.startsWith("https://", ignoreCase = true)) {
        val result = SingletonImageLoader.get(context).execute(ImageRequest.Builder(context).data(src).size(Size(widthPx, widthPx)).build())
        return (result as? SuccessResult)?.image?.toBitmap()
    }
    val server = server ?: return null
    val bytes = client.cachedMarkdownImage(OpenCodeClient.markdownImageKey(server, directory, src))
        ?: client.markdownImage(server, directory, src, null)
        ?: return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= widthPx) sample *= 2
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
}

package ai.opencode.mobilecode

import android.net.Uri
import android.widget.photopicker.EmbeddedPhotoPickerFeatureInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.photopicker.compose.EmbeddedPhotoPicker
import androidx.photopicker.compose.ExperimentalPhotoPickerComposeApi
import androidx.photopicker.compose.rememberEmbeddedPhotoPickerState

@OptIn(ExperimentalPhotoPickerComposeApi::class)
@Composable
fun EmbeddedPhotoPickerHost(
    selection: InlinePhotoSelection,
    filter: InlinePhotoFilter,
    includesVideos: Boolean,
    onPhotos: (List<PhotosPickerItem>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val mimeTypes = when (filter) {
        InlinePhotoFilter.videos -> arrayOf("video/*")
        InlinePhotoFilter.screenshots, InlinePhotoFilter.all -> if (includesVideos) {
            arrayOf("image/*", "video/*")
        } else {
            arrayOf("image/*")
        }
    }
    val info = remember(filter, includesVideos) {
        EmbeddedPhotoPickerFeatureInfo.Builder()
            .setMaxSelectionLimit(AttachmentPickerButton.maxPhotoSelection)
            .setOrderedSelection(true)
            .setMimeTypes(mimeTypes.toList())
            .build()
    }
    val state = rememberEmbeddedPhotoPickerState(
        onUriPermissionGranted = { uris: List<Uri> ->
            val picks = uris.map { uri ->
                PhotosPickerItem(uri.toString(), supportedContentTypes = mimeTypes.toList(), uri = uri)
            }
            selection.items = (selection.items + picks).distinctBy { it.itemIdentifier }
            onPhotos(selection.reconcile().added.map { it.item })
        },
        onUriPermissionRevoked = { uris: List<Uri> ->
            val gone = uris.map { it.toString() }.toSet()
            selection.items = selection.items.filter { it.itemIdentifier !in gone }
            selection.reconcile()
        },
        onSelectionComplete = {},
    )
    EmbeddedPhotoPicker(state = state, modifier = modifier, embeddedPhotoPickerFeatureInfo = info)
}

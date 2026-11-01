package com.halfblanket.cloudly.ui.common

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.halfblanket.cloudly.data.db.AccountEntity
import com.halfblanket.cloudly.ui.components.AccountBadge
import com.halfblanket.cloudly.ui.components.SheetAction
import com.halfblanket.cloudly.ui.theme.CloudlyTheme

class UploadLaunchers(val pickMedia: () -> Unit, val pickFiles: () -> Unit)

/** Android Photo Picker for photos/videos and the Storage Access Framework for any file. */
@Composable
fun rememberUploadLaunchers(onPicked: (List<Uri>) -> Unit): UploadLaunchers {
    val media = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { onPicked(it) }
    val docs = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { onPicked(it) }
    return remember(media, docs) {
        UploadLaunchers(
            pickMedia = { media.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
            pickFiles = { docs.launch(arrayOf("*/*")) },
        )
    }
}

/** "New" sheet: upload photos/videos, upload files, or create a folder — in a named account and folder. */
@Composable
fun NewItemSheet(
    account: AccountEntity?,
    destination: String,
    launchers: UploadLaunchers,
    onNewFolder: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            Text("Add to $destination", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp))
            Spacer(Modifier.height(6.dp))
            AccountBadge(account, Modifier.padding(horizontal = 24.dp))
            Spacer(Modifier.height(12.dp))
            SheetAction(Icons.Rounded.PhotoLibrary, "Upload photos & videos", { onDismiss(); launchers.pickMedia() },
                supporting = "Choose with the Android photo picker")
            SheetAction(Icons.Rounded.UploadFile, "Upload files", { onDismiss(); launchers.pickFiles() },
                supporting = "Browse any file on this device")
            if (onNewFolder != null) {
                SheetAction(Icons.Rounded.CreateNewFolder, "New folder", { onDismiss(); onNewFolder() })
            }
            Text(
                "Uploads continue in the background, even if you leave Cloudly.",
                style = MaterialTheme.typography.bodySmall,
                color = CloudlyTheme.extra.muted,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
        }
    }
}

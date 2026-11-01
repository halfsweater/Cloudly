package com.halfblanket.cloudly.ui.files

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DriveFileMove
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.OpenWith
import androidx.compose.material.icons.rounded.RestoreFromTrash
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarOutline
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.halfblanket.cloudly.core.Format
import com.halfblanket.cloudly.data.db.AccountEntity
import com.halfblanket.cloudly.data.drive.DriveFile
import com.halfblanket.cloudly.data.drive.MimeTypes
import com.halfblanket.cloudly.data.repo.AccountsState
import com.halfblanket.cloudly.ui.common.UiMessages
import com.halfblanket.cloudly.ui.components.AccountBadge
import com.halfblanket.cloudly.ui.components.AccountRow
import com.halfblanket.cloudly.ui.components.ConfirmDialog
import com.halfblanket.cloudly.ui.components.FileThumbnail
import com.halfblanket.cloudly.ui.components.SheetAction
import com.halfblanket.cloudly.ui.components.TextInputDialog
import com.halfblanket.cloudly.ui.components.color
import com.halfblanket.cloudly.ui.components.fileSubtitle
import com.halfblanket.cloudly.ui.theme.CloudlyTheme

/**
 * Renders every dialog and sheet owned by [FileActionsViewModel] and handles its events. Screens place
 * this once and call `actions.show(...)` to open the menu for a file.
 */
@Composable
fun FileActionsHost(
    actions: FileActionsViewModel,
    accounts: AccountsState,
    onOpen: (DriveFile) -> Unit,
    onChanged: () -> Unit,
) {
    val context = LocalContext.current
    val dialog by actions.dialog.collectAsStateWithLifecycle()
    val busy by actions.busy.collectAsStateWithLifecycle()
    val changed by rememberUpdatedState(onChanged)

    LaunchedEffect(actions) { actions.changed.collect { changed() } }
    LaunchedEffect(actions) {
        actions.events.collect { e ->
            val intent = when (e) {
                is ActionEvent.Share -> e.intent
                is ActionEvent.OpenWith -> e.intent
                is ActionEvent.OpenUrl -> Intent(Intent.ACTION_VIEW, Uri.parse(e.url))
            }
            try {
                context.startActivity(intent)
            } catch (_: ActivityNotFoundException) {
                UiMessages.show("No app on this device can open that")
            }
        }
    }

    when (val d = dialog) {
        null -> Unit
        is ActionDialog.Menu -> FileMenuSheet(d.file, accounts, actions, onOpen)
        is ActionDialog.Rename -> TextInputDialog(
            title = "Rename",
            initial = d.file.name,
            confirmLabel = "Rename",
            selectBaseName = !d.file.isFolder,
            onConfirm = { actions.rename(d.file, it) },
            onDismiss = actions::dismiss,
        )
        is ActionDialog.ConfirmTrash -> ConfirmDialog(
            title = if (d.files.size == 1) "Move to trash?" else "Move ${d.files.size} items to trash?",
            message = "Items stay in ${accounts.byId(d.files.first().accountId)?.email ?: "the account"}'s trash for 30 days " +
                "and can be restored from Storage → Trash.",
            confirmLabel = "Move to trash",
            icon = Icons.Rounded.DeleteOutline,
            onConfirm = { actions.trash(d.files) },
            onDismiss = actions::dismiss,
        )
        is ActionDialog.ConfirmDeleteForever -> ConfirmDialog(
            title = if (d.files.size == 1) "Delete forever?" else "Delete ${d.files.size} items forever?",
            message = "This permanently deletes ${if (d.files.size == 1) "“${d.files.first().name}”" else "these items"} from " +
                "${accounts.byId(d.files.first().accountId)?.email ?: "the account"}. It can't be undone.",
            confirmLabel = "Delete forever",
            destructive = true,
            icon = Icons.Rounded.DeleteForever,
            onConfirm = { actions.deleteForever(d.files) },
            onDismiss = actions::dismiss,
        )
        is ActionDialog.Details -> FileDetailsSheet(d.file, accounts.byId(d.file.accountId), actions::dismiss)
        is ActionDialog.PickAccount -> PickAccountDialog(d, accounts, actions)
        is ActionDialog.PickFolder -> FolderPickerSheet(
            accountId = d.accountId,
            account = accounts.byId(d.accountId),
            title = when (d.mode) {
                PickMode.MOVE, PickMode.COPY -> "${d.mode.verb} ${if (d.files.size == 1) "“${d.files.first().name}”" else "${d.files.size} items"}"
                PickMode.CROSS_COPY, PickMode.CROSS_MOVE -> "${d.mode.verb} to ${accounts.byId(d.accountId)?.email ?: "account"}"
            },
            confirmLabel = "${d.mode.verb} here",
            excludeIds = if (d.mode == PickMode.MOVE || d.mode == PickMode.COPY) d.files.filter { it.isFolder }.map { it.id }.toSet() else emptySet(),
            onPick = { id, name -> actions.moveOrCopy(d.files, d.mode, d.accountId, id, name) },
            onDismiss = actions::dismiss,
        )
        is ActionDialog.ConfirmCross -> {
            val from = accounts.byId(d.files.first().accountId)
            val to = accounts.byId(d.targetAccountId)
            ConfirmDialog(
                title = if (d.move) "Move to another account?" else "Copy to another account?",
                message = buildString {
                    append(if (d.files.size == 1) "“${d.files.first().name}”" else "${d.files.size} items")
                    append(" will be copied from ${from?.email} to ${to?.email} › ${d.targetFolderName}. ")
                    append("Files are downloaded and re-uploaded in the background; Google Docs, Sheets and Slides are converted ")
                    append("and recreated. Sharing settings and comments aren't carried over.")
                    if (d.move) append("\n\nOriginals are moved to ${from?.email}'s trash only after everything is copied successfully.")
                },
                confirmLabel = if (d.move) "Move" else "Copy",
                icon = Icons.Rounded.SwapHoriz,
                onConfirm = { actions.startCrossTransfer(d) },
                onDismiss = actions::dismiss,
            )
        }
    }

    busy?.let { b ->
        AlertDialog(
            onDismissRequest = {},
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = { Text(b.label, style = MaterialTheme.typography.titleMedium) },
            text = {
                Column {
                    if (b.progress != null) LinearProgressIndicator(progress = { b.progress }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = { TextButton(onClick = actions::cancelBusy) { Text("Cancel") } },
        )
    }
}

@Composable
private fun FileMenuSheet(file: DriveFile, accounts: AccountsState, actions: FileActionsViewModel, onOpen: (DriveFile) -> Unit) {
    val account = accounts.byId(file.accountId)
    val others = accounts.accounts.filter { it.id != file.accountId }
    val caps = file.capabilities
    val exportable = !file.isFolder && (!file.isGoogleNative || MimeTypes.exportFor(file.mimeType) != null)
    ModalBottomSheet(
        onDismissRequest = actions::dismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.navigationBarsPadding().verticalScroll(rememberScrollState())) {
            Row(Modifier.padding(horizontal = 24.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                FileThumbnail(file, Modifier.size(48.dp).clip(MaterialTheme.shapes.small), iconSize = 48.dp, thumbPx = 144)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(file.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        AccountBadge(account)
                        Text(fileSubtitle(file), style = MaterialTheme.typography.bodySmall, color = CloudlyTheme.extra.muted, maxLines = 1)
                    }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = CloudlyTheme.extra.line)
            if (file.trashed) {
                SheetAction(Icons.Rounded.RestoreFromTrash, "Restore", { actions.restore(listOf(file)) }, enabled = caps.canUntrash)
                SheetAction(Icons.Rounded.Info, "Details", { actions.show(ActionDialog.Details(file)) })
                SheetAction(
                    Icons.Rounded.DeleteForever, "Delete forever",
                    { actions.show(ActionDialog.ConfirmDeleteForever(listOf(file))) },
                    tint = CloudlyTheme.extra.danger, enabled = caps.canDelete,
                )
                return@Column
            }
            if (file.isFolder) {
                SheetAction(Icons.Rounded.FolderOpen, "Open folder", { actions.dismiss(); onOpen(file) })
            } else {
                SheetAction(Icons.Rounded.Visibility, "Preview", { actions.dismiss(); onOpen(file) })
            }
            if (exportable) {
                SheetAction(Icons.Rounded.Share, "Share file", { actions.share(file) }, supporting = "Send a copy through Android sharing")
            }
            SheetAction(Icons.Rounded.Link, "Share link", { actions.shareLink(file) }, enabled = file.webViewLink != null)
            if (exportable) {
                SheetAction(
                    Icons.Rounded.Download, "Download",
                    { actions.download(listOf(file)) },
                    enabled = caps.canDownload,
                    supporting = MimeTypes.exportFor(file.mimeType)?.let { "Saved as .${it.extension}" },
                )
                SheetAction(Icons.Rounded.OpenWith, "Open with…", { actions.openWith(file) }, enabled = caps.canDownload)
            }
            SheetAction(Icons.AutoMirrored.Rounded.OpenInNew, "Open in Google Drive", { actions.openInDrive(file) }, enabled = file.webViewLink != null)
            SheetAction(
                if (file.starred) Icons.Rounded.Star else Icons.Rounded.StarOutline,
                if (file.starred) "Remove from Starred" else "Add to Starred",
                { actions.setStarred(listOf(file), !file.starred) },
            )
            SheetAction(Icons.Rounded.DriveFileRenameOutline, "Rename", { actions.show(ActionDialog.Rename(file)) }, enabled = caps.canRename)
            SheetAction(
                Icons.AutoMirrored.Rounded.DriveFileMove, "Move",
                { actions.show(ActionDialog.PickFolder(listOf(file), PickMode.MOVE, file.accountId)) },
                enabled = caps.canMove,
            )
            SheetAction(
                Icons.Rounded.ContentCopy, "Make a copy",
                { actions.show(ActionDialog.PickFolder(listOf(file), PickMode.COPY, file.accountId)) },
                enabled = file.isFolder || caps.canCopy,
            )
            if (others.isNotEmpty()) {
                SheetAction(
                    Icons.Rounded.CloudSync, "Copy to another account",
                    { actions.show(ActionDialog.PickAccount(listOf(file), move = false)) },
                    enabled = file.isFolder || caps.canDownload,
                )
                SheetAction(
                    Icons.Rounded.SwapHoriz, "Move to another account",
                    { actions.show(ActionDialog.PickAccount(listOf(file), move = true)) },
                    enabled = caps.canTrash && (file.isFolder || caps.canDownload),
                )
            }
            SheetAction(Icons.Rounded.Info, "Details", { actions.show(ActionDialog.Details(file)) })
            SheetAction(
                Icons.Rounded.DeleteOutline, "Move to trash",
                { actions.show(ActionDialog.ConfirmTrash(listOf(file))) },
                tint = CloudlyTheme.extra.danger, enabled = caps.canTrash,
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun PickAccountDialog(d: ActionDialog.PickAccount, accounts: AccountsState, actions: FileActionsViewModel) {
    val source = d.files.first().accountId
    val targets = accounts.accounts.filter { it.id != source }
    AlertDialog(
        onDismissRequest = actions::dismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = { Text(if (d.move) "Move to which account?" else "Copy to which account?") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text(
                    "From ${accounts.byId(source)?.email}",
                    style = MaterialTheme.typography.bodySmall,
                    color = CloudlyTheme.extra.muted,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                targets.forEach { acc ->
                    AccountRow(acc, selected = false, onClick = {
                        actions.show(
                            ActionDialog.PickFolder(d.files, if (d.move) PickMode.CROSS_MOVE else PickMode.CROSS_COPY, acc.id),
                        )
                    })
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = actions::dismiss) { Text("Cancel") } },
    )
}

@Composable
fun FileDetailsSheet(file: DriveFile, account: AccountEntity?, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.navigationBarsPadding().verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            Box(
                Modifier.padding(horizontal = 20.dp).fillMaxWidth().height(180.dp).clip(MaterialTheme.shapes.large)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                FileThumbnail(file, Modifier.fillMaxWidth().height(180.dp), iconSize = 72.dp, thumbPx = 720)
            }
            Text(file.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp))
            if (account != null) {
                Row(
                    Modifier.padding(horizontal = 20.dp).fillMaxWidth().clip(MaterialTheme.shapes.medium)
                        .background(account.color.copy(alpha = 0.10f)).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    com.halfblanket.cloudly.ui.components.AccountAvatar(account, size = 28.dp)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("In account", style = MaterialTheme.typography.labelSmall, color = CloudlyTheme.extra.muted)
                        Text(account.email, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            DetailRow("Type", describeType(file))
            if (!file.isFolder) DetailRow("Size", file.size?.let { "${Format.bytes(it)} (${"%,d".format(it)} bytes)" } ?: "—")
            if (file.quotaBytesUsed != null && file.quotaBytesUsed != file.size) DetailRow("Storage used", Format.bytes(file.quotaBytesUsed))
            DetailRow("Owner", if (file.ownedByMe) "You" else listOfNotNull(file.ownerName, file.ownerEmail).joinToString(" · ").ifEmpty { "—" })
            DetailRow("Modified", Format.dateTime(file.modifiedTime) + (file.lastModifiedBy?.let { " by $it" } ?: ""))
            DetailRow("Created", Format.dateTime(file.createdTime))
            DetailRow("Last opened by you", Format.dateTime(file.viewedByMeTime))
            if (file.imageWidth != null && file.imageHeight != null) DetailRow("Dimensions", "${file.imageWidth} × ${file.imageHeight}")
            if (file.videoDurationMillis != null) DetailRow("Duration", Format.duration(file.videoDurationMillis))
            DetailRow("Shared", if (file.shared) "Yes" else "No")
            DetailRow("Starred", if (file.starred) "Yes" else "No")
            if (!file.description.isNullOrBlank()) DetailRow("Description", file.description)
            if (file.md5Checksum != null) DetailRow("MD5", file.md5Checksum)
            DetailRow("File ID", file.id)
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 9.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = CloudlyTheme.extra.muted, modifier = Modifier.width(140.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

fun describeType(file: DriveFile): String = when (file.mimeType) {
    MimeTypes.FOLDER -> "Folder"
    MimeTypes.G_DOC -> "Google Docs"
    MimeTypes.G_SHEET -> "Google Sheets"
    MimeTypes.G_SLIDES -> "Google Slides"
    MimeTypes.G_DRAWING -> "Google Drawings"
    MimeTypes.G_FORM -> "Google Forms"
    MimeTypes.SHORTCUT -> "Shortcut"
    else -> buildString {
        append(file.category.label.removeSuffix("s").let { if (it == "Other") "File" else it })
        file.fileExtension?.let { append(" · .").append(it.lowercase()) }
        append(" · ").append(file.mimeType)
    }
}

@Composable
fun SelectionConfirmButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().height(52.dp), shape = MaterialTheme.shapes.medium) {
        Text(text)
    }
}

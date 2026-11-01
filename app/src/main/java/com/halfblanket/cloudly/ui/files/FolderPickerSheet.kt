package com.halfblanket.cloudly.ui.files

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.halfblanket.cloudly.core.userMessage
import com.halfblanket.cloudly.data.db.AccountEntity
import com.halfblanket.cloudly.data.drive.DriveFile
import com.halfblanket.cloudly.data.drive.DriveQuery
import com.halfblanket.cloudly.ui.common.UiMessages
import com.halfblanket.cloudly.ui.common.container
import com.halfblanket.cloudly.ui.components.AccountBadge
import com.halfblanket.cloudly.ui.components.EmptyState
import com.halfblanket.cloudly.ui.components.TextInputDialog
import com.halfblanket.cloudly.ui.theme.CloudlyTheme
import com.halfblanket.cloudly.ui.theme.categoryColor
import com.halfblanket.cloudly.data.drive.FileCategory
import kotlinx.coroutines.launch

private data class PickerCrumb(val id: String, val name: String)

/**
 * Browses the folders of exactly one account ([accountId]) to choose a destination. The account is fixed
 * for the lifetime of the sheet, so a destination can never silently land in a different account.
 */
@Composable
fun FolderPickerSheet(
    accountId: String,
    account: AccountEntity?,
    title: String,
    confirmLabel: String,
    excludeIds: Set<String>,
    onPick: (folderId: String, folderName: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val repo = LocalContext.current.container.driveRepository
    val scope = rememberCoroutineScope()
    val crumbs = remember { mutableStateListOf<PickerCrumb>() }
    var folders by remember { mutableStateOf<List<DriveFile>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(accountId) {
        runCatching { repo.rootId(accountId) }
            .onSuccess { crumbs.clear(); crumbs.add(PickerCrumb(it, "My Drive")) }
            .onFailure { error = it.userMessage(); loading = false }
    }
    val current = crumbs.lastOrNull()
    LaunchedEffect(current?.id, reload) {
        val folderId = current?.id ?: return@LaunchedEffect
        loading = true
        error = null
        runCatching { repo.listAll(accountId, DriveQuery.childFolders(folderId), max = 2000) }
            .onSuccess { list -> folders = list.filter { it.id !in excludeIds }.sortedBy { it.name.lowercase() } }
            .onFailure { error = it.userMessage() }
        loading = false
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.fillMaxHeight(0.88f).navigationBarsPadding()) {
            Row(Modifier.padding(start = 24.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(4.dp))
                    AccountBadge(account)
                }
                IconButton(onClick = { creating = true }, enabled = current != null) {
                    Icon(Icons.Rounded.CreateNewFolder, "New folder")
                }
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                crumbs.forEachIndexed { i, c ->
                    if (i > 0) Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = CloudlyTheme.extra.muted, modifier = Modifier.size(18.dp))
                    TextButton(onClick = { while (crumbs.size > i + 1) crumbs.removeAt(crumbs.lastIndex) }) {
                        Text(
                            c.name,
                            color = if (i == crumbs.lastIndex) MaterialTheme.colorScheme.onSurface else CloudlyTheme.extra.muted,
                            maxLines = 1,
                        )
                    }
                }
            }
            Box(Modifier.weight(1f)) {
                when {
                    loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    error != null -> EmptyState(Icons.Rounded.FolderOff, "Couldn't load folders", error!!, actionLabel = "Retry", onAction = { reload++ })
                    folders.isEmpty() -> EmptyState(Icons.Rounded.Folder, "No subfolders", "You can place items right here, or create a new folder.")
                    else -> LazyColumn {
                        items(folders, key = { it.id }) { f ->
                            Row(
                                Modifier.fillMaxWidth().clickable { crumbs.add(PickerCrumb(f.id, f.name)) }
                                    .padding(horizontal = 24.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Rounded.Folder, null, tint = categoryColor(FileCategory.FOLDER))
                                Spacer(Modifier.width(16.dp))
                                Text(f.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = CloudlyTheme.extra.muted)
                            }
                        }
                    }
                }
            }
            Box(Modifier.padding(16.dp)) {
                SelectionConfirmButton(
                    text = "$confirmLabel${current?.let { " · ${it.name}" } ?: ""}",
                    enabled = current != null,
                    onClick = { current?.let { onPick(it.id, it.name) } },
                )
            }
        }
    }

    if (creating && current != null) {
        TextInputDialog(
            title = "New folder",
            initial = "Untitled folder",
            confirmLabel = "Create",
            onConfirm = { name ->
                creating = false
                scope.launch {
                    runCatching { repo.createFolder(accountId, current.id, name) }
                        .onSuccess { crumbs.add(PickerCrumb(it.id, it.name)) }
                        .onFailure { UiMessages.show(it.userMessage()) }
                }
            },
            onDismiss = { creating = false },
        )
    }
}

package com.halfblanket.cloudly.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.StarOutline
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.halfblanket.cloudly.core.Format
import com.halfblanket.cloudly.data.db.AccountEntity
import com.halfblanket.cloudly.data.db.DriveAccess
import com.halfblanket.cloudly.data.drive.DriveFile
import com.halfblanket.cloudly.data.drive.FileCategory
import com.halfblanket.cloudly.data.repo.AccountsState
import com.halfblanket.cloudly.ui.common.AccountConnector
import com.halfblanket.cloudly.ui.common.NewItemSheet
import com.halfblanket.cloudly.ui.common.cloudlyViewModel
import com.halfblanket.cloudly.ui.common.rememberUploadLaunchers
import com.halfblanket.cloudly.ui.components.AccountAvatar
import com.halfblanket.cloudly.ui.components.CloudlyTopBar
import com.halfblanket.cloudly.ui.components.SearchPill
import com.halfblanket.cloudly.ui.components.Banner
import com.halfblanket.cloudly.ui.components.BarSegment
import com.halfblanket.cloudly.ui.components.CloudlyCard
import com.halfblanket.cloudly.ui.components.FileListItem
import com.halfblanket.cloudly.ui.components.FileRailCard
import com.halfblanket.cloudly.ui.components.LegendDot
import com.halfblanket.cloudly.ui.components.SectionHeader
import com.halfblanket.cloudly.ui.components.SegmentedBar
import com.halfblanket.cloudly.ui.components.StorageBar
import com.halfblanket.cloudly.ui.components.TextInputDialog
import com.halfblanket.cloudly.ui.components.color
import com.halfblanket.cloudly.ui.files.ActionDialog
import com.halfblanket.cloudly.ui.files.FileActionsHost
import com.halfblanket.cloudly.ui.files.FileActionsViewModel
import com.halfblanket.cloudly.ui.theme.CloudlyTheme
import com.halfblanket.cloudly.ui.theme.categoryColor
import java.time.LocalTime

@Composable
fun HomeScreen(
    accounts: AccountsState,
    activeTransfers: Int,
    connector: AccountConnector,
    onOpenAccounts: () -> Unit,
    onSwitchAccount: (AccountEntity) -> Unit,
    onSearch: () -> Unit,
    onTransfers: () -> Unit,
    onStorage: () -> Unit,
    onOpenFolder: (DriveFile) -> Unit,
    onPreview: (DriveFile) -> Unit,
    onSeeAllFiles: () -> Unit,
    onOpenPhotos: () -> Unit = {},
) {
    val vm = cloudlyViewModel { HomeViewModel(it) }
    val actions = cloudlyViewModel { FileActionsViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val account = accounts.active
    var showNew by rememberSaveable { mutableStateOf(false) }
    var newFolder by rememberSaveable { mutableStateOf(false) }
    val launchers = rememberUploadLaunchers { vm.upload(it) }
    val open: (DriveFile) -> Unit = { if (it.isFolder) onOpenFolder(it) else onPreview(it) }
    val menu: (DriveFile) -> Unit = { actions.show(ActionDialog.Menu(it)) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showNew = true },
                icon = { Icon(Icons.Rounded.CloudUpload, null) },
                text = { Text("Upload") },
                shape = MaterialTheme.shapes.large,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        },
    ) { padding ->
        PullToRefreshBox(state.refreshing, onRefresh = vm::refresh, modifier = Modifier.padding(padding).fillMaxSize()) {
            LazyColumn(contentPadding = PaddingValues(bottom = 100.dp), modifier = Modifier.fillMaxSize()) {
                item {
                    CloudlyTopBar(
                        title = "Hi, " + (account?.displayName?.substringBefore(' ') ?: "there"),
                        subtitle = greeting(),
                        account = account,
                        onAvatar = onOpenAccounts,
                    ) {
                        if (activeTransfers > 0) {
                            IconButton(onClick = onTransfers) {
                                BadgedBox(badge = { Badge { Text("$activeTransfers") } }) { Icon(Icons.Rounded.SwapVert, "Transfers") }
                            }
                        }
                    }
                    SearchPill(if (accounts.accounts.size > 1) "Search all your Drives" else "Search in Drive", onSearch)
                    QuickActions(
                        onUpload = { showNew = true },
                        onPhotos = onOpenPhotos,
                        onFiles = onSeeAllFiles,
                    )
                }
                if (account != null) {
                    item { StorageCard(account, onStorage) }
                    if (account.needsReauth) {
                        item {
                            Banner(Icons.Rounded.ErrorOutline, "Access for ${account.email} needs to be renewed.",
                                color = CloudlyTheme.extra.danger, actionLabel = "Reconnect", onAction = { connector.reconnect(account) })
                        }
                    } else if (account.access == DriveAccess.APP_FILES_ONLY) {
                        item {
                            Banner(Icons.Rounded.Lock, "Limited access — only files created with Cloudly are visible.",
                                actionLabel = "Allow full", onAction = { connector.upgradeToFull(account) })
                        }
                    }
                }
                if (accounts.accounts.size > 1) {
                    item { SectionHeader("All accounts") }
                    item {
                        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(accounts.accounts, key = { it.id }) { acc ->
                                AccountMiniCard(acc, acc.id == account?.id) { onSwitchAccount(acc) }
                            }
                        }
                    }
                }

                item { SectionHeader("Quick access", action = "All files", onAction = onSeeAllFiles) }
                item {
                    RailOrHint(state.quickFolders, Icons.Rounded.Folder, "No folders in My Drive yet") {
                        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(state.quickFolders.items, key = { it.id }) { f -> FolderChip(f, { open(f) }) }
                        }
                    }
                }

                item { SectionHeader("Recent") }
                item {
                    RailOrHint(state.recent, Icons.Rounded.History, "Files you open will appear here") {
                        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(state.recent.items, key = { it.id }) { f -> FileRailCard(f, { open(f) }, { menu(f) }) }
                        }
                    }
                }

                item { SectionHeader("Starred") }
                item {
                    RailOrHint(state.starred, Icons.Rounded.StarOutline, "Star files to keep them one tap away") {
                        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(state.starred.items, key = { it.id }) { f -> FileRailCard(f, { open(f) }, { menu(f) }) }
                        }
                    }
                }

                item { SectionHeader("Recently modified") }
                if (state.modified.items.isEmpty()) {
                    item { RailOrHint(state.modified, Icons.Rounded.History, "Nothing modified recently") {} }
                } else {
                    items(state.modified.items, key = { "m_" + it.id }) { f ->
                        FileListItem(f, onClick = { open(f) }, onMore = { menu(f) }, onLongClick = { menu(f) })
                    }
                }
            }
        }
    }

    FileActionsHost(actions, accounts, onOpen = open, onChanged = vm::refresh)

    if (showNew) {
        NewItemSheet(account, "My Drive", launchers, onNewFolder = { newFolder = true }, onDismiss = { showNew = false })
    }
    if (newFolder) {
        TextInputDialog("New folder in My Drive", "Untitled folder", "Create", onConfirm = { newFolder = false; vm.createFolder(it) }, onDismiss = { newFolder = false })
    }
}

private fun greeting(): String = when (LocalTime.now().hour) {
    in 5..11 -> "Good morning"
    in 12..16 -> "Good afternoon"
    in 17..21 -> "Good evening"
    else -> "Hello"
}

@Composable
internal fun StorageCard(account: AccountEntity, onClick: () -> Unit) {
    CloudlyCard(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth(), onClick = onClick) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(Format.bytes(account.storageUsage), style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.width(6.dp))
                Text(
                    account.storageLimit?.let { "of ${Format.bytes(it)}" } ?: "used · unlimited",
                    style = MaterialTheme.typography.bodyMedium,
                    color = CloudlyTheme.extra.muted,
                    modifier = Modifier.padding(bottom = 3.dp),
                )
                Spacer(Modifier.weight(1f))
                account.storageLimit?.let {
                    Text("${(Format.percent(account.storageUsage, it) * 100).toInt()}%", style = MaterialTheme.typography.titleMedium, color = account.color)
                }
            }
            Spacer(Modifier.height(14.dp))
            val other = (account.storageUsage - account.usageInDrive).coerceAtLeast(0)
            val driveFiles = (account.usageInDrive - account.usageInTrash).coerceAtLeast(0)
            SegmentedBar(
                listOf(
                    BarSegment("Drive", driveFiles, account.color),
                    BarSegment("Trash", account.usageInTrash, CloudlyTheme.extra.warning),
                    BarSegment("Other", other, CloudlyTheme.extra.muted),
                ),
                total = account.storageLimit ?: account.storageUsage,
                height = 10.dp,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                MiniLegend(account.color, "Drive", Format.bytes(driveFiles))
                MiniLegend(CloudlyTheme.extra.warning, "Trash", Format.bytes(account.usageInTrash))
                MiniLegend(CloudlyTheme.extra.muted, "Other Google services", Format.bytes(other))
            }
        }
    }
}

@Composable
private fun MiniLegend(color: androidx.compose.ui.graphics.Color, label: String, value: String) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, color = CloudlyTheme.extra.muted, maxLines = 1)
        }
        Text(value, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
internal fun AccountMiniCard(account: AccountEntity, active: Boolean, onClick: () -> Unit) {
    CloudlyCard(Modifier.width(200.dp), onClick = onClick) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AccountAvatar(account, size = 28.dp, ring = active)
                Spacer(Modifier.width(10.dp))
                Text(account.email, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(12.dp))
            StorageBar(account.storageUsage, account.storageLimit, color = account.color, height = 6.dp)
            Spacer(Modifier.height(6.dp))
            Text(
                account.storageLimit?.let { "${Format.bytes((it - account.storageUsage).coerceAtLeast(0))} free" } ?: "${Format.bytes(account.storageUsage)} used",
                style = MaterialTheme.typography.labelSmall,
                color = CloudlyTheme.extra.muted,
            )
        }
    }
}

@Composable
internal fun FolderChip(folder: DriveFile, onClick: () -> Unit) {
    val color = categoryColor(FileCategory.FOLDER)
    Row(
        Modifier.clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp).width(140.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Folder, null, tint = color, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        Text(folder.name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun RailOrHint(section: Section, icon: androidx.compose.ui.graphics.vector.ImageVector, hint: String, content: @Composable () -> Unit) {
    when {
        section.items.isNotEmpty() -> content()
        section.loading -> Box(
            Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(64.dp).clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.surfaceContainer),
        )
        else -> Row(
            Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.surfaceContainer).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, tint = CloudlyTheme.extra.muted, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text(section.error ?: hint, style = MaterialTheme.typography.bodySmall, color = CloudlyTheme.extra.muted)
        }
    }
}

/** Three big, labelled shortcuts for the most common jobs. */
@Composable
internal fun QuickActions(onUpload: () -> Unit, onPhotos: () -> Unit, onFiles: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        QuickAction(Icons.Rounded.CloudUpload, "Upload", onUpload, Modifier.weight(1f))
        QuickAction(Icons.Rounded.PhotoLibrary, "Photos", onPhotos, Modifier.weight(1f))
        QuickAction(Icons.Rounded.Folder, "All files", onFiles, Modifier.weight(1f))
    }
}

@Composable
private fun QuickAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit, modifier: Modifier) {
    Column(
        modifier.clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onClick).padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp)) }
        Spacer(Modifier.height(8.dp))
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

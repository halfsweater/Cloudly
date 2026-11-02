package com.halfblanket.cloudly.ui.storage

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AutoDelete
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.halfblanket.cloudly.core.Format
import com.halfblanket.cloudly.data.db.AccountEntity
import com.halfblanket.cloudly.data.drive.DriveFile
import com.halfblanket.cloudly.data.repo.AccountsState
import com.halfblanket.cloudly.ui.common.cloudlyViewModel
import com.halfblanket.cloudly.ui.components.AccountRow
import com.halfblanket.cloudly.ui.components.BarSegment
import com.halfblanket.cloudly.ui.components.CloudlyCard
import com.halfblanket.cloudly.ui.components.CloudlyTopBar
import com.halfblanket.cloudly.ui.components.FileListItem
import com.halfblanket.cloudly.ui.components.LegendDot
import com.halfblanket.cloudly.ui.components.SectionHeader
import com.halfblanket.cloudly.ui.components.SegmentedBar
import com.halfblanket.cloudly.ui.components.color
import com.halfblanket.cloudly.ui.files.ActionDialog
import com.halfblanket.cloudly.ui.files.FileActionsHost
import com.halfblanket.cloudly.ui.files.FileActionsViewModel
import com.halfblanket.cloudly.ui.theme.CloudlyTheme
import com.halfblanket.cloudly.ui.theme.categoryColor

@Composable
fun StorageScreen(
    vm: StorageViewModel,
    accounts: AccountsState,
    onOpenCleanup: (String, CleanupKind) -> Unit,
    onOpenTrash: (String) -> Unit,
    onPreview: (DriveFile) -> Unit,
    onAvatar: () -> Unit = {},
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val actions = cloudlyViewModel { FileActionsViewModel(it) }
    val selected = accounts.byId(state.selectedId)
    val totalUsed = accounts.accounts.sumOf { it.storageUsage }
    val limits = accounts.accounts.mapNotNull { it.storageLimit }
    val totalLimit = if (limits.size == accounts.accounts.size) limits.sum() else null

    PullToRefreshBox(state.refreshing, onRefresh = vm::refresh, modifier = Modifier.fillMaxSize()) {
        LazyColumn(contentPadding = PaddingValues(bottom = 32.dp), modifier = Modifier.fillMaxSize()) {
            item {
                CloudlyTopBar(
                    title = "Storage",
                    subtitle = "${Format.bytes(totalUsed)} used" + (totalLimit?.let { " of ${Format.bytes(it)}" } ?: "") +
                        " · ${accounts.accounts.size} account${if (accounts.accounts.size == 1) "" else "s"}",
                    account = accounts.active,
                    onAvatar = onAvatar,
                ) { IconButton(onClick = vm::refresh) { Icon(Icons.Rounded.Refresh, "Refresh") } }
            }
            item { SectionHeader("Accounts") }
            items(accounts.accounts, key = { it.id }) { acc ->
                AccountRow(acc, selected = acc.id == state.selectedId, onClick = { vm.select(acc.id) })
            }

            if (selected != null) {
                item { SectionHeader("Usage · ${selected.email}") }
                item { UsageCard(selected) }
                item { CategoryCard(state, selected) }
                item { SectionHeader("Clean-up suggestions") }
                item { Suggestions(state, selected, onOpenCleanup, onOpenTrash) }
                val largest = state.scan?.files?.take(8).orEmpty()
                if (largest.isNotEmpty()) {
                    item { SectionHeader("Largest files", action = "See all", onAction = { onOpenCleanup(selected.id, CleanupKind.LARGEST) }) }
                    items(largest, key = { "l_" + it.id }) { f ->
                        FileListItem(
                            f, onClick = { onPreview(f) }, onMore = { actions.show(ActionDialog.Menu(f)) },
                            subtitle = "${Format.bytes(f.storageBytes)} · ${Format.relative(f.modifiedTime)}",
                        )
                    }
                }
            }
        }
    }

    FileActionsHost(actions, accounts, onOpen = onPreview, onChanged = { vm.refresh() })
}

@Composable
internal fun UsageCard(account: AccountEntity) {
    val trash = account.usageInTrash
    val driveFiles = (account.usageInDrive - trash).coerceAtLeast(0)
    val other = (account.storageUsage - account.usageInDrive).coerceAtLeast(0)
    val free = account.storageLimit?.let { (it - account.storageUsage).coerceAtLeast(0) }
    CloudlyCard(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(Format.bytes(account.storageUsage), style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.width(6.dp))
                Text(account.storageLimit?.let { "of ${Format.bytes(it)}" } ?: "used (unlimited plan)", color = CloudlyTheme.extra.muted, modifier = Modifier.padding(bottom = 3.dp))
            }
            Spacer(Modifier.height(14.dp))
            SegmentedBar(
                listOf(
                    BarSegment("Drive", driveFiles, account.color),
                    BarSegment("Trash", trash, CloudlyTheme.extra.warning),
                    BarSegment("Other", other, CloudlyTheme.extra.muted),
                ),
                total = account.storageLimit ?: account.storageUsage,
            )
            Spacer(Modifier.height(12.dp))
            LegendDot(account.color, "Drive files", Format.bytes(driveFiles))
            LegendDot(CloudlyTheme.extra.warning, "Drive trash", Format.bytes(trash))
            LegendDot(CloudlyTheme.extra.muted, "Other Google services (shared quota)", Format.bytes(other))
            if (free != null) LegendDot(MaterialTheme.colorScheme.surfaceContainerHighest, "Available", Format.bytes(free))
            if (account.storageUpdatedAt > 0) Text(
                "Updated ${Format.relative(java.time.Instant.ofEpochMilli(account.storageUpdatedAt))}",
                style = MaterialTheme.typography.labelSmall, color = CloudlyTheme.extra.muted, modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun CategoryCard(state: StorageUiState, account: AccountEntity) {
    CloudlyCard(Modifier.padding(horizontal = 16.dp, vertical = 10.dp).fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Insights, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text("By file type", style = MaterialTheme.typography.titleSmall)
            }
            Spacer(Modifier.height(12.dp))
            val scan = state.scan
            when {
                state.scanning && scan == null -> {
                    Text("Analysing your largest files…", style = MaterialTheme.typography.bodySmall, color = CloudlyTheme.extra.muted)
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                state.error != null && scan == null -> Text(state.error, style = MaterialTheme.typography.bodySmall, color = CloudlyTheme.extra.danger)
                scan == null || scan.files.isEmpty() -> Text("No files owned by this account yet.", style = MaterialTheme.typography.bodySmall, color = CloudlyTheme.extra.muted)
                else -> {
                    SegmentedBar(scan.byCategory.map { (cat, b) -> BarSegment(cat.label, b, categoryColor(cat)) }, total = scan.scannedBytes)
                    Spacer(Modifier.height(12.dp))
                    scan.byCategory.forEach { (cat, b) -> LegendDot(categoryColor(cat), cat.label, Format.bytes(b)) }
                    Text(
                        if (scan.complete) "Based on all ${scan.files.size} files you own (${Format.bytes(scan.scannedBytes)})."
                        else "Based on your ${scan.files.size} largest files (${Format.bytes(scan.scannedBytes)}). Google Drive doesn't report a per-type breakdown, so smaller files aren't counted.",
                        style = MaterialTheme.typography.labelSmall, color = CloudlyTheme.extra.muted, modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun Suggestions(state: StorageUiState, account: AccountEntity, onOpenCleanup: (String, CleanupKind) -> Unit, onOpenTrash: (String) -> Unit) {
    val scan = state.scan
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SuggestionRow(
            Icons.Rounded.DeleteSweep, "Trash",
            if (account.usageInTrash > 0) "${Format.bytes(account.usageInTrash)} in trash — review or empty it" else "Trash is empty",
            CloudlyTheme.extra.warning,
        ) { onOpenTrash(account.id) }
        if (scan != null) {
            val suggestions = listOf(
                Triple(CleanupKind.VIDEOS, Icons.Rounded.Movie, categoryColor(com.halfblanket.cloudly.data.drive.FileCategory.VIDEO)),
                Triple(CleanupKind.OLD, Icons.Rounded.History, MaterialTheme.colorScheme.primary),
                Triple(CleanupKind.DUPLICATES, Icons.Rounded.ContentCopy, CloudlyTheme.extra.success),
            )
            suggestions.forEach { (kind, icon, color) ->
                val files = scan.filesFor(kind)
                if (files.isNotEmpty()) {
                    val count = if (kind == CleanupKind.DUPLICATES) scan.duplicates().sumOf { it.size - 1 } else files.size
                    SuggestionRow(icon, kind.title, "$count file(s) · ${Format.bytes(scan.reclaimable(kind))} could be freed", color) {
                        onOpenCleanup(account.id, kind)
                    }
                }
            }
            if (suggestions.all { scan.filesFor(it.first).isEmpty() }) {
                SuggestionRow(Icons.Rounded.TaskAlt, "Looking tidy", "No large videos, stale files or duplicates found.", CloudlyTheme.extra.success, null)
            }
        } else if (state.scanning) {
            SuggestionRow(Icons.Rounded.AutoDelete, "Looking for things to clean up…", "This only reads file details.", CloudlyTheme.extra.muted, null)
        }
        Text(
            "Cloudly never deletes anything automatically. You review every file before it's moved to trash.",
            style = MaterialTheme.typography.labelSmall, color = CloudlyTheme.extra.muted, modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
        )
    }
}

@Composable
internal fun SuggestionRow(icon: ImageVector, title: String, subtitle: String, color: Color, onClick: (() -> Unit)?) {
    CloudlyCard(Modifier.fillMaxWidth(), onClick = onClick) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).background(color.copy(alpha = 0.14f), MaterialTheme.shapes.small),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, null, tint = color, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = CloudlyTheme.extra.muted)
            }
            if (onClick != null) Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = CloudlyTheme.extra.muted)
        }
    }
}

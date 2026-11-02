package com.halfblanket.cloudly.ui.files

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.DriveFileMove
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarOutline
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.halfblanket.cloudly.data.drive.DriveFile
import com.halfblanket.cloudly.data.drive.SortField
import com.halfblanket.cloudly.data.drive.SortOrder
import com.halfblanket.cloudly.data.prefs.LayoutMode
import com.halfblanket.cloudly.data.repo.AccountsState
import com.halfblanket.cloudly.ui.common.AccountConnector
import com.halfblanket.cloudly.ui.common.NewItemSheet
import com.halfblanket.cloudly.ui.common.cloudlyViewModel
import com.halfblanket.cloudly.ui.common.rememberUploadLaunchers
import com.halfblanket.cloudly.ui.components.CloudlyTopBar
import com.halfblanket.cloudly.ui.components.Banner
import com.halfblanket.cloudly.ui.components.EmptyState
import com.halfblanket.cloudly.ui.components.FileGridItem
import com.halfblanket.cloudly.ui.components.FileListItem
import com.halfblanket.cloudly.ui.components.SkeletonList
import com.halfblanket.cloudly.ui.components.TextInputDialog
import com.halfblanket.cloudly.ui.theme.CloudlyTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

@Composable
fun FilesScreen(
    vm: FilesViewModel,
    accounts: AccountsState,
    connector: AccountConnector,
    onOpenAccounts: () -> Unit,
    onSearch: () -> Unit,
    onPreview: (DriveFile) -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val actions = cloudlyViewModel { FileActionsViewModel(it) }
    val account = accounts.byId(state.accountId)
    var showNew by rememberSaveable { mutableStateOf(false) }
    var newFolder by rememberSaveable { mutableStateOf(false) }
    val launchers = rememberUploadLaunchers { vm.upload(it) }

    BackHandler(enabled = state.selectionMode || state.canGoUp) { vm.navigateUp() }

    val open: (DriveFile) -> Unit = { f ->
        when {
            state.selectionMode -> vm.toggleSelection(f)
            f.isFolder -> vm.openFolder(f)
            else -> onPreview(f)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
        topBar = {
            AnimatedContent(state.selectionMode, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "topbar") { selecting ->
                if (selecting) {
                    SelectionBar(state, vm, actions, accounts)
                } else {
                    Column {
                        CloudlyTopBar("Files", account, onOpenAccounts) {
                            IconButton(onClick = onSearch) { Icon(Icons.Rounded.Search, "Search") }
                            SortMenu(state.sort, vm::setSort, state.layout, vm::toggleLayout)
                        }
                        LocationChips(state.location, vm::setLocation)
                        if (state.crumbs.isNotEmpty()) Breadcrumbs(state, vm)
                    }
                }
            }
        },
        floatingActionButton = {
            AnimatedVisibility(!state.selectionMode && account != null, enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
                ExtendedFloatingActionButton(
                    onClick = { showNew = true },
                    icon = { Icon(Icons.Rounded.Add, null) },
                    text = { Text("New") },
                    shape = MaterialTheme.shapes.large,
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                )
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (account?.needsReauth == true || state.needsReauth) {
                Banner(
                    Icons.Rounded.ErrorOutline, "Access for ${account?.email} expired or was revoked.",
                    color = CloudlyTheme.extra.danger, actionLabel = "Reconnect",
                    onAction = { account?.let(connector::reconnect) },
                )
            } else if (state.limitedAccess) {
                Banner(
                    Icons.Rounded.Lock,
                    "Limited access: Cloudly can only see files it created in this account, not your whole Drive.",
                    actionLabel = "Allow full",
                    onAction = { account?.let(connector::upgradeToFull) },
                )
            }
            PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = vm::refresh, modifier = Modifier.weight(1f)) {
                when {
                    state.loading && state.items.isEmpty() -> SkeletonList()
                    state.error != null && state.items.isEmpty() -> Box(Modifier.fillMaxSize()) {
                        EmptyState(Icons.Rounded.CloudOff, "Couldn't load files", state.error!!, actionLabel = "Try again", onAction = vm::refresh)
                    }
                    state.items.isEmpty() -> Box(Modifier.fillMaxSize()) {
                        val (title, msg) = emptyCopy(state)
                        EmptyState(Icons.Rounded.FolderOpen, title, msg, actionLabel = if (state.location == Location.MY_DRIVE || state.canGoUp) "Add files" else null, onAction = { showNew = true })
                    }
                    state.layout == LayoutMode.GRID -> FileGrid(state, vm, actions, open)
                    else -> FileList(state, vm, actions, open)
                }
            }
        }
    }

    FileActionsHost(actions, accounts, onOpen = open, onChanged = vm::refresh)

    if (showNew) {
        NewItemSheet(
            account = account,
            destination = state.targetFolderName,
            launchers = launchers,
            onNewFolder = { newFolder = true },
            onDismiss = { showNew = false },
        )
    }
    if (newFolder) {
        TextInputDialog(
            title = "New folder in ${state.targetFolderName}",
            initial = "Untitled folder",
            confirmLabel = "Create",
            onConfirm = { newFolder = false; vm.createFolder(it) },
            onDismiss = { newFolder = false },
        )
    }
}

private fun emptyCopy(s: FilesUiState): Pair<String, String> = when {
    s.canGoUp -> "This folder is empty" to "Upload files or create a folder to get started."
    s.location == Location.MY_DRIVE && s.limitedAccess ->
        "Nothing created with Cloudly yet" to "With limited access, only files you upload or create here appear."
    s.location == Location.MY_DRIVE -> "Your Drive is empty" to "Upload photos, documents or anything else."
    s.location == Location.SHARED -> "Nothing shared with you" to "Files other people share with this account appear here."
    s.location == Location.STARRED -> "No starred files" to "Star important files to find them quickly."
    else -> "No recent files" to "Files you open in Drive will show up here."
}

@Composable
private fun FileList(state: FilesUiState, vm: FilesViewModel, actions: FileActionsViewModel, open: (DriveFile) -> Unit) {
    val listState = rememberLazyListState()
    LoadMoreEffect(remember(listState) { { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 } }, state.items.size, vm::loadMore)
    LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 96.dp, top = 4.dp), modifier = Modifier.fillMaxSize()) {
        items(state.items, key = { it.id }) { f ->
            FileListItem(
                file = f,
                onClick = { open(f) },
                onLongClick = { vm.toggleSelection(f) },
                onMore = { actions.show(ActionDialog.Menu(f)) },
                selected = f.id in state.selection,
                selectionMode = state.selectionMode,
                modifier = Modifier.animateItem(),
            )
        }
        if (state.loadingMore) item { LoadingFooter() }
    }
}

@Composable
private fun FileGrid(state: FilesUiState, vm: FilesViewModel, actions: FileActionsViewModel, open: (DriveFile) -> Unit) {
    val gridState = rememberLazyGridState()
    LoadMoreEffect(remember(gridState) { { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 } }, state.items.size, vm::loadMore)
    LazyVerticalGrid(
        columns = GridCells.Adaptive(156.dp),
        state = gridState,
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 96.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(state.items, key = { it.id }) { f ->
            FileGridItem(
                file = f,
                onClick = { open(f) },
                onLongClick = { vm.toggleSelection(f) },
                onMore = { actions.show(ActionDialog.Menu(f)) },
                selected = f.id in state.selection,
                selectionMode = state.selectionMode,
                modifier = Modifier.animateItem(),
            )
        }
        if (state.loadingMore) item(span = { GridItemSpan(maxLineSpan) }) { LoadingFooter() }
    }
}

/** Requests the next page when the user scrolls within a few items of the end. */
@Composable
fun LoadMoreEffect(lastVisible: () -> Int, count: Int, loadMore: () -> Unit) {
    LaunchedEffect(count) {
        snapshotFlow { lastVisible() }
            .distinctUntilChanged()
            .filter { count > 0 && it >= count - 8 }
            .collect { loadMore() }
    }
}

@Composable
fun LoadingFooter() {
    Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
    }
}

@Composable
internal fun LocationChips(selected: Location, onSelect: (Location) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Location.entries.forEach { loc ->
            FilterChip(
                selected = loc == selected,
                onClick = { onSelect(loc) },
                label = { Text(loc.label) },
                shape = MaterialTheme.shapes.small,
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                    selectedLabelColor = MaterialTheme.colorScheme.primary,
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true, selected = loc == selected,
                    borderColor = CloudlyTheme.extra.line, selectedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                ),
            )
        }
    }
}

@Composable
private fun Breadcrumbs(state: FilesUiState, vm: FilesViewModel) {
    val scroll = rememberScrollState()
    LaunchedEffect(state.crumbs.size) { scroll.animateScrollTo(scroll.maxValue) }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(scroll).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { vm.navigateUp() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Up") }
        TextButton(onClick = vm::goToLocationRoot) { Text(state.location.label, color = CloudlyTheme.extra.muted) }
        state.crumbs.forEachIndexed { i, c ->
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = CloudlyTheme.extra.muted, modifier = Modifier.size(18.dp))
            TextButton(onClick = { vm.goToCrumb(i) }) {
                Text(
                    c.name,
                    color = if (i == state.crumbs.lastIndex) MaterialTheme.colorScheme.onSurface else CloudlyTheme.extra.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
    }
    HorizontalDivider(color = CloudlyTheme.extra.line)
}

@Composable
fun SortMenu(
    sort: SortOrder,
    onChange: (SortOrder) -> Unit,
    layout: LayoutMode? = null,
    onToggleLayout: (() -> Unit)? = null,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.AutoMirrored.Rounded.Sort, "View and sort options") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
            if (layout != null && onToggleLayout != null) {
                Text("View", style = MaterialTheme.typography.labelMedium, color = CloudlyTheme.extra.muted, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                listOf(LayoutMode.LIST to "List", LayoutMode.GRID to "Grid").forEach { (mode, label) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = { open = false; if (layout != mode) onToggleLayout() },
                        leadingIcon = { if (layout == mode) Icon(Icons.Rounded.Check, null) else Spacer(Modifier.size(24.dp)) },
                        trailingIcon = {
                            Icon(if (mode == LayoutMode.GRID) Icons.Rounded.GridView else Icons.AutoMirrored.Rounded.ViewList, null, Modifier.size(18.dp))
                        },
                    )
                }
                HorizontalDivider(color = CloudlyTheme.extra.line)
            }
            Text("Sort by", style = MaterialTheme.typography.labelMedium, color = CloudlyTheme.extra.muted, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            SortField.entries.forEach { f ->
                DropdownMenuItem(
                    text = { Text(f.label) },
                    onClick = {
                        open = false
                        onChange(if (sort.field == f) sort.copy(ascending = !sort.ascending) else SortOrder(f, ascending = f == SortField.NAME || f == SortField.TYPE))
                    },
                    leadingIcon = { if (sort.field == f) Icon(Icons.Rounded.Check, null) else Spacer(Modifier.size(24.dp)) },
                    trailingIcon = {
                        if (sort.field == f) Icon(if (sort.ascending) Icons.Rounded.ArrowUpward else Icons.Rounded.ArrowDownward, if (sort.ascending) "Ascending" else "Descending", Modifier.size(18.dp))
                    },
                )
            }
        }
    }
}

@Composable
private fun SelectionBar(state: FilesUiState, vm: FilesViewModel, actions: FileActionsViewModel, accounts: AccountsState) {
    val selected = state.selectedFiles
    val allStarred by remember(selected) { derivedStateOf { selected.isNotEmpty() && selected.all { it.starred } } }
    var more by remember { mutableStateOf(false) }
    val hasOthers = accounts.accounts.size > 1
    Row(
        Modifier.statusBarsPadding().fillMaxWidth().height(64.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = vm::clearSelection) { Icon(Icons.Rounded.Close, "Clear selection") }
        Text("${state.selection.size} selected", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        IconButton(onClick = vm::selectAll) { Icon(Icons.Rounded.SelectAll, "Select all") }
        IconButton(onClick = { actions.setStarred(selected, !allStarred); vm.clearSelection() }) {
            Icon(if (allStarred) Icons.Rounded.Star else Icons.Rounded.StarOutline, if (allStarred) "Unstar" else "Star")
        }
        IconButton(onClick = { actions.download(selected); vm.clearSelection() }) { Icon(Icons.Rounded.Download, "Download") }
        IconButton(onClick = { actions.show(ActionDialog.ConfirmTrash(selected.filter { it.capabilities.canTrash })) }, enabled = selected.any { it.capabilities.canTrash }) {
            Icon(Icons.Rounded.DeleteOutline, "Move to trash")
        }
        Box {
            IconButton(onClick = { more = true }) { Icon(Icons.Rounded.MoreVert, "More") }
            DropdownMenu(expanded = more, onDismissRequest = { more = false }, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
                val acc = state.accountId ?: return@DropdownMenu
                DropdownMenuItem(
                    text = { Text("Move") }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.DriveFileMove, null) },
                    enabled = selected.all { it.capabilities.canMove },
                    onClick = { more = false; actions.show(ActionDialog.PickFolder(selected, PickMode.MOVE, acc)); vm.clearSelection() },
                )
                DropdownMenuItem(
                    text = { Text("Make a copy") }, leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) },
                    onClick = { more = false; actions.show(ActionDialog.PickFolder(selected, PickMode.COPY, acc)); vm.clearSelection() },
                )
                if (hasOthers) {
                    DropdownMenuItem(
                        text = { Text("Copy to another account") }, leadingIcon = { Icon(Icons.Rounded.CloudSync, null) },
                        onClick = { more = false; actions.show(ActionDialog.PickAccount(selected, move = false)); vm.clearSelection() },
                    )
                    DropdownMenuItem(
                        text = { Text("Move to another account") }, leadingIcon = { Icon(Icons.Rounded.SwapHoriz, null) },
                        enabled = selected.all { it.capabilities.canTrash },
                        onClick = { more = false; actions.show(ActionDialog.PickAccount(selected, move = true)); vm.clearSelection() },
                    )
                }
            }
        }
    }
}

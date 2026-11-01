package com.halfblanket.cloudly.ui.storage

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.halfblanket.cloudly.AppContainer
import com.halfblanket.cloudly.core.Format
import com.halfblanket.cloudly.core.userMessage
import com.halfblanket.cloudly.data.drive.DriveFile
import com.halfblanket.cloudly.data.repo.AccountsState
import com.halfblanket.cloudly.data.repo.FileSource
import com.halfblanket.cloudly.ui.common.UiMessages
import com.halfblanket.cloudly.ui.common.cloudlyViewModel
import com.halfblanket.cloudly.ui.components.AccountBadge
import com.halfblanket.cloudly.ui.components.ConfirmDialog
import com.halfblanket.cloudly.ui.components.EmptyState
import com.halfblanket.cloudly.ui.components.FileListItem
import com.halfblanket.cloudly.ui.components.SkeletonList
import com.halfblanket.cloudly.ui.files.ActionDialog
import com.halfblanket.cloudly.ui.files.FileActionsHost
import com.halfblanket.cloudly.ui.files.FileActionsViewModel
import com.halfblanket.cloudly.ui.files.LoadMoreEffect
import com.halfblanket.cloudly.ui.files.LoadingFooter
import com.halfblanket.cloudly.ui.theme.CloudlyTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Composable
private fun SubScreenHeader(title: String, subtitle: @Composable () -> Unit, onBack: () -> Unit, actions: @Composable () -> Unit = {}) {
    Row(Modifier.statusBarsPadding().fillMaxWidth().height(64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            subtitle()
        }
        actions()
    }
}

/** Review list for a clean-up suggestion. Nothing is selected by default; the user decides. */
@Composable
fun CleanupScreen(
    storage: StorageViewModel,
    accountId: String,
    kind: CleanupKind,
    accounts: AccountsState,
    onBack: () -> Unit,
    onPreview: (DriveFile) -> Unit,
) {
    val state by storage.state.collectAsStateWithLifecycle()
    val actions = cloudlyViewModel(key = "cleanup/$accountId/$kind") { FileActionsViewModel(it) }
    LaunchedEffect(accountId) { storage.select(accountId) }
    val scan = state.scan?.takeIf { it.accountId == accountId }
    val files = scan?.filesFor(kind).orEmpty()
    val groups = if (kind == CleanupKind.DUPLICATES) scan?.duplicates().orEmpty() else emptyList()
    var selection by rememberSaveable(accountId, kind) { mutableStateOf(setOf<String>()) }
    var confirm by remember { mutableStateOf(false) }
    val selectedFiles = files.filter { it.id in selection }
    BackHandler(selection.isNotEmpty()) { selection = emptySet() }

    Column(Modifier.fillMaxSize()) {
        SubScreenHeader(kind.title, { AccountBadge(accounts.byId(accountId)) }, onBack) {
            if (kind == CleanupKind.DUPLICATES && groups.isNotEmpty()) {
                TextButton(onClick = { selection = groups.flatMap { g -> g.drop(1).map { it.id } }.toSet() }) { Text("Keep oldest") }
            } else if (files.isNotEmpty()) {
                TextButton(onClick = { selection = if (selection.size == files.size) emptySet() else files.map { it.id }.toSet() }) {
                    Text(if (selection.size == files.size) "Clear" else "Select all")
                }
            }
        }
        Text(kind.description, style = MaterialTheme.typography.bodySmall, color = CloudlyTheme.extra.muted, modifier = Modifier.padding(horizontal = 20.dp))
        if (state.scanning) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
        Box(Modifier.weight(1f)) {
            when {
                scan == null && state.scanning -> SkeletonList()
                scan == null -> EmptyState(Icons.Rounded.CloudOff, "Couldn't analyse storage", state.error ?: "", actionLabel = "Retry", onAction = storage::scan)
                files.isEmpty() -> EmptyState(Icons.Rounded.TaskAlt, "Nothing to clean up", "No files match this suggestion.")
                else -> LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                    val toggle: (DriveFile) -> Unit = { f -> selection = if (f.id in selection) selection - f.id else selection + f.id }
                    if (kind == CleanupKind.DUPLICATES) {
                        groups.forEachIndexed { gi, g ->
                            item(key = "g$gi") {
                                Text(
                                    "${g.size} copies · ${Format.bytes(g.first().storageBytes)} each",
                                    style = MaterialTheme.typography.labelMedium, color = CloudlyTheme.extra.muted,
                                    modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 4.dp),
                                )
                            }
                            items(g, key = { "d_" + it.id }) { f ->
                                FileListItem(
                                    f, onClick = { toggle(f) }, onLongClick = { onPreview(f) },
                                    onMore = { actions.show(ActionDialog.Menu(f)) },
                                    selected = f.id in selection, selectionMode = true,
                                    subtitle = (if (f == g.first()) "Oldest · " else "") + "Created ${Format.date(f.createdTime ?: f.modifiedTime)}",
                                )
                            }
                        }
                    } else {
                        items(files, key = { it.id }) { f ->
                            FileListItem(
                                f, onClick = { toggle(f) }, onLongClick = { onPreview(f) },
                                onMore = { actions.show(ActionDialog.Menu(f)) },
                                selected = f.id in selection, selectionMode = true,
                                subtitle = "${Format.bytes(f.storageBytes)} · modified ${Format.date(f.modifiedTime)}",
                            )
                        }
                    }
                }
            }
        }
        if (selection.isNotEmpty()) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                Button(
                    onClick = { confirm = true },
                    modifier = Modifier.navigationBarsPadding().fillMaxWidth().padding(16.dp).height(52.dp),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Icon(Icons.Rounded.DeleteOutline, null)
                    Text("  Move ${selection.size} to trash · ${Format.bytes(selectedFiles.sumOf { it.storageBytes })}")
                }
            }
        }
    }

    if (confirm) {
        ConfirmDialog(
            title = "Move ${selectedFiles.size} file(s) to trash?",
            message = "They'll stay in ${accounts.byId(accountId)?.email}'s trash for 30 days, so you can still restore them. " +
                "Storage is freed once the trash is emptied.",
            confirmLabel = "Move to trash",
            icon = Icons.Rounded.DeleteOutline,
            onConfirm = {
                confirm = false
                val trashable = selectedFiles.filter { it.capabilities.canTrash }
                actions.trash(trashable)
                storage.forget(accountId, trashable.map { it.id }.toSet())
                selection = emptySet()
            },
            onDismiss = { confirm = false },
        )
    }
    FileActionsHost(actions, accounts, onOpen = onPreview, onChanged = {})
}

// ---------------------------------------------------------------------- Trash

data class TrashUiState(
    val items: List<DriveFile> = emptyList(),
    val nextPageToken: String? = null,
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val refreshing: Boolean = false,
    val error: String? = null,
)

class TrashViewModel(private val c: AppContainer, private val accountId: String) : ViewModel() {
    private val _state = MutableStateFlow(TrashUiState())
    val state: StateFlow<TrashUiState> = _state

    init { load(reset = true) }

    fun load(reset: Boolean, refreshing: Boolean = false) {
        val s = _state.value
        if (!reset && (s.nextPageToken == null || s.loadingMore)) return
        viewModelScope.launch {
            _state.update { if (reset) it.copy(loading = it.items.isEmpty(), refreshing = refreshing, error = null) else it.copy(loadingMore = true) }
            try {
                val page = c.driveRepository.list(accountId, FileSource.Trash, pageToken = if (reset) null else s.nextPageToken, pageSize = 100)
                _state.update {
                    it.copy(
                        items = if (reset) page.items else (it.items + page.items).distinctBy { f -> f.id },
                        nextPageToken = page.nextPageToken, loading = false, loadingMore = false, refreshing = false,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _state.update { it.copy(loading = false, loadingMore = false, refreshing = false, error = e.userMessage()) }
            }
        }
    }

    /** Irreversible; only called after the confirmation dialog. */
    fun emptyTrash() {
        viewModelScope.launch {
            try {
                c.driveRepository.emptyTrash(accountId)
                UiMessages.show("Trash emptied")
                runCatching { c.accountRepository.refreshStorage(accountId) }
                load(reset = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                UiMessages.show(e.userMessage())
            }
        }
    }
}

@Composable
fun TrashScreen(accountId: String, accounts: AccountsState, onBack: () -> Unit) {
    val vm = cloudlyViewModel(key = "trash/$accountId") { TrashViewModel(it, accountId) }
    val actions = cloudlyViewModel(key = "trashActions/$accountId") { FileActionsViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val account = accounts.byId(accountId)
    var confirmEmpty by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        SubScreenHeader("Trash", { AccountBadge(account) }, onBack) {
            TextButton(onClick = { confirmEmpty = true }, enabled = state.items.isNotEmpty()) {
                Text("Empty trash", color = if (state.items.isNotEmpty()) CloudlyTheme.extra.danger else Color.Unspecified)
            }
        }
        Text(
            "Items in trash are deleted forever by Google after 30 days. ${account?.usageInTrash?.let { "Using ${Format.bytes(it)}." } ?: ""}",
            style = MaterialTheme.typography.bodySmall, color = CloudlyTheme.extra.muted, modifier = Modifier.padding(horizontal = 20.dp),
        )
        HorizontalDivider(Modifier.padding(top = 10.dp), color = CloudlyTheme.extra.line)
        PullToRefreshBox(state.refreshing, onRefresh = { vm.load(reset = true, refreshing = true) }, modifier = Modifier.weight(1f)) {
            when {
                state.loading -> SkeletonList()
                state.error != null && state.items.isEmpty() -> Box(Modifier.fillMaxSize()) {
                    EmptyState(Icons.Rounded.CloudOff, "Couldn't load trash", state.error!!, actionLabel = "Retry", onAction = { vm.load(true) })
                }
                state.items.isEmpty() -> Box(Modifier.fillMaxSize()) { EmptyState(Icons.Rounded.DeleteSweep, "Trash is empty", "Deleted files will appear here for 30 days.") }
                else -> {
                    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
                    LoadMoreEffect({ listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }, state.items.size) { vm.load(reset = false) }
                    LazyColumn(state = listState, contentPadding = PaddingValues(vertical = 8.dp)) {
                        items(state.items, key = { it.id }) { f ->
                            FileListItem(
                                f, onClick = { actions.show(ActionDialog.Menu(f)) }, onMore = { actions.show(ActionDialog.Menu(f)) },
                                subtitle = "Trashed · ${Format.bytes(f.storageBytes)}",
                                modifier = Modifier.animateItem(),
                            )
                        }
                        if (state.loadingMore) item { LoadingFooter() }
                    }
                }
            }
        }
    }

    if (confirmEmpty) {
        ConfirmDialog(
            title = "Empty trash?",
            message = "Everything in ${account?.email}'s trash will be permanently deleted" +
                (account?.usageInTrash?.let { ", freeing about ${Format.bytes(it)}" } ?: "") + ". This can't be undone.",
            confirmLabel = "Delete forever",
            destructive = true,
            icon = Icons.Rounded.DeleteForever,
            onConfirm = { confirmEmpty = false; vm.emptyTrash() },
            onDismiss = { confirmEmpty = false },
        )
    }
    FileActionsHost(actions, accounts, onOpen = { actions.show(ActionDialog.Menu(it)) }, onChanged = { vm.load(reset = true) })
}

package com.halfblanket.cloudly.ui.files

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.halfblanket.cloudly.AppContainer
import com.halfblanket.cloudly.core.CloudlyException
import com.halfblanket.cloudly.core.userMessage
import com.halfblanket.cloudly.data.db.DriveAccess
import com.halfblanket.cloudly.data.drive.DriveFile
import com.halfblanket.cloudly.data.drive.DriveQuery
import com.halfblanket.cloudly.data.drive.SortOrder
import com.halfblanket.cloudly.data.drive.sortedFor
import com.halfblanket.cloudly.data.prefs.LayoutMode
import com.halfblanket.cloudly.data.repo.FileSource
import com.halfblanket.cloudly.ui.common.UiMessages
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class Location(val label: String) { MY_DRIVE("My Drive"), SHARED("Shared"), STARRED("Starred"), RECENT("Recent") }

data class Crumb(val id: String, val name: String)

data class FilesUiState(
    val accountId: String? = null,
    val location: Location = Location.MY_DRIVE,
    val crumbs: List<Crumb> = emptyList(),
    val items: List<DriveFile> = emptyList(),
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    val nextPageToken: String? = null,
    val error: String? = null,
    val sort: SortOrder = SortOrder(),
    val layout: LayoutMode = LayoutMode.LIST,
    val selection: Set<String> = emptySet(),
    val limitedAccess: Boolean = false,
    val needsReauth: Boolean = false,
) {
    val selectionMode get() = selection.isNotEmpty()
    val selectedFiles get() = items.filter { it.id in selection }
    /** Folder new uploads/folders go into: the open folder, else My Drive. */
    val targetFolderId: String get() = crumbs.lastOrNull()?.id ?: DriveQuery.ROOT
    val targetFolderName: String get() = crumbs.lastOrNull()?.name ?: "My Drive"
    val title: String get() = crumbs.lastOrNull()?.name ?: location.label
    val canGoUp get() = crumbs.isNotEmpty()
}

/**
 * Activity-scoped browser state. Each account remembers its own location and folder path, so switching
 * accounts is instant and switching back returns to where the user was. Results from a load are discarded
 * if the active account changed while it was in flight.
 */
class FilesViewModel(private val c: AppContainer) : ViewModel() {
    private val repo = c.driveRepository
    private val _state = MutableStateFlow(FilesUiState())
    val state: StateFlow<FilesUiState> = _state

    private data class Position(val location: Location, val crumbs: List<Crumb>)
    private val positions = mutableMapOf<String, Position>()
    private var loadJob: Job? = null

    init {
        // Refresh the open listing when an upload into this account finishes.
        viewModelScope.launch {
            val seen = HashSet<String>()
            var first = true
            c.transferRepository.observe().collect { list ->
                val done = list.filter {
                    it.kind == com.halfblanket.cloudly.data.db.TransferKind.UPLOAD &&
                        it.status == com.halfblanket.cloudly.data.db.TransferStatus.SUCCEEDED
                }
                val fresh = done.filter { seen.add(it.id) }
                if (!first && fresh.any { it.sourceAccountId == _state.value.accountId }) refresh()
                first = false
            }
        }
        viewModelScope.launch {
            c.settings.settings.collect { s ->
                val changedSort = s.sort != _state.value.sort
                _state.update { it.copy(sort = s.sort, layout = s.layout) }
                if (changedSort && _state.value.accountId != null) load(reset = true)
            }
        }
        viewModelScope.launch {
            c.accountRepository.state
                .map { it.active }
                .distinctUntilChanged { a, b -> a?.id == b?.id && a?.access == b?.access && a?.needsReauth == b?.needsReauth }
                .collect { acc ->
                    val prev = _state.value
                    if (acc == null) {
                        _state.value = FilesUiState(sort = prev.sort, layout = prev.layout)
                        return@collect
                    }
                    val switching = prev.accountId != acc.id
                    if (switching) {
                        prev.accountId?.let { positions[it] = Position(prev.location, prev.crumbs) }
                        val pos = positions[acc.id] ?: Position(Location.MY_DRIVE, emptyList())
                        _state.value = prev.copy(
                            accountId = acc.id, location = pos.location, crumbs = pos.crumbs,
                            items = emptyList(), selection = emptySet(), nextPageToken = null, error = null,
                        )
                    }
                    _state.update {
                        it.copy(limitedAccess = acc.access == DriveAccess.APP_FILES_ONLY, needsReauth = acc.needsReauth)
                    }
                    if (switching || prev.items.isEmpty()) load(reset = true)
                }
        }
    }

    private fun source(s: FilesUiState): FileSource = s.crumbs.lastOrNull()?.let { FileSource.Folder(it.id) } ?: when (s.location) {
        Location.MY_DRIVE -> FileSource.Folder(DriveQuery.ROOT)
        Location.SHARED -> FileSource.SharedWithMe
        Location.STARRED -> FileSource.Starred
        Location.RECENT -> FileSource.Recent
    }

    fun load(reset: Boolean, refreshing: Boolean = false) {
        val s = _state.value
        val accountId = s.accountId ?: return
        if (!reset && (s.nextPageToken == null || s.loadingMore)) return
        val src = source(s)
        val token = if (reset) null else s.nextPageToken
        if (reset) loadJob?.cancel()
        val job = viewModelScope.launch {
            if (reset) {
                val cached = if (!refreshing) repo.cached(accountId, src, s.sort) else null
                _state.update {
                    it.copy(
                        loading = it.items.isEmpty() && cached.isNullOrEmpty(), refreshing = refreshing, error = null,
                        items = if (!cached.isNullOrEmpty() && it.items.isEmpty()) cached.sortedFor(s.sort) else it.items,
                    )
                }
            } else _state.update { it.copy(loadingMore = true) }
            try {
                val page = repo.list(accountId, src, s.sort, token)
                if (_state.value.accountId != accountId) return@launch // account switched meanwhile
                _state.update { cur ->
                    val merged = if (reset) page.items else (cur.items + page.items).distinctBy { it.id }
                    cur.copy(
                        items = merged.sortedFor(cur.sort),
                        nextPageToken = page.nextPageToken,
                        loading = false, refreshing = false, loadingMore = false, error = null,
                        selection = cur.selection.intersect(merged.map { it.id }.toSet()),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (_state.value.accountId != accountId) return@launch
                _state.update {
                    it.copy(
                        loading = false, refreshing = false, loadingMore = false,
                        error = if (it.items.isEmpty() || reset) e.userMessage() else it.error,
                        needsReauth = it.needsReauth || e is CloudlyException.ReauthRequired,
                    )
                }
                if (!reset || _state.value.items.isNotEmpty()) UiMessages.show(e.userMessage())
            }
        }
        if (reset) loadJob = job
    }

    fun refresh() = load(reset = true, refreshing = true)
    fun loadMore() = load(reset = false)

    fun setLocation(location: Location) {
        _state.update { it.copy(location = location, crumbs = emptyList(), items = emptyList(), selection = emptySet(), nextPageToken = null) }
        load(reset = true)
    }

    fun openFolder(folder: DriveFile) {
        val s = _state.value
        if (folder.accountId != s.accountId) return // never browse another account's folder in this tab
        _state.update {
            it.copy(crumbs = it.crumbs + Crumb(folder.id, folder.name), items = emptyList(), selection = emptySet(), nextPageToken = null)
        }
        load(reset = true)
    }

    /** Opens a folder found elsewhere (search, dashboard), building its breadcrumb path. */
    fun openFolderFromElsewhere(folder: DriveFile) {
        viewModelScope.launch {
            if (c.accountRepository.state.value.activeId != folder.accountId) {
                c.accountRepository.setActive(folder.accountId)
            }
            // Wait for the account switch to be applied before navigating within it.
            var tries = 0
            while (_state.value.accountId != folder.accountId && tries++ < 50) kotlinx.coroutines.delay(20)
            val path = runCatching { repo.pathTo(folder.accountId, folder.id) }.getOrDefault(listOf(folder))
            val inMyDrive = runCatching { path.firstOrNull()?.parents?.firstOrNull() == repo.rootId(folder.accountId) }.getOrDefault(false)
            _state.update {
                it.copy(
                    location = if (inMyDrive) Location.MY_DRIVE else Location.SHARED,
                    crumbs = path.map { f -> Crumb(f.id, f.name) }.ifEmpty { listOf(Crumb(folder.id, folder.name)) },
                    items = emptyList(), selection = emptySet(), nextPageToken = null,
                )
            }
            load(reset = true)
        }
    }

    fun navigateUp(): Boolean {
        val s = _state.value
        if (s.selectionMode) {
            clearSelection()
            return true
        }
        if (s.crumbs.isEmpty()) return false
        _state.update { it.copy(crumbs = it.crumbs.dropLast(1), items = emptyList(), nextPageToken = null) }
        load(reset = true)
        return true
    }

    fun goToCrumb(index: Int) {
        val s = _state.value
        if (index >= s.crumbs.lastIndex) return
        _state.update { it.copy(crumbs = it.crumbs.take(index + 1), items = emptyList(), selection = emptySet(), nextPageToken = null) }
        load(reset = true)
    }

    fun goToLocationRoot() {
        if (_state.value.crumbs.isEmpty()) return
        _state.update { it.copy(crumbs = emptyList(), items = emptyList(), selection = emptySet(), nextPageToken = null) }
        load(reset = true)
    }

    fun setSort(order: SortOrder) {
        viewModelScope.launch { c.settings.setSort(order) }
    }

    fun toggleLayout() {
        val next = if (_state.value.layout == LayoutMode.LIST) LayoutMode.GRID else LayoutMode.LIST
        _state.update { it.copy(layout = next) }
        viewModelScope.launch { c.settings.setLayout(next) }
    }

    fun toggleSelection(file: DriveFile) {
        _state.update { s ->
            s.copy(selection = if (file.id in s.selection) s.selection - file.id else s.selection + file.id)
        }
    }

    fun selectAll() = _state.update { s -> s.copy(selection = s.items.map { it.id }.toSet()) }
    fun clearSelection() = _state.update { it.copy(selection = emptySet()) }

    fun createFolder(name: String) {
        val s = _state.value
        val accountId = s.accountId ?: return
        viewModelScope.launch {
            try {
                val folder = repo.createFolder(accountId, s.targetFolderId, name)
                UiMessages.show("Created “${folder.name}” in ${s.targetFolderName}")
                if (_state.value.accountId == accountId) refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                UiMessages.show(e.userMessage())
            }
        }
    }

    fun upload(uris: List<Uri>) {
        val s = _state.value
        val accountId = s.accountId ?: return
        if (uris.isEmpty()) return
        viewModelScope.launch {
            try {
                val n = c.transferRepository.enqueueUploads(accountId, s.targetFolderId, uris)
                val email = c.accountRepository.state.value.byId(accountId)?.email
                UiMessages.show("Uploading $n file(s) to ${s.targetFolderName} · $email")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                UiMessages.show(e.userMessage())
            }
        }
    }
}

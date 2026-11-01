package com.halfblanket.cloudly.ui.home

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.halfblanket.cloudly.AppContainer
import com.halfblanket.cloudly.core.userMessage
import com.halfblanket.cloudly.data.drive.DriveFile
import com.halfblanket.cloudly.data.drive.DriveQuery
import com.halfblanket.cloudly.data.repo.FileSource
import com.halfblanket.cloudly.ui.common.UiMessages
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class Section(val items: List<DriveFile> = emptyList(), val loading: Boolean = true, val error: String? = null)

data class HomeUiState(
    val accountId: String? = null,
    val quickFolders: Section = Section(),
    val recent: Section = Section(),
    val modified: Section = Section(),
    val starred: Section = Section(),
    val refreshing: Boolean = false,
)

class HomeViewModel(private val c: AppContainer) : ViewModel() {
    private val repo = c.driveRepository
    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state
    private var job: Job? = null

    init {
        viewModelScope.launch {
            c.accountRepository.state.map { it.active?.id }.distinctUntilChanged().collect { id ->
                _state.value = HomeUiState(accountId = id)
                if (id != null) load(id, refreshing = false)
            }
        }
    }

    fun refresh() {
        val id = _state.value.accountId ?: return
        load(id, refreshing = true)
        viewModelScope.launch { runCatching { c.accountRepository.refreshAllStorage() } }
    }

    private fun load(accountId: String, refreshing: Boolean) {
        job?.cancel()
        job = viewModelScope.launch {
            _state.update { it.copy(refreshing = refreshing) }
            if (!refreshing) {
                // Instant paint from the local cache, then revalidate.
                val cached = coroutineScope {
                    listOf(FileSource.QuickFolders(), FileSource.Recent, FileSource.RecentlyModified, FileSource.Starred)
                        .map { async { repo.cached(accountId, it) } }.map { it.await() }
                }
                _state.update {
                    it.copy(
                        quickFolders = cached[0]?.let { l -> Section(l, loading = true) } ?: it.quickFolders,
                        recent = cached[1]?.let { l -> Section(l, loading = true) } ?: it.recent,
                        modified = cached[2]?.let { l -> Section(l, loading = true) } ?: it.modified,
                        starred = cached[3]?.let { l -> Section(l, loading = true) } ?: it.starred,
                    )
                }
                runCatching { c.accountRepository.refreshStorage(accountId) }
            }
            coroutineScope {
                launch { fetch(accountId, FileSource.QuickFolders(DriveQuery.ROOT), 12) { s, v -> s.copy(quickFolders = v) } }
                launch { fetch(accountId, FileSource.Recent, 12) { s, v -> s.copy(recent = v) } }
                launch { fetch(accountId, FileSource.RecentlyModified, 10) { s, v -> s.copy(modified = v) } }
                launch { fetch(accountId, FileSource.Starred, 12) { s, v -> s.copy(starred = v) } }
            }
            _state.update { it.copy(refreshing = false) }
        }
    }

    private suspend fun fetch(accountId: String, source: FileSource, size: Int, set: (HomeUiState, Section) -> HomeUiState) {
        try {
            val page = repo.list(accountId, source, pageSize = size)
            if (_state.value.accountId == accountId) _state.update { set(it, Section(page.items, loading = false)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (_state.value.accountId == accountId) {
                _state.update { s ->
                    val existing = when (source) {
                        is FileSource.QuickFolders -> s.quickFolders
                        FileSource.Recent -> s.recent
                        FileSource.RecentlyModified -> s.modified
                        else -> s.starred
                    }
                    set(s, existing.copy(loading = false, error = e.userMessage()))
                }
            }
        }
    }

    fun upload(uris: List<Uri>) {
        val accountId = _state.value.accountId ?: return
        if (uris.isEmpty()) return
        viewModelScope.launch {
            try {
                val n = c.transferRepository.enqueueUploads(accountId, DriveQuery.ROOT, uris)
                UiMessages.show("Uploading $n file(s) to My Drive · ${c.accountRepository.state.value.byId(accountId)?.email}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                UiMessages.show(e.userMessage())
            }
        }
    }

    fun createFolder(name: String) {
        val accountId = _state.value.accountId ?: return
        viewModelScope.launch {
            try {
                val f = repo.createFolder(accountId, DriveQuery.ROOT, name)
                UiMessages.show("Created “${f.name}” in My Drive")
                refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                UiMessages.show(e.userMessage())
            }
        }
    }
}

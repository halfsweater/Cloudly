package com.halfblanket.cloudly.ui.search

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.automirrored.rounded.ManageSearch
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.halfblanket.cloudly.AppContainer
import com.halfblanket.cloudly.core.userMessage
import com.halfblanket.cloudly.data.drive.DriveFile
import com.halfblanket.cloudly.data.drive.FileCategory
import com.halfblanket.cloudly.data.repo.AccountsState
import com.halfblanket.cloudly.ui.common.cloudlyViewModel
import com.halfblanket.cloudly.ui.components.Banner
import com.halfblanket.cloudly.ui.components.EmptyState
import com.halfblanket.cloudly.ui.components.FileListItem
import com.halfblanket.cloudly.ui.files.ActionDialog
import com.halfblanket.cloudly.ui.files.FileActionsHost
import com.halfblanket.cloudly.ui.files.FileActionsViewModel
import com.halfblanket.cloudly.ui.files.LoadingFooter
import com.halfblanket.cloudly.ui.theme.CloudlyTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class SearchScope { ACTIVE, ALL }
enum class TypeFilter(val label: String, val categories: Set<FileCategory>?) {
    ANY("Any type", null),
    FOLDERS("Folders", setOf(FileCategory.FOLDER)),
    DOCS("Documents", setOf(FileCategory.DOCUMENT, FileCategory.SPREADSHEET, FileCategory.PRESENTATION, FileCategory.PDF)),
    IMAGES("Images", setOf(FileCategory.IMAGE)),
    VIDEOS("Videos", setOf(FileCategory.VIDEO)),
    AUDIO("Audio", setOf(FileCategory.AUDIO)),
    ARCHIVES("Archives", setOf(FileCategory.ARCHIVE)),
}

data class SearchUiState(
    val query: String = "",
    val scope: SearchScope = SearchScope.ALL,
    val type: TypeFilter = TypeFilter.ANY,
    val results: List<DriveFile> = emptyList(),
    val nextTokens: Map<String, String> = emptyMap(),
    val failures: Map<String, String> = emptyMap(),
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val searched: Boolean = false,
) {
    val visible get() = type.categories?.let { cats -> results.filter { it.category in cats } } ?: results
}

@OptIn(FlowPreview::class)
class SearchViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state
    private var job: Job? = null

    init {
        viewModelScope.launch {
            _state.map { it.query.trim() to it.scope }.distinctUntilChanged().debounce(350).collect { (q, _) ->
                if (q.length >= 2) search() else _state.update { it.copy(results = emptyList(), searched = false, failures = emptyMap(), nextTokens = emptyMap()) }
            }
        }
    }

    fun setQuery(q: String) = _state.update { it.copy(query = q) }
    fun setScope(s: SearchScope) = _state.update { it.copy(scope = s) }
    fun setType(t: TypeFilter) = _state.update { it.copy(type = t) }

    private fun accountIds(): List<String> {
        val acc = c.accountRepository.state.value
        return when (_state.value.scope) {
            SearchScope.ACTIVE -> listOfNotNull(acc.active?.id)
            SearchScope.ALL -> acc.accounts.map { it.id }
        }
    }

    fun search() {
        val q = _state.value.query.trim()
        if (q.isEmpty()) return
        job?.cancel()
        job = viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            try {
                val res = c.driveRepository.searchAccounts(accountIds(), q)
                _state.update {
                    it.copy(
                        results = res.items, nextTokens = res.nextTokens,
                        failures = res.failures.mapValues { (_, e) -> e.userMessage() },
                        loading = false, searched = true,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _state.update { it.copy(loading = false, searched = true, failures = mapOf("" to e.userMessage())) }
            }
        }
    }

    fun loadMore() {
        val s = _state.value
        if (s.nextTokens.isEmpty() || s.loadingMore || s.loading) return
        viewModelScope.launch {
            _state.update { it.copy(loadingMore = true) }
            try {
                val res = c.driveRepository.searchAccounts(emptyList(), s.query.trim(), s.nextTokens)
                _state.update {
                    it.copy(
                        results = (it.results + res.items).distinctBy { f -> f.key },
                        nextTokens = res.nextTokens, loadingMore = false,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _state.update { it.copy(loadingMore = false) }
            }
        }
    }
}

@Composable
fun SearchScreen(
    accounts: AccountsState,
    onBack: () -> Unit,
    onOpenFolder: (DriveFile) -> Unit,
    onPreview: (DriveFile) -> Unit,
) {
    val vm = cloudlyViewModel { SearchViewModel(it) }
    val actions = cloudlyViewModel { FileActionsViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { focus.requestFocus() }
    val multi = accounts.accounts.size > 1
    val open: (DriveFile) -> Unit = { if (it.isFolder) onOpenFolder(it) else onPreview(it) }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp)) {
            IconButton(onClick = onBack, modifier = Modifier.padding(top = 4.dp)) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            TextField(
                value = state.query,
                onValueChange = vm::setQuery,
                placeholder = { Text(if (state.scope == SearchScope.ALL && multi) "Search all accounts" else "Search ${accounts.active?.email ?: "Drive"}") },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
                trailingIcon = {
                    if (state.query.isNotEmpty()) IconButton(onClick = { vm.setQuery("") }) { Icon(Icons.Rounded.Close, "Clear") }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); vm.search() }),
                modifier = Modifier.weight(1f).padding(end = 12.dp).focusRequester(focus),
            )
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (multi) {
                FilterChip(state.scope == SearchScope.ALL, { vm.setScope(SearchScope.ALL) }, { Text("All accounts") })
                FilterChip(
                    state.scope == SearchScope.ACTIVE, { vm.setScope(SearchScope.ACTIVE) },
                    { Text(accounts.active?.email?.substringBefore('@') ?: "This account") },
                )
            }
            TypeFilter.entries.forEach { t -> FilterChip(state.type == t, { vm.setType(t) }, { Text(t.label) }) }
        }
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
        state.failures.forEach { (accId, msg) ->
            Banner(
                Icons.Rounded.ErrorOutline,
                "${accounts.byId(accId)?.email ?: "Search"}: $msg",
                color = CloudlyTheme.extra.danger,
            )
        }
        Box(Modifier.weight(1f)) {
            when {
                !state.searched && !state.loading -> EmptyState(
                    Icons.AutoMirrored.Rounded.ManageSearch, "Search your Drive",
                    if (multi) "Find files and folders by name across every connected account." else "Find files and folders by name.",
                )
                state.searched && state.visible.isEmpty() && !state.loading -> EmptyState(
                    Icons.Rounded.SearchOff, "No matches", "Nothing named “${state.query.trim()}” was found.",
                )
                else -> LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                    items(state.visible, key = { it.accountId + "/" + it.id }) { f ->
                        FileListItem(
                            f,
                            onClick = { open(f) },
                            onMore = { actions.show(ActionDialog.Menu(f)) },
                            onLongClick = { actions.show(ActionDialog.Menu(f)) },
                            account = if (multi) accounts.byId(f.accountId) else null,
                        )
                    }
                    if (state.nextTokens.isNotEmpty()) {
                        item {
                            if (state.loadingMore) LoadingFooter()
                            else Box(Modifier.fillMaxWidth().padding(16.dp)) {
                                OutlinedButton(onClick = vm::loadMore, modifier = Modifier.fillMaxWidth()) { Text("Load more results") }
                            }
                        }
                    }
                }
            }
        }
    }

    FileActionsHost(actions, accounts, onOpen = open, onChanged = vm::search)
}

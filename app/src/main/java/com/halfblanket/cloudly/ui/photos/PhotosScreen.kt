package com.halfblanket.cloudly.ui.photos

import android.app.Activity
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddToDrive
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil3.compose.SubcomposeAsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import com.halfblanket.cloudly.AppContainer
import com.halfblanket.cloudly.auth.GoogleAuthManager
import com.halfblanket.cloudly.core.userMessage
import com.halfblanket.cloudly.data.drive.DriveImageAuthInterceptor
import com.halfblanket.cloudly.data.photos.PickedPhoto
import com.halfblanket.cloudly.data.photos.PickingSession
import com.halfblanket.cloudly.data.repo.AccountsState
import com.halfblanket.cloudly.ui.common.UiMessages
import com.halfblanket.cloudly.ui.components.AccountRow
import com.halfblanket.cloudly.ui.components.CloudlyTopBar
import com.halfblanket.cloudly.ui.files.FolderPickerSheet
import com.halfblanket.cloudly.ui.theme.CloudlyTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface PhotosStage {
    data object Idle : PhotosStage
    data object Starting : PhotosStage
    data class Picking(val session: PickingSession) : PhotosStage
    data object Loading : PhotosStage
    data class Picked(val items: List<PickedPhoto>) : PhotosStage
    data class Error(val message: String) : PhotosStage
}

data class PhotosUiState(
    val accountId: String? = null,
    val stage: PhotosStage = PhotosStage.Idle,
    val selection: Set<String> = emptySet(),
)

/**
 * Drives one Google Photos Picker session for the active account: get consent, open Google's picker,
 * poll until the user is done, then list the picked items so they can be saved to Drive or the phone.
 */
class PhotosViewModel(private val c: AppContainer) : ViewModel() {
    private val api = c.photosApi
    private val _state = MutableStateFlow(PhotosUiState())
    val state: StateFlow<PhotosUiState> = _state

    private val _consent = MutableSharedFlow<PendingIntent>(extraBufferCapacity = 1)
    val consent: SharedFlow<PendingIntent> = _consent
    private val _openPicker = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val openPicker: SharedFlow<String> = _openPicker

    private var pollJob: Job? = null

    init {
        viewModelScope.launch {
            c.accountRepository.state.collect { acc ->
                val id = acc.active?.id
                if (id != _state.value.accountId) {
                    pollJob?.cancel()
                    _state.value = PhotosUiState(accountId = id)
                }
            }
        }
    }

    fun start() {
        val accountId = _state.value.accountId ?: return
        viewModelScope.launch {
            _state.update { it.copy(stage = PhotosStage.Starting) }
            try {
                when (val o = c.auth.authorizePhotos(accountId)) {
                    is GoogleAuthManager.AuthOutcome.NeedsConsent -> {
                        _state.update { it.copy(stage = PhotosStage.Idle) }
                        _consent.emit(o.intent)
                    }
                    is GoogleAuthManager.AuthOutcome.Granted -> openSession(accountId)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                fail(e)
            }
        }
    }

    fun onConsentResult(ok: Boolean, data: Intent?) {
        val accountId = _state.value.accountId ?: return
        viewModelScope.launch {
            try {
                if (!ok) {
                    UiMessages.show("Google Photos access wasn't approved")
                    return@launch
                }
                when (val o = c.auth.outcomeFromIntent(data)) {
                    is GoogleAuthManager.AuthOutcome.Granted -> {
                        c.auth.storePhotos(accountId, o.token)
                        openSession(accountId)
                    }
                    is GoogleAuthManager.AuthOutcome.NeedsConsent -> UiMessages.show("Google Photos access wasn't approved")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                fail(e)
            }
        }
    }

    private suspend fun openSession(accountId: String) {
        val session = api.createSession(accountId)
        _state.update { it.copy(stage = PhotosStage.Picking(session)) }
        _openPicker.emit(session.pickerUri)
        poll(accountId, session)
    }

    private fun poll(accountId: String, session: PickingSession) {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            val deadline = System.currentTimeMillis() + session.timeoutMs
            try {
                while (System.currentTimeMillis() < deadline) {
                    delay(session.pollIntervalMs)
                    val s = api.getSession(accountId, session.id)
                    if (s.mediaItemsSet) {
                        _state.update { it.copy(stage = PhotosStage.Loading) }
                        val items = api.listPicked(accountId, session.id)
                        api.deleteSession(accountId, session.id)
                        _state.update { it.copy(stage = PhotosStage.Picked(items), selection = items.map { i -> i.id }.toSet()) }
                        return@launch
                    }
                }
                api.deleteSession(accountId, session.id)
                _state.update { it.copy(stage = PhotosStage.Error("The picker timed out. Start again when you're ready.")) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                fail(e)
            }
        }
    }

    /** Called when the app returns to the foreground, so results show up without waiting for the next poll. */
    fun pollNow() {
        val st = _state.value
        val stage = st.stage as? PhotosStage.Picking ?: return
        val accountId = st.accountId ?: return
        poll(accountId, stage.session.copy(pollIntervalMs = 300))
    }

    fun reopenPicker() {
        (_state.value.stage as? PhotosStage.Picking)?.let { viewModelScope.launch { _openPicker.emit(it.session.pickerUri) } }
    }

    fun cancel() {
        val st = _state.value
        pollJob?.cancel()
        (st.stage as? PhotosStage.Picking)?.let { s -> st.accountId?.let { a -> viewModelScope.launch { api.deleteSession(a, s.session.id) } } }
        _state.update { it.copy(stage = PhotosStage.Idle, selection = emptySet()) }
    }

    fun toggle(item: PickedPhoto) = _state.update {
        it.copy(selection = if (item.id in it.selection) it.selection - item.id else it.selection + item.id)
    }

    fun selectAll(all: Boolean) = _state.update { s ->
        val items = (s.stage as? PhotosStage.Picked)?.items.orEmpty()
        s.copy(selection = if (all) items.map { it.id }.toSet() else emptySet())
    }

    private fun selected(): List<PickedPhoto> {
        val s = _state.value
        return (s.stage as? PhotosStage.Picked)?.items.orEmpty().filter { it.id in s.selection }
    }

    fun saveToDrive(targetAccountId: String, folderId: String, folderName: String) = save(targetAccountId, folderId, "Saving to $folderName")
    fun saveToPhone() = save(null, null, "Saving to this phone")

    private fun save(account: String?, folder: String?, label: String) {
        val items = selected()
        if (items.isEmpty()) return
        viewModelScope.launch {
            try {
                c.transferRepository.enqueuePhotos(items, account, folder)
                UiMessages.show("$label · ${items.size} item(s) in the background")
                _state.update { it.copy(stage = PhotosStage.Idle, selection = emptySet()) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                UiMessages.show(e.userMessage())
            }
        }
    }

    private fun fail(e: Throwable) {
        if (e is GoogleAuthManager.PhotosConsentRequired) {
            _state.update { it.copy(stage = PhotosStage.Idle) }
            _consent.tryEmit(e.intent)
            return
        }
        val msg = (e as? com.google.android.gms.common.api.ApiException)?.let { c.auth.describeError(it) } ?: e.userMessage()
        _state.update { it.copy(stage = PhotosStage.Error(msg)) }
    }
}

@Composable
fun PhotosScreen(vm: PhotosViewModel, accounts: AccountsState, onAvatar: () -> Unit) {
    val context = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()
    val account = accounts.byId(state.accountId)
    var chooseAccount by remember { mutableStateOf(false) }
    var folderFor by remember { mutableStateOf<String?>(null) }

    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        vm.onConsentResult(it.resultCode == Activity.RESULT_OK, it.data)
    }
    LaunchedEffect(vm) { vm.consent.collect { consent.launch(IntentSenderRequest.Builder(it.intentSender).build()) } }
    LaunchedEffect(vm) {
        vm.openPicker.collect { uri ->
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)))
            } catch (_: ActivityNotFoundException) {
                UiMessages.show("No browser is available to open Google Photos")
            }
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) vm.pollNow() }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    Column(Modifier.fillMaxSize()) {
        CloudlyTopBar("Photos", account, onAvatar)
        when (val stage = state.stage) {
            PhotosStage.Idle -> PhotosIntro(account?.email, onStart = vm::start)
            PhotosStage.Starting, PhotosStage.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text(if (stage == PhotosStage.Loading) "Getting your picks…" else "Connecting to Google Photos…", color = CloudlyTheme.extra.muted)
                }
            }
            is PhotosStage.Picking -> CenteredMessage(
                icon = Icons.Rounded.PhotoLibrary,
                title = "Choose in Google Photos",
                body = "Pick photos and videos in Google's picker, tap Done, then come back here. Your picks will appear automatically.",
            ) {
                Button(onClick = vm::reopenPicker, modifier = Modifier.fillMaxWidth()) { Text("Open Google Photos picker") }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = vm::cancel) { Text("Cancel") }
            }
            is PhotosStage.Error -> CenteredMessage(Icons.Rounded.CloudOff, "Couldn't open Google Photos", stage.message) {
                Button(onClick = vm::start, modifier = Modifier.fillMaxWidth()) { Text("Try again") }
            }
            is PhotosStage.Picked -> PickedGrid(
                items = stage.items,
                selection = state.selection,
                onToggle = vm::toggle,
                onSelectAll = vm::selectAll,
                onSaveToDrive = { if (accounts.accounts.size > 1) chooseAccount = true else folderFor = state.accountId },
                onSaveToPhone = vm::saveToPhone,
                onDone = vm::cancel,
            )
        }
    }

    if (chooseAccount) {
        AlertDialog(
            onDismissRequest = { chooseAccount = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = { Text("Save to which Drive?") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    accounts.accounts.forEach { acc ->
                        AccountRow(acc, selected = acc.id == state.accountId, onClick = { chooseAccount = false; folderFor = acc.id })
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { chooseAccount = false }) { Text("Cancel") } },
        )
    }
    folderFor?.let { target ->
        FolderPickerSheet(
            accountId = target,
            account = accounts.byId(target),
            title = "Save ${state.selection.size} item(s)",
            confirmLabel = "Save here",
            excludeIds = emptySet(),
            onPick = { id, name -> folderFor = null; vm.saveToDrive(target, id, name) },
            onDismiss = { folderFor = null },
        )
    }
}

@Composable
private fun PhotosIntro(email: String?, onStart: () -> Unit) {
    CenteredMessage(
        icon = Icons.Rounded.PhotoLibrary,
        title = "Your Google Photos",
        body = "Pick photos and videos from Google Photos${email?.let { " ($it)" } ?: ""}, then save them to any of your Drives or to this phone.",
    ) {
        Button(onClick = onStart, modifier = Modifier.fillMaxWidth().height(52.dp), shape = MaterialTheme.shapes.medium) {
            Text("Choose from Google Photos")
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "Google only lets apps see the photos you pick here, not your whole library. That's Google's privacy rule for all third-party apps.",
            style = MaterialTheme.typography.bodySmall, color = CloudlyTheme.extra.muted, textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun CenteredMessage(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String,
    actions: @Composable () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(96.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp)) }
        Spacer(Modifier.height(20.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = CloudlyTheme.extra.muted, textAlign = TextAlign.Center)
        Spacer(Modifier.height(28.dp))
        actions()
    }
}

@Composable
private fun PickedGrid(
    items: List<PickedPhoto>,
    selection: Set<String>,
    onToggle: (PickedPhoto) -> Unit,
    onSelectAll: (Boolean) -> Unit,
    onSaveToDrive: () -> Unit,
    onSaveToPhone: () -> Unit,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${selection.size} of ${items.size} selected", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = { onSelectAll(selection.size != items.size) }) { Text(if (selection.size == items.size) "Clear" else "Select all") }
            TextButton(onClick = onDone) { Text("Done") }
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(110.dp),
            contentPadding = PaddingValues(8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.weight(1f),
        ) {
            items(items, key = { it.id }) { item ->
                val selected = item.id in selection
                Box(Modifier.aspectRatio(1f).clip(MaterialTheme.shapes.small).clickable { onToggle(item) }) {
                    SubcomposeAsyncImage(
                        model = remember(item.id) {
                            ImageRequest.Builder(context).data(item.thumbnailUrl(360))
                                .httpHeaders(NetworkHeaders.Builder().set(DriveImageAuthInterceptor.PHOTOS_HEADER, item.accountId).build())
                                .build()
                        },
                        contentDescription = item.filename,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    )
                    if (item.isVideo) {
                        Icon(Icons.Rounded.PlayArrow, "Video", tint = Color.White,
                            modifier = Modifier.align(Alignment.BottomStart).padding(6.dp).size(20.dp))
                    }
                    if (!selected) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
                    Icon(
                        if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked, null,
                        tint = if (selected) MaterialTheme.colorScheme.primary else Color.White,
                        modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp)
                            .background(if (selected) Color.White else Color.Transparent, CircleShape),
                    )
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(8.dp)) }
        }
        Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onSaveToDrive, enabled = selection.isNotEmpty(), modifier = Modifier.weight(1f).height(50.dp)) {
                    Icon(Icons.Rounded.AddToDrive, null); Spacer(Modifier.width(8.dp)); Text("Save to Drive")
                }
                FilledTonalButton(onClick = onSaveToPhone, enabled = selection.isNotEmpty(), modifier = Modifier.weight(1f).height(50.dp)) {
                    Icon(Icons.Rounded.Download, null); Spacer(Modifier.width(8.dp)); Text("To phone")
                }
            }
        }
    }
}


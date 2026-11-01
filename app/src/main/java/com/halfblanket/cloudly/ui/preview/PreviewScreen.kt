package com.halfblanket.cloudly.ui.preview

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.annotation.OptIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.OpenWith
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarOutline
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import coil3.compose.SubcomposeAsyncImage
import com.halfblanket.cloudly.AppContainer
import com.halfblanket.cloudly.core.Format
import com.halfblanket.cloudly.core.userMessage
import com.halfblanket.cloudly.data.drive.DriveFile
import com.halfblanket.cloudly.data.drive.FileCategory
import com.halfblanket.cloudly.data.drive.MimeTypes
import com.halfblanket.cloudly.data.repo.AccountsState
import com.halfblanket.cloudly.data.repo.TransferRepository
import com.halfblanket.cloudly.ui.common.cloudlyViewModel
import com.halfblanket.cloudly.ui.common.container
import com.halfblanket.cloudly.ui.components.AccountBadge
import com.halfblanket.cloudly.ui.components.EmptyState
import com.halfblanket.cloudly.ui.components.FileTypeIcon
import com.halfblanket.cloudly.ui.components.driveImageRequest
import com.halfblanket.cloudly.ui.files.ActionDialog
import com.halfblanket.cloudly.ui.files.FileActionsHost
import com.halfblanket.cloudly.ui.files.FileActionsViewModel
import com.halfblanket.cloudly.ui.files.describeType
import com.halfblanket.cloudly.ui.theme.CloudlyTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

sealed interface PreviewContent {
    data object Loading : PreviewContent
    data object Image : PreviewContent
    data object Media : PreviewContent
    data class Downloading(val progress: Float?) : PreviewContent
    data class Pdf(val file: File) : PreviewContent
    data class Text(val text: String, val truncated: Boolean) : PreviewContent
    data class Unsupported(val reason: String) : PreviewContent
    data class Error(val message: String) : PreviewContent
}

data class PreviewUiState(val file: DriveFile? = null, val content: PreviewContent = PreviewContent.Loading)

/** Loads a file with exactly the account it belongs to ([accountId]) and decides how to preview it. */
class PreviewViewModel(private val c: AppContainer, private val accountId: String, private val fileId: String) : ViewModel() {
    private val _state = MutableStateFlow(PreviewUiState())
    val state: StateFlow<PreviewUiState> = _state

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(content = PreviewContent.Loading) }
            try {
                val file = c.driveRepository.get(accountId, fileId)
                _state.value = PreviewUiState(file, PreviewContent.Loading)
                _state.update { it.copy(content = decide(file)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _state.update { it.copy(content = PreviewContent.Error(e.userMessage())) }
            }
        }
    }

    fun refreshMetadata() {
        viewModelScope.launch {
            runCatching { c.driveRepository.get(accountId, fileId) }.onSuccess { f -> _state.update { it.copy(file = f) } }
        }
    }

    private suspend fun decide(file: DriveFile): PreviewContent {
        if (file.isShortcut) return PreviewContent.Unsupported("This is a shortcut to another item. Open it in Google Drive.")
        if (!file.capabilities.canDownload && !file.isGoogleNative) {
            return PreviewContent.Unsupported("The owner has disabled downloading, so it can only be viewed in Google Drive.")
        }
        return when {
            file.category == FileCategory.IMAGE && !file.isGoogleNative -> PreviewContent.Image
            file.category == FileCategory.VIDEO || file.category == FileCategory.AUDIO -> PreviewContent.Media
            file.mimeType == MimeTypes.PDF -> fetchPdf(file, asPdf = false)
            MimeTypes.canExportPdf(file.mimeType) -> fetchPdf(file, asPdf = true)
            MimeTypes.isText(file.mimeType, file.name) && (file.size ?: 0) <= 2_000_000 -> fetchText(file)
            else -> PreviewContent.Unsupported("There's no built-in preview for ${describeType(file).substringBefore(" ·")} files.")
        }
    }

    private suspend fun fetchPdf(file: DriveFile, asPdf: Boolean): PreviewContent {
        _state.update { it.copy(content = PreviewContent.Downloading(null)) }
        val local = c.transferRepository.fetchToCache(file, TransferRepository.CachePurpose.PREVIEW, asPdf = asPdf) { d, t ->
            val total = if (t > 0) t else file.size ?: 0
            _state.update { it.copy(content = PreviewContent.Downloading(if (total > 0) d.toFloat() / total else null)) }
        }
        return PreviewContent.Pdf(local)
    }

    private suspend fun fetchText(file: DriveFile): PreviewContent {
        _state.update { it.copy(content = PreviewContent.Downloading(null)) }
        val local = c.transferRepository.fetchToCache(file, TransferRepository.CachePurpose.PREVIEW)
        return withContext(Dispatchers.IO) {
            val limit = 200_000
            val bytes = local.inputStream().use { it.readNBytesCompat(limit + 1) }
            PreviewContent.Text(String(bytes, 0, minOf(bytes.size, limit), Charsets.UTF_8), bytes.size > limit)
        }
    }
}

private fun java.io.InputStream.readNBytesCompat(n: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(8192)
    var remaining = n
    while (remaining > 0) {
        val r = read(buf, 0, minOf(buf.size, remaining))
        if (r < 0) break
        out.write(buf, 0, r)
        remaining -= r
    }
    return out.toByteArray()
}

@Composable
fun PreviewScreen(accountId: String, fileId: String, accounts: AccountsState, onBack: () -> Unit) {
    val vm = cloudlyViewModel(key = "$accountId/$fileId") { PreviewViewModel(it, accountId, fileId) }
    val actions = cloudlyViewModel(key = "actions/$accountId/$fileId") { FileActionsViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val file = state.file
    var chrome by remember { mutableStateOf(true) }
    val immersive = state.content is PreviewContent.Image || state.content is PreviewContent.Media

    Box(Modifier.fillMaxSize().background(if (immersive) Color.Black else MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize()) {
            if (!immersive || chrome) Spacer(Modifier.statusBarsPadding().height(64.dp))
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (val c = state.content) {
                    PreviewContent.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    is PreviewContent.Downloading -> Column(
                        Modifier.align(Alignment.Center).padding(40.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        file?.let { FileTypeIcon(it, 64.dp) }
                        Spacer(Modifier.height(20.dp))
                        Text(if (file?.isGoogleNative == true) "Preparing preview…" else "Downloading…", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(12.dp))
                        if (c.progress != null) LinearProgressIndicator(progress = { c.progress }, Modifier.width(220.dp))
                        else LinearProgressIndicator(Modifier.width(220.dp))
                    }
                    PreviewContent.Image -> file?.let { ZoomableImage(it) { chrome = !chrome } }
                    PreviewContent.Media -> file?.let { MediaPlayer(it) }
                    is PreviewContent.Pdf -> PdfView(c.file)
                    is PreviewContent.Text -> TextPreview(c)
                    is PreviewContent.Unsupported -> file?.let { UnsupportedPreview(it, c.reason, actions) }
                    is PreviewContent.Error -> EmptyState(Icons.Rounded.CloudOff, "Couldn't open this file", c.message, actionLabel = "Try again", onAction = vm::load)
                }
            }
        }
        if (!immersive || chrome) {
            PreviewTopBar(file, accounts, immersive, onBack, actions)
        }
    }

    FileActionsHost(actions, accounts, onOpen = {}, onChanged = vm::refreshMetadata)
}

@Composable
private fun PreviewTopBar(file: DriveFile?, accounts: AccountsState, immersive: Boolean, onBack: () -> Unit, actions: FileActionsViewModel) {
    val bg = if (immersive) Color.Black.copy(alpha = 0.55f) else MaterialTheme.colorScheme.background
    val fg = if (immersive) Color.White else MaterialTheme.colorScheme.onSurface
    Row(
        Modifier.fillMaxWidth().background(bg).statusBarsPadding().height(64.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = fg) }
        Column(Modifier.weight(1f)) {
            Text(file?.name ?: "", style = MaterialTheme.typography.titleMedium, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (file != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AccountBadge(accounts.byId(file.accountId))
                    Text(
                        listOfNotNull(file.displaySize?.let { Format.bytes(it) }, Format.relative(file.modifiedTime)).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = fg.copy(alpha = 0.7f),
                    )
                }
            }
        }
        if (file != null) {
            IconButton(onClick = { actions.setStarred(listOf(file), !file.starred) }) {
                Icon(if (file.starred) Icons.Rounded.Star else Icons.Rounded.StarOutline, "Star", tint = if (file.starred) CloudlyTheme.extra.warning else fg)
            }
            IconButton(onClick = { actions.share(file) }) { Icon(Icons.Rounded.Share, "Share", tint = fg) }
            IconButton(onClick = { actions.download(listOf(file)) }) { Icon(Icons.Rounded.Download, "Download", tint = fg) }
            IconButton(onClick = { actions.show(ActionDialog.Menu(file)) }) { Icon(Icons.Rounded.MoreVert, "More", tint = fg) }
        }
    }
}

@Composable
private fun ZoomableImage(file: DriveFile, onTap: () -> Unit) {
    val api = LocalContext.current.container.driveApi
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    SubcomposeAsyncImage(
        model = driveImageRequest(file.accountId, api.mediaUrl(file.id)),
        contentDescription = file.name,
        contentScale = ContentScale.Fit,
        loading = {
            Box(Modifier.fillMaxSize()) {
                file.thumbnailLink?.let {
                    SubcomposeAsyncImage(
                        model = driveImageRequest(file.accountId, it.replace(Regex("=s\\d+$"), "=s1200")),
                        contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize(),
                    )
                }
                CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
            }
        },
        error = { EmptyState(Icons.Rounded.CloudOff, "Couldn't load image", "Check your connection and try again.") },
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures(onTap = { onTap() }, onDoubleTap = { scale = if (scale > 1f) 1f else 2.5f; offset = Offset.Zero }) }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 6f)
                    offset = if (scale == 1f) Offset.Zero else offset + pan
                }
            }
            .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
    )
}

/** Streams video/audio straight from Drive, authorising each request with the file's own account. */
@OptIn(UnstableApi::class)
@Composable
private fun MediaPlayer(file: DriveFile) {
    val context = LocalContext.current
    val c = context.container
    val player = remember(file.key) {
        val http = OkHttpDataSource.Factory(c.http)
        val authed = ResolvingDataSource.Factory(http) { spec ->
            val token = runBlocking { c.auth.token(file.accountId) }
            spec.withAdditionalHeaders(mapOf("Authorization" to "Bearer $token"))
        }
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(authed))
            .build().apply {
                setMediaItem(MediaItem.Builder().setUri(c.driveApi.mediaUrl(file.id)).setMimeType(file.mimeType).build())
                prepare()
                playWhenReady = true
            }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (file.category == FileCategory.AUDIO) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(bottom = 160.dp)) {
                FileTypeIcon(file, 120.dp)
                Spacer(Modifier.height(16.dp))
                Text(file.name, color = Color.White, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 32.dp))
            }
        }
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = true
                    controllerShowTimeoutMs = if (file.category == FileCategory.AUDIO) 0 else 3000
                    controllerHideOnTouch = file.category != FileCategory.AUDIO
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** Renders PDF pages lazily with the platform PdfRenderer (one page open at a time). */
@Composable
private fun PdfView(file: File) {
    val renderer = remember(file) {
        runCatching { PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)) }
    }
    DisposableEffect(renderer) { onDispose { renderer.getOrNull()?.close() } }
    val pdf = renderer.getOrNull()
    if (pdf == null) {
        EmptyState(Icons.Rounded.CloudOff, "Can't display this PDF", renderer.exceptionOrNull()?.userMessage() ?: "The file may be damaged or password protected.")
        return
    }
    val mutex = remember(pdf) { Mutex() }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val widthPx = with(LocalDensity.current) { (maxWidth - 24.dp).roundToPx() }
        LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(pdf.pageCount) { index ->
                val bitmap by produceState<Bitmap?>(null, index, widthPx) {
                    value = withContext(Dispatchers.IO) {
                        mutex.withLock {
                            pdf.openPage(index).use { page ->
                                val h = (widthPx.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
                                Bitmap.createBitmap(widthPx, h, Bitmap.Config.ARGB_8888).also { bmp ->
                                    bmp.eraseColor(android.graphics.Color.WHITE)
                                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                }
                            }
                        }
                    }
                }
                val ratio = bitmap?.let { it.width.toFloat() / it.height } ?: 0.707f
                Box(
                    Modifier.fillMaxWidth().aspectRatio(ratio).background(Color.White, MaterialTheme.shapes.small),
                    contentAlignment = Alignment.Center,
                ) {
                    bitmap?.let { Image(it.asImageBitmap(), "Page ${index + 1}", Modifier.fillMaxSize()) }
                        ?: CircularProgressIndicator(color = Color.Gray)
                }
            }
        }
    }
}

@Composable
private fun TextPreview(c: PreviewContent.Text) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(16.dp)) {
        SelectionContainer {
            Text(c.text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }
        if (c.truncated) {
            Text("Preview truncated — download the file to see everything.", color = CloudlyTheme.extra.muted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 12.dp))
        }
    }
}

@Composable
private fun UnsupportedPreview(file: DriveFile, reason: String, actions: FileActionsViewModel) {
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        FileTypeIcon(file, 88.dp)
        Spacer(Modifier.height(20.dp))
        Text(file.name, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(reason, style = MaterialTheme.typography.bodyMedium, color = CloudlyTheme.extra.muted, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        val downloadable = file.capabilities.canDownload && (!file.isGoogleNative || MimeTypes.exportFor(file.mimeType) != null)
        if (downloadable) {
            Button(onClick = { actions.openWith(file) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.OpenWith, null); Spacer(Modifier.width(8.dp)); Text("Open with another app")
            }
            Spacer(Modifier.height(8.dp))
        }
        OutlinedButton(onClick = { actions.openInDrive(file) }, modifier = Modifier.fillMaxWidth(), enabled = file.webViewLink != null) {
            Icon(Icons.AutoMirrored.Rounded.OpenInNew, null); Spacer(Modifier.width(8.dp)); Text("Open in Google Drive")
        }
    }
}

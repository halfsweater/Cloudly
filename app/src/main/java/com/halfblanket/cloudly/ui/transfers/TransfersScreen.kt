package com.halfblanket.cloudly.ui.transfers

import android.content.Intent
import android.net.Uri
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
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.halfblanket.cloudly.core.Format
import com.halfblanket.cloudly.data.db.TransferEntity
import com.halfblanket.cloudly.data.db.TransferKind
import com.halfblanket.cloudly.data.db.TransferStatus
import com.halfblanket.cloudly.data.repo.AccountsState
import com.halfblanket.cloudly.ui.common.UiMessages
import com.halfblanket.cloudly.ui.common.container
import com.halfblanket.cloudly.ui.components.AccountBadge
import com.halfblanket.cloudly.ui.components.CloudlyCard
import com.halfblanket.cloudly.ui.components.EmptyState
import com.halfblanket.cloudly.ui.theme.CloudlyTheme
import kotlinx.coroutines.launch

@Composable
fun TransfersScreen(accounts: AccountsState, onBack: () -> Unit) {
    val context = LocalContext.current
    val repo = context.container.transferRepository
    val scope = rememberCoroutineScope()
    val transfers by repo.observe().collectAsStateWithLifecycle(emptyList())

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.statusBarsPadding().fillMaxWidth().height(64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            Text("Transfers", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = { scope.launch { repo.clearFinished() } }, enabled = transfers.any { !it.isActive }) { Text("Clear finished") }
        }
        if (transfers.isEmpty()) {
            EmptyState(Icons.Rounded.SwapVert, "No transfers", "Uploads, downloads and copies between accounts will show up here.")
        } else {
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(transfers, key = { it.id }) { t ->
                    TransferCard(
                        t, accounts,
                        onCancel = { scope.launch { repo.cancel(t.id) } },
                        onRetry = { scope.launch { repo.retry(t.id) } },
                        onOpen = {
                            t.resultUri?.let { raw ->
                                var uri = Uri.parse(raw)
                                if (uri.scheme == "file") {
                                    // Android 8–9 downloads are plain files; expose them through our FileProvider.
                                    uri = com.halfblanket.cloudly.work.LocalFiles.shareUri(context, java.io.File(uri.path!!))
                                }
                                val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                runCatching { context.startActivity(Intent.createChooser(intent, "Open with")) }
                                    .onFailure { UiMessages.show("No app can open this file") }
                            }
                        },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }
}

@Composable
private fun TransferCard(
    t: TransferEntity,
    accounts: AccountsState,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val (icon, verb) = when (t.kind) {
        TransferKind.UPLOAD -> Icons.Rounded.CloudUpload to "Upload"
        TransferKind.DOWNLOAD -> Icons.Rounded.CloudDownload to "Download"
        TransferKind.CROSS_COPY -> Icons.Rounded.SwapHoriz to "Copy between accounts"
        TransferKind.CROSS_MOVE -> Icons.Rounded.SwapHoriz to "Move between accounts"
        TransferKind.PHOTOS_TO_DRIVE -> Icons.Rounded.CloudUpload to "Google Photos → Drive"
        TransferKind.PHOTOS_DOWNLOAD -> Icons.Rounded.CloudDownload to "Google Photos → phone"
    }
    val statusColor = when (t.status) {
        TransferStatus.SUCCEEDED -> CloudlyTheme.extra.success
        TransferStatus.FAILED -> CloudlyTheme.extra.danger
        TransferStatus.CANCELLED -> CloudlyTheme.extra.muted
        else -> MaterialTheme.colorScheme.primary
    }
    CloudlyCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).clip(MaterialTheme.shapes.small).background(statusColor.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                    Icon(
                        when (t.status) {
                            TransferStatus.SUCCEEDED -> Icons.Rounded.CheckCircle
                            TransferStatus.FAILED -> Icons.Rounded.ErrorOutline
                            else -> icon
                        },
                        null, tint = statusColor, modifier = Modifier.size(22.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(t.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("$verb · ${statusLabel(t)}", style = MaterialTheme.typography.bodySmall, color = CloudlyTheme.extra.muted)
                }
                when {
                    t.isActive -> IconButton(onClick = onCancel) { Icon(Icons.Rounded.Close, "Cancel") }
                    t.status == TransferStatus.FAILED || t.status == TransferStatus.CANCELLED ->
                        IconButton(onClick = onRetry) { Icon(Icons.Rounded.Refresh, "Retry") }
                    t.status == TransferStatus.SUCCEEDED && t.resultUri != null -> TextButton(onClick = onOpen) { Text("Open") }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AccountBadge(accounts.byId(t.sourceAccountId))
                if (t.targetAccountId != null) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = CloudlyTheme.extra.muted, modifier = Modifier.size(14.dp))
                    AccountBadge(accounts.byId(t.targetAccountId))
                }
            }
            if (t.isActive) {
                Spacer(Modifier.height(10.dp))
                if (t.status == TransferStatus.RUNNING && (t.bytesTotal > 0 || t.itemsTotal > 0)) {
                    LinearProgressIndicator(progress = { t.progress }, modifier = Modifier.fillMaxWidth())
                } else LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            val detail = t.error ?: t.detail
            if (!detail.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    detail, style = MaterialTheme.typography.bodySmall,
                    color = if (t.status == TransferStatus.FAILED) CloudlyTheme.extra.danger else CloudlyTheme.extra.muted,
                    maxLines = 3, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun statusLabel(t: TransferEntity): String = when (t.status) {
    TransferStatus.QUEUED -> if (t.error != null) "Waiting to retry" else "Waiting"
    TransferStatus.RUNNING -> buildString {
        if (t.bytesTotal > 0) append("${Format.bytes(t.bytesDone)} of ${Format.bytes(t.bytesTotal)}")
        if (t.itemsTotal > 1) {
            if (isNotEmpty()) append(" · ")
            append("${t.itemsDone}/${t.itemsTotal} items")
        }
        if (isEmpty()) append("In progress")
    }
    TransferStatus.SUCCEEDED -> "Done · ${Format.relative(java.time.Instant.ofEpochMilli(t.updatedAt))}"
    TransferStatus.FAILED -> "Failed"
    TransferStatus.CANCELLED -> "Cancelled"
}

package com.halfblanket.cloudly.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.ManageAccounts
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.SubcomposeAsyncImage
import coil3.request.ImageRequest
import com.halfblanket.cloudly.core.Format
import com.halfblanket.cloudly.data.db.AccountEntity
import com.halfblanket.cloudly.data.db.DriveAccess
import com.halfblanket.cloudly.ui.theme.CloudlyTheme
import com.halfblanket.cloudly.ui.theme.accountColor

val AccountEntity.color: Color get() = accountColor(colorIndex)

/** Profile picture with an initial fallback, optionally ringed in the account's colour. */
@Composable
fun AccountAvatar(account: AccountEntity?, size: Dp = 36.dp, ring: Boolean = false, modifier: Modifier = Modifier) {
    val color = account?.color ?: MaterialTheme.colorScheme.primary
    val ringMod = if (ring) Modifier.border(2.dp, color, CircleShape).padding(3.dp) else Modifier
    Box(modifier.then(ringMod)) {
        val initial = (account?.displayName?.firstOrNull() ?: account?.email?.firstOrNull() ?: '?').uppercaseChar()
        val fallback: @Composable () -> Unit = {
            Box(
                Modifier.size(size).clip(CircleShape).background(color.copy(alpha = 0.22f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(initial.toString(), color = color, fontWeight = FontWeight.SemiBold, fontSize = (size.value * 0.42f).sp)
            }
        }
        if (account?.photoUrl.isNullOrBlank()) {
            fallback()
        } else {
            SubcomposeAsyncImage(
                model = ImageRequest.Builder(LocalContext.current).data(account?.photoUrl).build(),
                contentDescription = account?.displayName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size).clip(CircleShape),
                loading = { fallback() },
                error = { fallback() },
            )
        }
    }
}

/** Tiny marker showing which account an item belongs to. */
@Composable
fun AccountBadge(account: AccountEntity?, modifier: Modifier = Modifier) {
    if (account == null) return
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(account.color.copy(alpha = 0.14f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(account.color))
        Spacer(Modifier.width(4.dp))
        Text(
            account.email.substringBefore('@'),
            style = MaterialTheme.typography.labelSmall,
            color = account.color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Compact account selector shown at the top of screens. */
@Composable
fun AccountSwitcherChip(account: AccountEntity?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier,
    ) {
        Row(
            Modifier.padding(start = 4.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AccountAvatar(account, size = 28.dp)
            Spacer(Modifier.width(8.dp))
            Text(
                account?.email?.substringBefore('@') ?: "Account",
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(end = 2.dp).widthIn(max = 120.dp),
            )
            if (account?.needsReauth == true) {
                Icon(Icons.Rounded.ErrorOutline, null, tint = CloudlyTheme.extra.warning, modifier = Modifier.size(16.dp))
            }
            Icon(Icons.Rounded.ExpandMore, null, modifier = Modifier.size(18.dp), tint = CloudlyTheme.extra.muted)
        }
    }
}


@Composable
fun AccountSwitcherSheet(
    accounts: List<AccountEntity>,
    activeId: String?,
    onSelect: (AccountEntity) -> Unit,
    onAdd: () -> Unit,
    onManage: () -> Unit,
    onDismiss: () -> Unit,
    onTransfers: (() -> Unit)? = null,
    activeTransfers: Int = 0,
    onSettings: (() -> Unit)? = null,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            Text(
                "Accounts",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            accounts.forEach { acc ->
                AccountRow(acc, selected = acc.id == activeId, onClick = { onSelect(acc) })
            }
            Spacer(Modifier.height(8.dp))
            SheetAction(Icons.Rounded.Add, "Add another account", onAdd)
            SheetAction(Icons.Rounded.ManageAccounts, "Manage accounts", onManage)
            androidx.compose.material3.HorizontalDivider(Modifier.padding(vertical = 6.dp), color = CloudlyTheme.extra.line)
            if (onTransfers != null) {
                SheetAction(
                    androidx.compose.material.icons.Icons.Rounded.SwapVert, "Transfers", onTransfers,
                    supporting = if (activeTransfers > 0) "$activeTransfers in progress" else "Uploads, downloads and copies",
                )
            }
            if (onSettings != null) {
                SheetAction(androidx.compose.material.icons.Icons.Rounded.Settings, "Settings", onSettings,
                    supporting = "Theme, downloads, cache, privacy")
            }
        }
    }
}

@Composable
fun AccountRow(account: AccountEntity, selected: Boolean, onClick: () -> Unit, trailing: @Composable (() -> Unit)? = null) {
    val bg by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f) else Color.Transparent,
        label = "accountRowBg",
    )
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AccountAvatar(account, size = 40.dp, ring = selected)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(account.displayName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                account.email,
                style = MaterialTheme.typography.bodySmall,
                color = CloudlyTheme.extra.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            StorageBar(account.storageUsage, account.storageLimit, color = account.color, height = 4.dp)
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    storageSummary(account),
                    style = MaterialTheme.typography.labelSmall,
                    color = CloudlyTheme.extra.muted,
                )
                if (account.access == DriveAccess.APP_FILES_ONLY) MiniTag("Limited", CloudlyTheme.extra.warning)
                if (account.needsReauth) MiniTag("Reconnect", CloudlyTheme.extra.danger)
            }
        }
        if (trailing != null) trailing()
        else if (selected) Icon(Icons.Rounded.CheckCircle, "Active", tint = MaterialTheme.colorScheme.primary)
    }
}

fun storageSummary(account: AccountEntity): String =
    if (account.storageLimit != null) "${Format.bytes(account.storageUsage)} of ${Format.bytes(account.storageLimit)}"
    else "${Format.bytes(account.storageUsage)} used · unlimited"

@Composable
fun MiniTag(text: String, color: Color) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

@Composable
fun SheetAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    enabled: Boolean = true,
    supporting: String? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val alpha = if (enabled) 1f else 0.38f
        Icon(icon, null, tint = tint.copy(alpha = alpha), modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = tint.copy(alpha = alpha))
            if (supporting != null) {
                Text(supporting, style = MaterialTheme.typography.bodySmall, color = CloudlyTheme.extra.muted.copy(alpha = alpha))
            }
        }
    }
}

package com.halfblanket.cloudly.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.halfblanket.cloudly.data.db.AccountEntity
import com.halfblanket.cloudly.ui.theme.CloudlyTheme

/**
 * The one header every main screen uses: a title on the left, optional actions, and the active account's
 * avatar on the right. Tapping the avatar opens the account menu (switch account, transfers, settings).
 */
@Composable
fun CloudlyTopBar(
    title: String,
    account: AccountEntity?,
    onAvatar: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier.statusBarsPadding().fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 10.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = CloudlyTheme.extra.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        actions()
        Spacer(Modifier.width(4.dp))
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .clickable(onClick = onAvatar)
                .semantics { contentDescription = "Account menu" },
            contentAlignment = Alignment.Center,
        ) {
            AccountAvatar(account, size = 34.dp, ring = true)
            if (account?.needsReauth == true) {
                Box(
                    Modifier.align(Alignment.TopEnd).size(12.dp).clip(CircleShape)
                        .background(CloudlyTheme.extra.danger)
                        .border(2.dp, MaterialTheme.colorScheme.background, CircleShape),
                )
            }
        }
    }
}

/** Large, obvious search entry point ("Search in Drive"). */
@Composable
fun SearchPill(placeholder: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .height(52.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Search, null, tint = CloudlyTheme.extra.muted)
        Spacer(Modifier.width(12.dp))
        Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = CloudlyTheme.extra.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

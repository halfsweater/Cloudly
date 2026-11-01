package com.halfblanket.cloudly.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderShared
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.automirrored.rounded.Shortcut
import androidx.compose.material.icons.rounded.Slideshow
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import com.halfblanket.cloudly.core.Format
import com.halfblanket.cloudly.data.db.AccountEntity
import com.halfblanket.cloudly.data.drive.DriveFile
import com.halfblanket.cloudly.data.drive.DriveImageAuthInterceptor
import com.halfblanket.cloudly.data.drive.FileCategory
import com.halfblanket.cloudly.ui.theme.CloudlyTheme
import com.halfblanket.cloudly.ui.theme.categoryColor

fun categoryIcon(file: DriveFile): ImageVector = when {
    file.isShortcut -> Icons.AutoMirrored.Rounded.Shortcut
    file.isFolder && file.shared -> Icons.Rounded.FolderShared
    else -> categoryIcon(file.category)
}

fun categoryIcon(category: FileCategory): ImageVector = when (category) {
    FileCategory.FOLDER -> Icons.Rounded.Folder
    FileCategory.IMAGE -> Icons.Rounded.Image
    FileCategory.VIDEO -> Icons.Rounded.Movie
    FileCategory.AUDIO -> Icons.Rounded.AudioFile
    FileCategory.PDF -> Icons.Rounded.PictureAsPdf
    FileCategory.DOCUMENT -> Icons.Rounded.Description
    FileCategory.SPREADSHEET -> Icons.Rounded.TableChart
    FileCategory.PRESENTATION -> Icons.Rounded.Slideshow
    FileCategory.ARCHIVE -> Icons.Rounded.FolderZip
    FileCategory.OTHER -> Icons.AutoMirrored.Rounded.InsertDriveFile
}

/** Rounded tile with the file type's icon in its category colour. */
@Composable
fun FileTypeIcon(file: DriveFile, size: Dp = 40.dp, modifier: Modifier = Modifier) {
    val color = categoryColor(file.category)
    Box(
        modifier.size(size).clip(RoundedCornerShape(size * 0.3f)).background(color.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(categoryIcon(file), null, tint = color, modifier = Modifier.size(size * 0.55f))
    }
}

/** Builds an image request for Drive content, authorised with the file's own account. */
@Composable
fun driveImageRequest(accountId: String, url: String): ImageRequest {
    val context = LocalContext.current
    return remember(accountId, url) {
        ImageRequest.Builder(context)
            .data(url)
            .httpHeaders(NetworkHeaders.Builder().set(DriveImageAuthInterceptor.HEADER, accountId).build())
            .build()
    }
}

/** Drive thumbnail at a requested pixel size, falling back to the file-type icon. */
@Composable
fun FileThumbnail(file: DriveFile, modifier: Modifier = Modifier, iconSize: Dp = 40.dp, thumbPx: Int = 320) {
    val link = file.thumbnailLink
    if (link == null || file.isFolder) {
        Box(modifier, contentAlignment = Alignment.Center) { FileTypeIcon(file, iconSize) }
        return
    }
    val sized = remember(link, thumbPx) { link.replace(Regex("=s\\d+$"), "=s$thumbPx") }
    SubcomposeAsyncImage(
        model = driveImageRequest(file.accountId, sized),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier,
        loading = { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { FileTypeIcon(file, iconSize) } },
        error = { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { FileTypeIcon(file, iconSize) } },
    )
}

fun fileSubtitle(file: DriveFile): String {
    val parts = mutableListOf<String>()
    parts += Format.relative(file.modifiedTime)
    if (!file.isFolder) file.displaySize?.let { parts += Format.bytes(it) }
    if (!file.ownedByMe && file.ownerName != null) parts += file.ownerName
    return parts.joinToString(" · ")
}

@Composable
fun FileListItem(
    file: DriveFile,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    onMore: (() -> Unit)? = null,
    selected: Boolean = false,
    selectionMode: Boolean = false,
    account: AccountEntity? = null,
    subtitle: String = fileSubtitle(file),
) {
    val bg by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent,
        label = "rowBg",
    )
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 1.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(bg)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = 10.dp, end = 2.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(contentAlignment = Alignment.Center) {
            FileThumbnail(
                file,
                Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)),
                iconSize = 44.dp,
                thumbPx = 132,
            )
            SelectionMark(selected, selectionMode, Modifier.align(Alignment.BottomEnd))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    file.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (file.starred) {
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Rounded.Star, "Starred", tint = CloudlyTheme.extra.warning, modifier = Modifier.size(14.dp))
                }
            }
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (account != null) AccountBadge(account)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = CloudlyTheme.extra.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (onMore != null && !selectionMode) {
            IconButton(onClick = onMore) {
                Icon(Icons.Rounded.MoreVert, "More actions", tint = CloudlyTheme.extra.muted)
            }
        } else Spacer(Modifier.width(12.dp))
    }
}

@Composable
fun SelectionMark(selected: Boolean, visible: Boolean, modifier: Modifier = Modifier) {
    AnimatedVisibility(visible, modifier, enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
        Box(
            Modifier.size(20.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                null,
                tint = if (selected) MaterialTheme.colorScheme.primary else CloudlyTheme.extra.muted,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
fun FileGridItem(
    file: DriveFile,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    onMore: (() -> Unit)? = null,
    selected: Boolean = false,
    selectionMode: Boolean = false,
    account: AccountEntity? = null,
) {
    val scale by animateFloatAsState(if (selected) 0.96f else 1f, label = "gridScale")
    val border by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
        label = "gridBorder",
    )
    Column(
        modifier
            .scale(scale)
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .then(if (selected) Modifier.background(border.copy(alpha = 0.10f)) else Modifier)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1.15f)
                .padding(6.dp)
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            FileThumbnail(file, Modifier.fillMaxSize(), iconSize = 52.dp, thumbPx = 480)
            if (file.category == FileCategory.VIDEO && file.thumbnailLink != null) {
                Box(
                    Modifier.size(34.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.PlayArrow, null, tint = Color.White) }
            }
            SelectionMark(selected, selectionMode, Modifier.align(Alignment.TopStart).padding(6.dp))
            if (file.starred) {
                Icon(
                    Icons.Rounded.Star, "Starred",
                    tint = CloudlyTheme.extra.warning,
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).size(16.dp),
                )
            }
        }
        Row(Modifier.padding(start = 12.dp, end = 0.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(file.name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (account != null) {
                    Spacer(Modifier.height(3.dp))
                    AccountBadge(account)
                } else {
                    Text(
                        file.displaySize?.takeIf { !file.isFolder }?.let { Format.bytes(it) } ?: Format.relative(file.modifiedTime),
                        style = MaterialTheme.typography.labelSmall,
                        color = CloudlyTheme.extra.muted,
                        maxLines = 1,
                    )
                }
            }
            if (onMore != null && !selectionMode) {
                IconButton(onClick = onMore, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Rounded.MoreVert, "More actions", tint = CloudlyTheme.extra.muted, modifier = Modifier.size(18.dp))
                }
            } else Spacer(Modifier.height(36.dp))
        }
    }
}

/** Compact card used in horizontal rails on the dashboard. */
@Composable
fun FileRailCard(file: DriveFile, onClick: () -> Unit, onLongClick: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    Column(
        modifier
            .width(148.dp)
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(100.dp)
                .padding(6.dp)
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            FileThumbnail(file, Modifier.fillMaxSize(), iconSize = 44.dp, thumbPx = 400)
        }
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(file.name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                Format.relative(file.viewedByMeTime ?: file.modifiedTime),
                style = MaterialTheme.typography.labelSmall,
                color = CloudlyTheme.extra.muted,
                maxLines = 1,
            )
        }
    }
}

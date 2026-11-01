package com.halfblanket.cloudly.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PrivacyTip
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.StarOutline
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.SingletonImageLoader
import com.halfblanket.cloudly.core.Format
import com.halfblanket.cloudly.data.db.AccountEntity
import com.halfblanket.cloudly.data.db.DriveAccess
import com.halfblanket.cloudly.data.prefs.AppSettings
import com.halfblanket.cloudly.data.prefs.LayoutMode
import com.halfblanket.cloudly.data.prefs.ThemeMode
import com.halfblanket.cloudly.data.repo.AccountsState
import com.halfblanket.cloudly.ui.common.AccountConnector
import com.halfblanket.cloudly.ui.common.UiMessages
import com.halfblanket.cloudly.ui.common.container
import com.halfblanket.cloudly.ui.components.AccountRow
import com.halfblanket.cloudly.ui.components.CloudlyCard
import com.halfblanket.cloudly.ui.components.ConfirmDialog
import com.halfblanket.cloudly.ui.components.SectionHeader
import com.halfblanket.cloudly.ui.components.SheetAction
import com.halfblanket.cloudly.ui.theme.CloudlyTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(
    accounts: AccountsState,
    connector: AccountConnector,
    onTransfers: () -> Unit,
    onPrivacy: () -> Unit,
    onAbout: () -> Unit,
) {
    val context = LocalContext.current
    val c = context.container
    val scope = rememberCoroutineScope()
    val settings by c.settings.settings.collectAsStateWithLifecycle(AppSettings())
    var adding by rememberSaveable { mutableStateOf(false) }
    var managing by remember { mutableStateOf<AccountEntity?>(null) }
    var removing by remember { mutableStateOf<AccountEntity?>(null) }
    var cacheBytes by remember { mutableLongStateOf(-1L) }
    var cacheTick by remember { mutableStateOf(0) }

    LaunchedEffect(cacheTick) {
        cacheBytes = withContext(Dispatchers.IO) {
            val images = SingletonImageLoader.get(context).diskCache?.size ?: 0L
            images + c.transferRepository.cacheSize()
        }
    }

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            scope.launch { c.settings.setDownloadTree(uri.toString()) }
        }
    }
    val downloadLabel = remember(settings.downloadTreeUri) {
        settings.downloadTreeUri?.let { DocumentFile.fromTreeUri(context, Uri.parse(it))?.name } ?: "Downloads/Cloudly"
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item {
            Text("Settings", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.statusBarsPadding().padding(start = 20.dp, top = 16.dp))
        }

        item { SectionHeader("Google accounts") }
        items(accounts.accounts, key = { it.id }) { acc ->
            AccountRow(acc, selected = acc.id == accounts.activeId, onClick = { managing = acc }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (acc.id == accounts.defaultId) {
                        Text("Default", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(onClick = { managing = acc }) { Icon(Icons.Rounded.MoreVert, "Manage ${acc.email}") }
                }
            }
        }
        item {
            SettingRow(Icons.Rounded.Add, "Add Google account", "Connect another Drive", onClick = { adding = true })
        }

        item { SectionHeader("Appearance") }
        item {
            SettingBlock(Icons.Rounded.DarkMode, "Theme") {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    val options = listOf(ThemeMode.DARK, ThemeMode.LIGHT, ThemeMode.SYSTEM)
                    options.forEachIndexed { i, mode ->
                        SegmentedButton(
                            selected = settings.theme == mode,
                            onClick = { scope.launch { c.settings.setTheme(mode) } },
                            shape = SegmentedButtonDefaults.itemShape(i, options.size),
                        ) { Text(if (mode == ThemeMode.SYSTEM) "System" else mode.label) }
                    }
                }
            }
        }
        item {
            SettingBlock(Icons.Rounded.GridView, "Default file layout") {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    LayoutMode.entries.forEachIndexed { i, mode ->
                        SegmentedButton(
                            selected = settings.layout == mode,
                            onClick = { scope.launch { c.settings.setLayout(mode) } },
                            shape = SegmentedButtonDefaults.itemShape(i, LayoutMode.entries.size),
                        ) { Text(if (mode == LayoutMode.LIST) "List" else "Grid") }
                    }
                }
            }
        }

        item { SectionHeader("Transfers") }
        item {
            SettingRow(Icons.Rounded.Folder, "Download location", downloadLabel, onClick = { pickFolder.launch(null) }) {
                if (settings.downloadTreeUri != null) {
                    TextButton(onClick = { scope.launch { c.settings.setDownloadTree(null) } }) { Text("Reset") }
                }
            }
        }
        item {
            SettingRow(
                Icons.Rounded.Wifi, "Transfer on Wi-Fi only", "Uploads and downloads wait for an unmetered network",
                onClick = { scope.launch { c.settings.setWifiOnly(!settings.downloadOnWifiOnly) } },
            ) { Switch(settings.downloadOnWifiOnly, onCheckedChange = { v -> scope.launch { c.settings.setWifiOnly(v) } }) }
        }
        item { SettingRow(Icons.Rounded.SwapVert, "Transfers", "Uploads, downloads and cross-account copies", onClick = onTransfers) }

        item { SectionHeader("Storage on this device") }
        item {
            SettingRow(
                Icons.Rounded.CleaningServices, "Clear cache",
                if (cacheBytes < 0) "Calculating…" else "${Format.bytes(cacheBytes)} of thumbnails and previews",
                onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            SingletonImageLoader.get(context).diskCache?.clear()
                            SingletonImageLoader.get(context).memoryCache?.clear()
                            c.transferRepository.clearFileCache()
                            c.db.listCache().clearAll()
                        }
                        UiMessages.show("Cache cleared")
                        cacheTick++
                    }
                },
            )
        }

        item { SectionHeader("About") }
        item { SettingRow(Icons.Rounded.PrivacyTip, "Privacy", "What Cloudly stores and what it never does", onClick = onPrivacy) }
        item { SettingRow(Icons.Rounded.Info, "About Cloudly", "Version, licences and permissions", onClick = onAbout) }
    }

    if (adding) {
        AddAccountDialog(onConfirm = { adding = false; connector.addAccount(it) }, onDismiss = { adding = false })
    }

    managing?.let { acc ->
        ModalBottomSheet(onDismissRequest = { managing = null }, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
                AccountRow(acc, selected = acc.id == accounts.activeId, onClick = {})
                Text(
                    when (acc.access) {
                        DriveAccess.FULL -> "Full Drive access"
                        DriveAccess.APP_FILES_ONLY -> "Limited: only files created with Cloudly"
                    },
                    style = MaterialTheme.typography.bodySmall, color = CloudlyTheme.extra.muted,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                )
                Spacer(Modifier.height(8.dp))
                SheetAction(Icons.Rounded.StarOutline, if (acc.id == accounts.defaultId) "Default account" else "Make default account", {
                    scope.launch { c.accountRepository.setDefault(acc.id) }
                    managing = null
                }, enabled = acc.id != accounts.defaultId, supporting = "Opened first when Cloudly starts")
                if (acc.access == DriveAccess.APP_FILES_ONLY) {
                    SheetAction(Icons.Rounded.LockOpen, "Allow full Drive access", { managing = null; connector.upgradeToFull(acc) })
                }
                SheetAction(Icons.Rounded.Refresh, "Reconnect", { managing = null; connector.reconnect(acc) }, supporting = "Renew Google's approval for this account")
                SheetAction(Icons.AutoMirrored.Rounded.OpenInNew, "Manage Google permissions", {
                    managing = null
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse("https://myaccount.google.com/connections?authuser=${Uri.encode(acc.email)}")),
                        )
                    }
                }, supporting = "Revoke Cloudly's access in your Google Account")
                SheetAction(Icons.AutoMirrored.Rounded.Logout, "Remove from Cloudly", { managing = null; removing = acc }, tint = CloudlyTheme.extra.danger)
            }
        }
    }

    removing?.let { acc ->
        ConfirmDialog(
            title = "Remove ${acc.email}?",
            message = "Cloudly forgets this account, its cached data and pending transfers. Files in Google Drive are not touched. " +
                "To fully revoke access, also remove Cloudly under Google Account → Security → Third-party connections.",
            confirmLabel = "Remove",
            destructive = true,
            onConfirm = {
                removing = null
                scope.launch {
                    c.transferRepository.cancelAllFor(acc.id)
                    c.accountRepository.remove(acc.id)
                    UiMessages.show("Removed ${acc.email}")
                }
            },
            onDismiss = { removing = null },
        )
    }
}

@Composable
private fun SettingRow(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = CloudlyTheme.extra.muted, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(20.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = CloudlyTheme.extra.muted)
        }
        if (trailing != null) trailing() else Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = CloudlyTheme.extra.muted)
    }
}

@Composable
private fun SettingBlock(icon: ImageVector, title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = CloudlyTheme.extra.muted, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(20.dp))
            Text(title, style = MaterialTheme.typography.bodyLarge)
        }
        Spacer(Modifier.height(10.dp))
        content()
    }
}

// ---------------------------------------------------------------------- Privacy & About

@Composable
fun InfoScreen(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.statusBarsPadding().fillMaxWidth().height(64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            Text(title, style = MaterialTheme.typography.titleLarge)
        }
        LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { content() }
        }
    }
}

@Composable
private fun InfoCard(title: String, body: String) {
    CloudlyCard(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(Modifier.padding(18.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium, color = CloudlyTheme.extra.muted)
        }
    }
}

@Composable
fun PrivacyScreen(onBack: () -> Unit) = InfoScreen("Privacy", onBack) {
    Column {
        InfoCard("Your Google account is your identity", "Cloudly has no accounts or passwords of its own. You sign in on Google's screens; Cloudly never sees or stores your Google password.")
        InfoCard("Only Google Drive", "Cloudly requests a single Google Drive permission per account — full Drive access, or access to files created with Cloudly if you choose. It doesn't request Gmail, Calendar, Photos or contacts.")
        InfoCard("What's stored on this device", "Each connected account's name, email, profile picture link, granted permission and storage figures; your settings; a cache of recent folder listings and thumbnails; and short-lived access tokens, encrypted with a key kept in the Android Keystore. None of it is backed up to the cloud or included in device transfers.")
        InfoCard("No servers in between", "Cloudly talks directly to Google's Drive API from your phone. There is no Cloudly server, no analytics and no advertising SDK. Your files go only between your device and Google.")
        InfoCard("Cross-account transfers", "Copying between accounts downloads each file to this device's private cache, uploads it to the other account, then deletes the temporary copy.")
        InfoCard("Nothing is deleted automatically", "Clean-up suggestions only list files. Moving to trash and permanent deletion always ask for your confirmation first.")
        InfoCard("Removing access", "Removing an account in Cloudly deletes everything Cloudly stored for it. To revoke Cloudly's permission entirely, remove it under Google Account → Security → Third-party connections.")
    }
}

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "—"
    }
    InfoScreen("About Cloudly", onBack) {
        Column {
            InfoCard("Cloudly $version", "A premium manager for multiple Google Drive accounts: browse, organise, transfer and clean up files across every Drive you use.")
            InfoCard("Built with", "Kotlin, Jetpack Compose and Material 3; Google Identity Services and the Google Drive API v3; Room, DataStore and WorkManager; Coil for images and Media3 for playback.")
            InfoCard("Typeface", "Inter, by Rasmus Andersson — SIL Open Font License 1.1.")
            InfoCard("Permissions", "Internet (Drive API), notifications (transfer progress), foreground service (background transfers) and, on Android 8–9 only, storage (saving downloads to the Downloads folder).")
            InfoCard("Trademarks", "Google Drive is a trademark of Google LLC. Cloudly is an independent app and is not affiliated with or endorsed by Google.")
        }
    }
}

package com.halfblanket.cloudly.ui.navigation

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.PieChart
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.PieChart
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.halfblanket.cloudly.data.db.TransferKind
import com.halfblanket.cloudly.data.drive.DriveFile
import com.halfblanket.cloudly.ui.common.UiMessages
import com.halfblanket.cloudly.ui.common.activityViewModel
import com.halfblanket.cloudly.ui.common.container
import com.halfblanket.cloudly.ui.common.rememberAccountConnector
import com.halfblanket.cloudly.ui.components.AccountSwitcherSheet
import com.halfblanket.cloudly.ui.files.FilesScreen
import com.halfblanket.cloudly.ui.files.FilesViewModel
import com.halfblanket.cloudly.ui.home.HomeScreen
import com.halfblanket.cloudly.ui.onboarding.WelcomeScreen
import com.halfblanket.cloudly.ui.preview.PreviewScreen
import com.halfblanket.cloudly.ui.search.SearchScreen
import com.halfblanket.cloudly.ui.settings.AboutScreen
import com.halfblanket.cloudly.ui.settings.AddAccountDialog
import com.halfblanket.cloudly.ui.settings.PrivacyScreen
import com.halfblanket.cloudly.ui.settings.SettingsScreen
import com.halfblanket.cloudly.ui.storage.CleanupKind
import com.halfblanket.cloudly.ui.storage.CleanupScreen
import com.halfblanket.cloudly.ui.storage.StorageScreen
import com.halfblanket.cloudly.ui.storage.StorageViewModel
import com.halfblanket.cloudly.ui.storage.TrashScreen
import com.halfblanket.cloudly.ui.transfers.TransfersScreen
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable object HomeRoute
@Serializable object FilesRoute
@Serializable object StorageRoute
@Serializable object SettingsRoute
@Serializable object SearchRoute
@Serializable object TransfersRoute
@Serializable object PrivacyRoute
@Serializable object AboutRoute
@Serializable data class PreviewRoute(val accountId: String, val fileId: String)
@Serializable data class TrashRoute(val accountId: String)
@Serializable data class CleanupRoute(val accountId: String, val kind: String)

private data class Tab(val route: Any, val label: String, val selected: ImageVector, val unselected: ImageVector)

private val tabs = listOf(
    Tab(HomeRoute, "Home", Icons.Rounded.Home, Icons.Outlined.Home),
    Tab(FilesRoute, "Files", Icons.Rounded.Folder, Icons.Outlined.Folder),
    Tab(StorageRoute, "Storage", Icons.Rounded.PieChart, Icons.Outlined.PieChart),
    Tab(SettingsRoute, "Settings", Icons.Rounded.Settings, Icons.Outlined.Settings),
)

@Composable
fun CloudlyRoot(openTransfers: StateFlow<Int>) {
    val context = LocalContext.current
    val c = context.container
    val accounts by c.accountRepository.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val connector = rememberAccountConnector()

    LaunchedEffect(Unit) {
        UiMessages.flow.collect { m ->
            val result = snackbar.showSnackbar(
                m.text, actionLabel = m.actionLabel, withDismissAction = m.actionLabel == null,
                duration = if (m.actionLabel != null) SnackbarDuration.Long else SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) m.action?.invoke()
        }
    }
    TransferPermissionRequests()

    if (!accounts.loaded) return

    if (accounts.accounts.isEmpty()) {
        var privacy by rememberSaveable { mutableStateOf(false) }
        Scaffold(snackbarHost = { SnackbarHost(snackbar) }, contentWindowInsets = WindowInsets(0)) { p ->
            Box(Modifier.padding(p)) {
                if (privacy) {
                    androidx.activity.compose.BackHandler { privacy = false }
                    PrivacyScreen(onBack = { privacy = false })
                } else WelcomeScreen(connector, onPrivacy = { privacy = true })
            }
        }
        return
    }

    val nav = rememberNavController()
    val filesVm = activityViewModel { FilesViewModel(it) }
    val storageVm = activityViewModel { StorageViewModel(it) }
    val transfers by c.transferRepository.observe().collectAsStateWithLifecycle(emptyList())
    val activeTransfers = transfers.count { it.isActive }
    var switcher by rememberSaveable { mutableStateOf(false) }
    var adding by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(openTransfers) {
        openTransfers.collect { n -> if (n > 0) nav.navigate(TransfersRoute) { launchSingleTop = true } }
    }

    val backStack by nav.currentBackStackEntryAsState()
    val dest = backStack?.destination
    val showBar = tabs.any { t -> dest?.hasRoute(t.route::class) == true }

    val openPreview: (DriveFile) -> Unit = { f -> nav.navigate(PreviewRoute(f.accountId, f.id)) }
    val openFolderInFiles: (DriveFile) -> Unit = { f ->
        filesVm.openFolderFromElsewhere(f)
        nav.switchTab(FilesRoute)
    }
    val openAccounts = { switcher = true }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            if (showBar) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = androidx.compose.ui.unit.Dp(0f)) {
                    tabs.forEach { t ->
                        val selected = dest?.hasRoute(t.route::class) == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = { nav.switchTab(t.route) },
                            icon = { Icon(if (selected) t.selected else t.unselected, null) },
                            label = { Text(t.label) },
                            colors = NavigationBarItemDefaults.colors(
                                indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.onSurface,
                            ),
                        )
                    }
                }
            }
        },
    ) { inner ->
        NavHost(
            nav,
            startDestination = HomeRoute,
            modifier = Modifier.padding(inner).fillMaxSize(),
            enterTransition = { fadeIn(tween(220)) + slideInHorizontally(tween(260)) { it / 12 } },
            exitTransition = { fadeOut(tween(160)) },
            popEnterTransition = { fadeIn(tween(220)) },
            popExitTransition = { fadeOut(tween(160)) + slideOutHorizontally(tween(260)) { it / 12 } },
        ) {
            composable<HomeRoute>(enterTransition = { fadeIn(tween(200)) }, exitTransition = { fadeOut(tween(150)) }) {
                HomeScreen(
                    accounts = accounts,
                    activeTransfers = activeTransfers,
                    connector = connector,
                    onOpenAccounts = openAccounts,
                    onSwitchAccount = { acc -> scope.launch { c.accountRepository.setActive(acc.id) } },
                    onSearch = { nav.navigate(SearchRoute) },
                    onTransfers = { nav.navigate(TransfersRoute) },
                    onStorage = { nav.switchTab(StorageRoute) },
                    onOpenFolder = openFolderInFiles,
                    onPreview = openPreview,
                    onSeeAllFiles = { nav.switchTab(FilesRoute) },
                )
            }
            composable<FilesRoute>(enterTransition = { fadeIn(tween(200)) }, exitTransition = { fadeOut(tween(150)) }) {
                FilesScreen(
                    vm = filesVm,
                    accounts = accounts,
                    connector = connector,
                    onOpenAccounts = openAccounts,
                    onSearch = { nav.navigate(SearchRoute) },
                    onPreview = openPreview,
                )
            }
            composable<StorageRoute>(enterTransition = { fadeIn(tween(200)) }, exitTransition = { fadeOut(tween(150)) }) {
                StorageScreen(
                    vm = storageVm,
                    accounts = accounts,
                    onOpenCleanup = { id, kind -> nav.navigate(CleanupRoute(id, kind.name)) },
                    onOpenTrash = { id -> nav.navigate(TrashRoute(id)) },
                    onPreview = openPreview,
                )
            }
            composable<SettingsRoute>(enterTransition = { fadeIn(tween(200)) }, exitTransition = { fadeOut(tween(150)) }) {
                SettingsScreen(
                    accounts = accounts,
                    connector = connector,
                    onTransfers = { nav.navigate(TransfersRoute) },
                    onPrivacy = { nav.navigate(PrivacyRoute) },
                    onAbout = { nav.navigate(AboutRoute) },
                )
            }
            composable<SearchRoute> {
                SearchScreen(accounts, onBack = { nav.popBackStack() }, onOpenFolder = openFolderInFiles, onPreview = openPreview)
            }
            composable<PreviewRoute> { entry ->
                val r = entry.toRoute<PreviewRoute>()
                PreviewScreen(r.accountId, r.fileId, accounts, onBack = { nav.popBackStack() })
            }
            composable<TrashRoute> { entry ->
                TrashScreen(entry.toRoute<TrashRoute>().accountId, accounts, onBack = { nav.popBackStack() })
            }
            composable<CleanupRoute> { entry ->
                val r = entry.toRoute<CleanupRoute>()
                CleanupScreen(storageVm, r.accountId, CleanupKind.valueOf(r.kind), accounts, onBack = { nav.popBackStack() }, onPreview = openPreview)
            }
            composable<TransfersRoute> { TransfersScreen(accounts, onBack = { nav.popBackStack() }) }
            composable<PrivacyRoute> { PrivacyScreen(onBack = { nav.popBackStack() }) }
            composable<AboutRoute> { AboutScreen(onBack = { nav.popBackStack() }) }
        }
    }

    if (switcher) {
        AccountSwitcherSheet(
            accounts = accounts.accounts,
            activeId = accounts.active?.id,
            onSelect = { acc ->
                switcher = false
                scope.launch { c.accountRepository.setActive(acc.id) }
            },
            onAdd = { switcher = false; adding = true },
            onManage = { switcher = false; nav.switchTab(SettingsRoute) },
            onDismiss = { switcher = false },
        )
    }
    if (adding) {
        AddAccountDialog(onConfirm = { adding = false; connector.addAccount(it) }, onDismiss = { adding = false })
    }
}

private fun NavHostController.switchTab(route: Any) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/**
 * Asks for notification permission (Android 13+) the first time a transfer runs, and for storage
 * permission on Android 8–9 the first time a download runs, so progress and saved files are visible.
 */
@Composable
private fun TransferPermissionRequests() {
    val context = LocalContext.current
    val transfers by context.container.transferRepository.observe().collectAsStateWithLifecycle(emptyList())
    var askedNotif by rememberSaveable { mutableStateOf(false) }
    var askedStorage by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val active = transfers.filter { it.isActive }
    LaunchedEffect(active.size) {
        if (active.isEmpty()) return@LaunchedEffect
        if (Build.VERSION.SDK_INT >= 33 && !askedNotif &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askedNotif = true
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else if (Build.VERSION.SDK_INT < 29 && !askedStorage && active.any { it.kind == TransferKind.DOWNLOAD } &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            askedStorage = true
            @Suppress("DEPRECATION")
            launcher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }
}

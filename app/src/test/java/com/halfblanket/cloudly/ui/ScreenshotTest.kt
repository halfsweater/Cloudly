package com.halfblanket.cloudly.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.halfblanket.cloudly.data.db.AccountEntity
import com.halfblanket.cloudly.data.db.DriveAccess
import com.halfblanket.cloudly.data.drive.MimeTypes
import com.halfblanket.cloudly.data.drive.testFile
import com.halfblanket.cloudly.data.prefs.ThemeMode
import com.halfblanket.cloudly.ui.components.AccountRow
import com.halfblanket.cloudly.ui.components.AccountSwitcherChip
import com.halfblanket.cloudly.ui.components.Banner
import com.halfblanket.cloudly.ui.components.EmptyState
import com.halfblanket.cloudly.ui.components.FileGridItem
import com.halfblanket.cloudly.ui.components.FileListItem
import com.halfblanket.cloudly.ui.components.FileRailCard
import com.halfblanket.cloudly.ui.components.SectionHeader
import com.halfblanket.cloudly.ui.files.Location
import com.halfblanket.cloudly.ui.files.LocationChips
import com.halfblanket.cloudly.ui.home.AccountMiniCard
import com.halfblanket.cloudly.ui.home.FolderChip
import com.halfblanket.cloudly.ui.home.StorageCard
import com.halfblanket.cloudly.ui.storage.SuggestionRow
import com.halfblanket.cloudly.ui.storage.UsageCard
import com.halfblanket.cloudly.ui.theme.CloudlyTheme
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant
import java.time.temporal.ChronoUnit

/** Renders representative UI with sample data to PNGs (build/outputs/roborazzi) for visual review. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h860dp-xxhdpi", application = android.app.Application::class)
class ScreenshotTest {
    private val gb = 1024L * 1024 * 1024
    private val personal = AccountEntity("p", "sam.rivera@gmail.com", "Sam Rivera", null, DriveAccess.FULL, "", colorIndex = 0,
        storageLimit = 15 * gb, storageUsage = (9.4 * gb).toLong(), usageInDrive = (6.1 * gb).toLong(), usageInTrash = (0.6 * gb).toLong())
    private val work = AccountEntity("w", "sam@northwind.studio", "Sam Rivera", null, DriveAccess.FULL, "", colorIndex = 1,
        storageLimit = 2048 * gb, storageUsage = 312 * gb, usageInDrive = 300 * gb, usageInTrash = 4 * gb)
    private val school = AccountEntity("s", "s.rivera@uni.edu", "Sam R.", null, DriveAccess.APP_FILES_ONLY, "", colorIndex = 3,
        storageLimit = 100 * gb, storageUsage = 14 * gb, usageInDrive = 14 * gb, needsReauth = true)
    private val now = Instant.now()
    private val files = listOf(
        testFile("1", name = "Q3 Planning", mime = MimeTypes.G_DOC, size = null, modified = now.minus(2, ChronoUnit.HOURS)),
        testFile("2", name = "Invoices", mime = MimeTypes.FOLDER, size = null, modified = now.minus(1, ChronoUnit.DAYS)),
        testFile("3", name = "Budget 2026.xlsx", mime = MimeTypes.XLSX, size = 482_000, modified = now.minus(3, ChronoUnit.DAYS)),
        testFile("4", name = "Lisbon trip.mp4", mime = "video/mp4", size = 1_840_000_000, modified = now.minus(9, ChronoUnit.DAYS)),
        testFile("5", name = "Contract signed.pdf", mime = "application/pdf", size = 2_300_000, modified = now.minus(20, ChronoUnit.DAYS)),
        testFile("6", name = "Pitch deck", mime = MimeTypes.G_SLIDES, size = null, modified = now.minus(40, ChronoUnit.DAYS)),
        testFile("7", name = "IMG_2041.HEIC", mime = "image/heic", size = 3_100_000, modified = now.minus(60, ChronoUnit.DAYS)),
        testFile("8", name = "Podcast ep 12.mp3", mime = "audio/mpeg", size = 58_000_000),
        testFile("9", name = "site-backup.zip", mime = "application/zip", size = 740_000_000),
    ).mapIndexed { i, f -> if (i == 0 || i == 4) f.copy(starred = true) else f }

    @Composable
    private fun Frame(theme: ThemeMode = ThemeMode.DARK, content: @Composable () -> Unit) {
        CloudlyTheme(theme) {
            // Mirrors MainActivity: a Surface supplies background and content colour.
            androidx.compose.material3.Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(Modifier.fillMaxSize()) { content() }
            }
        }
    }

    @Test fun home() = captureRoboImage("build/outputs/roborazzi/home.png") {
        Frame {
            Column {
                com.halfblanket.cloudly.ui.components.CloudlyTopBar("Hi, Sam", personal, {}, subtitle = "Good evening")
                com.halfblanket.cloudly.ui.components.SearchPill("Search all your Drives", {})
                com.halfblanket.cloudly.ui.home.QuickActions({}, {}, {})
                StorageCard(personal) {}
                SectionHeader("All accounts")
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(listOf(personal, work, school)) { AccountMiniCard(it, it == personal) {} }
                }
                SectionHeader("Quick access", action = "All files", onAction = {})
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(listOf("Invoices", "Photos", "Clients")) { FolderChip(files[1].copy(name = it), {}) }
                }
                SectionHeader("Recent")
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(files.filter { !it.isFolder }.take(4)) { FileRailCard(it, {}) }
                }
            }
        }
    }

    @Test fun filesList() = captureRoboImage("build/outputs/roborazzi/files_list.png") {
        Frame {
            Text("Files", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(start = 16.dp, top = 16.dp))
            LocationChips(Location.MY_DRIVE) {}
            Banner(Icons.Rounded.Lock, "Limited access: Cloudly can only see files it created in this account.", actionLabel = "Allow full", onAction = {})
            files.forEachIndexed { i, f ->
                FileListItem(f, onClick = {}, onMore = {}, selected = i == 2, selectionMode = false)
            }
        }
    }

    @Test fun searchAllAccounts() = captureRoboImage("build/outputs/roborazzi/search.png") {
        Frame {
            SectionHeader("Results across 3 accounts")
            val owners = listOf(personal, work, school)
            files.take(7).forEachIndexed { i, f -> FileListItem(f, onClick = {}, onMore = {}, account = owners[i % 3]) }
        }
    }

    @Test fun filesGridLight() = captureRoboImage("build/outputs/roborazzi/files_grid_light.png") {
        Frame(ThemeMode.LIGHT) {
            LazyVerticalGrid(
                GridCells.Adaptive(156.dp), contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(files) { f -> FileGridItem(f, onClick = {}, onMore = {}, selected = f.id == "3", selectionMode = f.id == "3") }
            }
        }
    }

    @Test fun storage() = captureRoboImage("build/outputs/roborazzi/storage.png") {
        Frame {
            Text("Storage", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(start = 20.dp, top = 16.dp))
            SectionHeader("Accounts")
            listOf(personal, work, school).forEach { AccountRow(it, selected = it == personal, onClick = {}) }
            SectionHeader("Usage · ${personal.email}")
            UsageCard(personal)
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SuggestionRow(Icons.Rounded.Movie, "Large videos", "4 file(s) · 6.2 GB could be freed", CloudlyTheme.extra.danger) {}
            }
        }
    }

    @Test fun emptyState() = captureRoboImage("build/outputs/roborazzi/empty.png") {
        Frame {
            Row(Modifier.fillMaxWidth()) {}
            EmptyState(Icons.Rounded.FolderOpen, "This folder is empty", "Upload files or create a folder to get started.", actionLabel = "Add files", onAction = {})
        }
    }
}

package com.halfblanket.cloudly.ui.storage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.halfblanket.cloudly.AppContainer
import com.halfblanket.cloudly.core.userMessage
import com.halfblanket.cloudly.data.drive.DriveFile
import com.halfblanket.cloudly.data.drive.FileCategory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

enum class CleanupKind(val title: String, val description: String) {
    LARGEST("Largest files", "Your biggest files, by storage used"),
    VIDEOS("Large videos", "Videos over 100 MB"),
    OLD("Old large files", "Over 25 MB and not opened or changed in a year"),
    DUPLICATES("Possible duplicates", "Files with identical content (same checksum)"),
}

data class StorageScan(
    val accountId: String,
    val files: List<DriveFile>,
    val complete: Boolean,
    val scannedAt: Instant = Instant.now(),
) {
    val byCategory: List<Pair<FileCategory, Long>> by lazy {
        files.groupBy { it.category }.mapValues { (_, l) -> l.sumOf { it.storageBytes } }
            .toList().filter { it.second > 0 }.sortedByDescending { it.second }
    }
    val scannedBytes: Long by lazy { files.sumOf { it.storageBytes } }

    fun filesFor(kind: CleanupKind): List<DriveFile> {
        val yearAgo = Instant.now().minus(Duration.ofDays(365))
        return when (kind) {
            CleanupKind.LARGEST -> files.take(100)
            CleanupKind.VIDEOS -> files.filter { it.category == FileCategory.VIDEO && it.storageBytes >= 100L * 1024 * 1024 }
            CleanupKind.OLD -> files.filter {
                it.storageBytes >= 25L * 1024 * 1024 &&
                    (it.modifiedTime?.isBefore(yearAgo) ?: false) &&
                    (it.viewedByMeTime?.isBefore(yearAgo) ?: true)
            }
            CleanupKind.DUPLICATES -> duplicates().flatten()
        }
    }

    /** Groups of 2+ files with the same checksum and size; the first of each group is the oldest. */
    fun duplicates(): List<List<DriveFile>> = files
        .filter { it.md5Checksum != null && it.storageBytes > 0 }
        .groupBy { it.md5Checksum + ":" + it.size }
        .values.filter { it.size > 1 }
        .map { g -> g.sortedBy { it.createdTime ?: it.modifiedTime } }
        .sortedByDescending { g -> g.drop(1).sumOf { it.storageBytes } }

    fun reclaimable(kind: CleanupKind): Long = when (kind) {
        CleanupKind.DUPLICATES -> duplicates().sumOf { g -> g.drop(1).sumOf { it.storageBytes } }
        else -> filesFor(kind).sumOf { it.storageBytes }
    }
}

data class StorageUiState(
    val selectedId: String? = null,
    val scan: StorageScan? = null,
    val scanning: Boolean = false,
    val error: String? = null,
    val refreshing: Boolean = false,
)

/** Activity-scoped so the (expensive) scan survives navigating into cleanup lists and back. */
class StorageViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(StorageUiState())
    val state: StateFlow<StorageUiState> = _state
    private val scans = mutableMapOf<String, StorageScan>()
    private var job: Job? = null

    init {
        viewModelScope.launch {
            c.accountRepository.state.collect { acc ->
                val sel = _state.value.selectedId
                if (sel == null || acc.byId(sel) == null) acc.active?.id?.let { select(it) }
            }
        }
    }

    fun select(accountId: String) {
        if (_state.value.selectedId == accountId && (_state.value.scan != null || _state.value.scanning)) return
        _state.update { it.copy(selectedId = accountId, scan = scans[accountId], error = null) }
        if (scans[accountId] == null) scan()
    }

    fun refresh() {
        val id = _state.value.selectedId ?: return
        _state.update { it.copy(refreshing = true) }
        viewModelScope.launch {
            runCatching { c.accountRepository.refreshAllStorage() }
            scans.remove(id)
            scan()
            _state.update { it.copy(refreshing = false) }
        }
    }

    fun scan() {
        val id = _state.value.selectedId ?: return
        job?.cancel()
        job = viewModelScope.launch {
            _state.update { it.copy(scanning = true, error = null) }
            try {
                val files = c.driveRepository.scanLargest(id, SCAN_LIMIT)
                val scan = StorageScan(id, files, complete = files.size < SCAN_LIMIT)
                scans[id] = scan
                if (_state.value.selectedId == id) _state.update { it.copy(scan = scan, scanning = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (_state.value.selectedId == id) _state.update { it.copy(scanning = false, error = e.userMessage()) }
            }
        }
    }

    /** After files were trashed elsewhere, drop them from the cached scan. */
    fun forget(accountId: String, ids: Set<String>) {
        val s = scans[accountId] ?: return
        val updated = s.copy(files = s.files.filter { it.id !in ids })
        scans[accountId] = updated
        if (_state.value.selectedId == accountId) _state.update { it.copy(scan = updated) }
    }

    fun scanFor(accountId: String): StorageScan? = scans[accountId]

    companion object {
        const val SCAN_LIMIT = 2000
    }
}

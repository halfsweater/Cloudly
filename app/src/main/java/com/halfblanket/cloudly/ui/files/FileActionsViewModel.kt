package com.halfblanket.cloudly.ui.files

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.halfblanket.cloudly.AppContainer
import com.halfblanket.cloudly.core.userMessage
import com.halfblanket.cloudly.data.drive.DriveFile
import com.halfblanket.cloudly.data.drive.MimeTypes
import com.halfblanket.cloudly.data.repo.TransferRepository
import com.halfblanket.cloudly.ui.common.UiMessages
import com.halfblanket.cloudly.work.LocalFiles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

enum class PickMode(val verb: String) {
    MOVE("Move"), COPY("Copy"), CROSS_COPY("Copy"), CROSS_MOVE("Move")
}

sealed interface ActionDialog {
    data class Menu(val file: DriveFile) : ActionDialog
    data class Rename(val file: DriveFile) : ActionDialog
    data class ConfirmTrash(val files: List<DriveFile>) : ActionDialog
    data class ConfirmDeleteForever(val files: List<DriveFile>) : ActionDialog
    data class Details(val file: DriveFile) : ActionDialog
    data class PickAccount(val files: List<DriveFile>, val move: Boolean) : ActionDialog
    data class PickFolder(val files: List<DriveFile>, val mode: PickMode, val accountId: String) : ActionDialog
    data class ConfirmCross(
        val files: List<DriveFile>,
        val targetAccountId: String,
        val targetFolderId: String,
        val targetFolderName: String,
        val move: Boolean,
    ) : ActionDialog
}

sealed interface ActionEvent {
    data class Share(val intent: Intent) : ActionEvent
    data class OpenUrl(val url: String) : ActionEvent
    data class OpenWith(val intent: Intent) : ActionEvent
}

data class BusyState(val label: String, val progress: Float? = null)

/**
 * Performs file operations for any screen and owns the related dialogs. Every operation uses the
 * account carried by the file itself; multi-file operations are refused if the files span accounts.
 */
class FileActionsViewModel(private val c: AppContainer) : ViewModel() {
    private val drive = c.driveRepository
    private val transfers = c.transferRepository

    private val _dialog = MutableStateFlow<ActionDialog?>(null)
    val dialog: StateFlow<ActionDialog?> = _dialog

    private val _busy = MutableStateFlow<BusyState?>(null)
    val busy: StateFlow<BusyState?> = _busy

    /** Emits after something changed on Drive so the host screen can refresh. */
    private val _changed = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val changed: SharedFlow<Unit> = _changed

    private val _events = MutableSharedFlow<ActionEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<ActionEvent> = _events

    private var busyJob: Job? = null

    fun show(dialog: ActionDialog?) {
        _dialog.value = dialog
    }

    fun dismiss() {
        _dialog.value = null
    }

    fun cancelBusy() {
        busyJob?.cancel()
        _busy.value = null
    }

    private fun singleAccount(files: List<DriveFile>): Boolean {
        if (files.map { it.accountId }.distinct().size > 1) {
            UiMessages.show("Select files from one account at a time")
            return false
        }
        return files.isNotEmpty()
    }

    private fun op(success: String?, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
                success?.let { UiMessages.show(it) }
                _changed.emit(Unit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                UiMessages.show(e.userMessage())
                _changed.emit(Unit)
            }
        }
    }

    // ------------------------------------------------------------------ operations

    fun rename(file: DriveFile, name: String) {
        dismiss()
        if (name == file.name) return
        op("Renamed to $name") { drive.rename(file, name) }
    }

    fun setStarred(files: List<DriveFile>, starred: Boolean) {
        dismiss()
        if (!singleAccount(files)) return
        op(if (starred) "Added to Starred" else "Removed from Starred") {
            files.forEach { drive.setStarred(it, starred) }
        }
    }

    fun trash(files: List<DriveFile>) {
        dismiss()
        if (!singleAccount(files)) return
        val allowed = files.filter { it.capabilities.canTrash }
        val skipped = files.size - allowed.size
        op(null) {
            allowed.forEach { drive.trash(it) }
            val msg = buildString {
                append(if (allowed.size == 1) "Moved “${allowed.first().name}” to trash" else "Moved ${allowed.size} items to trash")
                if (skipped > 0) append(" · $skipped can't be trashed by you")
            }
            UiMessages.show(msg, "Undo") { restore(allowed, quiet = true) }
        }
    }

    fun restore(files: List<DriveFile>, quiet: Boolean = false) {
        dismiss()
        if (!singleAccount(files)) return
        op(if (quiet) "Restored" else if (files.size == 1) "Restored “${files.first().name}”" else "Restored ${files.size} items") {
            files.forEach { drive.restore(it) }
        }
    }

    /** Irreversible; only reachable through [ActionDialog.ConfirmDeleteForever]. */
    fun deleteForever(files: List<DriveFile>) {
        dismiss()
        if (!singleAccount(files)) return
        op(if (files.size == 1) "Deleted forever" else "Deleted ${files.size} items forever") {
            files.forEach { drive.deleteForever(it) }
        }
    }

    fun moveOrCopy(files: List<DriveFile>, mode: PickMode, targetAccountId: String, folderId: String, folderName: String) {
        dismiss()
        if (!singleAccount(files)) return
        val source = files.first().accountId
        when (mode) {
            PickMode.MOVE, PickMode.COPY -> {
                if (targetAccountId != source) {
                    UiMessages.show("That folder is in another account. Use “Copy to another account”.")
                    return
                }
                val move = mode == PickMode.MOVE
                viewModelScope.launch {
                    _busy.value = BusyState(if (move) "Moving…" else "Copying…")
                    try {
                        var count = 0
                        files.forEach {
                            if (move) drive.move(it, targetAccountId, folderId)
                            else drive.copy(it, targetAccountId, folderId) { name ->
                                count++
                                _busy.value = BusyState("Copying $name…")
                            }
                        }
                        UiMessages.show("${if (move) "Moved" else "Copied"} ${plural(files)} to $folderName")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        UiMessages.show(e.userMessage())
                    } finally {
                        _busy.value = null
                        _changed.emit(Unit)
                    }
                }
            }
            PickMode.CROSS_COPY, PickMode.CROSS_MOVE -> {
                // Always confirm cross-account transfers, naming both accounts.
                _dialog.value = ActionDialog.ConfirmCross(files, targetAccountId, folderId, folderName, mode == PickMode.CROSS_MOVE)
            }
        }
    }

    fun startCrossTransfer(d: ActionDialog.ConfirmCross) {
        dismiss()
        op(null) {
            transfers.enqueueCrossAccount(d.files, d.targetAccountId, d.targetFolderId, d.move)
            UiMessages.show(if (d.move) "Moving ${plural(d.files)} in the background" else "Copying ${plural(d.files)} in the background")
        }
    }

    fun download(files: List<DriveFile>) {
        dismiss()
        op(null) {
            val n = transfers.enqueueDownloads(files)
            val skipped = files.size - n
            UiMessages.show(
                when {
                    n == 0 -> "Nothing to download — folders and some Google files can't be downloaded"
                    skipped > 0 -> "Downloading $n file(s) · $skipped skipped"
                    else -> "Downloading ${if (n == 1) "“${files.first().name}”" else "$n files"}"
                },
            )
        }
    }

    fun share(file: DriveFile) {
        dismiss()
        if (file.isFolder || (file.isGoogleNative && MimeTypes.exportFor(file.mimeType) == null)) {
            shareLink(file)
            return
        }
        withBusy("Preparing “${file.name}”…") { report ->
            val local = transfers.fetchToCache(file, TransferRepository.CachePurpose.SHARE) { d, t -> report(d, t) }
            val uri = LocalFiles.shareUri(c.app, local)
            val mime = MimeTypes.exportFor(file.mimeType)?.mime ?: file.mimeType
            val send = Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, file.name)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            _events.emit(ActionEvent.Share(Intent.createChooser(send, "Share ${file.name}")))
        }
    }

    fun shareLink(file: DriveFile) {
        dismiss()
        val link = file.webViewLink ?: run { UiMessages.show("No link available for this item"); return }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, link)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
        }
        viewModelScope.launch { _events.emit(ActionEvent.Share(Intent.createChooser(send, "Share link"))) }
    }

    fun openInDrive(file: DriveFile) {
        dismiss()
        file.webViewLink?.let { viewModelScope.launch { _events.emit(ActionEvent.OpenUrl(it)) } }
            ?: UiMessages.show("No Drive link available")
    }

    fun openWith(file: DriveFile) {
        dismiss()
        withBusy("Opening “${file.name}”…") { report ->
            val local = transfers.fetchToCache(file, TransferRepository.CachePurpose.SHARE) { d, t -> report(d, t) }
            val uri = LocalFiles.shareUri(c.app, local)
            val mime = MimeTypes.exportFor(file.mimeType)?.mime ?: file.mimeType.ifBlank { MimeTypes.fromName(local.name) }
            val view = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            _events.emit(ActionEvent.OpenWith(Intent.createChooser(view, "Open with")))
        }
    }

    private fun withBusy(label: String, block: suspend (report: (Long, Long) -> Unit) -> Unit) {
        busyJob?.cancel()
        busyJob = viewModelScope.launch {
            _busy.value = BusyState(label)
            try {
                block { d, t -> _busy.value = BusyState(label, if (t > 0) d.toFloat() / t else null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                UiMessages.show(e.userMessage())
            } finally {
                _busy.value = null
            }
        }
    }

    private fun plural(files: List<DriveFile>) = if (files.size == 1) "“${files.first().name}”" else "${files.size} items"
}

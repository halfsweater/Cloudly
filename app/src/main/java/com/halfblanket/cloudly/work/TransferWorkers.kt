package com.halfblanket.cloudly.work

import android.content.Context
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.halfblanket.cloudly.CloudlyApp
import com.halfblanket.cloudly.core.CloudlyException
import com.halfblanket.cloudly.core.Format
import com.halfblanket.cloudly.core.userMessage
import com.halfblanket.cloudly.data.db.TransferKind
import com.halfblanket.cloudly.data.db.TransferStatus
import com.halfblanket.cloudly.data.drive.DriveFile
import com.halfblanket.cloudly.data.drive.DriveQuery
import com.halfblanket.cloudly.data.drive.FileMetadataBody
import com.halfblanket.cloudly.data.drive.MimeTypes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.io.File
import java.io.IOException
import kotlinx.coroutines.currentCoroutineContext

@Serializable
sealed interface TransferPayload {
    @Serializable
    data class Upload(val accountId: String, val parentId: String, val uri: String, val name: String, val mime: String) : TransferPayload

    @Serializable
    data class Download(val accountId: String, val fileId: String) : TransferPayload

    @Serializable
    data class CrossAccount(
        val sourceAccountId: String,
        val fileIds: List<String>,
        val targetAccountId: String,
        val targetParentId: String,
        val move: Boolean,
    ) : TransferPayload
}

/** Progress written from blocking I/O callbacks and published periodically by the worker. */
class ProgressState {
    @Volatile var bytesDone = 0L
    @Volatile var bytesTotal = 0L
    @Volatile var itemsDone = 0
    @Volatile var itemsTotal = 0
    @Volatile var detail: String? = null
}

/**
 * Base for transfer workers: progress is persisted to the transfers table (which the Transfers screen
 * observes) and shown in a foreground notification. Transient failures are retried with backoff by
 * WorkManager; permanent ones are recorded with a readable message.
 */
abstract class TransferWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    protected val container get() = (applicationContext as CloudlyApp).container
    protected val transferId: String get() = inputData.getString(KEY_ID)!!
    private val notificationId get() = transferId.hashCode() and 0x7fffffff
    protected val progress = ProgressState()
    private var title = "Transfer"

    abstract suspend fun run(payload: TransferPayload)

    /**
     * Whether WorkManager may re-run the whole job after a transient failure. Single-file jobs are safe to
     * repeat; multi-item cross-account jobs are not (a re-run would duplicate items already copied), so
     * they rely on per-request retries and otherwise fail visibly for the user to retry.
     */
    protected open val autoRetry = true

    override suspend fun doWork(): Result {
        val dao = container.db.transfers()
        val entity = dao.byId(transferId) ?: return Result.failure()
        if (entity.status == TransferStatus.CANCELLED) return Result.failure()
        title = entity.title
        val payload = runCatching { container.json.decodeFromString<TransferPayload>(entity.payload) }
            .getOrElse { return fail("This transfer can't be resumed") }
        dao.setStatus(transferId, TransferStatus.RUNNING, null)
        runCatching { setForeground(TransferNotifications.progress(applicationContext, notificationId, title, "Starting…", null)) }
        return try {
            coroutineScope {
                val ticker = launch {
                    while (isActive) {
                        delay(500)
                        publish()
                    }
                }
                try {
                    run(payload)
                } finally {
                    ticker.cancel()
                }
            }
            publish()
            dao.setStatus(transferId, TransferStatus.SUCCEEDED, null)
            TransferNotifications.finished(applicationContext, notificationId, doneTitle(entity.kind), title)
            Result.success()
        } catch (e: CancellationException) {
            dao.setStatus(transferId, TransferStatus.CANCELLED, "Cancelled")
            throw e
        } catch (e: Throwable) {
            val transient = e is CloudlyException.Offline ||
                (e is CloudlyException.Api && (e.code >= 500 || e.code == 429 || e.reason == "uploadSessionExpired")) ||
                (e is IOException && e !is CloudlyException)
            if (transient && autoRetry && runAttemptCount < MAX_ATTEMPTS) {
                dao.setStatus(transferId, TransferStatus.QUEUED, "Retrying: ${e.userMessage()}")
                Result.retry()
            } else fail(e.userMessage())
        }
    }

    private suspend fun publish() {
        val p = progress
        container.db.transfers().setProgress(transferId, p.bytesDone, p.bytesTotal, p.itemsDone, p.itemsTotal, p.detail)
        val fraction = when {
            p.bytesTotal > 0 -> p.bytesDone.toFloat() / p.bytesTotal
            p.itemsTotal > 0 -> p.itemsDone.toFloat() / p.itemsTotal
            else -> null
        }
        val text = p.detail ?: if (p.bytesTotal > 0) "${Format.bytes(p.bytesDone)} of ${Format.bytes(p.bytesTotal)}" else "Working…"
        runCatching { setForeground(TransferNotifications.progress(applicationContext, notificationId, title, text, fraction)) }
    }

    private suspend fun fail(message: String): Result {
        container.db.transfers().setStatus(transferId, TransferStatus.FAILED, message)
        TransferNotifications.finished(applicationContext, notificationId, "Transfer failed", "$title — $message")
        return Result.failure(workDataOf("error" to message))
    }

    private fun doneTitle(kind: TransferKind) = when (kind) {
        TransferKind.UPLOAD -> "Upload complete"
        TransferKind.DOWNLOAD -> "Download complete"
        TransferKind.CROSS_COPY -> "Copied to other account"
        TransferKind.CROSS_MOVE -> "Moved to other account"
    }

    companion object {
        const val KEY_ID = "transfer_id"
        const val MAX_ATTEMPTS = 4
    }
}

class UploadWorker(context: Context, params: WorkerParameters) : TransferWorker(context, params) {
    override suspend fun run(payload: TransferPayload) {
        val p = payload as TransferPayload.Upload
        val uri = Uri.parse(p.uri)
        val meta = LocalFiles.meta(applicationContext, uri)
        var temp: File? = null
        val source = if (meta.size != null) {
            LocalFiles.ContentUriSource(applicationContext, uri, meta.size)
        } else {
            // Unknown length: stage a copy so the resumable protocol can address byte ranges.
            progress.detail = "Preparing…"
            val staged = File(applicationContext.cacheDir, "upload/$transferId").apply { parentFile?.mkdirs() }
            temp = staged
            applicationContext.contentResolver.openInputStream(uri)?.use { input ->
                staged.outputStream().use { input.copyTo(it) }
            } ?: throw IOException("Can't read the selected file")
            LocalFiles.FileUploadSource(staged)
        }
        try {
            progress.detail = null
            progress.bytesTotal = source.length
            container.driveApi.uploadResumable(
                accountId = p.accountId,
                metadata = FileMetadataBody(name = p.name, parents = listOf(p.parentId)),
                contentType = p.mime,
                source = source,
            ) { done, total ->
                progress.bytesDone = done
                progress.bytesTotal = total
            }
            progress.bytesDone = source.length
        } finally {
            temp?.delete()
        }
    }
}

class DownloadWorker(context: Context, params: WorkerParameters) : TransferWorker(context, params) {
    override suspend fun run(payload: TransferPayload) {
        val p = payload as TransferPayload.Download
        val api = container.driveApi
        val file = api.get(p.accountId, p.fileId)
        if (file.isFolder) throw CloudlyException.Unsupported("Folders can't be downloaded as a single file")
        if (!file.capabilities.canDownload) throw CloudlyException.Unsupported("The owner has disabled downloading ${file.name}")
        val export = if (file.isGoogleNative) {
            MimeTypes.exportFor(file.mimeType)
                ?: throw CloudlyException.Unsupported("${file.name} can't be downloaded; open it in Drive instead")
        } else null
        val name = LocalFiles.sanitize(if (export != null) "${file.name}.${export.extension}" else file.name)
        val mime = export?.mime ?: file.mimeType.ifBlank { MimeTypes.fromName(name) }
        val settings = container.settings.current()
        val target = LocalFiles.openDownloadTarget(applicationContext, settings.downloadTreeUri, name, mime)
        progress.bytesTotal = file.size ?: 0
        try {
            target.output.use { out ->
                val listener: (Long, Long) -> Unit = { d, t ->
                    progress.bytesDone = d
                    if (t > 0) progress.bytesTotal = t
                }
                if (export != null) api.export(file.accountId, file.id, export.mime, out, listener)
                else api.download(file.accountId, file.id, out, listener)
            }
            target.commit()
            container.db.transfers().setResult(transferId, target.uri.toString())
            progress.bytesTotal = progress.bytesDone
            progress.detail = "Saved to ${target.displayPath}"
        } catch (e: Throwable) {
            target.abort()
            throw e
        }
    }
}

/**
 * Copies (or moves) files from one connected account to another by downloading from the source account
 * and uploading to the destination. Google-native documents are exported to Office formats and converted
 * back on upload. A move only trashes the original (never deletes it permanently), and only once every
 * item inside it was transferred successfully.
 */
class CrossAccountWorker(context: Context, params: WorkerParameters) : TransferWorker(context, params) {

    private class Node(val file: DriveFile, val children: List<Node>)

    override val autoRetry = false

    private val skipped = mutableListOf<String>()

    override suspend fun run(payload: TransferPayload) {
        val p = payload as TransferPayload.CrossAccount
        if (p.sourceAccountId == p.targetAccountId) throw CloudlyException.Safety("Source and destination are the same account")
        val api = container.driveApi
        // Both accounts must still be connected before anything is touched.
        val accounts = container.db.accounts()
        accounts.byId(p.sourceAccountId) ?: throw CloudlyException.Safety("The source account is no longer connected")
        accounts.byId(p.targetAccountId) ?: throw CloudlyException.Safety("The destination account is no longer connected")

        progress.detail = "Preparing…"
        val roots = p.fileIds.map { id -> plan(api.get(p.sourceAccountId, id)) }
        progress.detail = null

        val keptOriginals = mutableListOf<String>()
        for (root in roots) {
            currentCoroutineContext().ensureActive()
            val complete = transfer(root, p.targetAccountId, p.targetParentId)
            if (p.move) {
                if (complete && root.file.capabilities.canTrash) {
                    api.update(root.file.accountId, root.file.id, FileMetadataBody(trashed = true))
                } else keptOriginals += root.file.name
            }
        }
        val notes = buildList {
            if (skipped.isNotEmpty()) {
                add("Skipped ${skipped.size} (not exportable or download disabled): ${skipped.take(3).joinToString()}" +
                    if (skipped.size > 3) "…" else "")
            }
            if (keptOriginals.isNotEmpty()) add("Originals kept for: ${keptOriginals.take(3).joinToString()}")
        }
        progress.bytesDone = progress.bytesTotal
        progress.detail = notes.joinToString(" · ").ifEmpty { "Done" }
    }

    private suspend fun plan(file: DriveFile, depth: Int = 0): Node {
        currentCoroutineContext().ensureActive()
        progress.itemsTotal++
        if (!file.isFolder) {
            progress.bytesTotal += file.size ?: 0
            return Node(file, emptyList())
        }
        if (depth > 40) throw CloudlyException.Unsupported("Folder nesting is too deep to transfer")
        val children = container.driveRepository.listAll(file.accountId, DriveQuery.children(file.id))
        return Node(file, children.map { plan(it, depth + 1) })
    }

    /** Returns true when the node and all of its descendants were transferred. */
    private suspend fun transfer(node: Node, targetAccount: String, targetParent: String): Boolean {
        currentCoroutineContext().ensureActive()
        val api = container.driveApi
        val f = node.file
        progress.detail = f.name
        if (f.isFolder) {
            val folder = api.createFolder(targetAccount, f.name, targetParent)
            progress.itemsDone++
            var all = true
            for (child in node.children) all = transfer(child, targetAccount, folder.id) and all
            return all
        }
        val export = if (f.isGoogleNative) MimeTypes.exportFor(f.mimeType) else null
        if ((f.isGoogleNative && export == null) || !f.capabilities.canDownload) {
            skipped += f.name
            progress.itemsDone++
            return false
        }
        val temp = File(applicationContext.cacheDir, "transfer/${transferId}_${f.id}").apply { parentFile?.mkdirs() }
        val size = f.size ?: 0
        val start = progress.bytesDone
        try {
            // Each byte counts once for download and once for upload, so progress is split in halves.
            temp.outputStream().use { out ->
                if (export != null) api.export(f.accountId, f.id, export.mime, out)
                else api.download(f.accountId, f.id, out) { d, _ -> progress.bytesDone = start + d / 2 }
            }
            val convertTo = MimeTypes.importTargetFor(f.mimeType)
            val name = if (export != null && convertTo == null) "${f.name}.${export.extension}" else f.name
            api.uploadResumable(
                accountId = targetAccount,
                metadata = FileMetadataBody(
                    name = name,
                    parents = listOf(targetParent),
                    mimeType = convertTo,
                    description = f.description,
                    modifiedTime = f.modifiedTime?.toString(),
                ),
                contentType = export?.mime ?: f.mimeType.ifBlank { "application/octet-stream" },
                source = LocalFiles.FileUploadSource(temp),
            ) { d, t -> if (size > 0 && t > 0) progress.bytesDone = start + size / 2 + (d * size / t) / 2 }
            progress.bytesDone = start + size
            progress.itemsDone++
            return true
        } finally {
            temp.delete()
        }
    }
}

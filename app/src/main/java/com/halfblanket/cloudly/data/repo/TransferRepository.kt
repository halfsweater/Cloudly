package com.halfblanket.cloudly.data.repo

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.halfblanket.cloudly.core.CloudlyException
import com.halfblanket.cloudly.data.db.TransferDao
import com.halfblanket.cloudly.data.db.TransferEntity
import com.halfblanket.cloudly.data.db.TransferKind
import com.halfblanket.cloudly.data.db.TransferStatus
import com.halfblanket.cloudly.data.drive.DriveApi
import com.halfblanket.cloudly.data.drive.DriveFile
import com.halfblanket.cloudly.data.drive.MimeTypes
import com.halfblanket.cloudly.data.prefs.SettingsRepository
import com.halfblanket.cloudly.work.CrossAccountWorker
import com.halfblanket.cloudly.work.DownloadWorker
import com.halfblanket.cloudly.work.LocalFiles
import com.halfblanket.cloudly.work.TransferPayload
import com.halfblanket.cloudly.work.TransferWorker
import com.halfblanket.cloudly.work.UploadWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Queues background uploads, downloads and cross-account transfers, and fetches files for preview/share. */
class TransferRepository(
    private val context: Context,
    private val dao: TransferDao,
    private val api: DriveApi,
    private val settings: SettingsRepository,
    private val json: Json,
) {
    private val workManager get() = WorkManager.getInstance(context)

    fun observe(): Flow<List<TransferEntity>> = dao.observeRecent()

    suspend fun enqueueUploads(accountId: String, parentId: String, uris: List<Uri>): Int {
        uris.forEach { uri ->
            // Keep read access across process death so the background upload can still open the file.
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val meta = withContext(Dispatchers.IO) { LocalFiles.meta(context, uri) }
            val payload = TransferPayload.Upload(accountId, parentId, uri.toString(), meta.name, meta.mime)
            enqueue(TransferKind.UPLOAD, accountId, null, meta.name, meta.size ?: 0, payload)
        }
        return uris.size
    }

    suspend fun enqueueDownloads(files: List<DriveFile>): Int {
        val downloadable = files.filter { !it.isFolder && (!it.isGoogleNative || MimeTypes.exportFor(it.mimeType) != null) }
        downloadable.forEach { f ->
            enqueue(TransferKind.DOWNLOAD, f.accountId, null, f.name, f.size ?: 0, TransferPayload.Download(f.accountId, f.id))
        }
        return downloadable.size
    }

    /**
     * Copies or moves [files] into another account. All files must come from one source account, and that
     * account must differ from [targetAccountId]; anything else is refused before work is queued.
     */
    suspend fun enqueueCrossAccount(files: List<DriveFile>, targetAccountId: String, targetParentId: String, move: Boolean) {
        require(files.isNotEmpty())
        val source = files.first().accountId
        if (files.any { it.accountId != source }) throw CloudlyException.Safety("Select files from one account at a time")
        if (source == targetAccountId) throw CloudlyException.Safety("Pick a different account as the destination")
        val title = if (files.size == 1) files.first().name else "${files.size} items"
        val payload = TransferPayload.CrossAccount(source, files.map { it.id }, targetAccountId, targetParentId, move)
        enqueue(
            if (move) TransferKind.CROSS_MOVE else TransferKind.CROSS_COPY,
            source, targetAccountId, title, files.sumOf { it.size ?: 0 }, payload,
        )
    }

    /** Saves picked Google Photos items to a Drive folder, or to this device when no target is given. */
    suspend fun enqueuePhotos(
        items: List<com.halfblanket.cloudly.data.photos.PickedPhoto>,
        targetAccountId: String?,
        targetFolderId: String?,
    ) {
        require(items.isNotEmpty())
        val source = items.first().accountId
        val title = if (items.size == 1) items.first().filename else "${items.size} photos & videos"
        enqueue(
            if (targetAccountId != null) TransferKind.PHOTOS_TO_DRIVE else TransferKind.PHOTOS_DOWNLOAD,
            source, targetAccountId, title, 0,
            TransferPayload.Photos(items, targetAccountId, targetFolderId),
        )
    }

    private suspend fun enqueue(
        kind: TransferKind,
        sourceAccount: String,
        targetAccount: String?,
        title: String,
        bytes: Long,
        payload: TransferPayload,
    ) {
        val id = UUID.randomUUID().toString()
        dao.insert(
            TransferEntity(
                id = id, kind = kind, sourceAccountId = sourceAccount, targetAccountId = targetAccount,
                title = title, bytesTotal = bytes, payload = json.encodeToString(payload),
            ),
        )
        schedule(id, kind, sourceAccount, targetAccount)
    }

    private suspend fun schedule(id: String, kind: TransferKind, sourceAccount: String, targetAccount: String?) {
        val wifiOnly = settings.current().downloadOnWifiOnly
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .build()
        val builder: OneTimeWorkRequest.Builder = when (kind) {
            TransferKind.UPLOAD -> OneTimeWorkRequestBuilder<UploadWorker>()
            TransferKind.DOWNLOAD -> OneTimeWorkRequestBuilder<DownloadWorker>()
            TransferKind.CROSS_COPY, TransferKind.CROSS_MOVE -> OneTimeWorkRequestBuilder<CrossAccountWorker>()
            TransferKind.PHOTOS_TO_DRIVE, TransferKind.PHOTOS_DOWNLOAD -> OneTimeWorkRequestBuilder<com.halfblanket.cloudly.work.PhotosWorker>()
        }
        val request = builder
            .setInputData(workDataOf(TransferWorker.KEY_ID to id))
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 20, TimeUnit.SECONDS)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag(TAG)
            .addTag("account:$sourceAccount")
            .apply { if (targetAccount != null) addTag("account:$targetAccount") }
            .build()
        workManager.enqueueUniqueWork(id, ExistingWorkPolicy.REPLACE, request)
    }

    suspend fun cancel(id: String) {
        workManager.cancelUniqueWork(id)
        dao.setStatus(id, TransferStatus.CANCELLED, "Cancelled")
    }

    suspend fun retry(id: String) {
        val t = dao.byId(id) ?: return
        if (t.isActive) return
        dao.insert(t.copy(status = TransferStatus.QUEUED, error = null, bytesDone = 0, itemsDone = 0, updatedAt = System.currentTimeMillis()))
        schedule(t.id, t.kind, t.sourceAccountId, t.targetAccountId)
    }

    suspend fun clearFinished() = dao.clearFinished()

    fun cancelAllFor(accountId: String) {
        workManager.cancelAllWorkByTag("account:$accountId")
    }

    // ------------------------------------------------------------------ preview / share cache

    /**
     * Downloads a file into the app cache for preview, sharing or "open with". Google-native documents are
     * exported (as PDF when [asPdf], otherwise their Office equivalent). Results are reused while the file
     * is unchanged.
     */
    suspend fun fetchToCache(
        file: DriveFile,
        purpose: CachePurpose,
        asPdf: Boolean = false,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): File = withContext(Dispatchers.IO) {
        if (file.isFolder) throw CloudlyException.Unsupported("Folders can't be opened as files")
        if (!file.capabilities.canDownload) throw CloudlyException.Unsupported("The owner has disabled downloading this file")
        val export = when {
            !file.isGoogleNative -> null
            asPdf && MimeTypes.canExportPdf(file.mimeType) -> MimeTypes.Export(MimeTypes.PDF, "pdf")
            else -> MimeTypes.exportFor(file.mimeType)
                ?: throw CloudlyException.Unsupported("This Google file type can only be opened in Drive")
        }
        val name = LocalFiles.sanitize(if (export != null) "${file.name}.${export.extension}" else file.name)
        val stamp = file.modifiedTime?.toEpochMilli() ?: 0
        val dir = File(context.cacheDir, "${purpose.dir}/${file.accountId.hashCode()}_${file.id}_$stamp").apply { mkdirs() }
        val target = File(dir, name)
        if (target.exists() && target.length() > 0) return@withContext target
        val tmp = File(dir, "$name.part")
        try {
            tmp.outputStream().use { out ->
                if (export != null) api.export(file.accountId, file.id, export.mime, out, onProgress)
                else api.download(file.accountId, file.id, out, onProgress)
            }
            if (!tmp.renameTo(target)) throw java.io.IOException("Couldn't save the file")
        } finally {
            tmp.delete()
        }
        target
    }

    fun cacheSize(): Long = CachePurpose.entries.sumOf { LocalFiles.dirSize(File(context.cacheDir, it.dir)) } +
        LocalFiles.dirSize(File(context.cacheDir, "transfer")) + LocalFiles.dirSize(File(context.cacheDir, "upload"))

    fun clearFileCache() {
        CachePurpose.entries.forEach { File(context.cacheDir, it.dir).deleteRecursively() }
    }

    enum class CachePurpose(val dir: String) { PREVIEW("preview"), SHARE("share") }

    companion object {
        const val TAG = "cloudly-transfer"
    }
}

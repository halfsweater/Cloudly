package com.halfblanket.cloudly.data.drive

import com.halfblanket.cloudly.core.CloudlyException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Supplies OAuth access tokens per connected account. */
interface AccessTokenSource {
    /** Returns a valid access token for [accountId], or throws [CloudlyException.ReauthRequired]. */
    suspend fun token(accountId: String): String

    /** Discards [token] after Drive rejected it, so the next [token] call fetches a fresh one. */
    suspend fun invalidate(accountId: String, token: String)
}

/** A page of results from a Drive list call. */
data class Page<T>(val items: List<T>, val nextPageToken: String?)

/** Source bytes for a resumable upload. [open] must return a stream positioned at [offset]. */
interface UploadSource {
    val length: Long
    fun open(offset: Long): InputStream
}

/**
 * Minimal, explicit client for the Google Drive REST API v3.
 *
 * Every call takes the `accountId` whose credentials must be used. There is no notion of a "current"
 * account at this layer, which keeps account data isolated: a request can only ever be authorised with
 * the token of the account that was passed in.
 */
class DriveApi(
    private val http: OkHttpClient,
    private val tokens: AccessTokenSource,
    private val json: Json,
    private val baseUrl: String = "https://www.googleapis.com/",
) {
    private val jsonMedia = "application/json; charset=UTF-8".toMediaType()
    private val api: HttpUrl get() = "${baseUrl}drive/v3/".toHttpUrl()
    private val upload: HttpUrl get() = "${baseUrl}upload/drive/v3/".toHttpUrl()

    // ---------------------------------------------------------------- metadata

    suspend fun about(accountId: String): AboutDto = getJson(
        accountId,
        api.newBuilder().addPathSegment("about")
            .addQueryParameter("fields", "user(displayName,emailAddress,photoLink,permissionId,me),storageQuota")
            .build(),
    )

    suspend fun list(
        accountId: String,
        query: String,
        orderBy: String? = null,
        pageSize: Int = 50,
        pageToken: String? = null,
        fields: String = FILE_FIELDS,
    ): Page<DriveFile> {
        val url = api.newBuilder().addPathSegment("files")
            .addQueryParameter("q", query)
            .addQueryParameter("pageSize", pageSize.coerceIn(1, 1000).toString())
            .addQueryParameter("spaces", "drive")
            .addQueryParameter("fields", "nextPageToken,incompleteSearch,files($fields)")
            .apply {
                if (orderBy != null) addQueryParameter("orderBy", orderBy)
                if (pageToken != null) addQueryParameter("pageToken", pageToken)
            }
            .build()
        val dto: FileListDto = getJson(accountId, url)
        return Page(dto.files.map { it.toDomain(accountId) }, dto.nextPageToken)
    }

    suspend fun get(accountId: String, fileId: String): DriveFile = getJson<DriveFileDto>(
        accountId,
        api.newBuilder().addPathSegment("files").addPathSegment(fileId)
            .addQueryParameter("fields", FILE_FIELDS).build(),
    ).toDomain(accountId)

    suspend fun createFolder(accountId: String, name: String, parentId: String): DriveFile =
        create(accountId, FileMetadataBody(name = name, mimeType = MimeTypes.FOLDER, parents = listOf(parentId)))

    suspend fun create(accountId: String, body: FileMetadataBody): DriveFile {
        val url = api.newBuilder().addPathSegment("files").addQueryParameter("fields", FILE_FIELDS).build()
        val req = Request.Builder().url(url).post(json.encodeToString(body).toRequestBody(jsonMedia))
        return parse<DriveFileDto>(execute(accountId, req)).toDomain(accountId)
    }

    suspend fun update(
        accountId: String,
        fileId: String,
        body: FileMetadataBody = FileMetadataBody(),
        addParents: String? = null,
        removeParents: String? = null,
    ): DriveFile {
        val url = api.newBuilder().addPathSegment("files").addPathSegment(fileId)
            .addQueryParameter("fields", FILE_FIELDS)
            .apply {
                if (addParents != null) addQueryParameter("addParents", addParents)
                if (removeParents != null) addQueryParameter("removeParents", removeParents)
            }.build()
        val req = Request.Builder().url(url).patch(json.encodeToString(body).toRequestBody(jsonMedia))
        return parse<DriveFileDto>(execute(accountId, req)).toDomain(accountId)
    }

    suspend fun copy(accountId: String, fileId: String, name: String?, parentId: String): DriveFile {
        val url = api.newBuilder().addPathSegment("files").addPathSegment(fileId).addPathSegment("copy")
            .addQueryParameter("fields", FILE_FIELDS).build()
        val body = FileMetadataBody(name = name, parents = listOf(parentId))
        val req = Request.Builder().url(url).post(json.encodeToString(body).toRequestBody(jsonMedia))
        return parse<DriveFileDto>(execute(accountId, req)).toDomain(accountId)
    }

    /** Permanently deletes a file, bypassing the trash. Callers must confirm with the user first. */
    suspend fun deletePermanently(accountId: String, fileId: String) {
        val url = api.newBuilder().addPathSegment("files").addPathSegment(fileId).build()
        execute(accountId, Request.Builder().url(url).delete()).close()
    }

    /** Permanently deletes every trashed file of the account. Callers must confirm with the user first. */
    suspend fun emptyTrash(accountId: String) {
        val url = api.newBuilder().addPathSegment("files").addPathSegment("trash").build()
        execute(accountId, Request.Builder().url(url).delete()).close()
    }

    // ---------------------------------------------------------------- content

    fun mediaUrl(fileId: String): String =
        api.newBuilder().addPathSegment("files").addPathSegment(fileId)
            .addQueryParameter("alt", "media").build().toString()

    /** Streams the binary content of [fileId] into [out]. Returns the number of bytes written. */
    suspend fun download(
        accountId: String,
        fileId: String,
        out: OutputStream,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): Long {
        val url = api.newBuilder().addPathSegment("files").addPathSegment(fileId)
            .addQueryParameter("alt", "media").addQueryParameter("acknowledgeAbuse", "false").build()
        return stream(accountId, Request.Builder().url(url).get(), out, onProgress)
    }

    /** Exports a Google-native file (Docs, Sheets, …) as [mimeType] into [out]. */
    suspend fun export(
        accountId: String,
        fileId: String,
        mimeType: String,
        out: OutputStream,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): Long {
        val url = api.newBuilder().addPathSegment("files").addPathSegment(fileId).addPathSegment("export")
            .addQueryParameter("mimeType", mimeType).build()
        return stream(accountId, Request.Builder().url(url).get(), out, onProgress)
    }

    private suspend fun stream(
        accountId: String,
        req: Request.Builder,
        out: OutputStream,
        onProgress: (Long, Long) -> Unit,
    ): Long = withContext(Dispatchers.IO) {
        execute(accountId, req).use { resp ->
            val body = resp.body ?: throw IOException("Empty response")
            val total = body.contentLength()
            var written = 0L
            val buffer = ByteArray(64 * 1024)
            body.byteStream().use { input ->
                while (true) {
                    coroutineContext.ensureActive()
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    written += n
                    onProgress(written, total)
                }
            }
            out.flush()
            written
        }
    }

    /**
     * Uploads [source] with Drive's resumable protocol, in chunks, resuming after transient failures.
     * [metadata] supplies name/parents and, optionally, a Google-native mimeType to convert into.
     */
    suspend fun uploadResumable(
        accountId: String,
        metadata: FileMetadataBody,
        contentType: String,
        source: UploadSource,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): DriveFile = withContext(Dispatchers.IO) {
        val startUrl = upload.newBuilder().addPathSegment("files")
            .addQueryParameter("uploadType", "resumable")
            .addQueryParameter("fields", FILE_FIELDS).build()
        val start = Request.Builder().url(startUrl)
            .header("X-Upload-Content-Type", contentType)
            .header("X-Upload-Content-Length", source.length.toString())
            .post(json.encodeToString(metadata).toRequestBody(jsonMedia))
        val session = execute(accountId, start).use { it.header("Location") }
            ?: throw IOException("Drive did not return an upload session")

        val total = source.length
        var offset = 0L
        var failures = 0
        while (true) {
            coroutineContext.ensureActive()
            val chunk = if (total == 0L) 0L else minOf(CHUNK_SIZE, total - offset)
            val range = if (total == 0L) "bytes */0" else "bytes $offset-${offset + chunk - 1}/$total"
            val body = ChunkBody(source, offset, chunk, contentType) { sent -> onProgress(offset + sent, total) }
            val result = runCatching {
                executeRaw(accountId, Request.Builder().url(session).header("Content-Range", range).put(body))
            }
            val resp = result.getOrNull()
            if (resp == null) {
                val e = result.exceptionOrNull()!!
                if (e is CloudlyException.ReauthRequired || e is kotlinx.coroutines.CancellationException ||
                    ++failures > MAX_CHUNK_RETRIES
                ) throw e
                delay(backoff(failures))
                offset = queryUploadOffset(accountId, session, total)
                    ?: return@withContext fetchCompleted(accountId, session, total)
                continue
            }
            resp.use {
                when {
                    it.code == 200 || it.code == 201 -> {
                        onProgress(total, total)
                        return@withContext parse<DriveFileDto>(it).toDomain(accountId)
                    }
                    it.code == 308 -> {
                        failures = 0
                        offset = it.header("Range")?.substringAfterLast('-')?.toLongOrNull()?.plus(1) ?: 0L
                    }
                    it.code == 404 || it.code == 410 ->
                        throw CloudlyException.Api(it.code, "uploadSessionExpired", "The upload session expired. Retrying from the start.")
                    it.code in RETRYABLE || it.code == 429 -> {
                        if (++failures > MAX_CHUNK_RETRIES) throw toApiException(it)
                        delay(backoff(failures))
                        offset = queryUploadOffset(accountId, session, total) ?: return@withContext fetchCompleted(accountId, session, total)
                    }
                    else -> throw toApiException(it)
                }
            }
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }

    /** Asks Drive how much of the upload it has; null means the upload is already complete. */
    private suspend fun queryUploadOffset(accountId: String, session: String, total: Long): Long? {
        val req = Request.Builder().url(session).header("Content-Range", "bytes */$total")
            .put(ByteArray(0).toRequestBody(null))
        executeRaw(accountId, req).use {
            return when (it.code) {
                308 -> it.header("Range")?.substringAfterLast('-')?.toLongOrNull()?.plus(1) ?: 0L
                200, 201 -> null
                else -> throw toApiException(it)
            }
        }
    }

    private suspend fun fetchCompleted(accountId: String, session: String, total: Long): DriveFile {
        val req = Request.Builder().url(session).header("Content-Range", "bytes */$total")
            .put(ByteArray(0).toRequestBody(null))
        executeRaw(accountId, req).use { return parse<DriveFileDto>(it).toDomain(accountId) }
    }

    private class ChunkBody(
        private val source: UploadSource,
        private val offset: Long,
        private val length: Long,
        private val type: String,
        private val onSent: (Long) -> Unit,
    ) : RequestBody() {
        override fun contentType() = type.toMediaType()
        override fun contentLength() = length
        override fun writeTo(sink: BufferedSink) {
            if (length == 0L) return
            source.open(offset).use { input ->
                val buffer = ByteArray(64 * 1024)
                var remaining = length
                var sent = 0L
                while (remaining > 0) {
                    val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (n < 0) throw IOException("Source ended early")
                    sink.write(buffer, 0, n)
                    remaining -= n
                    sent += n
                    onSent(sent)
                }
            }
        }
    }

    // ---------------------------------------------------------------- plumbing

    private suspend inline fun <reified T> getJson(accountId: String, url: HttpUrl): T =
        parse(execute(accountId, Request.Builder().url(url).get()))

    private inline fun <reified T> parse(resp: Response): T = resp.use {
        val text = it.body?.string().orEmpty()
        json.decodeFromString<T>(text)
    }

    /** Executes with the account's token; on 401 refreshes the token once. Throws on non-2xx. */
    private suspend fun execute(accountId: String, builder: Request.Builder): Response {
        val resp = executeRaw(accountId, builder)
        if (!resp.isSuccessful) {
            resp.use { throw toApiException(it) }
        }
        return resp
    }

    /** Executes with auth and token refresh, but returns the response whatever its status. */
    private suspend fun executeRaw(accountId: String, builder: Request.Builder): Response {
        var token = tokens.token(accountId)
        var resp = http.newCall(builder.header("Authorization", "Bearer $token").build()).await()
        if (resp.code == 401) {
            resp.close()
            tokens.invalidate(accountId, token)
            token = tokens.token(accountId)
            resp = http.newCall(builder.header("Authorization", "Bearer $token").build()).await()
        }
        return resp
    }

    private fun toApiException(resp: Response): CloudlyException {
        val raw = runCatching { resp.body?.string() }.getOrNull()
        val body = raw?.let { runCatching { json.decodeFromString<DriveErrorEnvelope>(it).error }.getOrNull() }
        val reason = body?.errors?.firstOrNull()?.reason
        val friendly = when {
            resp.code == 404 -> "The file no longer exists or you don't have access to it."
            resp.code == 403 && reason == "insufficientPermissions" ->
                "This account didn't grant Cloudly permission for that action."
            resp.code == 403 && reason == "storageQuotaExceeded" -> "The destination account is out of storage."
            resp.code == 403 && (reason == "cannotDownloadAbusiveFile") -> "Drive flagged this file and blocks downloading it."
            resp.code == 403 && reason == "exportSizeLimitExceeded" -> "This document is too large for Drive to export."
            resp.code == 403 && reason in setOf("rateLimitExceeded", "userRateLimitExceeded") ->
                "Google Drive is rate limiting requests. Try again shortly."
            resp.code == 429 -> "Google Drive is rate limiting requests. Try again shortly."
            resp.code >= 500 -> "Google Drive is temporarily unavailable. Try again."
            else -> body?.message ?: "Drive request failed (${resp.code})"
        }
        return CloudlyException.Api(resp.code, reason, friendly)
    }

    companion object {
        const val CHUNK_SIZE = 8L * 1024 * 1024 // multiple of 256 KiB, as the protocol requires
        private const val MAX_CHUNK_RETRIES = 6
        private val RETRYABLE = setOf(500, 502, 503, 504)
        private fun backoff(attempt: Int) = (1000L shl (attempt - 1).coerceAtMost(5)) + (0..500).random()

        const val FILE_FIELDS = "id,name,mimeType,size,quotaBytesUsed,createdTime,modifiedTime,viewedByMeTime," +
            "starred,trashed,explicitlyTrashed,parents,thumbnailLink,hasThumbnail,webViewLink,ownedByMe," +
            "owners(displayName,emailAddress),lastModifyingUser(displayName),shared,md5Checksum,fileExtension," +
            "fullFileExtension,description," +
            "capabilities(canAddChildren,canCopy,canDelete,canDownload,canEdit,canRename,canTrash,canUntrash," +
            "canMoveItemWithinDrive,canShare)," +
            "imageMediaMetadata(width,height),videoMediaMetadata(width,height,durationMillis)," +
            "shortcutDetails(targetId,targetMimeType)"

        /** Lighter field set for storage scans across many files. */
        const val SCAN_FIELDS = "id,name,mimeType,size,quotaBytesUsed,modifiedTime,viewedByMeTime,md5Checksum," +
            "parents,starred,trashed,ownedByMe,thumbnailLink,hasThumbnail,webViewLink," +
            "capabilities(canTrash,canDelete,canDownload,canCopy,canRename,canMoveItemWithinDrive,canAddChildren)"
    }
}

/** Suspends on an OkHttp call, cancelling it if the coroutine is cancelled. */
suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) = cont.resume(response)
        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }
    })
    cont.invokeOnCancellation { runCatching { cancel() } }
}

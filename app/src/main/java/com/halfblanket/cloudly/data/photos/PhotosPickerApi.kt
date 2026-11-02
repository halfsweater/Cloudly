package com.halfblanket.cloudly.data.photos

import com.halfblanket.cloudly.core.CloudlyException
import com.halfblanket.cloudly.data.drive.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.io.OutputStream

/**
 * Client for the Google Photos Picker API.
 *
 * Since March 2025 Google no longer lets third-party apps read a user's whole Photos library. The Picker
 * API is the supported way in: Cloudly opens a picking session, the user chooses items in Google's own
 * picker, and Cloudly can then read exactly those items (for a limited time).
 */
class PhotosPickerApi(
    private val http: OkHttpClient,
    private val tokens: PhotosTokenSource,
    private val json: Json,
    private val baseUrl: String = "https://photospicker.googleapis.com/v1/",
) {
    interface PhotosTokenSource {
        suspend fun photosToken(accountId: String): String
        suspend fun invalidatePhotos(accountId: String, token: String)
    }

    suspend fun createSession(accountId: String): PickingSession =
        parse<PickingSessionDto>(call(accountId, Request.Builder().url("${baseUrl}sessions").post("{}".toRequestBody(JSON))))
            .toDomain()

    suspend fun getSession(accountId: String, id: String): PickingSession =
        parse<PickingSessionDto>(call(accountId, Request.Builder().url("${baseUrl}sessions/$id").get())).toDomain()

    suspend fun deleteSession(accountId: String, id: String) {
        runCatching { call(accountId, Request.Builder().url("${baseUrl}sessions/$id").delete()).close() }
    }

    /** Every item the user picked in [sessionId]. */
    suspend fun listPicked(accountId: String, sessionId: String): List<PickedPhoto> {
        val out = ArrayList<PickedPhoto>()
        var token: String? = null
        do {
            currentCoroutineContext().ensureActive()
            val url = "${baseUrl}mediaItems".toHttpUrl().newBuilder()
                .addQueryParameter("sessionId", sessionId)
                .addQueryParameter("pageSize", "100")
                .apply { if (token != null) addQueryParameter("pageToken", token) }
                .build()
            val page = parse<PickedListDto>(call(accountId, Request.Builder().url(url).get()))
            out += page.mediaItems.mapNotNull { it.toDomain(accountId) }
            token = page.nextPageToken
        } while (token != null)
        return out
    }

    /** Downloads the original bytes of a picked item (with metadata for photos). */
    suspend fun download(item: PickedPhoto, out: OutputStream, onProgress: (Long, Long) -> Unit = { _, _ -> }): Long =
        withContext(Dispatchers.IO) {
            call(item.accountId, Request.Builder().url(item.downloadUrl).get()).use { resp ->
                val body = resp.body ?: throw IOException("Empty response")
                val total = body.contentLength()
                var done = 0L
                val buf = ByteArray(64 * 1024)
                body.byteStream().use { input ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        onProgress(done, total)
                    }
                }
                done
            }
        }

    private suspend fun call(accountId: String, builder: Request.Builder): Response {
        var token = tokens.photosToken(accountId)
        var resp = http.newCall(builder.header("Authorization", "Bearer $token").build()).await()
        if (resp.code == 401) {
            resp.close()
            tokens.invalidatePhotos(accountId, token)
            token = tokens.photosToken(accountId)
            resp = http.newCall(builder.header("Authorization", "Bearer $token").build()).await()
        }
        if (!resp.isSuccessful) {
            val code = resp.code
            val text = runCatching { resp.body?.string() }.getOrNull().orEmpty()
            resp.close()
            val msg = when {
                code == 403 && ("SERVICE_DISABLED" in text || "has not been used" in text) ->
                    "The Photos Picker API isn't enabled in your Google Cloud project."
                code == 403 -> "Google Photos access wasn't granted for this account."
                code == 404 -> "The picking session expired. Start again."
                code == 429 -> "Google Photos is rate limiting requests. Try again shortly."
                code == 400 && "FAILED_PRECONDITION" in text -> "Finish choosing photos in the picker first."
                else -> "Google Photos request failed ($code)"
            }
            throw CloudlyException.Api(code, null, msg)
        }
        return resp
    }

    private inline fun <reified T> parse(resp: Response): T = resp.use { json.decodeFromString<T>(it.body?.string().orEmpty()) }

    companion object {
        const val SCOPE = "https://www.googleapis.com/auth/photospicker.mediaitems.readonly"
        private val JSON = "application/json; charset=UTF-8".toMediaType()
    }
}

data class PickingSession(
    val id: String,
    val pickerUri: String,
    val mediaItemsSet: Boolean,
    val pollIntervalMs: Long,
    val timeoutMs: Long,
)

@Serializable
data class PickedPhoto(
    val accountId: String,
    val id: String,
    val baseUrl: String,
    val filename: String,
    val mimeType: String,
    val isVideo: Boolean,
    val width: Int? = null,
    val height: Int? = null,
    val createTime: String? = null,
) {
    /** Photos: `=d` keeps EXIF metadata. Videos: `=dv` returns the playable original. */
    val downloadUrl: String get() = baseUrl + if (isVideo) "=dv" else "=d"
    fun thumbnailUrl(px: Int) = "$baseUrl=w$px-h$px-c"
}

@Serializable
private data class PollingConfigDto(val pollInterval: String? = null, val timeoutIn: String? = null)

@Serializable
private data class PickingSessionDto(
    val id: String,
    val pickerUri: String = "",
    val pollingConfig: PollingConfigDto? = null,
    val mediaItemsSet: Boolean? = null,
)

@Serializable
private data class MediaFileMetadataDto(val width: Int? = null, val height: Int? = null)

@Serializable
private data class MediaFileDto(
    val baseUrl: String? = null,
    val mimeType: String? = null,
    val filename: String? = null,
    val mediaFileMetadata: MediaFileMetadataDto? = null,
)

@Serializable
private data class PickedMediaItemDto(
    val id: String,
    val createTime: String? = null,
    val type: String? = null,
    val mediaFile: MediaFileDto? = null,
)

@Serializable
private data class PickedListDto(val mediaItems: List<PickedMediaItemDto> = emptyList(), val nextPageToken: String? = null)

private fun durationMs(s: String?, default: Long): Long =
    s?.removeSuffix("s")?.toDoubleOrNull()?.let { (it * 1000).toLong() } ?: default

private fun PickingSessionDto.toDomain() = PickingSession(
    id = id,
    pickerUri = pickerUri,
    mediaItemsSet = mediaItemsSet == true,
    pollIntervalMs = durationMs(pollingConfig?.pollInterval, 3000).coerceIn(1000, 15_000),
    timeoutMs = durationMs(pollingConfig?.timeoutIn, 30 * 60 * 1000L),
)

private fun PickedMediaItemDto.toDomain(accountId: String): PickedPhoto? {
    val file = mediaFile ?: return null
    val base = file.baseUrl ?: return null
    val mime = file.mimeType ?: if (type == "VIDEO") "video/mp4" else "image/jpeg"
    return PickedPhoto(
        accountId = accountId,
        id = id,
        baseUrl = base,
        filename = file.filename ?: id,
        mimeType = mime,
        isVideo = type == "VIDEO" || mime.startsWith("video/"),
        width = file.mediaFileMetadata?.width,
        height = file.mediaFileMetadata?.height,
        createTime = createTime,
    )
}

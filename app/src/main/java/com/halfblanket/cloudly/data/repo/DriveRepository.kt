package com.halfblanket.cloudly.data.repo

import com.halfblanket.cloudly.core.CloudlyException
import com.halfblanket.cloudly.data.db.CachedListEntity
import com.halfblanket.cloudly.data.db.ListCacheDao
import com.halfblanket.cloudly.data.drive.DriveApi
import com.halfblanket.cloudly.data.drive.DriveFile
import com.halfblanket.cloudly.data.drive.DriveQuery
import com.halfblanket.cloudly.data.drive.FileMetadataBody
import com.halfblanket.cloudly.data.drive.MimeTypes
import com.halfblanket.cloudly.data.drive.Page
import com.halfblanket.cloudly.data.drive.SortOrder
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

/** Where a listing comes from. */
sealed interface FileSource {
    val cacheKey: String?

    data class Folder(val folderId: String) : FileSource {
        override val cacheKey get() = "folder:$folderId"
    }
    data object SharedWithMe : FileSource { override val cacheKey = "shared" }
    data object Starred : FileSource { override val cacheKey = "starred" }
    data object Recent : FileSource { override val cacheKey = "recent" }
    data object RecentlyModified : FileSource { override val cacheKey = "modified" }
    data object Trash : FileSource { override val cacheKey: String? = null }
    data object Largest : FileSource { override val cacheKey: String? = null }
    data class QuickFolders(val parentId: String = DriveQuery.ROOT) : FileSource {
        override val cacheKey get() = "quickfolders:$parentId"
    }
    data class Search(val text: String) : FileSource { override val cacheKey: String? = null }
}

/** Search results from several accounts at once, with a page token per account. */
data class MultiAccountPage(
    val items: List<DriveFile>,
    val nextTokens: Map<String, String>,
    val failures: Map<String, Throwable>,
)

/**
 * File operations on Google Drive. Every method takes the file (which carries its account) or an explicit
 * accountId, and operations that involve two files refuse to run if the accounts differ — cross-account
 * work goes through [TransferRepository], which downloads from one account and uploads to the other.
 */
class DriveRepository(
    private val api: DriveApi,
    private val cache: ListCacheDao,
    private val json: Json,
) {
    private val rootIds = ConcurrentHashMap<String, String>()

    // ------------------------------------------------------------------ listing

    suspend fun list(
        accountId: String,
        source: FileSource,
        sort: SortOrder = SortOrder(),
        pageToken: String? = null,
        pageSize: Int = 60,
    ): Page<DriveFile> {
        val (q, orderBy) = when (source) {
            is FileSource.Folder -> DriveQuery.children(source.folderId) to sort.toDriveOrderBy()
            FileSource.SharedWithMe -> DriveQuery.SHARED_WITH_ME to sort.toDriveOrderBy()
            FileSource.Starred -> DriveQuery.STARRED to sort.toDriveOrderBy()
            FileSource.Recent -> DriveQuery.RECENT to "viewedByMeTime desc"
            FileSource.RecentlyModified -> DriveQuery.RECENTLY_MODIFIED to "modifiedTime desc"
            FileSource.Trash -> DriveQuery.TRASH to "modifiedTime desc"
            FileSource.Largest -> DriveQuery.OWNED_FILES to "quotaBytesUsed desc"
            is FileSource.QuickFolders -> DriveQuery.childFolders(source.parentId) to "modifiedTime desc"
            is FileSource.Search -> DriveQuery.nameContains(source.text) to "folder,modifiedTime desc"
        }
        val page = api.list(accountId, q, orderBy, pageSize, pageToken)
        val items = if (source == FileSource.Trash) page.items.filter { it.explicitlyTrashed } else page.items
        if (pageToken == null && source.cacheKey != null) {
            runCatching {
                cache.put(
                    CachedListEntity(
                        accountId, source.cacheKey!! + "|" + sort.toDriveOrderBy(),
                        json.encodeToString(items), System.currentTimeMillis(),
                    ),
                )
            }
        }
        return Page(items, page.nextPageToken)
    }

    /** The last cached first page for this listing, if any. */
    suspend fun cached(accountId: String, source: FileSource, sort: SortOrder = SortOrder()): List<DriveFile>? {
        val key = source.cacheKey ?: return null
        val entry = cache.get(accountId, key + "|" + sort.toDriveOrderBy()) ?: return null
        return runCatching { json.decodeFromString<List<DriveFile>>(entry.json) }.getOrNull()
            ?.filter { it.accountId == accountId }
    }

    suspend fun get(accountId: String, fileId: String): DriveFile = api.get(accountId, fileId)

    suspend fun rootId(accountId: String): String =
        rootIds[accountId] ?: api.get(accountId, DriveQuery.ROOT).id.also { rootIds[accountId] = it }

    /** Folder chain from My Drive down to [folderId] (inclusive). Stops at folders the user can't see. */
    suspend fun pathTo(accountId: String, folderId: String): List<DriveFile> {
        val root = rootId(accountId)
        val chain = ArrayList<DriveFile>()
        var current: String? = folderId
        var depth = 0
        while (current != null && current != root && depth < 32) {
            val f = runCatching { api.get(accountId, current) }.getOrNull() ?: break
            chain.add(0, f)
            current = f.parents.firstOrNull()
            depth++
        }
        return chain
    }

    /** Searches every given account in parallel. One account failing doesn't hide the others' results. */
    suspend fun searchAccounts(
        accountIds: List<String>,
        text: String,
        tokens: Map<String, String?> = emptyMap(),
    ): MultiAccountPage = coroutineScope {
        val targets = if (tokens.isEmpty()) accountIds.associateWith { null } else tokens
        val results = targets.map { (acc, token) ->
            async { acc to runCatching { list(acc, FileSource.Search(text), pageToken = token, pageSize = 40) } }
        }.awaitAll()
        val items = results.flatMap { it.second.getOrNull()?.items.orEmpty() }
            .sortedWith(compareBy<DriveFile> { !it.isFolder }.thenByDescending { it.modifiedTime })
        MultiAccountPage(
            items = items,
            nextTokens = results.mapNotNull { (acc, r) -> r.getOrNull()?.nextPageToken?.let { acc to it } }.toMap(),
            failures = results.mapNotNull { (acc, r) -> r.exceptionOrNull()?.let { acc to it } }.toMap(),
        )
    }

    // ------------------------------------------------------------------ mutations

    suspend fun createFolder(accountId: String, parentId: String, name: String): DriveFile {
        val clean = name.trim()
        require(clean.isNotEmpty()) { "Folder name can't be empty" }
        return api.createFolder(accountId, clean, parentId)
    }

    suspend fun rename(file: DriveFile, newName: String): DriveFile {
        val clean = newName.trim()
        require(clean.isNotEmpty()) { "Name can't be empty" }
        return api.update(file.accountId, file.id, FileMetadataBody(name = clean))
    }

    suspend fun setStarred(file: DriveFile, starred: Boolean): DriveFile =
        api.update(file.accountId, file.id, FileMetadataBody(starred = starred))

    /** Moves to Drive's trash (recoverable for 30 days). */
    suspend fun trash(file: DriveFile): DriveFile = api.update(file.accountId, file.id, FileMetadataBody(trashed = true))

    suspend fun restore(file: DriveFile): DriveFile = api.update(file.accountId, file.id, FileMetadataBody(trashed = false))

    /** Irreversible. Only call after explicit user confirmation. */
    suspend fun deleteForever(file: DriveFile) = api.deletePermanently(file.accountId, file.id)

    /** Irreversible. Only call after explicit user confirmation. */
    suspend fun emptyTrash(accountId: String) = api.emptyTrash(accountId)

    /** Moves within the same account. */
    suspend fun move(file: DriveFile, targetAccountId: String, targetParentId: String): DriveFile {
        requireSameAccount(file, targetAccountId)
        if (file.isFolder) ensureNotIntoItself(file, targetParentId)
        if (file.parents.contains(targetParentId)) return file
        return api.update(
            file.accountId, file.id,
            addParents = targetParentId,
            removeParents = file.parents.joinToString(",").ifEmpty { null },
        )
    }

    /** Copies within the same account. Folders are copied recursively (Drive can't copy folders itself). */
    suspend fun copy(
        file: DriveFile,
        targetAccountId: String,
        targetParentId: String,
        onItem: (String) -> Unit = {},
    ): DriveFile {
        requireSameAccount(file, targetAccountId)
        if (!file.isFolder) {
            if (!file.capabilities.canCopy) throw CloudlyException.Unsupported("The owner doesn't allow copying ${file.name}")
            onItem(file.name)
            return api.copy(file.accountId, file.id, copyName(file, targetParentId), targetParentId)
        }
        ensureNotIntoItself(file, targetParentId)
        val newFolder = api.createFolder(file.accountId, copyName(file, targetParentId), targetParentId)
        onItem(file.name)
        copyChildren(file, newFolder.id, onItem)
        return newFolder
    }

    private suspend fun copyChildren(folder: DriveFile, newParentId: String, onItem: (String) -> Unit) {
        listAll(folder.accountId, DriveQuery.children(folder.id)).forEach { child ->
            coroutineContext.ensureActive()
            if (child.isFolder) {
                val sub = api.createFolder(child.accountId, child.name, newParentId)
                onItem(child.name)
                copyChildren(child, sub.id, onItem)
            } else if (child.capabilities.canCopy) {
                api.copy(child.accountId, child.id, child.name, newParentId)
                onItem(child.name)
            }
        }
    }

    /** Keeps the original name, except when copying into the same folder, like Drive's "Copy of …". */
    private fun copyName(file: DriveFile, targetParentId: String) =
        if (file.parents.contains(targetParentId)) "Copy of ${file.name}" else file.name

    suspend fun listAll(accountId: String, q: String, fields: String = DriveApi.FILE_FIELDS, max: Int = 10_000): List<DriveFile> {
        val out = ArrayList<DriveFile>()
        var token: String? = null
        do {
            coroutineContext.ensureActive()
            val page = api.list(accountId, q, null, 1000, token, fields)
            out += page.items
            token = page.nextPageToken
        } while (token != null && out.size < max)
        return out
    }

    /**
     * Scans the account's largest owned files (by storage used). Drive has no per-category quota API, so
     * storage breakdowns are computed from this sample and labelled as such in the UI.
     */
    suspend fun scanLargest(accountId: String, limit: Int = 1000): List<DriveFile> {
        val out = ArrayList<DriveFile>()
        var token: String? = null
        do {
            coroutineContext.ensureActive()
            val page = api.list(
                accountId, DriveQuery.OWNED_FILES, "quotaBytesUsed desc",
                minOf(1000, limit - out.size), token, DriveApi.SCAN_FIELDS,
            )
            out += page.items
            token = page.nextPageToken
        } while (token != null && out.size < limit)
        return out
    }

    private fun requireSameAccount(file: DriveFile, accountId: String) {
        if (file.accountId != accountId) {
            throw CloudlyException.Safety("That file belongs to a different account. Use a cross-account transfer instead.")
        }
    }

    private suspend fun ensureNotIntoItself(folder: DriveFile, targetParentId: String) {
        if (targetParentId == folder.id) throw CloudlyException.Safety("A folder can't be placed inside itself")
        val path = pathTo(folder.accountId, targetParentId)
        if (path.any { it.id == folder.id }) throw CloudlyException.Safety("A folder can't be placed inside its own subfolder")
    }

    fun isExportable(file: DriveFile) = !file.isGoogleNative || MimeTypes.exportFor(file.mimeType) != null
}

package com.halfblanket.cloudly.data.drive

/** Builders for Drive `q` search expressions and `orderBy` clauses. */
object DriveQuery {
    const val ROOT = "root"

    fun escape(value: String): String = value.replace("\\", "\\\\").replace("'", "\\'")

    fun children(parentId: String) = "'${escape(parentId)}' in parents and trashed = false"
    fun childFolders(parentId: String) = "${children(parentId)} and mimeType = '${MimeTypes.FOLDER}'"
    const val SHARED_WITH_ME = "sharedWithMe = true and trashed = false"
    const val STARRED = "starred = true and trashed = false"
    const val TRASH = "trashed = true"
    const val NOT_FOLDER = "mimeType != '${MimeTypes.FOLDER}'"
    const val RECENT = "trashed = false and $NOT_FOLDER and viewedByMeTime > '2000-01-01T00:00:00'"
    const val RECENTLY_MODIFIED = "trashed = false and $NOT_FOLDER"
    const val ALL_FILES = "trashed = false and $NOT_FOLDER"
    const val OWNED_FILES = "trashed = false and $NOT_FOLDER and 'me' in owners"

    fun nameContains(text: String, foldersOnly: Boolean = false): String {
        val base = "name contains '${escape(text.trim())}' and trashed = false"
        return if (foldersOnly) "$base and mimeType = '${MimeTypes.FOLDER}'" else base
    }
}

enum class SortField(val label: String) {
    NAME("Name"),
    MODIFIED("Date modified"),
    SIZE("Size"),
    TYPE("File type"),
}

data class SortOrder(val field: SortField = SortField.NAME, val ascending: Boolean = true) {
    /** Server-side order. File type has no Drive equivalent, so it is sorted client-side by name order. */
    fun toDriveOrderBy(foldersFirst: Boolean = true): String {
        val dir = if (ascending) "" else " desc"
        val key = when (field) {
            SortField.NAME, SortField.TYPE -> "name_natural$dir"
            SortField.MODIFIED -> "modifiedTime$dir"
            SortField.SIZE -> "quotaBytesUsed$dir"
        }
        return if (foldersFirst) "folder,$key" else key
    }
}

/** Client-side ordering that matches the server order and also handles [SortField.TYPE]. */
fun List<DriveFile>.sortedFor(order: SortOrder): List<DriveFile> {
    if (order.field != SortField.TYPE) return this
    val cmp = compareBy<DriveFile>({ !it.isFolder }, { it.category.ordinal }, { it.name.lowercase() })
    return if (order.ascending) sortedWith(cmp) else sortedWith(compareBy<DriveFile> { !it.isFolder }.then(cmp.reversed()))
}

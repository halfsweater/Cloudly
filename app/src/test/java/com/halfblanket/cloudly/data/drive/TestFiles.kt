package com.halfblanket.cloudly.data.drive

import java.time.Instant

fun testFile(
    id: String,
    accountId: String = "acc1",
    name: String = "$id.bin",
    mime: String = "application/octet-stream",
    size: Long? = 1000,
    md5: String? = null,
    created: Instant? = Instant.parse("2020-01-01T00:00:00Z"),
    modified: Instant? = created,
    viewed: Instant? = null,
) = DriveFile(
    accountId = accountId, id = id, name = name, mimeType = mime, size = size, quotaBytesUsed = size,
    createdTime = created, modifiedTime = modified, viewedByMeTime = viewed, starred = false, trashed = false,
    explicitlyTrashed = false, parents = listOf("root"), thumbnailLink = null, webViewLink = null, ownedByMe = true,
    ownerName = null, ownerEmail = null, lastModifiedBy = null, shared = false, md5Checksum = md5, fileExtension = null,
    description = null,
    capabilities = DriveFile.Capabilities(true, true, true, true, true, true, true, true, true, true),
    imageWidth = null, imageHeight = null, videoDurationMillis = null, shortcutTargetId = null, shortcutTargetMimeType = null,
)

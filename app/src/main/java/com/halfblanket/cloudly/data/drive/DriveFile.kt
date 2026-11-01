package com.halfblanket.cloudly.data.drive

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.Instant

/**
 * A Drive file as Cloudly sees it. [accountId] is the owning connected account — every operation on a
 * file is routed through the client for that account, so it is impossible to act on a file with the
 * wrong account's credentials.
 */
@Serializable
data class DriveFile(
    val accountId: String,
    val id: String,
    val name: String,
    val mimeType: String,
    val size: Long?,
    val quotaBytesUsed: Long?,
    @Serializable(with = InstantSerializer::class) val createdTime: Instant?,
    @Serializable(with = InstantSerializer::class) val modifiedTime: Instant?,
    @Serializable(with = InstantSerializer::class) val viewedByMeTime: Instant?,
    val starred: Boolean,
    val trashed: Boolean,
    val explicitlyTrashed: Boolean,
    val parents: List<String>,
    val thumbnailLink: String?,
    val webViewLink: String?,
    val ownedByMe: Boolean,
    val ownerName: String?,
    val ownerEmail: String?,
    val lastModifiedBy: String?,
    val shared: Boolean,
    val md5Checksum: String?,
    val fileExtension: String?,
    val description: String?,
    val capabilities: Capabilities,
    val imageWidth: Int?,
    val imageHeight: Int?,
    val videoDurationMillis: Long?,
    val shortcutTargetId: String?,
    val shortcutTargetMimeType: String?,
) {
    @Serializable
    data class Capabilities(
        val canAddChildren: Boolean,
        val canCopy: Boolean,
        val canDelete: Boolean,
        val canDownload: Boolean,
        val canEdit: Boolean,
        val canRename: Boolean,
        val canTrash: Boolean,
        val canUntrash: Boolean,
        val canMove: Boolean,
        val canShare: Boolean,
    )

    val key: FileKey get() = FileKey(accountId, id)
    val isFolder: Boolean get() = mimeType == MimeTypes.FOLDER
    val isShortcut: Boolean get() = mimeType == MimeTypes.SHORTCUT
    val isGoogleNative: Boolean get() = MimeTypes.isGoogleNative(mimeType)
    val category: FileCategory get() = FileCategory.of(mimeType, name)

    /** Bytes for display: binary size, else storage used. Google-native files usually report none. */
    val displaySize: Long? get() = size ?: quotaBytesUsed?.takeIf { it > 0 }
    val storageBytes: Long get() = quotaBytesUsed ?: size ?: 0L
}

/** Uniquely identifies a file across all connected accounts. */
data class FileKey(val accountId: String, val fileId: String)

internal fun DriveFileDto.toDomain(accountId: String): DriveFile {
    val owner = owners?.firstOrNull()
    val caps = capabilities
    return DriveFile(
        accountId = accountId,
        id = id,
        name = name,
        mimeType = mimeType,
        size = size?.toLongOrNull(),
        quotaBytesUsed = quotaBytesUsed?.toLongOrNull(),
        createdTime = createdTime.toInstantOrNull(),
        modifiedTime = modifiedTime.toInstantOrNull(),
        viewedByMeTime = viewedByMeTime.toInstantOrNull(),
        starred = starred == true,
        trashed = trashed == true,
        explicitlyTrashed = explicitlyTrashed == true,
        parents = parents.orEmpty(),
        thumbnailLink = thumbnailLink?.takeIf { hasThumbnail != false },
        webViewLink = webViewLink,
        ownedByMe = ownedByMe != false,
        ownerName = owner?.displayName,
        ownerEmail = owner?.emailAddress,
        lastModifiedBy = lastModifyingUser?.displayName,
        shared = shared == true,
        md5Checksum = md5Checksum,
        fileExtension = fullFileExtension ?: fileExtension,
        description = description,
        capabilities = DriveFile.Capabilities(
            canAddChildren = caps?.canAddChildren == true,
            canCopy = caps?.canCopy != false,
            canDelete = caps?.canDelete == true,
            canDownload = caps?.canDownload != false,
            canEdit = caps?.canEdit == true,
            canRename = caps?.canRename == true,
            canTrash = caps?.canTrash == true,
            canUntrash = caps?.canUntrash == true,
            canMove = caps?.canMoveItemWithinDrive == true,
            canShare = caps?.canShare == true,
        ),
        imageWidth = imageMediaMetadata?.width ?: videoMediaMetadata?.width,
        imageHeight = imageMediaMetadata?.height ?: videoMediaMetadata?.height,
        videoDurationMillis = videoMediaMetadata?.durationMillis?.toLongOrNull(),
        shortcutTargetId = shortcutDetails?.targetId,
        shortcutTargetMimeType = shortcutDetails?.targetMimeType,
    )
}

private fun String?.toInstantOrNull(): Instant? = this?.let { runCatching { Instant.parse(it) }.getOrNull() }

object InstantSerializer : KSerializer<Instant> {
    override val descriptor = PrimitiveSerialDescriptor("Instant", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): Instant = Instant.parse(decoder.decodeString())
}

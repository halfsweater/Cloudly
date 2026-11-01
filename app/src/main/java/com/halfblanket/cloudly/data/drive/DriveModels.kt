package com.halfblanket.cloudly.data.drive

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Wire models for the Google Drive REST API v3. Only fields Cloudly requests are declared. */

@Serializable
data class DriveUserDto(
    val displayName: String? = null,
    val emailAddress: String? = null,
    val photoLink: String? = null,
    val permissionId: String? = null,
    val me: Boolean? = null,
)

@Serializable
data class StorageQuotaDto(
    val limit: String? = null,
    val usage: String? = null,
    val usageInDrive: String? = null,
    val usageInDriveTrash: String? = null,
)

@Serializable
data class AboutDto(
    val user: DriveUserDto? = null,
    val storageQuota: StorageQuotaDto? = null,
)

@Serializable
data class CapabilitiesDto(
    val canAddChildren: Boolean? = null,
    val canCopy: Boolean? = null,
    val canDelete: Boolean? = null,
    val canDownload: Boolean? = null,
    val canEdit: Boolean? = null,
    val canRename: Boolean? = null,
    val canTrash: Boolean? = null,
    val canUntrash: Boolean? = null,
    val canMoveItemWithinDrive: Boolean? = null,
    val canShare: Boolean? = null,
)

@Serializable
data class ImageMetadataDto(val width: Int? = null, val height: Int? = null)

@Serializable
data class VideoMetadataDto(
    val width: Int? = null,
    val height: Int? = null,
    val durationMillis: String? = null,
)

@Serializable
data class ShortcutDetailsDto(val targetId: String? = null, val targetMimeType: String? = null)

@Serializable
data class DriveFileDto(
    val id: String,
    val name: String = "",
    val mimeType: String = "",
    val size: String? = null,
    val quotaBytesUsed: String? = null,
    val createdTime: String? = null,
    val modifiedTime: String? = null,
    val viewedByMeTime: String? = null,
    val starred: Boolean? = null,
    val trashed: Boolean? = null,
    val explicitlyTrashed: Boolean? = null,
    val parents: List<String>? = null,
    val thumbnailLink: String? = null,
    val hasThumbnail: Boolean? = null,
    val iconLink: String? = null,
    val webViewLink: String? = null,
    val ownedByMe: Boolean? = null,
    val owners: List<DriveUserDto>? = null,
    val lastModifyingUser: DriveUserDto? = null,
    val shared: Boolean? = null,
    val md5Checksum: String? = null,
    val fileExtension: String? = null,
    val fullFileExtension: String? = null,
    val originalFilename: String? = null,
    val description: String? = null,
    val capabilities: CapabilitiesDto? = null,
    val imageMediaMetadata: ImageMetadataDto? = null,
    val videoMediaMetadata: VideoMetadataDto? = null,
    val shortcutDetails: ShortcutDetailsDto? = null,
)

@Serializable
data class FileListDto(
    val files: List<DriveFileDto> = emptyList(),
    val nextPageToken: String? = null,
    val incompleteSearch: Boolean? = null,
)

@Serializable
data class DriveErrorEnvelope(val error: DriveErrorBody? = null)

@Serializable
data class DriveErrorBody(
    val code: Int? = null,
    val message: String? = null,
    val errors: List<DriveErrorItem>? = null,
)

@Serializable
data class DriveErrorItem(val reason: String? = null, val message: String? = null, val domain: String? = null)

/** Body for creating files/folders or updating metadata. Null fields are omitted. */
@Serializable
data class FileMetadataBody(
    val name: String? = null,
    val mimeType: String? = null,
    val parents: List<String>? = null,
    val starred: Boolean? = null,
    val trashed: Boolean? = null,
    val description: String? = null,
    @SerialName("modifiedTime") val modifiedTime: String? = null,
)

package com.halfblanket.cloudly.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** How much of the user's Drive the account actually granted (from the scopes Google returned). */
enum class DriveAccess { FULL, APP_FILES_ONLY }

/**
 * A connected Google account. No password or refresh token is ever stored: Google Play services owns the
 * grant, and Cloudly only keeps public profile data plus short-lived access tokens (encrypted, see TokenVault).
 */
@Entity(tableName = "accounts", indices = [Index(value = ["email"], unique = true)])
data class AccountEntity(
    /** Drive's stable permissionId for the user. */
    @PrimaryKey val id: String,
    val email: String,
    val displayName: String,
    val photoUrl: String?,
    val access: DriveAccess,
    val grantedScopes: String,
    val needsReauth: Boolean = false,
    val colorIndex: Int,
    val storageLimit: Long? = null,
    val storageUsage: Long = 0,
    val usageInDrive: Long = 0,
    val usageInTrash: Long = 0,
    val storageUpdatedAt: Long = 0,
    val addedAt: Long = System.currentTimeMillis(),
)

enum class TransferKind { UPLOAD, DOWNLOAD, CROSS_COPY, CROSS_MOVE, PHOTOS_TO_DRIVE, PHOTOS_DOWNLOAD }
enum class TransferStatus { QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED }

@Entity(tableName = "transfers", indices = [Index("createdAt")])
data class TransferEntity(
    @PrimaryKey val id: String,
    val kind: TransferKind,
    val sourceAccountId: String,
    val targetAccountId: String? = null,
    val title: String,
    val detail: String? = null,
    val status: TransferStatus = TransferStatus.QUEUED,
    val bytesDone: Long = 0,
    val bytesTotal: Long = 0,
    val itemsDone: Int = 0,
    val itemsTotal: Int = 0,
    val error: String? = null,
    val resultUri: String? = null,
    /** Serialized worker input, so a failed transfer can be retried exactly as it was requested. */
    val payload: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    val isActive get() = status == TransferStatus.QUEUED || status == TransferStatus.RUNNING
    val progress: Float
        get() = when {
            bytesTotal > 0 -> (bytesDone.toFloat() / bytesTotal).coerceIn(0f, 1f)
            itemsTotal > 0 -> (itemsDone.toFloat() / itemsTotal).coerceIn(0f, 1f)
            else -> 0f
        }
}

/** Last-known first page of a listing, shown instantly while fresh data loads. */
@Entity(tableName = "list_cache", primaryKeys = ["accountId", "listKey"])
data class CachedListEntity(
    val accountId: String,
    val listKey: String,
    val json: String,
    val updatedAt: Long,
)

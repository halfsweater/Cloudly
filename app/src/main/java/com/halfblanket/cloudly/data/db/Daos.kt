package com.halfblanket.cloudly.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface AccountDao {
    @Query("SELECT * FROM accounts ORDER BY addedAt")
    fun observeAll(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts ORDER BY addedAt")
    suspend fun all(): List<AccountEntity>

    @Query("SELECT * FROM accounts WHERE id = :id")
    suspend fun byId(id: String): AccountEntity?

    @Query("SELECT * FROM accounts WHERE email = :email COLLATE NOCASE")
    suspend fun byEmail(email: String): AccountEntity?

    @Upsert
    suspend fun upsert(account: AccountEntity)

    @Query("UPDATE accounts SET needsReauth = :needs WHERE id = :id")
    suspend fun setNeedsReauth(id: String, needs: Boolean)

    @Query(
        "UPDATE accounts SET storageLimit = :limit, storageUsage = :usage, usageInDrive = :inDrive, " +
            "usageInTrash = :inTrash, storageUpdatedAt = :at WHERE id = :id",
    )
    suspend fun updateStorage(id: String, limit: Long?, usage: Long, inDrive: Long, inTrash: Long, at: Long)

    @Query("DELETE FROM accounts WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface TransferDao {
    @Query("SELECT * FROM transfers ORDER BY createdAt DESC LIMIT 200")
    fun observeRecent(): Flow<List<TransferEntity>>

    @Query("SELECT * FROM transfers WHERE id = :id")
    suspend fun byId(id: String): TransferEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(transfer: TransferEntity)

    @Query("UPDATE transfers SET status = :status, error = :error, updatedAt = :at WHERE id = :id")
    suspend fun setStatus(id: String, status: TransferStatus, error: String?, at: Long = System.currentTimeMillis())

    @Query(
        "UPDATE transfers SET bytesDone = :done, bytesTotal = :total, itemsDone = :itemsDone, " +
            "itemsTotal = :itemsTotal, detail = COALESCE(:detail, detail), updatedAt = :at WHERE id = :id",
    )
    suspend fun setProgress(
        id: String, done: Long, total: Long, itemsDone: Int, itemsTotal: Int, detail: String?,
        at: Long = System.currentTimeMillis(),
    )

    @Query("UPDATE transfers SET resultUri = :uri WHERE id = :id")
    suspend fun setResult(id: String, uri: String?)

    @Query("DELETE FROM transfers WHERE status IN ('SUCCEEDED','FAILED','CANCELLED')")
    suspend fun clearFinished()

    @Query("UPDATE transfers SET status = 'CANCELLED', error = 'Account removed' WHERE (sourceAccountId = :accountId OR targetAccountId = :accountId) AND status IN ('QUEUED','RUNNING')")
    suspend fun cancelForAccount(accountId: String)
}

@Dao
interface ListCacheDao {
    @Query("SELECT * FROM list_cache WHERE accountId = :accountId AND listKey = :key")
    suspend fun get(accountId: String, key: String): CachedListEntity?

    @Upsert
    suspend fun put(entry: CachedListEntity)

    @Query("DELETE FROM list_cache WHERE accountId = :accountId")
    suspend fun clearAccount(accountId: String)

    @Query("DELETE FROM list_cache")
    suspend fun clearAll()
}

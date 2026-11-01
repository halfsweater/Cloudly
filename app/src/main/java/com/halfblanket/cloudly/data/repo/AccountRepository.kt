package com.halfblanket.cloudly.data.repo

import com.halfblanket.cloudly.auth.DriveScopes
import com.halfblanket.cloudly.auth.GoogleAuthManager
import com.halfblanket.cloudly.core.CloudlyException
import com.halfblanket.cloudly.data.db.AccountDao
import com.halfblanket.cloudly.data.db.AccountEntity
import com.halfblanket.cloudly.data.db.DriveAccess
import com.halfblanket.cloudly.data.db.ListCacheDao
import com.halfblanket.cloudly.data.db.TransferDao
import com.halfblanket.cloudly.data.drive.DriveApi
import com.halfblanket.cloudly.data.prefs.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.util.UUID

/** Snapshot of connected accounts and which one is active. */
data class AccountsState(
    val accounts: List<AccountEntity> = emptyList(),
    val activeId: String? = null,
    val defaultId: String? = null,
    val loaded: Boolean = false,
) {
    val active: AccountEntity? get() = accounts.firstOrNull { it.id == activeId } ?: accounts.firstOrNull()
    fun byId(id: String?): AccountEntity? = accounts.firstOrNull { it.id == id }
}

class AccountRepository(
    private val dao: AccountDao,
    private val transfers: TransferDao,
    private val listCache: ListCacheDao,
    private val auth: GoogleAuthManager,
    private val api: DriveApi,
    private val settings: SettingsRepository,
    appScope: CoroutineScope,
) {
    val state: StateFlow<AccountsState> = combine(dao.observeAll(), settings.settings) { list, s ->
        val active = s.activeAccountId?.takeIf { id -> list.any { it.id == id } }
            ?: s.defaultAccountId?.takeIf { id -> list.any { it.id == id } }
            ?: list.firstOrNull()?.id
        AccountsState(list, active, s.defaultAccountId?.takeIf { id -> list.any { it.id == id } }, loaded = true)
    }.stateIn(appScope, SharingStarted.Eagerly, AccountsState())

    suspend fun setActive(id: String) {
        require(dao.byId(id) != null) { "Unknown account" }
        settings.setActiveAccount(id)
    }

    suspend fun setDefault(id: String?) = settings.setDefaultAccount(id)

    /**
     * Completes a connection after Google granted a token. Confirms, via Drive itself, which account the
     * token belongs to; if the user picked one account but the token is for another, nothing is saved.
     */
    suspend fun completeConnection(
        expectedEmail: String?,
        token: String,
        grantedScopes: List<String>,
        pickedName: String?,
        pickedPhoto: String?,
    ): AccountEntity {
        val access = DriveScopes.accessFromGranted(grantedScopes)
            ?: throw CloudlyException.Safety("Google Drive access wasn't granted, so the account wasn't added.")
        val tempId = GoogleAuthManager.TEMP_PREFIX + UUID.randomUUID()
        auth.store(tempId, token)
        try {
            val about = api.about(tempId)
            val user = about.user ?: throw CloudlyException.Unsupported("Drive didn't return the account profile")
            val email = user.emailAddress ?: expectedEmail
                ?: throw CloudlyException.Unsupported("Drive didn't return the account email")
            if (expectedEmail != null && !email.equals(expectedEmail, ignoreCase = true)) {
                throw CloudlyException.Safety(
                    "Google returned access for $email instead of $expectedEmail. Nothing was changed.",
                )
            }
            val existing = dao.byEmail(email)
            val id = existing?.id ?: user.permissionId ?: email.lowercase()
            val quota = about.storageQuota
            val entity = AccountEntity(
                id = id,
                email = email,
                displayName = user.displayName ?: pickedName ?: email.substringBefore('@'),
                photoUrl = pickedPhoto ?: user.photoLink ?: existing?.photoUrl,
                access = access,
                grantedScopes = grantedScopes.joinToString(" "),
                needsReauth = false,
                colorIndex = existing?.colorIndex ?: nextColorIndex(),
                storageLimit = quota?.limit?.toLongOrNull(),
                storageUsage = quota?.usage?.toLongOrNull() ?: 0,
                usageInDrive = quota?.usageInDrive?.toLongOrNull() ?: 0,
                usageInTrash = quota?.usageInDriveTrash?.toLongOrNull() ?: 0,
                storageUpdatedAt = System.currentTimeMillis(),
                addedAt = existing?.addedAt ?: System.currentTimeMillis(),
            )
            dao.upsert(entity)
            auth.store(id, token)
            if (existing != null && existing.access != access) listCache.clearAccount(id)
            settings.setActiveAccount(id)
            if (settings.current().defaultAccountId == null) settings.setDefaultAccount(id)
            return entity
        } finally {
            auth.dropTemporary(tempId)
        }
    }

    /** Disconnects an account from Cloudly: cached tokens, listings and pending transfers are removed. */
    suspend fun remove(id: String) {
        transfers.cancelForAccount(id)
        auth.forget(id)
        listCache.clearAccount(id)
        dao.delete(id)
        val s = settings.current()
        val remaining = dao.all()
        if (s.activeAccountId == id) settings.setActiveAccount(remaining.firstOrNull()?.id)
        if (s.defaultAccountId == id) settings.setDefaultAccount(remaining.firstOrNull()?.id)
    }

    suspend fun refreshStorage(id: String) {
        val quota = api.about(id).storageQuota ?: return
        dao.updateStorage(
            id = id,
            limit = quota.limit?.toLongOrNull(),
            usage = quota.usage?.toLongOrNull() ?: 0,
            inDrive = quota.usageInDrive?.toLongOrNull() ?: 0,
            inTrash = quota.usageInDriveTrash?.toLongOrNull() ?: 0,
            at = System.currentTimeMillis(),
        )
    }

    /** Refreshes storage for every account; failures for one account don't affect the others. */
    suspend fun refreshAllStorage(): Map<String, Throwable> = coroutineScope {
        dao.all().map { acc -> async { acc.id to runCatching { refreshStorage(acc.id) }.exceptionOrNull() } }
            .awaitAll().mapNotNull { (id, e) -> e?.let { id to it } }.toMap()
    }

    suspend fun account(id: String): AccountEntity? = dao.byId(id)

    private suspend fun nextColorIndex(): Int {
        val used = dao.all().map { it.colorIndex }.toSet()
        return (0 until 8).firstOrNull { it !in used } ?: dao.all().size % 8
    }

    fun accessLabel(access: DriveAccess) = when (access) {
        DriveAccess.FULL -> "Full Drive access"
        DriveAccess.APP_FILES_ONLY -> "Files created with Cloudly only"
    }
}

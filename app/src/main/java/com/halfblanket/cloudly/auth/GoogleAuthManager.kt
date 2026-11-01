package com.halfblanket.cloudly.auth

import android.accounts.Account
import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.halfblanket.cloudly.BuildConfig
import com.halfblanket.cloudly.core.CloudlyException
import com.halfblanket.cloudly.data.db.AccountDao
import com.halfblanket.cloudly.data.drive.AccessTokenSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Google sign-in and Drive authorization, built on Google Identity Services:
 *
 * - Credential Manager ("Sign in with Google") lets the user pick which Google account to connect.
 * - The Identity AuthorizationClient grants a Drive scope for that specific account and returns
 *   short-lived access tokens. Once consent is given, later tokens are issued silently, so accounts can be
 *   used side by side and switched instantly without signing in again.
 *
 * Passwords never pass through Cloudly, and refresh tokens are held by Google Play services, not the app.
 */
class GoogleAuthManager(
    context: Context,
    private val vault: TokenVault,
    private val accounts: AccountDao,
) : AccessTokenSource {

    private val app = context.applicationContext
    private val authClient = Identity.getAuthorizationClient(app)
    private val memory = ConcurrentHashMap<String, TokenVault.Entry>()
    private val locks = ConcurrentHashMap<String, Mutex>()

    data class PickedAccount(val email: String, val displayName: String?, val photoUrl: String?)

    sealed interface AuthOutcome {
        data class Granted(val token: String, val grantedScopes: List<String>) : AuthOutcome
        data class NeedsConsent(val intent: PendingIntent) : AuthOutcome
    }

    val canPickAccounts: Boolean get() = BuildConfig.WEB_CLIENT_ID.isNotBlank()

    /**
     * Shows Google's account chooser via Credential Manager. Returns null if the user cancelled.
     * Requires a Web OAuth client ID (see README); without it the caller falls back to letting the
     * authorization screen pick the account.
     */
    suspend fun pickGoogleAccount(activity: Activity): PickedAccount? {
        val option = GetSignInWithGoogleOption.Builder(BuildConfig.WEB_CLIENT_ID).build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        val result = try {
            CredentialManager.create(activity).getCredential(activity, request)
        } catch (e: GetCredentialCancellationException) {
            return null
        } catch (e: NoCredentialException) {
            throw CloudlyException.Unsupported("No Google account is available on this device. Add one in system settings.")
        }
        val credential = result.credential
        if (credential is CustomCredential &&
            credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        ) {
            val google = GoogleIdTokenCredential.createFrom(credential.data)
            return PickedAccount(google.id, google.displayName, google.profilePictureUri?.toString())
        }
        throw CloudlyException.Unsupported("Unexpected credential type returned by Google")
    }

    /** Requests [scope] for [email] (or lets Google choose the account when [email] is null). */
    suspend fun authorize(email: String?, scope: String): AuthOutcome {
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(scope)))
            .apply { if (email != null) setAccount(Account(email, GOOGLE_ACCOUNT_TYPE)) }
            .build()
        val result = authClient.authorize(request).await()
        return result.toOutcome()
    }

    /** Parses the result of the consent screen launched from [AuthOutcome.NeedsConsent]. */
    fun outcomeFromIntent(data: Intent?): AuthOutcome = authClient.getAuthorizationResultFromIntent(data).toOutcome()

    private fun AuthorizationResult.toOutcome(): AuthOutcome {
        val pending = pendingIntent
        if (hasResolution() && pending != null) return AuthOutcome.NeedsConsent(pending)
        val token = accessToken ?: throw CloudlyException.Unsupported("Google did not return an access token")
        return AuthOutcome.Granted(token, grantedScopes)
    }

    // ------------------------------------------------------------------ token source

    override suspend fun token(accountId: String): String = lockFor(accountId).withLock {
        memory[accountId]?.takeIf { it.isFresh() }?.let { return it.token }
        vault.read(accountId)?.takeIf { it.isFresh() }?.let {
            memory[accountId] = it
            return it.token
        }
        val account = accounts.byId(accountId)
            ?: throw CloudlyException.Safety("This account is no longer connected to Cloudly")
        val outcome = try {
            authorize(account.email, DriveScopes.scopeFor(account.access))
        } catch (e: ApiException) {
            if (e.statusCode == CommonStatusCodes.NETWORK_ERROR) throw CloudlyException.Offline(e)
            accounts.setNeedsReauth(accountId, true)
            throw CloudlyException.ReauthRequired(accountId, account.email)
        }
        when (outcome) {
            is AuthOutcome.NeedsConsent -> {
                accounts.setNeedsReauth(accountId, true)
                throw CloudlyException.ReauthRequired(accountId, account.email)
            }
            is AuthOutcome.Granted -> {
                store(accountId, outcome.token)
                if (account.needsReauth) accounts.setNeedsReauth(accountId, false)
                outcome.token
            }
        }
    }

    override suspend fun invalidate(accountId: String, token: String) {
        lockFor(accountId).withLock {
            memory.remove(accountId)
            vault.delete(accountId)
        }
        withContext(Dispatchers.IO) { runCatching { GoogleAuthUtil.clearToken(app, token) } }
    }

    /** Caches a token obtained interactively. Temporary ids (not yet saved accounts) stay in memory only. */
    fun store(accountId: String, token: String) {
        val entry = TokenVault.Entry(token, System.currentTimeMillis() + TOKEN_LIFETIME_MS)
        memory[accountId] = entry
        if (!accountId.startsWith(TEMP_PREFIX)) vault.write(accountId, entry)
    }

    /** Forgets everything Cloudly cached for an account. */
    suspend fun forget(accountId: String) {
        val token = memory.remove(accountId)?.token ?: vault.read(accountId)?.token
        vault.delete(accountId)
        if (token != null) withContext(Dispatchers.IO) { runCatching { GoogleAuthUtil.clearToken(app, token) } }
    }

    fun dropTemporary(tempId: String) {
        memory.remove(tempId)
    }

    private fun lockFor(id: String) = locks.getOrPut(id) { Mutex() }
    private fun TokenVault.Entry.isFresh() = expiresAtMillis - System.currentTimeMillis() > 60_000

    companion object {
        const val GOOGLE_ACCOUNT_TYPE = "com.google"
        const val TEMP_PREFIX = "pending:"
        /** Google access tokens last ~60 minutes; refresh a little early. 401s also trigger a refresh. */
        private const val TOKEN_LIFETIME_MS = 45 * 60 * 1000L
    }
}

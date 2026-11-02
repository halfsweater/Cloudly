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
) : AccessTokenSource, com.halfblanket.cloudly.data.photos.PhotosPickerApi.PhotosTokenSource {

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

    /** Turns Google Identity errors into a message that says what actually went wrong. */
    fun describeError(e: Throwable): String {
        val code = (e as? ApiException)?.statusCode ?: return e.message ?: "Google sign-in failed"
        return when (code) {
            CommonStatusCodes.DEVELOPER_ERROR ->
                "Google rejected Cloudly's configuration (code 10). ${configHint()}"
            CommonStatusCodes.CANCELED, 12501 -> "Sign-in was cancelled"
            CommonStatusCodes.NETWORK_ERROR -> "Network error while contacting Google. Check your connection."
            CommonStatusCodes.SIGN_IN_REQUIRED -> "Google needs you to sign in to this account on the device first."
            CommonStatusCodes.INTERNAL_ERROR -> "Google Play services returned an internal error (code 8). ${configHint()}"
            else -> "Google authorization failed (status $code: ${CommonStatusCodes.getStatusCodeString(code)})"
        }
    }

    /** What Google Cloud Console must match: the real package and signing-certificate SHA-1 of this install. */
    private fun configHint(): String =
        "Google Cloud needs an Android OAuth client for package ${app.packageName} with SHA-1 ${signingSha1()}, " +
            "in the same project where the Drive API is enabled and your account is a test user."

    fun signingSha1(): String = runCatching {
        val pm = app.packageManager
        val sig = if (android.os.Build.VERSION.SDK_INT >= 28) {
            pm.getPackageInfo(app.packageName, android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo!!.apkContentsSigners.first()
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(app.packageName, android.content.pm.PackageManager.GET_SIGNATURES).signatures!!.first()
        }
        java.security.MessageDigest.getInstance("SHA-1").digest(sig.toByteArray())
            .joinToString(":") { "%02X".format(it) }
    }.getOrDefault("unknown")

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

    // ------------------------------------------------------------------ Google Photos (Picker API)

    /** Photos tokens are requested separately (incremental authorization) and kept in memory only. */
    private val photosMemory = ConcurrentHashMap<String, TokenVault.Entry>()

    /** Thrown when the account hasn't approved Google Photos access yet; the UI shows Google's consent. */
    class PhotosConsentRequired(val intent: PendingIntent) : Exception("Google Photos access needs your approval")

    /** Requests the Photos Picker scope for [accountId]; returns the consent intent if approval is needed. */
    suspend fun authorizePhotos(accountId: String): AuthOutcome {
        val account = accounts.byId(accountId) ?: throw CloudlyException.Safety("This account is no longer connected")
        val outcome = authorize(account.email, com.halfblanket.cloudly.data.photos.PhotosPickerApi.SCOPE)
        if (outcome is AuthOutcome.Granted) storePhotos(accountId, outcome.token)
        return outcome
    }

    fun storePhotos(accountId: String, token: String) {
        photosMemory[accountId] = TokenVault.Entry(token, System.currentTimeMillis() + TOKEN_LIFETIME_MS)
    }

    override suspend fun photosToken(accountId: String): String {
        photosMemory[accountId]?.takeIf { it.isFresh() }?.let { return it.token }
        return when (val o = authorizePhotos(accountId)) {
            is AuthOutcome.Granted -> o.token
            is AuthOutcome.NeedsConsent -> throw PhotosConsentRequired(o.intent)
        }
    }

    override suspend fun invalidatePhotos(accountId: String, token: String) {
        photosMemory.remove(accountId)
        withContext(Dispatchers.IO) { runCatching { GoogleAuthUtil.clearToken(app, token) } }
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

package com.halfblanket.cloudly.ui.common

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.halfblanket.cloudly.auth.DriveScopes
import com.halfblanket.cloudly.auth.GoogleAuthManager
import com.halfblanket.cloudly.core.userMessage
import com.halfblanket.cloudly.data.db.AccountEntity
import com.halfblanket.cloudly.data.db.DriveAccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Drives the interactive Google flows: pick an account, approve Drive access, and confirm the result with
 * Drive before saving anything. Used for adding accounts, reconnecting expired ones and upgrading access.
 */
class AccountConnector internal constructor(
    private val activity: Activity,
    private val scope: CoroutineScope,
    private val busyState: MutableState<Boolean>,
    private val launch: (IntentSenderRequest) -> Unit,
    private val onConnected: (AccountEntity) -> Unit,
) {
    internal data class Pending(val email: String?, val name: String?, val photo: String?, val reason: Reason)
    internal enum class Reason { ADD, RECONNECT, UPGRADE }

    internal var pending: Pending? = null
    val busy: Boolean get() = busyState.value
    private val auth get() = activity.container.auth
    private val repo get() = activity.container.accountRepository

    fun addAccount(access: DriveAccess) = run {
        val picked = if (auth.canPickAccounts) auth.pickGoogleAccount(activity) ?: return@run else null
        request(Pending(picked?.email, picked?.displayName, picked?.photoUrl, Reason.ADD), access)
    }

    fun reconnect(account: AccountEntity) = run {
        request(Pending(account.email, null, account.photoUrl, Reason.RECONNECT), account.access)
    }

    fun upgradeToFull(account: AccountEntity) = run {
        request(Pending(account.email, null, account.photoUrl, Reason.UPGRADE), DriveAccess.FULL)
    }

    private fun run(block: suspend () -> Unit) {
        if (busyState.value) return
        scope.launch {
            busyState.value = true
            try {
                block()
            } catch (e: Throwable) {
                pending = null
                if (e is kotlinx.coroutines.CancellationException) throw e
                UiMessages.show(
                    if (e is com.google.android.gms.common.api.ApiException) auth.describeError(e) else e.userMessage(),
                    "OK",
                ) {}
            } finally {
                if (pending == null) busyState.value = false
            }
        }
    }

    private suspend fun request(p: Pending, access: DriveAccess) {
        pending = p
        when (val outcome = auth.authorize(p.email, DriveScopes.scopeFor(access))) {
            is GoogleAuthManager.AuthOutcome.Granted -> finish(outcome)
            is GoogleAuthManager.AuthOutcome.NeedsConsent ->
                launch(IntentSenderRequest.Builder(outcome.intent.intentSender).build())
        }
    }

    internal fun onConsentResult(ok: Boolean, data: android.content.Intent?) {
        val p = pending
        if (p == null) {
            busyState.value = false
            return
        }
        scope.launch {
            try {
                if (!ok) {
                    // A non-OK result may still carry Google's error; surface it instead of a generic message.
                    val reason = if (data == null) "Google Drive access wasn't approved" else try {
                        auth.outcomeFromIntent(data)
                        "Google Drive access wasn't approved"
                    } catch (e: Throwable) {
                        auth.describeError(e)
                    }
                    UiMessages.show(reason, "OK") {}
                } else {
                    when (val outcome = auth.outcomeFromIntent(data)) {
                        is GoogleAuthManager.AuthOutcome.Granted -> finish(outcome)
                        is GoogleAuthManager.AuthOutcome.NeedsConsent -> UiMessages.show("Google Drive access wasn't approved")
                    }
                }
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                UiMessages.show(
                    if (e is com.google.android.gms.common.api.ApiException) auth.describeError(e) else e.userMessage(),
                    "OK",
                ) {}
            } finally {
                pending = null
                busyState.value = false
            }
        }
    }

    private suspend fun finish(outcome: GoogleAuthManager.AuthOutcome.Granted) {
        val p = pending ?: return
        try {
            val account = repo.completeConnection(p.email, outcome.token, outcome.grantedScopes, p.name, p.photo)
            val msg = when (p.reason) {
                Reason.ADD -> "Connected ${account.email}"
                Reason.RECONNECT -> "Reconnected ${account.email}"
                Reason.UPGRADE -> if (account.access == DriveAccess.FULL) "Full Drive access enabled for ${account.email}"
                else "Google only granted access to files created with Cloudly"
            }
            if (account.access == DriveAccess.APP_FILES_ONLY && p.reason == Reason.ADD) {
                UiMessages.show("$msg · limited to files created with Cloudly")
            } else UiMessages.show(msg)
            onConnected(account)
        } finally {
            pending = null
        }
    }
}

@Composable
fun rememberAccountConnector(onConnected: (AccountEntity) -> Unit = {}): AccountConnector {
    val activity = LocalContext.current.findActivity()
    val scope = rememberCoroutineScope()
    val busy = remember { mutableStateOf(false) }
    val holder = remember { arrayOfNulls<AccountConnector>(1) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        holder[0]?.onConsentResult(result.resultCode == Activity.RESULT_OK, result.data)
    }
    val connector = remember(activity) {
        AccountConnector(activity, scope, busy, { launcher.launch(it) }, onConnected)
    }
    holder[0] = connector
    return connector
}

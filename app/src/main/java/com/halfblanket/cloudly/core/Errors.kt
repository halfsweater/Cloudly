package com.halfblanket.cloudly.core

import java.io.IOException

/** Errors surfaced from Cloudly's data layer. Each carries a message suitable for display. */
sealed class CloudlyException(message: String, cause: Throwable? = null) : IOException(message, cause) {

    /** The account needs the user to re-approve access (consent revoked or expired). */
    class ReauthRequired(val accountId: String, val email: String) :
        CloudlyException("$email needs to be reconnected")

    /** Drive returned a non-success HTTP status. */
    class Api(val code: Int, val reason: String?, message: String) : CloudlyException(message)

    /** The account only granted access to files created by Cloudly (drive.file). */
    class LimitedAccess(val email: String) :
        CloudlyException("$email only granted access to files created with Cloudly")

    class Offline(cause: Throwable? = null) : CloudlyException("You're offline. Check your connection.", cause)

    /** A safety check refused an operation (e.g. account mismatch). */
    class Safety(message: String) : CloudlyException(message)

    class Unsupported(message: String) : CloudlyException(message)
}

/** Turns any throwable into a short, human-readable message. */
fun Throwable.userMessage(): String = when (this) {
    is CloudlyException -> message ?: "Something went wrong"
    is java.net.UnknownHostException, is java.net.ConnectException ->
        "You're offline. Check your connection."
    is java.net.SocketTimeoutException -> "The connection timed out. Try again."
    is SecurityException -> "Permission denied: ${message ?: "access to the file was revoked"}"
    else -> message?.takeIf { it.isNotBlank() } ?: "Something went wrong"
}

package com.halfblanket.cloudly.auth

import com.halfblanket.cloudly.data.db.DriveAccess

/**
 * OAuth scopes. Cloudly asks for one Drive scope per account, chosen by the user:
 *
 * - [FULL] (`drive`) is required to browse, organise and transfer the files that already exist in a Drive.
 * - [APP_FILES] (`drive.file`) only reaches files created or opened by Cloudly. It does NOT expose the rest
 *   of the user's Drive, so in that mode Cloudly says so in the UI instead of showing an empty Drive as if
 *   it were the whole story.
 *
 * What was *granted* (not what was requested) decides the account's [DriveAccess]; Google lets users
 * decline scopes, so the result is always read back from the authorization response.
 */
object DriveScopes {
    const val FULL = "https://www.googleapis.com/auth/drive"
    const val APP_FILES = "https://www.googleapis.com/auth/drive.file"

    fun scopeFor(access: DriveAccess) = when (access) {
        DriveAccess.FULL -> FULL
        DriveAccess.APP_FILES_ONLY -> APP_FILES
    }

    fun accessFromGranted(granted: Collection<String>): DriveAccess? = when {
        FULL in granted -> DriveAccess.FULL
        APP_FILES in granted -> DriveAccess.APP_FILES_ONLY
        else -> null
    }
}

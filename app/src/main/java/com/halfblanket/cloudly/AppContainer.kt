package com.halfblanket.cloudly

import android.app.Application
import com.halfblanket.cloudly.auth.GoogleAuthManager
import com.halfblanket.cloudly.auth.TokenVault
import com.halfblanket.cloudly.data.db.CloudlyDatabase
import com.halfblanket.cloudly.data.drive.DriveApi
import com.halfblanket.cloudly.data.drive.DriveImageAuthInterceptor
import com.halfblanket.cloudly.data.drive.RetryInterceptor
import com.halfblanket.cloudly.data.prefs.SettingsRepository
import com.halfblanket.cloudly.data.repo.AccountRepository
import com.halfblanket.cloudly.data.repo.DriveRepository
import com.halfblanket.cloudly.data.repo.TransferRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Manual dependency container; one instance per process, owned by [CloudlyApp]. */
class AppContainer(val app: Application) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = false
        isLenient = true
    }

    val db: CloudlyDatabase by lazy { CloudlyDatabase.create(app) }
    val settings by lazy { SettingsRepository(app) }
    private val vault by lazy { TokenVault(app) }
    val auth by lazy { GoogleAuthManager(app, vault, db.accounts()) }

    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .addInterceptor(RetryInterceptor())
            .build()
    }

    /** OkHttp client for images: attaches the owning account's token to Drive thumbnail/content URLs. */
    val imageHttp: OkHttpClient by lazy { http.newBuilder().addInterceptor(DriveImageAuthInterceptor(auth)).build() }

    val driveApi by lazy { DriveApi(http, auth, json) }
    val driveRepository by lazy { DriveRepository(driveApi, db.listCache(), json) }
    val accountRepository by lazy {
        AccountRepository(db.accounts(), db.transfers(), db.listCache(), auth, driveApi, settings, appScope)
    }
    val transferRepository by lazy { TransferRepository(app, db.transfers(), driveApi, settings, json) }
}

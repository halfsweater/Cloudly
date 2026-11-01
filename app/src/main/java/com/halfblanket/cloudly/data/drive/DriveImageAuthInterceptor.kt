package com.halfblanket.cloudly.data.drive

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Image requests for Drive content carry an `X-Cloudly-Account` header naming the owning account. This
 * interceptor swaps it for that account's bearer token — and only ever sends tokens to Google hosts.
 * Thumbnail links (googleusercontent.com) are usually pre-signed, so they are tried without a token first.
 */
class DriveImageAuthInterceptor(private val tokens: AccessTokenSource) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val accountId = original.header(HEADER) ?: return chain.proceed(original)
        val bare = original.newBuilder().removeHeader(HEADER).build()
        val host = bare.url.host
        val isApi = host == "www.googleapis.com"
        val isContent = host.endsWith(".googleusercontent.com")
        if (!isApi && !isContent) return chain.proceed(bare)

        if (isContent) {
            val first = chain.proceed(bare)
            if (first.code != 401 && first.code != 403) return first
            first.close()
        }
        val token = runBlocking { tokens.token(accountId) }
        val resp = chain.proceed(bare.newBuilder().header("Authorization", "Bearer $token").build())
        if (resp.code != 401) return resp
        resp.close()
        runBlocking { tokens.invalidate(accountId, token) }
        val fresh = runBlocking { tokens.token(accountId) }
        return chain.proceed(bare.newBuilder().header("Authorization", "Bearer $fresh").build())
    }

    companion object {
        const val HEADER = "X-Cloudly-Account"
    }
}

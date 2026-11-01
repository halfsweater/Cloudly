package com.halfblanket.cloudly.data.drive

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * Retries transient Drive failures with exponential backoff and jitter, as Google recommends.
 *
 * - 429 and 403 rate-limit responses are retried for every method (the request was rejected, not applied).
 * - 5xx responses and network errors are retried only for idempotent methods, so a create or copy is
 *   never accidentally performed twice.
 * - Resumable upload chunks (requests carrying Content-Range) are excluded; the uploader resumes them itself.
 */
class RetryInterceptor(private val maxAttempts: Int = 4) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.header("Content-Range") != null) return chain.proceed(request)
        val idempotent = request.method in setOf("GET", "HEAD", "PUT", "DELETE", "PATCH")
        var attempt = 0
        while (true) {
            attempt++
            val response = try {
                chain.proceed(request)
            } catch (e: IOException) {
                if (!idempotent || attempt >= maxAttempts || chain.call().isCanceled()) throw e
                sleep(attempt, null)
                continue
            }
            val retry = when {
                response.code == 429 -> true
                response.code == 403 && isRateLimit(response) -> true
                response.code in 500..504 && idempotent -> true
                else -> false
            }
            if (!retry || attempt >= maxAttempts) return response
            val retryAfter = response.header("Retry-After")?.toLongOrNull()
            response.close()
            sleep(attempt, retryAfter)
        }
    }

    private fun isRateLimit(response: Response): Boolean {
        val peek = runCatching { response.peekBody(4096).string() }.getOrDefault("")
        return "rateLimitExceeded" in peek || "userRateLimitExceeded" in peek
    }

    private fun sleep(attempt: Int, retryAfterSeconds: Long?) {
        val base = retryAfterSeconds?.times(1000) ?: (500L shl (attempt - 1))
        Thread.sleep(base.coerceAtMost(16_000) + (0..400).random())
    }
}

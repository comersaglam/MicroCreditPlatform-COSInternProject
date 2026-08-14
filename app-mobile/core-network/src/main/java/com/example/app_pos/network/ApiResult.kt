package com.example.app_pos.network

import java.io.IOException

/**
 * The outcome of one network call. Errors are values here rather than thrown exceptions,
 * because the caller in :core-data has to make a decision about each one and a swallowed
 * `catch` is exactly how offline-first goes quietly wrong.
 *
 * The split between [NetworkError] and [ApiError] is not cosmetic — it IS the outbox's
 * retry rule:
 *  - [NetworkError]  the request never reached the server. Keep the queued entry, retry.
 *  - [ApiError] 4xx  the server understood and refused. Retrying cannot help; drop it.
 *  - [ApiError] 5xx  the server broke. Retry with backoff.
 *  - [UnexpectedError] the answer was unusable (malformed body). Not retryable.
 *
 * Get that wrong and you end up with either a queue that never drains or one that retries
 * a permanently-rejected write forever.
 */
sealed interface ApiResult<out T> {

    data class Success<T>(val data: T) : ApiResult<T>

    /** The server answered with a status outside 2xx. [code] comes from the error body. */
    data class ApiError(
        val status: Int,
        val code: String,
        val message: String
    ) : ApiResult<Nothing> {
        /** A refusal the client caused; replaying the same request will fail again. */
        val isClientError: Boolean get() = status in 400..499

        /** The server's own failure; the same request may well succeed later. */
        val isServerError: Boolean get() = status in 500..599
    }

    /** No answer reached us: offline, timeout, DNS, TLS. Always worth retrying. */
    data class NetworkError(val cause: IOException) : ApiResult<Nothing>

    /** We got an answer we could not use — malformed JSON, a missing required field. */
    data class UnexpectedError(val cause: Throwable) : ApiResult<Nothing>
}

/** The value on success, or null on any failure — for callers that just want the data. */
fun <T> ApiResult<T>.getOrNull(): T? = (this as? ApiResult.Success)?.data

/**
 * Whether retrying this exact request could plausibly succeed later. The outbox drains
 * on this answer, so it is defined once here rather than re-derived at each call site.
 */
fun ApiResult<*>.isRetryable(): Boolean = when (this) {
    is ApiResult.NetworkError -> true
    is ApiResult.ApiError -> isServerError
    is ApiResult.Success, is ApiResult.UnexpectedError -> false
}

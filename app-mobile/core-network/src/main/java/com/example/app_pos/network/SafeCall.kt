package com.example.app_pos.network

import com.example.app_pos.network.dto.ErrorEnvelopeDto
import com.squareup.moshi.Moshi
import retrofit2.HttpException
import java.io.IOException

/**
 * Turns a Retrofit call into an [ApiResult], so every failure mode is handled in one
 * place instead of at each call site.
 *
 * The API interfaces return the DTO directly (not `Response<T>`), which means Retrofit
 * throws [HttpException] on a non-2xx; catching it here is what makes that choice safe.
 */
suspend fun <T> apiCall(moshi: Moshi, block: suspend () -> T): ApiResult<T> =
    try {
        ApiResult.Success(block())
    } catch (e: HttpException) {
        // Parse ONCE: the error body is a one-shot stream, so a second read would come
        // back empty and silently lose the server's message.
        val body = parseError(moshi, e)?.error
        ApiResult.ApiError(
            status = e.code(),
            code = body?.code ?: "http_${e.code()}",
            message = body?.message ?: (e.message() ?: "HTTP ${e.code()}")
        )
    } catch (e: IOException) {
        // Never reached the server: offline, timeout, DNS, TLS. Retryable.
        ApiResult.NetworkError(e)
    } catch (e: Throwable) {
        // Reached the server but the answer was unusable (malformed JSON, missing field).
        ApiResult.UnexpectedError(e)
    }

/**
 * Best-effort read of the shared `{error:{code,message}}` envelope.
 *
 * Deliberately forgiving: anything between the app and the backend — a proxy 502, a
 * captive portal, a gateway timeout page — answers with HTML rather than the envelope,
 * and a parse failure there must not mask the real status code. Returns null so the
 * caller falls back to `http_<status>`.
 */
private fun parseError(moshi: Moshi, e: HttpException): ErrorEnvelopeDto? =
    runCatching {
        e.response()?.errorBody()?.string()
            ?.takeIf { it.isNotBlank() }
            ?.let { moshi.adapter(ErrorEnvelopeDto::class.java).fromJson(it) }
    }.getOrNull()

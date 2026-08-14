package com.example.app_pos.network.auth

import com.example.app_pos.network.api.AuthApi
import com.example.app_pos.network.dto.RefreshDto
import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

/**
 * Renews an expired session when the server answers 401, and retries the failed request
 * once with the new token — so the shopkeeper is not sent back to the sign-in screen
 * mid-sale (the friction the auth design exists to avoid).
 *
 * An Authenticator rather than an Interceptor on purpose: OkHttp invokes this only on a
 * 401 and threads the failed exchange through `priorResponse`, so bailing out after one
 * attempt is a property of the framework instead of a hand-rolled retry counter that is
 * easy to get subtly wrong.
 *
 * [authApiProvider] is deferred because AuthApi is built on the token-free client, which
 * is constructed alongside this object; taking it lazily breaks the construction cycle.
 */
class TokenAuthenticator(
    private val tokens: TokenStore,
    private val authApiProvider: () -> AuthApi
) : Authenticator {

    /**
     * Returning null means "give up" — OkHttp then surfaces the 401 to the caller, which
     * clears the session and drops the app to the login gate.
     */
    @Synchronized
    override fun authenticate(route: Route?, response: Response): Request? {
        // Already retried once: a second 401 means refreshing is not the answer.
        if (response.priorResponse != null) return null

        val refreshToken = tokens.refreshTokenOrNull() ?: return null

        // Another thread may have refreshed while this one waited on the lock. If the
        // token now differs from the one that failed, just retry with it.
        tokens.accessTokenOrNull()
            ?.takeIf { it != response.request.bearerToken() }
            ?.let { return response.request.withBearer(it) }

        // Blocking is correct here: authenticate() runs on an OkHttp background thread,
        // never the main thread, and OkHttp expects a synchronous answer.
        val renewed = runBlocking {
            runCatching { authApiProvider().refresh(RefreshDto(refreshToken)) }.getOrNull()
        } ?: return null

        runBlocking { tokens.save(renewed) }
        return response.request.withBearer(renewed.token)
    }

    private fun Request.bearerToken(): String? =
        header(HEADER)?.removePrefix(BEARER_PREFIX)

    private fun Request.withBearer(token: String): Request =
        newBuilder().header(HEADER, "$BEARER_PREFIX$token").build()

    private companion object {
        const val HEADER = "Authorization"
        const val BEARER_PREFIX = "Bearer "
    }
}

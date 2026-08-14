package com.example.app_pos.network.auth

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Attaches `Authorization: Bearer <token>` to every outgoing request, so no call site has
 * to remember it.
 *
 * Installed only on the authenticated client; the sign-in endpoints run on the bare one.
 * A request that already carries the header is left alone, and a signed-out client simply
 * sends none — letting the server answer 401 rather than guessing locally.
 */
class AuthInterceptor(private val tokens: TokenStore) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.header(HEADER) != null) return chain.proceed(request)

        val token = tokens.accessTokenOrNull() ?: return chain.proceed(request)
        return chain.proceed(
            request.newBuilder().header(HEADER, "Bearer $token").build()
        )
    }

    private companion object {
        const val HEADER = "Authorization"
    }
}

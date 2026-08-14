package com.example.app_pos.network.auth

import com.example.app_pos.network.dto.SessionDto
import kotlinx.coroutines.flow.Flow

/** A persisted session: the tokens plus who they belong to and when they expire. */
data class StoredSession(
    val accessToken: String,
    val refreshToken: String?,
    val userId: String,
    /** Epoch millis; 0 when the server sent an expiry this client could not parse. */
    val expiresAtMillis: Long
) {
    /**
     * An unparsable or absent expiry is treated as valid rather than expired: the server
     * is the real authority, and answering 401 is how it says otherwise. Failing closed
     * here would sign the shopkeeper out over a formatting difference.
     */
    fun isValid(nowMillis: Long): Boolean = expiresAtMillis == 0L || expiresAtMillis > nowMillis
}

/**
 * Where the session lives across process restarts.
 *
 * The reads that back the login gate are SYNCHRONOUS by design. MainActivity picks the
 * navigation start destination in onCreate, before the graph exists, so it cannot await
 * anything; serving those reads from an in-memory cache primed once at startup keeps a
 * disk read off the main thread on every call, which would eventually ANR on a slow POS
 * terminal.
 *
 * Kept as an interface so encrypted storage can replace the implementation later without
 * touching a caller (see the note in docs/architecture-pos.md §5).
 */
interface TokenStore {

    /** Loads persisted tokens into the cache. Call once, from Application.onCreate. */
    suspend fun prime()

    /** The bearer token, or null when signed out. Synchronous: the interceptor needs it. */
    fun accessTokenOrNull(): String?

    /** The long-lived token used to renew a session silently, if the server issued one. */
    fun refreshTokenOrNull(): String?

    /** Who is signed in — backs currentSellerId(). */
    fun currentUserIdOrNull(): String?

    /** Whether a usable, unexpired session exists — backs isSessionValid(). */
    fun isValid(): Boolean

    /** Emits on every sign-in, refresh and sign-out, so reads can recombine reactively. */
    fun observeSession(): Flow<StoredSession?>

    /** Persists a session — from sign-in or from a refresh; both carry the full user. */
    suspend fun save(session: SessionDto)

    suspend fun clear()
}

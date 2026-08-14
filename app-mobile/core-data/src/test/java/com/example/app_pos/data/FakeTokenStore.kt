package com.example.app_pos.data

import com.example.app_pos.network.auth.StoredSession
import com.example.app_pos.network.auth.TokenStore
import com.example.app_pos.network.dto.SessionDto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * A signed-in session, held in memory.
 *
 * The write-path tests are about what gets stored, not about how the session is persisted,
 * so this keeps DataStore (and a Context) out of a plain JVM test.
 */
class FakeTokenStore(private val userId: String = "u1") : TokenStore {
    override suspend fun prime() = Unit
    override fun accessTokenOrNull(): String? = "token"
    override fun refreshTokenOrNull(): String? = null
    override fun currentUserIdOrNull(): String? = userId
    override fun isValid(): Boolean = true
    override fun observeSession(): Flow<StoredSession?> = flowOf(
        StoredSession(
            accessToken = "token",
            refreshToken = null,
            userId = userId,
            // 0 means "no expiry" to StoredSession.isValid, which keeps the test from
            // depending on the clock.
            expiresAtMillis = 0L
        )
    )
    override suspend fun save(session: SessionDto) = Unit
    override suspend fun clear() = Unit
}

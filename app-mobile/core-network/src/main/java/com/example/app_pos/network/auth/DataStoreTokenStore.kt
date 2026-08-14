package com.example.app_pos.network.auth

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.app_pos.network.dto.SessionDto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

private val Context.sessionDataStore by preferencesDataStore(name = "session")

/**
 * DataStore-backed [TokenStore] with an in-memory cache in front of it.
 *
 * The cache is what makes the synchronous reads honest: [prime] performs the single disk
 * read at startup, and everything after is served from memory, so the login gate never
 * blocks the main thread. Writes go to disk and refresh the cache together.
 *
 * Storage is app-private and unencrypted for now. On a shop-floor POS the sandbox plus
 * file-based encryption already cover the realistic threat, and the alternative the docs
 * name (EncryptedSharedPreferences) is deprecated — so this stays behind the interface
 * until a Keystore-backed implementation is worth adding.
 */
class DataStoreTokenStore(private val context: Context) : TokenStore {

    private val cached = MutableStateFlow<StoredSession?>(null)

    override suspend fun prime() {
        val prefs = context.sessionDataStore.data.first()
        cached.value = prefs.toSession()
    }

    override fun accessTokenOrNull(): String? = validSession()?.accessToken

    // Deliberately NOT gated on validity: an expired access token is exactly when a
    // refresh token has to still be reachable.
    override fun refreshTokenOrNull(): String? = cached.value?.refreshToken

    override fun currentUserIdOrNull(): String? = validSession()?.userId

    override fun isValid(): Boolean = validSession() != null

    override fun observeSession(): Flow<StoredSession?> = cached.asStateFlow()

    override suspend fun save(session: SessionDto) {
        val stored = StoredSession(
            accessToken = session.token,
            refreshToken = session.refreshToken,
            userId = session.user.userId,
            expiresAtMillis = parseExpiry(session.expiresAt)
        )
        context.sessionDataStore.edit { prefs ->
            prefs[KEY_ACCESS_TOKEN] = stored.accessToken
            stored.refreshToken?.let { prefs[KEY_REFRESH_TOKEN] = it } ?: prefs.remove(KEY_REFRESH_TOKEN)
            prefs[KEY_USER_ID] = stored.userId
            prefs[KEY_EXPIRES_AT] = stored.expiresAtMillis
        }
        cached.value = stored
    }

    override suspend fun clear() {
        context.sessionDataStore.edit { it.clear() }
        cached.value = null
    }

    private fun validSession(): StoredSession? =
        cached.value?.takeIf { it.isValid(System.currentTimeMillis()) }

    private fun Preferences.toSession(): StoredSession? {
        val token = this[KEY_ACCESS_TOKEN] ?: return null
        val userId = this[KEY_USER_ID] ?: return null
        return StoredSession(
            accessToken = token,
            refreshToken = this[KEY_REFRESH_TOKEN],
            userId = userId,
            expiresAtMillis = this[KEY_EXPIRES_AT] ?: 0L
        )
    }

    /**
     * Parses the contract's ISO-8601 UTC expiry. Returns 0 when it cannot be read, which
     * [StoredSession.isValid] treats as "let the server decide" rather than as expired.
     */
    private fun parseExpiry(raw: String): Long =
        try {
            SimpleDateFormat(ISO_PATTERN, Locale.ROOT)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
                .parse(raw)?.time ?: 0L
        } catch (_: ParseException) {
            0L
        }

    private companion object {
        const val ISO_PATTERN = "yyyy-MM-dd'T'HH:mm:ss'Z'"

        val KEY_ACCESS_TOKEN = stringPreferencesKey("access_token")
        val KEY_REFRESH_TOKEN = stringPreferencesKey("refresh_token")
        val KEY_USER_ID = stringPreferencesKey("user_id")
        val KEY_EXPIRES_AT = longPreferencesKey("expires_at")
    }
}

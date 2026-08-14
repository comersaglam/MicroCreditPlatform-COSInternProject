package com.example.app_pos.network.auth

import com.example.app_pos.network.api.AuthApi
import com.example.app_pos.network.api.UserApi
import com.example.app_pos.network.dto.SessionDto
import com.squareup.moshi.Moshi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

/**
 * Exercises the auth stack over a real socket: the bearer header going out, and the
 * silent renewal when the server answers 401.
 *
 * This is the part of the module most likely to be wrong in a way that compiles — a
 * missing header, a refresh that loops forever, a retry that never happens — so it is
 * tested against actual traffic rather than by reading the code.
 */
class AuthFlowTest {

    private lateinit var server: MockWebServer
    private lateinit var tokens: FakeTokenStore
    private val moshi = Moshi.Builder().build()

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        tokens = FakeTokenStore()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `every request carries the bearer token`() = runTest {
        tokens.session = storedSession(access = "tok-1")
        server.enqueue(jsonResponse(USER_JSON))

        buildUserApi().me()

        assertEquals("Bearer tok-1", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `a signed-out client sends no Authorization header`() = runTest {
        tokens.session = null
        server.enqueue(jsonResponse(USER_JSON))

        buildUserApi().me()

        // Let the server answer 401 rather than guessing locally that we are signed out.
        assertNull(server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `a 401 refreshes the session and retries the request once`() = runTest {
        tokens.session = storedSession(access = "expired", refresh = "refresh-1")

        server.enqueue(MockResponse().setResponseCode(401))       // the original call
        server.enqueue(jsonResponse(SESSION_JSON))                // POST /auth/refresh
        server.enqueue(jsonResponse(USER_JSON))                   // the retry

        val user = buildUserApi().me()

        assertEquals("u_owner", user.userId)
        assertEquals(3, server.requestCount)

        assertEquals("Bearer expired", server.takeRequest().getHeader("Authorization"))
        assertEquals("/auth/refresh", server.takeRequest().path)
        // The retry must carry the NEW token, not the stale one.
        assertEquals("Bearer tok-fresh", server.takeRequest().getHeader("Authorization"))

        // And the renewed session is persisted, so the next cold start stays signed in.
        assertEquals("tok-fresh", tokens.accessTokenOrNull())
    }

    @Test
    fun `without a refresh token a 401 is surfaced instead of looping`() = runTest {
        tokens.session = storedSession(access = "expired", refresh = null)
        server.enqueue(MockResponse().setResponseCode(401))

        runCatching { buildUserApi().me() }

        // Exactly one attempt: nothing to refresh with, so the caller sees the 401 and
        // drops to the sign-in gate.
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a failing refresh gives up rather than retrying forever`() = runTest {
        tokens.session = storedSession(access = "expired", refresh = "refresh-1")

        server.enqueue(MockResponse().setResponseCode(401))   // original
        server.enqueue(MockResponse().setResponseCode(401))   // refresh itself rejected

        runCatching { buildUserApi().me() }

        // Original + one refresh attempt, then stop. A loop here would hammer the server.
        assertEquals(2, server.requestCount)
    }

    // --- helpers -------------------------------------------------------------

    /** Mirrors NetworkModule: AuthApi on a bare client, everything else authenticated. */
    private fun buildUserApi(): UserApi {
        val bare = OkHttpClient.Builder().build()
        val authApi = retrofit(bare).create(AuthApi::class.java)
        val authed = bare.newBuilder()
            .addInterceptor(AuthInterceptor(tokens))
            .authenticator(TokenAuthenticator(tokens) { authApi })
            .build()
        return retrofit(authed).create(UserApi::class.java)
    }

    private fun retrofit(client: OkHttpClient): Retrofit =
        Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()

    private fun storedSession(access: String, refresh: String? = null) = StoredSession(
        accessToken = access,
        refreshToken = refresh,
        userId = "u_owner",
        expiresAtMillis = 0L // 0 = let the server decide; keeps the token usable here
    )

    private fun jsonResponse(body: String) =
        MockResponse().setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(body)

    /** An in-memory TokenStore; the real one needs a Context for DataStore. */
    private class FakeTokenStore : TokenStore {
        var session: StoredSession? = null
        private val flow = MutableStateFlow<StoredSession?>(null)

        override suspend fun prime() = Unit
        override fun accessTokenOrNull(): String? = session?.accessToken
        override fun refreshTokenOrNull(): String? = session?.refreshToken
        override fun currentUserIdOrNull(): String? = session?.userId
        override fun isValid(): Boolean = session != null
        override fun observeSession(): Flow<StoredSession?> = flow

        override suspend fun save(session: SessionDto) {
            this.session = StoredSession(
                session.token, session.refreshToken, session.user.userId, 0L
            )
            flow.value = this.session
        }

        override suspend fun clear() {
            session = null
            flow.value = null
        }
    }

    private companion object {
        const val USER_JSON = """
            {"user_id":"u_owner","phone":"+905554443322","display_name":"Ahmet Demirtaş",
             "is_buyer":true,"is_seller":true,"email":null,
             "seller_info":{"shop_name":"Ahmet Bakkal","shop_phone":null},
             "created_at":"2026-07-01T09:00:00Z"}
        """

        const val SESSION_JSON = """
            {"token":"tok-fresh","refresh_token":"refresh-2",
             "expires_at":"2026-08-13T09:00:00Z",
             "user":{"user_id":"u_owner","phone":"+905554443322",
               "display_name":"Ahmet Demirtaş","is_buyer":true,"is_seller":true,"email":null,
               "seller_info":{"shop_name":"Ahmet Bakkal","shop_phone":null},
               "created_at":"2026-07-01T09:00:00Z"}}
        """
    }
}

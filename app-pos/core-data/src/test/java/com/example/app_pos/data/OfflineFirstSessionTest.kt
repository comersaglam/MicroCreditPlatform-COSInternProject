package com.example.app_pos.data

import com.example.app_pos.network.api.ApprovalApi
import com.example.app_pos.network.api.AuthApi
import com.example.app_pos.network.api.BuyerApi
import com.example.app_pos.network.api.CustomerApi
import com.example.app_pos.network.api.LedgerApi
import com.example.app_pos.network.api.SyncApi
import com.example.app_pos.network.api.UserApi
import com.example.app_pos.data.remote.RemoteDataSource
import com.example.app_pos.data.sync.SyncEngine
import com.squareup.moshi.Moshi
import com.example.app_pos.model.SignInResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The session gate, which is what phase 7 actually changes.
 *
 * Before this phase the session lived in a MutableStateFlow inside the Room repository, so
 * it died with the process; now it comes from the persisted TokenStore. These tests pin the
 * behaviour the login gate depends on — MainActivity picks its start destination from
 * isSessionValid() synchronously, before the nav graph exists, so a regression here shows up
 * as "the app asks for a login it should not" (or worse, does not ask when it should).
 *
 * Sign-in DOES reach the network now (the server decides whether an account exists), so a
 * MockWebServer stands in for the backend. Everything else here stays offline.
 */
class OfflineFirstSessionTest {

    private val tokens = FakeTokenStore()
    private val server = MockWebServer()

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun repo(vararg users: com.example.app_pos.model.User): OfflineFirstRepository {
        val local = FakeLocalSource(users.toList())
        val remote = remoteAgainstServer()
        val moshi = Moshi.Builder().build()
        return OfflineFirstRepository(
            local = local,
            remote = remote,
            syncEngine = SyncEngine(local, remote, moshi),
            tokens = tokens,
            moshi = moshi
        )
    }

    /**
     * Queues the session the real backend would answer verify with. Two responses because
     * OfflineFirstRepository re-registers nothing here, but logout() also calls out.
     */
    private fun enqueueSession(userId: String = "u_owner", expiresAt: String = farFuture()) {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {"token":"t-access","refresh_token":"t-refresh","expires_at":"$expiresAt",
                 "user":{"user_id":"$userId","phone":"+905554443322","display_name":"Ahmet",
                         "is_buyer":true,"is_seller":true,"email":null,"seller_info":null,
                         "created_at":"2026-07-01T06:00:00Z"}}
                """.trimIndent()
            )
        )
    }

    private fun enqueueError(code: Int, errorCode: String) {
        server.enqueue(
            MockResponse().setResponseCode(code)
                .setBody("""{"error":{"code":"$errorCode","message":"nope"}}""")
        )
    }

    /** Well past any TTL these tests advance the clock by. */
    private fun farFuture(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }
        .format(Date(tokens.now + 7L * 24 * 60 * 60 * 1000))

    /** Signs in through the stubbed server, the way the app now does. */
    private suspend fun OfflineFirstRepository.signInOk(): Boolean {
        enqueueSession()
        return signIn("05554443322", "123456") == SignInResult.Success
    }

    @Test
    fun `no session means the gate is closed`() {
        val repo = repo(testUser())

        assertFalse(repo.isSessionValid())
        assertNull(repo.currentSellerId())
    }

    @Test
    fun `signing in opens the gate and names the seller`() = runTest {
        val repo = repo(testUser())

        assertTrue(repo.signInOk())

        assertTrue(repo.isSessionValid())
        assertEquals("u_owner", repo.currentSellerId())
    }

    @Test
    /**
     * The server owns this answer, not a local lookup: verify does not auto-register, so an
     * unknown number comes back as a branch the caller acts on rather than a failure.
     */
    fun `an unknown number is told to register instead`() = runTest {
        val repo = repo(testUser())
        enqueueError(404, "user_not_found")

        assertEquals(SignInResult.NeedsRegister, repo.signIn("05550001122", "123456"))

        assertFalse(repo.isSessionValid())
    }

    @Test
    fun `a wrong code does not open the gate`() = runTest {
        val repo = repo(testUser())
        enqueueError(401, "invalid_code")

        assertEquals(SignInResult.InvalidCode, repo.signIn("05554443322", "000000"))

        assertFalse(repo.isSessionValid())
    }

    /**
     * The reopen-the-app case, and the whole point of the phase: the store keeps the
     * session, so a fresh repository over the same store is already signed in. Before,
     * this was a new MutableStateFlow(null) and the merchant saw the login screen again.
     */
    @Test
    fun `a session outlives the repository instance`() = runTest {
        repo(testUser()).signInOk()

        val afterRestart = repo(testUser())

        assertTrue(afterRestart.isSessionValid())
        assertEquals("u_owner", afterRestart.currentSellerId())
    }

    @Test
    fun `an expired session closes the gate`() = runTest {
        val repo = repo(testUser())
        repo.signInOk()
        assertTrue(repo.isSessionValid())

        // Past the 7-day TTL the login minted.
        tokens.now += 8L * 24 * 60 * 60 * 1000

        assertFalse(repo.isSessionValid())
        assertNull(repo.currentSellerId())
    }

    @Test
    fun `signing out clears the stored session`() = runTest {
        val repo = repo(testUser())
        repo.signInOk()

        repo.logout()

        assertFalse(repo.isSessionValid())
        assertNull(repo.currentSellerId())
    }

    @Test
    fun `the current user resolves from the persisted session`() = runTest {
        val repo = repo(testUser())

        assertNull("signed out means no user", repo.observeCurrentUser().first())

        repo.signInOk()

        val user = repo.observeCurrentUser().first()
        assertNotNull(user)
        assertEquals("u_owner", user!!.userId)
    }

    /** Sign-out has to reach the screens, not just the gate — the profile listens on this. */
    @Test
    fun `signing out emits a null user`() = runTest {
        val repo = repo(testUser())
        repo.signInOk()
        assertNotNull(repo.observeCurrentUser().first())

        repo.logout()

        assertNull(repo.observeCurrentUser().first())
    }

    /**
     * A stored session whose user is gone (the database was wiped, the row was removed)
     * must not resolve to some other account. Null is the safe answer.
     */
    @Test
    fun `a session for an unknown user resolves to null`() = runTest {
        val repo = repo(testUser())
        repo.signInOk()

        val emptyDb = repo()  // same token store, no users

        assertNull(emptyDb.observeCurrentUser().first())
    }

    /**
     * A RemoteDataSource pointed at the mock server. Only the auth calls are exercised;
     * every other path in these tests stays on the local source.
     */
    private fun remoteAgainstServer(): RemoteDataSource {
        val moshi = Moshi.Builder().build()
        val retrofit = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
        return RemoteDataSource(
            authApi = retrofit.create(AuthApi::class.java),
            userApi = retrofit.create(UserApi::class.java),
            customerApi = retrofit.create(CustomerApi::class.java),
            ledgerApi = retrofit.create(LedgerApi::class.java),
            buyerApi = retrofit.create(BuyerApi::class.java),
            approvalApi = retrofit.create(ApprovalApi::class.java),
            syncApi = retrofit.create(SyncApi::class.java),
            moshi = moshi
        )
    }
}

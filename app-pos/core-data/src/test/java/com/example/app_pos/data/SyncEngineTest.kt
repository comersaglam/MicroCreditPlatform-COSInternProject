package com.example.app_pos.data

import com.example.app_pos.data.db.entity.OutboxEntity
import com.example.app_pos.data.remote.RemoteDataSource
import com.example.app_pos.data.sync.SyncEngine
import com.example.app_pos.network.api.ApprovalApi
import com.example.app_pos.network.api.PgwJobApi
import com.example.app_pos.network.api.AuthApi
import com.example.app_pos.network.api.BuyerApi
import com.example.app_pos.network.api.CustomerApi
import com.example.app_pos.network.api.FxApi
import com.example.app_pos.network.api.LedgerApi
import com.example.app_pos.network.api.SyncApi
import com.example.app_pos.network.api.UserApi
import com.squareup.moshi.Moshi
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

/**
 * The outbox drain, driven against a real HTTP server.
 *
 * MockWebServer rather than a stubbed RemoteDataSource on purpose: the rules being checked
 * here (what counts as retryable, what the Idempotency-Key carries, what a dropped socket
 * does) only mean something if the real Retrofit/OkHttp stack produces them. A hand-written
 * stub would be asserting on this test's own assumptions.
 */
class SyncEngineTest {

    private lateinit var server: MockWebServer
    private lateinit var local: FakeLocalSource
    private lateinit var engine: SyncEngine

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()

        val moshi = Moshi.Builder().build()
        val retrofit = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
        val remote = RemoteDataSource(
            authApi = retrofit.create(AuthApi::class.java),
            userApi = retrofit.create(UserApi::class.java),
            customerApi = retrofit.create(CustomerApi::class.java),
            ledgerApi = retrofit.create(LedgerApi::class.java),
            buyerApi = retrofit.create(BuyerApi::class.java),
            approvalApi = retrofit.create(ApprovalApi::class.java),
            pgwJobApi = retrofit.create(PgwJobApi::class.java),
            syncApi = retrofit.create(SyncApi::class.java),
            fxApi = retrofit.create(FxApi::class.java),
            moshi = moshi
        )
        local = FakeLocalSource()
        engine = SyncEngine(local, remote, moshi)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `an empty queue is a no-op`() = runTest {
        val outcome = engine.drainOutbox()

        assertEquals(0, outcome.attempted)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `an accepted entry leaves the queue`() = runTest {
        local.enqueueRaw(queued("t1"))
        server.enqueue(created("t1"))

        val outcome = engine.drainOutbox()

        assertEquals(1, outcome.sent)
        assertTrue("the queue should be empty", local.pendingOutbox().isEmpty())
    }

    /**
     * The idempotency contract: the key is the entry's own transaction id, so a resend after
     * a lost response replays the original write instead of booking a second veresiye.
     */
    @Test
    fun `the send carries the transaction id as the idempotency key`() = runTest {
        local.enqueueRaw(queued("t1"))
        server.enqueue(created("t1"))

        engine.drainOutbox()

        val request = server.takeRequest()
        assertEquals("/transactions", request.path)
        assertEquals("t1", request.getHeader("Idempotency-Key"))
        assertTrue(request.body.readUtf8().contains("\"transaction_id\":\"t1\""))
    }

    @Test
    fun `entries are sent oldest first`() = runTest {
        local.enqueueRaw(queued("t1", createdAt = "2026-08-01T10:00:00Z"))
        local.enqueueRaw(queued("t2", createdAt = "2026-08-02T10:00:00Z"))
        server.enqueue(created("t1"))
        server.enqueue(created("t2"))

        engine.drainOutbox()

        assertEquals("t1", server.takeRequest().getHeader("Idempotency-Key"))
        assertEquals("t2", server.takeRequest().getHeader("Idempotency-Key"))
    }

    /**
     * Offline is the case the outbox exists for: the entry must survive, and the run must
     * stop rather than burn through the rest of the queue against a dead connection.
     */
    @Test
    fun `a network failure keeps the entry and stops the run`() = runTest {
        local.enqueueRaw(queued("t1"))
        local.enqueueRaw(queued("t2"))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        val outcome = engine.drainOutbox()

        assertEquals(0, outcome.sent)
        assertEquals(1, outcome.retryable)
        assertEquals("both entries must survive", 2, local.pendingOutbox().size)
        assertEquals("the second entry must not be attempted", 1, server.requestCount)
        assertEquals(1, local.retryCounts["t1"])
    }

    /** A server-side fault is the server's problem, not the entry's: keep it, try later. */
    @Test
    fun `a 500 keeps the entry`() = runTest {
        local.enqueueRaw(queued("t1"))
        server.enqueue(MockResponse().setResponseCode(500))

        val outcome = engine.drainOutbox()

        assertEquals(1, outcome.retryable)
        assertEquals(1, local.pendingOutbox().size)
    }

    /**
     * The opposite rule: the server understood and refused. Retrying replays the same
     * rejection forever, so the entry stops consuming the queue. It stays in the local
     * ledger — that is append-only and the merchant already saw it.
     */
    @Test
    fun `a 400 drops the entry`() = runTest {
        local.enqueueRaw(queued("t1"))
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"code":"bad","message":"no"}}"""))

        val outcome = engine.drainOutbox()

        assertEquals(1, outcome.dropped)
        assertTrue(local.pendingOutbox().isEmpty())
    }

    /** A conflict means the server already holds a DIFFERENT body under this key — a bug on
     *  our side, not a transient fault. Retrying cannot resolve it. */
    @Test
    fun `a 409 drops the entry`() = runTest {
        local.enqueueRaw(queued("t1"))
        server.enqueue(MockResponse().setResponseCode(409))

        assertEquals(1, engine.drainOutbox().dropped)
        assertTrue(local.pendingOutbox().isEmpty())
    }

    /**
     * A payload this build cannot parse can never be sent. Keeping it would wedge the queue
     * head permanently, so it is dropped without ever reaching the network.
     */
    @Test
    fun `an unparseable payload is dropped without a request`() = runTest {
        local.enqueueRaw(queued("t1").copy(payload = "}{ not json"))

        val outcome = engine.drainOutbox()

        assertEquals(1, outcome.dropped)
        assertEquals(0, server.requestCount)
        assertTrue(local.pendingOutbox().isEmpty())
    }

    /** After a good entry, a bad one still only costs its own row. */
    @Test
    fun `a drop does not stop the entries behind it`() = runTest {
        local.enqueueRaw(queued("t1", createdAt = "2026-08-01T10:00:00Z"))
        local.enqueueRaw(queued("t2", createdAt = "2026-08-02T10:00:00Z"))
        server.enqueue(MockResponse().setResponseCode(400))
        server.enqueue(created("t2"))

        val outcome = engine.drainOutbox()

        assertEquals(1, outcome.dropped)
        assertEquals(1, outcome.sent)
        assertTrue(local.pendingOutbox().isEmpty())
    }

    // --- helpers -------------------------------------------------------------

    private fun queued(id: String, createdAt: String = "2026-08-01T10:00:00Z") = OutboxEntity(
        id = id,
        transactionId = id,
        payload = """
            {"transaction_id":"$id","customer_id":"c1","amount_minor":4000,
             "type":"DEBT","description":"veresiye"}
        """.trimIndent(),
        createdAt = createdAt,
        retryCount = 0
    )

    /** The server's 201, shaped like the contract's TransactionDto. */
    private fun created(id: String) = MockResponse()
        .setResponseCode(201)
        .setBody(
            """
            {"transaction_id":"$id","seller_id":"u_owner","customer_id":"c1",
             "amount_minor":4000,"type":"DEBT","description":"veresiye",
             "created_at":"2026-08-01T10:00:00Z"}
            """.trimIndent()
        )
}

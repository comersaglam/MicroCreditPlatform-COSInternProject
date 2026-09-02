package com.example.app_pos.data

import com.example.app_pos.data.remote.RemoteDataSource
import com.example.app_pos.data.sync.PullEngine
import com.example.app_pos.data.sync.SyncEngine
import com.example.app_pos.model.ApprovalOutcome
import com.example.app_pos.model.ClaimStatus
import com.example.app_pos.model.Customer
import com.example.app_pos.model.DecisionOutcome
import com.example.app_pos.model.TransactionType
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
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

/**
 * The write path through the approval gate, driven against a real (local) server.
 *
 * Every case here is a bug found on a device, so each test is a regression guard rather
 * than a description of the design:
 *
 *  - approving booked the amount TWICE (a 75 TL approval landed twice in the ledger)
 *  - a payment reported success while nothing had been sent
 *  - an entry was written straight to the ledger with no approval at all
 *
 * MockWebServer rather than a stubbed RemoteDataSource, because two of these depend on how
 * the SERVER's two response shapes are told apart — a hand-stubbed remote would just repeat
 * whatever assumption the production code makes.
 */
class ApprovalWritePathTest {

    private lateinit var server: MockWebServer
    private lateinit var remote: RemoteDataSource
    private val moshi = Moshi.Builder().build()

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        val retrofit = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(OkHttpClient.Builder().build())
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
        remote = RemoteDataSource(
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
    }

    @After
    fun tearDown() = server.shutdown()

    private fun repository(local: FakeLocalSource) = OfflineFirstRepository(
        local = local,
        remote = remote,
        syncEngine = SyncEngine(local, remote, moshi),
        pullEngine = PullEngine(local, remote),
        tokens = FakeTokenStore(),
        moshi = moshi
    )

    /**
     * THE regression that matters: approving must add exactly ONE ledger entry.
     *
     * The bug wrote two — the server's transaction, then a second one minted locally by
     * `local.approvePending`. They carried different ids, so the ledger's insert-IGNORE
     * could not collapse them and the customer's balance was double-counted.
     */
    @Test
    fun `approving books the entry exactly once`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {"transaction_id":"t-1","seller_id":"u_market","customer_id":"o1",
                 "amount_minor":7500,"type":"DEBT","description":"Temizlik malzemesi",
                 "created_at":"2026-08-13T10:00:00Z"}
                """.trimIndent()
            )
        )
        val local = FakeLocalSource()

        repository(local).approvePending("p2")

        assertEquals("exactly one ledger entry", 1, local.ledger.size)
        assertEquals(7500L, local.ledger.single().amountMinor)
        // The server's id survives — a locally minted one would mean a second copy.
        assertEquals("t-1", local.ledger.single().transactionId)
        assertEquals("APPROVED", local.decided["p2"])
    }

    /**
     * A card addressed to somebody else. The server answers 403, and retrying can never
     * change that — so the row is DROPPED instead of being left pending forever.
     *
     * Found on a device: a seeded approval belonging to another account sat in the list and
     * could be neither approved nor rejected, because every non-success was treated as
     * "leave it, they can try again".
     */
    @Test
    fun `an approval addressed to someone else is dropped, not left pending`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(403).setBody(
                """{"error":{"code":"forbidden","message":"This approval is not addressed to you"}}"""
            )
        )
        val local = FakeLocalSource()

        val outcome = repository(local).approvePending("p1")

        assertEquals(DecisionOutcome.NotYours, outcome)
        assertEquals(listOf("p1"), local.deletedApprovals)
        assertTrue("nothing may be booked", local.ledger.isEmpty())
    }

    /** Already answered (409) is stale for the same reason, and dropped the same way. */
    @Test
    fun `an already decided approval is dropped`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(409).setBody(
                """{"error":{"code":"already_decided","message":"Already approved"}}"""
            )
        )
        val local = FakeLocalSource()

        val outcome = repository(local).rejectPending("p2")

        assertEquals(DecisionOutcome.AlreadyDecided, outcome)
        assertEquals(listOf("p2"), local.deletedApprovals)
    }

    /**
     * Offline is the opposite case: retrying IS the right move, so the card must SURVIVE.
     * This is what keeps the drop-on-403 rule from swallowing recoverable failures.
     */
    @Test
    fun `an unreachable server keeps the card`() = runTest {
        server.shutdown()
        val local = FakeLocalSource()

        val outcome = repository(local).approvePending("p1")

        assertEquals(DecisionOutcome.Unreachable, outcome)
        assertTrue("the card must stay for a retry", local.deletedApprovals.isEmpty())
        assertTrue(local.ledger.isEmpty())
    }

    /** A 201 carries an approval id: a card is raised and the ledger stays untouched. */
    @Test
    fun `server raising an approval writes a card, not a ledger entry`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(201).setBody(
                """
                {"approval_id":"a-1","initiator_user_id":"u_owner","initiator_role":"SELLER",
                 "target_user_id":"u1","seller_id":"u_owner","shop_name":"Ahmet Bakkal",
                 "customer_id":"c1","amount_minor":5000,"type":"DEBT","description":"Ekmek",
                 "channel":"APP_PUSH","status":"PENDING","requested_at":"2026-08-13T10:00:00Z"}
                """.trimIndent()
            )
        )
        val local = FakeLocalSource()

        val outcome = repository(local).requestApproval(
            fromUserId = "u_owner", sellerId = "u_owner", customerId = "c1",
            amountMinor = 5000, type = TransactionType.DEBT, description = "Ekmek"
        )

        // The SERVER's approval id, not a locally invented one: a caller that waits for
        // the answer has to ask about the row the server actually raised.
        assertEquals(ApprovalOutcome.SentForApproval("a-1"), outcome)
        assertEquals(1, local.approvals.size)
        assertTrue("ledger must stay empty", local.ledger.isEmpty())
        // The local branch decides for itself; on the online path it must not run at all.
        assertEquals(0, local.localRequestApprovalCalls)
    }

    /**
     * A 200 carries a transaction id: the counterparty has no account, so the server took
     * the SMS-OTP branch and booked it. The client mirrors that WITHOUT queueing — the
     * server already has the entry, and queueing would send a second copy.
     */
    @Test
    fun `server writing immediately books the entry and queues nothing`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {"transaction_id":"t-9","seller_id":"u_owner","customer_id":"c4",
                 "amount_minor":2500,"type":"PAYMENT","description":"Ödeme",
                 "created_at":"2026-08-13T10:00:00Z"}
                """.trimIndent()
            )
        )
        val local = FakeLocalSource()

        val outcome = repository(local).requestApproval(
            fromUserId = "u_owner", sellerId = "u_owner", customerId = "c4",
            amountMinor = 2500, type = TransactionType.PAYMENT, description = "Ödeme"
        )

        assertEquals(ApprovalOutcome.WrittenImmediately, outcome)
        assertEquals(1, local.ledger.size)
        assertTrue("nothing queued: the server already has it", local.queued.isEmpty())
        assertTrue(local.approvals.isEmpty())
    }

    /**
     * The false-success bug: a buyer with no history at this shop must be told so, rather
     * than seeing "payment received" for something that reached nobody.
     */
    @Test
    fun `paying a seller with no shared record reports it instead of claiming success`() = runTest {
        val local = FakeLocalSource(buyerCustomerId = null)

        val outcome = repository(local).initiatePayment("u1", "u_market", 1000)

        assertEquals(ApprovalOutcome.NoCustomerRecord, outcome)
        assertTrue(local.ledger.isEmpty())
        // Nothing was sent, so the server was never called.
        assertEquals(0, server.requestCount)
    }

    /** A payment with history goes through the same gate — and actually reaches the server. */
    @Test
    fun `paying a seller sends the request to the server`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(201).setBody(
                """
                {"approval_id":"a-2","initiator_user_id":"u1","initiator_role":"BUYER",
                 "target_user_id":"u_market","seller_id":"u_market","shop_name":"Ayşe Market",
                 "customer_id":"m1","amount_minor":1000,"type":"PAYMENT","description":"Uygulamadan ödeme",
                 "channel":"APP_PUSH","status":"PENDING","requested_at":"2026-08-13T10:00:00Z"}
                """.trimIndent()
            )
        )
        val local = FakeLocalSource(
            customer = Customer(
                customerId = "m1", displayName = "Ahmet Y.", phone = "+905551112233",
                claimStatus = ClaimStatus.CLAIMED, claimedByUserId = "u1",
                createdBySellerId = null, balanceMinor = 0L
            ),
            buyerCustomerId = "m1"
        )

        val outcome = repository(local).initiatePayment("u1", "u_market", 1000)

        assertEquals(ApprovalOutcome.SentForApproval("a-2"), outcome)
        assertEquals("the request must reach the server", 1, server.requestCount)
        assertEquals("/approvals", server.takeRequest().path)
    }

    /**
     * Offline: the entry is kept on the device so the user's action is not lost, but the
     * outcome says so rather than reading as a completed payment.
     */
    @Test
    fun `no network keeps the entry locally and says it is queued`() = runTest {
        server.shutdown()   // nothing is listening
        val local = FakeLocalSource()

        val outcome = repository(local).requestApproval(
            fromUserId = "u_owner", sellerId = "u_owner", customerId = "c1",
            amountMinor = 5000, type = TransactionType.DEBT, description = "Ekmek"
        )

        assertEquals(ApprovalOutcome.QueuedOffline, outcome)
        assertEquals("the offline branch is what records it", 1, local.localRequestApprovalCalls)
    }
}

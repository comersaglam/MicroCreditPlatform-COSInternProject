package com.example.app_pos.data

import com.example.app_pos.data.remote.RemoteDataSource
import com.example.app_pos.data.sync.PullEngine
import com.example.app_pos.model.PullOutcome
import com.example.app_pos.network.api.ApprovalApi
import com.example.app_pos.network.api.AuthApi
import com.example.app_pos.network.api.BuyerApi
import com.example.app_pos.network.api.CustomerApi
import com.example.app_pos.network.api.LedgerApi
import com.example.app_pos.network.api.SyncApi
import com.example.app_pos.network.api.UserApi
import com.squareup.moshi.Moshi
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

/**
 * The READ path: what the device does with the server's answer about its inbox.
 *
 * The rule under test throughout is that the server's list is AUTHORITATIVE — including
 * when it is empty, and excluding when it never arrived. Those two look identical to code
 * that only checks "did I get rows", and confusing them is how a phone in a lift would
 * erase its own pending cards.
 *
 * MockWebServer rather than a stubbed RemoteDataSource, so the failure branches are real
 * ones (a dropped connection, a 500, a body this client cannot parse) instead of a
 * restatement of whatever the production code already assumes.
 */
class PullPathTest {

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
            syncApi = retrofit.create(SyncApi::class.java),
            moshi = moshi
        )
    }

    @After
    fun tearDown() = server.shutdown()

    private fun engine(local: FakeLocalSource) = PullEngine(local, remote)

    private fun approvalJson(
        id: String = "p9",
        type: String = "DEBT",
        status: String = "PENDING",
        channel: String = "APP_PUSH",
        initiatorRole: String = "SELLER"
    ) = """
        {"approval_id":"$id","initiator_user_id":"u_market","initiator_role":"$initiatorRole",
         "target_user_id":"u_owner","seller_id":"u_market","shop_name":"Ayşe Market",
         "customer_id":"o1","amount_minor":7500,"type":"$type","description":"Test",
         "channel":"$channel","status":"$status","requested_at":"2026-08-14T09:00:00Z",
         "updated_at":"2026-08-14T09:00:00Z"}
    """.trimIndent()

    @Test
    fun `a card raised on another device is stored locally`() = runTest {
        // The whole reason this path exists: nothing local could have produced this row.
        server.enqueue(MockResponse().setResponseCode(200).setBody("[${approvalJson()}]"))
        val local = FakeLocalSource()

        val outcome = engine(local).pullApprovals("u_owner")

        assertEquals(PullOutcome.Refreshed(1), outcome)
        assertEquals("p9", local.syncedApprovals?.single()?.approvalId)
        assertEquals("u_owner", local.syncedForUserId)
    }

    /**
     * An empty answer is NEWS, not a no-op: it means every card was decided elsewhere, and
     * the local table must be emptied to match. A pull that skipped the sync here is
     * exactly how a card outlives its own decision.
     */
    @Test
    fun `an empty server list still clears the inbox`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("[]"))
        val local = FakeLocalSource()

        val outcome = engine(local).pullApprovals("u_owner")

        assertEquals(PullOutcome.Refreshed(0), outcome)
        assertEquals("storage was told to clear", emptyList<Any>(), local.syncedApprovals)
    }

    /**
     * THE dangerous case. No connection means this device does not know what changed, so
     * storage must not be touched at all — treating silence as "nothing is pending" would
     * let a phone with no signal wipe its own inbox.
     */
    @Test
    fun `an unreachable server leaves local rows untouched`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        val local = FakeLocalSource()

        val outcome = engine(local).pullApprovals("u_owner")

        assertEquals(PullOutcome.Unreachable, outcome)
        assertNull("storage must not be touched", local.syncedApprovals)
    }

    /** A server fault is the same story: retry later, change nothing now. */
    @Test
    fun `a server error leaves local rows untouched`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("{}"))
        val local = FakeLocalSource()

        val outcome = engine(local).pullApprovals("u_owner")

        assertEquals(PullOutcome.Unreachable, outcome)
        assertNull(local.syncedApprovals)
    }

    /** A refusal retrying cannot fix is reported as such, and still changes nothing. */
    @Test
    fun `a refusal is reported as failed and changes nothing`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(403)
                .setBody("""{"error":{"code":"forbidden","message":"Nope"}}""")
        )
        val local = FakeLocalSource()

        val outcome = engine(local).pullApprovals("u_owner")

        assertTrue(outcome is PullOutcome.Failed)
        assertNull(local.syncedApprovals)
    }

    /**
     * A row this build cannot read is dropped rather than guessed, because the type carries
     * the SIGN of the amount: a card nobody can read correctly must never be approvable.
     * The readable rows in the same response still land.
     */
    @Test
    fun `an unreadable row is dropped and the rest survive`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                "[${approvalJson(id = "good")}, ${approvalJson(id = "weird", type = "TRANSFER")}]"
            )
        )
        val local = FakeLocalSource()

        val outcome = engine(local).pullApprovals("u_owner")

        assertEquals(PullOutcome.Refreshed(1), outcome)
        assertEquals("good", local.syncedApprovals?.single()?.approvalId)
    }

    // --- the buyer's ledger: the OTHER pull, with the opposite deletion rule ---

    @Test
    fun `the buyer's entries and shop names are stored`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """[{"seller_id":"u_owner","shop_name":"Ahmet Bakkal",
                    "shop_phone":"+902121112233","balance_minor":5000}]"""
            )
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """[{"transaction_id":"t1","seller_id":"u_owner","customer_id":"c1",
                    "amount_minor":5000,"type":"DEBT","description":"Ekmek",
                    "created_at":"2026-08-14T09:00:00Z"}]"""
            )
        )
        val local = FakeLocalSource()

        val outcome = engine(local).pullMyLedger("u1")

        assertEquals(PullOutcome.Refreshed(1), outcome)
        assertEquals("t1", local.storedBuyerLedger?.single()?.transactionId)
        // The name comes off /me/debts: a buyer cannot read another account.
        assertEquals(mapOf("u_owner" to ("Ahmet Bakkal" to "+902121112233")), local.storedShopNames)
    }

    /**
     * THE distinction between the two pulls. An approval missing from a response has been
     * decided and must be deleted; a ledger entry missing from one has NOT been withdrawn,
     * because the ledger is append-only — the response was simply partial.
     *
     * So this path must never reach for a delete. If the shop list is empty there is nothing
     * to fetch and nothing to remove; treating it the way the inbox is treated would erase
     * ledger history on a truncated answer.
     */
    @Test
    fun `an empty debt list never deletes stored entries`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("[]"))
        val local = FakeLocalSource()

        val outcome = engine(local).pullMyLedger("u1")

        assertEquals(PullOutcome.Refreshed(0), outcome)
        assertNull("the ledger is append-only; nothing may be cleared", local.storedBuyerLedger)
    }

    /** Unreachable is unreachable on this path too: storage is left completely alone. */
    @Test
    fun `an unreachable server leaves the stored ledger untouched`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        val local = FakeLocalSource()

        val outcome = engine(local).pullMyLedger("u1")

        assertEquals(PullOutcome.Unreachable, outcome)
        assertNull(local.storedBuyerLedger)
        assertNull(local.storedShopNames)
    }

    // --- the seller's book: the THIRD pull, and the one this app was missing ---

    private fun customerJson(id: String = "c1", name: String = "Ahmet Yılmaz") = """
        {"customer_id":"$id","display_name":"$name","phone":"+905551112233",
         "claim_status":"UNCLAIMED","claimed_by_user_id":null,
         "created_by_seller_id":"u_owner","balance_minor":4000}
    """.trimIndent()

    /**
     * The gap this whole path closes. Müşterilerim read a Room table that nothing
     * server-side ever wrote to, so it showed only what this install had created itself —
     * empty on a clean device, half-full afterwards.
     */
    @Test
    fun `the seller's customers and their entries are stored`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("[${customerJson()}]"))
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """[{"transaction_id":"t1","seller_id":"u_owner","customer_id":"c1",
                    "amount_minor":5000,"type":"DEBT","description":"Ekmek",
                    "created_at":"2026-08-14T09:00:00Z"}]"""
            )
        )
        val local = FakeLocalSource()

        val outcome = engine(local).pullBook()

        assertEquals(PullOutcome.Refreshed(2), outcome)
        assertEquals("c1", local.storedCustomers.single().customerId)
        assertEquals("t1", local.storedLedger.single().transactionId)
    }

    /**
     * A buyer-only account has no book, and the server says so with a 403. That is the
     * normal answer to a question this account had no business asking — not a fault to
     * report. Turning it into a Failed would put a red error on the screen of every user
     * who is not a shopkeeper.
     */
    @Test
    fun `a buyer-only account is refused without reporting a failure`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(403)
                .setBody("""{"error":{"code":"not_a_seller","message":"Not a seller"}}""")
        )
        val local = FakeLocalSource()

        val outcome = engine(local).pullBook()

        assertEquals(PullOutcome.Refreshed(0), outcome)
        assertTrue("nothing may be written", local.storedCustomers.isEmpty())
    }

    /** Any OTHER refusal is still a refusal, and must not be swallowed with it. */
    @Test
    fun `an unrelated refusal on the book pull is still reported`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(403)
                .setBody("""{"error":{"code":"forbidden","message":"Nope"}}""")
        )
        val local = FakeLocalSource()

        val outcome = engine(local).pullBook()

        assertTrue(outcome is PullOutcome.Failed)
        assertTrue(local.storedCustomers.isEmpty())
    }

    /**
     * Same rule as the buyer ledger: the book is append-only, so an empty answer removes
     * nothing. Only the inbox treats absence as news.
     */
    @Test
    fun `an empty book never deletes stored entries`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("[]"))
        val local = FakeLocalSource()

        val outcome = engine(local).pullBook()

        assertEquals(PullOutcome.Refreshed(0), outcome)
        assertTrue("the ledger is append-only", local.storedLedger.isEmpty())
    }

    /** Unreachable leaves storage completely alone on this path too. */
    @Test
    fun `an unreachable server leaves the stored book untouched`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        val local = FakeLocalSource()

        val outcome = engine(local).pullBook()

        assertEquals(PullOutcome.Unreachable, outcome)
        assertTrue(local.storedCustomers.isEmpty())
        assertTrue(local.storedLedger.isEmpty())
    }

    /**
     * The customers already stored stay when the history call fails partway. Rolling them
     * back would make a flaky connection show an empty list instead of a stale one, and the
     * next poll finishes the job either way.
     */
    @Test
    fun `customers survive a history call that fails partway`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("[${customerJson()}]"))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        val local = FakeLocalSource()

        val outcome = engine(local).pullBook()

        assertEquals(PullOutcome.Unreachable, outcome)
        assertEquals("c1", local.storedCustomers.single().customerId)
    }

    /**
     * The server's own words are stored, not a local re-derivation of them.
     *
     * The pre-existing domain mapper hardcodes channel to APP_PUSH, defaults status to
     * PENDING and infers initiatorRole from whether the initiator is the seller. Routing a
     * pull through it would quietly overwrite three facts the server had just stated —
     * which defeats the point of asking.
     */
    @Test
    fun `the server's own direction fields are preserved`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                "[${approvalJson(channel = "SMS_OTP", initiatorRole = "BUYER", status = "PENDING")}]"
            )
        )
        val local = FakeLocalSource()

        engine(local).pullApprovals("u_owner")

        val stored = local.syncedApprovals!!.single()
        assertEquals("SMS_OTP", stored.channel)
        assertEquals("BUYER", stored.initiatorRole)
        // Not re-derived from initiatorUserId == sellerId, which would have said SELLER.
        assertEquals("u_market", stored.initiatorUserId)
    }
}

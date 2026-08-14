package com.example.app_pos.network.api

import com.example.app_pos.model.TransactionType
import com.example.app_pos.network.ApiResult
import com.example.app_pos.network.apiCall
import com.example.app_pos.network.isRetryable
import com.example.app_pos.network.mapper.toCreateDto
import com.example.app_pos.network.mapper.toDomainList
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
 * Drives the REAL Retrofit/OkHttp stack against a local server.
 *
 * The mapper tests prove the shapes convert correctly, but they never make a request — so
 * a wrong path in a @GET, a missing header, or a base URL without its trailing slash all
 * compile and pass. Those only surface once bytes go over a socket, which is what this
 * does. The canned responses are copied from shared-contracts/openapi.yaml's examples, so
 * the test also fails if the contract and the DTOs drift apart.
 */
class LedgerApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: LedgerApi
    private val moshi = Moshi.Builder().build()

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        api = Retrofit.Builder()
            // Same trailing-slash rule the production module relies on.
            .baseUrl(server.url("/"))
            .client(OkHttpClient.Builder().build())
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(LedgerApi::class.java)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `history hits the contract's path and query, and parses the seed response`() = runTest {
        server.enqueue(jsonResponse(SEED_HISTORY))

        val entries = api.history(customerId = "c1").toDomainList()

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/transactions?customer_id=c1", request.path)

        // Ahmet's seeded ledger: 50 + 30 − 40 = 40,00 TL, newest first.
        assertEquals(3, entries.size)
        assertEquals("t3", entries.first().transactionId)
        assertEquals(TransactionType.PAYMENT, entries.first().type)
        assertEquals(4000L, entries.first().amountMinor)
    }

    @Test
    fun `creating an entry sends the idempotency key and the snake_case body`() = runTest {
        server.enqueue(jsonResponse(SEED_CREATED, code = 201))

        val entry = com.example.app_pos.model.Transaction(
            transactionId = "t-uuid",
            sellerId = "u_owner",
            customerId = "c1",
            amountMinor = 5000,
            type = TransactionType.DEBT,
            description = "Veresiye",
            createdAt = "2026-07-20T06:15:00Z"
        )

        api.create(idempotencyKey = entry.transactionId, body = entry.toCreateDto())

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/transactions", request.path)
        // The key must be the entry's own id, so a replay is recognised as the same write.
        assertEquals("t-uuid", request.getHeader("Idempotency-Key"))

        val body = request.body.readUtf8()
        assertTrue("body must use wire names", body.contains("\"transaction_id\":\"t-uuid\""))
        assertTrue(body.contains("\"amount_minor\":5000"))
        // Ownership comes from the token, never the body.
        assertTrue("seller_id must not be sent", !body.contains("seller_id"))
    }

    @Test
    fun `a server error becomes a retryable ApiError, not an exception`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(503)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":{"code":"unavailable","message":"Try later"}}""")
        )

        val result = apiCall(moshi) { api.balance("c1") }

        val error = result as ApiResult.ApiError
        assertEquals(503, error.status)
        assertEquals("unavailable", error.code)
        assertEquals("Try later", error.message)
        // The outbox keeps the entry queued on this answer.
        assertTrue(error.isRetryable())
    }

    @Test
    fun `an HTML error page still yields a usable status instead of crashing`() = runTest {
        // A proxy or captive portal answers with HTML, not the error envelope.
        server.enqueue(
            MockResponse().setResponseCode(502)
                .setHeader("Content-Type", "text/html")
                .setBody("<html><body>Bad Gateway</body></html>")
        )

        val result = apiCall(moshi) { api.balance("c1") }

        val error = result as ApiResult.ApiError
        assertEquals(502, error.status)
        // Falls back to a synthetic code rather than losing the status to a parse failure.
        assertEquals("http_502", error.code)
        assertTrue(error.isRetryable())
    }

    @Test
    fun `losing the connection is classified as retryable, not as a bad response`() = runTest {
        server.shutdown() // nothing is listening any more

        val result = apiCall(moshi) { api.balance("c1") }

        assertTrue(result is ApiResult.NetworkError)
        assertTrue(result.isRetryable())
    }

    private fun jsonResponse(body: String, code: Int = 200) =
        MockResponse().setResponseCode(code)
            .setHeader("Content-Type", "application/json")
            .setBody(body)

    private companion object {
        /** Copied from the contract's /transactions example (seed c1). */
        const val SEED_HISTORY = """
            [
              {"transaction_id":"t3","seller_id":"u_owner","customer_id":"c1","amount_minor":4000,
               "type":"PAYMENT","description":"Nakit ödeme","basket_id":null,
               "settled_via_pgw":false,"receipt_no":null,"created_at":"2026-07-22T18:00:00Z"},
              {"transaction_id":"t2","seller_id":"u_owner","customer_id":"c1","amount_minor":3000,
               "type":"DEBT","description":"Peynir","basket_id":null,
               "settled_via_pgw":false,"receipt_no":null,"created_at":"2026-07-21T10:40:00Z"},
              {"transaction_id":"t1","seller_id":"u_owner","customer_id":"c1","amount_minor":5000,
               "type":"DEBT","description":"Ekmek, süt","basket_id":null,
               "settled_via_pgw":false,"receipt_no":null,"created_at":"2026-07-20T09:15:00Z"}
            ]
        """

        const val SEED_CREATED = """
            {"transaction_id":"t-uuid","seller_id":"u_owner","customer_id":"c1",
             "amount_minor":5000,"type":"DEBT","description":"Veresiye","basket_id":null,
             "settled_via_pgw":false,"receipt_no":null,"created_at":"2026-07-20T06:15:00Z"}
        """
    }
}

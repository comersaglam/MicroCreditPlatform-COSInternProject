package com.example.app_pos.network.api

import com.example.app_pos.network.dto.BalanceDto
import com.example.app_pos.network.dto.TransactionCreateDto
import com.example.app_pos.network.dto.TransactionDto
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query

/** The append-only ledger: one write endpoint, and reads derived from it. */
interface LedgerApi {

    /**
     * The single write point. [idempotencyKey] must be the entry's own transaction id,
     * minted on the device BEFORE the local write and reused verbatim on every retry —
     * generating it at send time would make a replay look like a new entry.
     *
     * The server answers 201 for a fresh write and 200 replaying the original; both are
     * successes and the caller does not distinguish them. A different body under the same
     * key is a conflict, never a silent overwrite: the ledger is append-only.
     */
    @POST("transactions")
    suspend fun create(
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body body: TransactionCreateDto
    ): TransactionDto

    /** One customer's history with this seller, newest first. */
    @GET("transactions")
    suspend fun history(@Query("customer_id") customerId: String): List<TransactionDto>

    /** The (seller, customer) balance, summed server-side from the ledger. */
    @GET("balances")
    suspend fun balance(@Query("customer_id") customerId: String): BalanceDto
}

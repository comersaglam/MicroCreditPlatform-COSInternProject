package com.example.app_pos.network.api

import com.example.app_pos.network.dto.SettleRequestDto
import com.example.app_pos.network.dto.SyncBatchDto
import com.example.app_pos.network.dto.SyncResultDto
import com.example.app_pos.network.dto.TransactionDto
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * The contract's forward-phase endpoints. Declared now, called later — the same
 * "signature ready, body later" habit used for OtpService and the skeleton Room tables.
 */
interface SyncApi {

    /**
     * Drains the offline outbox in one round trip. Each entry carries its own transaction
     * id and is idempotent on its own, so a partially-applied batch is safe to resend.
     */
    @POST("sync/transactions")
    suspend fun syncTransactions(@Body body: SyncBatchDto): SyncResultDto

    /**
     * Records that an approved PAYMENT went through the payment gateway and produced a
     * receipt. Only PAYMENTs settle: a DEBT ends at the ledger, since no money moved.
     */
    @POST("transactions/{id}/settle")
    suspend fun settle(
        @Path("id") transactionId: String,
        @Body body: SettleRequestDto
    ): TransactionDto
}

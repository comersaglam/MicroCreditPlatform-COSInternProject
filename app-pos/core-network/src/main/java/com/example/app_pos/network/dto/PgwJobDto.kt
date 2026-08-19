package com.example.app_pos.network.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Work the server left for this terminal to hand to the payment gateway.
 *
 * The arrow only points one way: a phone can start a sale, but only the POS can talk to
 * the gateway, and the server cannot call the POS. So anything the terminal must do on
 * the server's behalf is written down and collected here.
 *
 * [kind] decides WHICH intent is fired, and the two are not interchangeable:
 *  - RECEIPT: print a slip for an entry already booked — [orderBody] carries it.
 *  - COLLECT: open the gateway to take money by card — [orderBody] is null, since the
 *    gateway builds its own basket for a payment.
 */
@JsonClass(generateAdapter = true)
data class PgwJobDto(
    @param:Json(name = "job_id") val jobId: String,
    @param:Json(name = "seller_id") val sellerId: String,
    @param:Json(name = "kind") val kind: String,
    @param:Json(name = "transaction_id") val transactionId: String? = null,
    @param:Json(name = "customer_id") val customerId: String,
    @param:Json(name = "amount_minor") val amountMinor: Long,
    /**
     * The gateway's orderBody JSON, passed through verbatim. Deliberately a String rather
     * than a parsed model: it is the PGW's contract, not ours, and re-shaping it on the
     * way through is how a field they require quietly goes missing.
     */
    @param:Json(name = "order_body") val orderBody: String? = null,
    @param:Json(name = "status") val status: String,
    @param:Json(name = "created_at") val createdAt: String,
    @param:Json(name = "updated_at") val updatedAt: String
)

/**
 * POST /pgw-jobs — a seller sends work to their OWN terminal (path 4).
 *
 * No seller_id: the terminal is addressed by the bearer token, so nobody can queue work
 * onto another shop's till.
 */
@JsonClass(generateAdapter = true)
data class PgwJobCreateDto(
    @param:Json(name = "kind") val kind: String,
    @param:Json(name = "customer_id") val customerId: String,
    @param:Json(name = "amount_minor") val amountMinor: Long
)

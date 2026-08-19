package com.example.app_pos.network.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * A job queued for a POS terminal, as the server echoes it back.
 *
 * Only the fields this app needs to confirm the request landed. app-pos's copy of this DTO
 * carries the rest — the orderBody and the delivery status — because it is the side that
 * COLLECTS jobs and hands them to the gateway. A phone only ever creates them.
 */
@JsonClass(generateAdapter = true)
data class PgwJobDto(
    @param:Json(name = "job_id") val jobId: String,
    @param:Json(name = "seller_id") val sellerId: String,
    @param:Json(name = "kind") val kind: String,
    @param:Json(name = "customer_id") val customerId: String,
    @param:Json(name = "amount_minor") val amountMinor: Long,
    @param:Json(name = "status") val status: String
)

/**
 * POST /pgw-jobs — a seller sends work to their OWN terminal (path 4).
 *
 * No seller_id: the till is addressed by the bearer token, so nobody can queue work onto
 * another shop's terminal.
 */
@JsonClass(generateAdapter = true)
data class PgwJobCreateDto(
    /**
     * COLLECT only. RECEIPT jobs are raised by the server alongside the ledger entry they
     * accompany, never asked for — a slip must not be printable for a debt nobody booked.
     */
    @param:Json(name = "kind") val kind: String,
    @param:Json(name = "customer_id") val customerId: String,
    @param:Json(name = "amount_minor") val amountMinor: Long
)

/** The gateway opens to TAKE money by card. The only kind a client may request. */
const val PGW_KIND_COLLECT: String = "COLLECT"

package com.example.app_pos.network.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/** Wire shapes for the seller-scoped customer endpoints. */

/**
 * A customer in a seller's book. `claimStatus` is UNCLAIMED or CLAIMED — whether a real
 * account backs this ledger entry — and is a separate axis from who the seller is.
 *
 * `balanceMinor` is DERIVED by the server from the ledger and never stored; it is
 * seller-scoped, so the same person carries a different balance in another shop's book.
 */
@JsonClass(generateAdapter = true)
data class CustomerDto(
    @param:Json(name = "customer_id") val customerId: String,
    @param:Json(name = "display_name") val displayName: String,
    @param:Json(name = "phone") val phone: String,
    @param:Json(name = "claim_status") val claimStatus: String,
    @param:Json(name = "claimed_by_user_id") val claimedByUserId: String? = null,
    @param:Json(name = "balance_minor") val balanceMinor: Long = 0L
)

/** POST /customers — the shopkeeper opens a book entry for an app-less customer. */
@JsonClass(generateAdapter = true)
data class CustomerCreateDto(
    @param:Json(name = "display_name") val displayName: String,
    @param:Json(name = "phone") val phone: String
)

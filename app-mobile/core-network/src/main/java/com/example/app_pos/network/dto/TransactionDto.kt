package com.example.app_pos.network.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/** Wire shapes for the append-only ledger and its derived reads. */

/**
 * A ledger entry as the server reports it. `amountMinor` is always positive; the sign is
 * carried by [type] (DEBT adds to what is owed, PAYMENT subtracts), which is why a row
 * whose type cannot be understood must never reach a balance sum.
 */
@JsonClass(generateAdapter = true)
data class TransactionDto(
    @param:Json(name = "transaction_id") val transactionId: String,
    @param:Json(name = "seller_id") val sellerId: String,
    @param:Json(name = "customer_id") val customerId: String,
    @param:Json(name = "amount_minor") val amountMinor: Long,
    @param:Json(name = "type") val type: String,
    @param:Json(name = "description") val description: String,
    @param:Json(name = "basket_id") val basketId: String? = null,
    @param:Json(name = "settled_via_pgw") val settledViaPgw: Boolean = false,
    @param:Json(name = "receipt_no") val receiptNo: String? = null,
    @param:Json(name = "created_at") val createdAt: String
)

/**
 * A new ledger entry. Sent with an `Idempotency-Key` header equal to [transactionId], so
 * a retry replays rather than duplicates.
 *
 * There is deliberately NO seller_id here: on seller-scoped endpoints the server takes it
 * from the bearer token and never trusts a body field for it. The domain Transaction does
 * carry sellerId (Room stores it), so the mapper drops it on the way out and the server
 * supplies it on the way back — that asymmetry is intended, not an oversight.
 *
 * [basket] is present only when a basket handoff rode along; a money-only entry omits it.
 */
@JsonClass(generateAdapter = true)
data class TransactionCreateDto(
    @param:Json(name = "transaction_id") val transactionId: String,
    @param:Json(name = "customer_id") val customerId: String,
    @param:Json(name = "amount_minor") val amountMinor: Long,
    @param:Json(name = "type") val type: String,
    @param:Json(name = "description") val description: String,
    @param:Json(name = "basket") val basket: OrderBodyDto? = null
)

/** GET /balances — the (seller, customer) pair's summed ledger, computed server-side. */
@JsonClass(generateAdapter = true)
data class BalanceDto(
    @param:Json(name = "seller_id") val sellerId: String,
    @param:Json(name = "customer_id") val customerId: String,
    @param:Json(name = "balance_minor") val balanceMinor: Long,
    @param:Json(name = "as_of") val asOf: String
)

/** GET /me/debts — one row per shop the buyer owes, for app-mobile's main screen. */
@JsonClass(generateAdapter = true)
data class SellerDebtDto(
    @param:Json(name = "seller_id") val sellerId: String,
    @param:Json(name = "shop_name") val shopName: String,
    @param:Json(name = "balance_minor") val balanceMinor: Long
)

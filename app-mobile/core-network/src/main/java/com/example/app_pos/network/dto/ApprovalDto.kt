package com.example.app_pos.network.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/** Wire shapes for the three approval lines, which share one direction-aware schema. */

/**
 * A pending (or settled) approval request.
 *
 * The direction fields say who is on each end: [initiatorUserId] started it,
 * [targetUserId] must answer, and [initiatorRole] says which of the three lines this is
 * (buyer→POS, POS→buyer, seller-mobile→buyer). The rule that matters: whoever starts a
 * request never approves it.
 *
 * [shopName] is denormalised so the counterparty's card can render without a second
 * lookup. [channel] is APP_PUSH for an app-holding target, SMS_OTP otherwise.
 */
@JsonClass(generateAdapter = true)
data class ApprovalDto(
    @param:Json(name = "approval_id") val approvalId: String,
    @param:Json(name = "initiator_user_id") val initiatorUserId: String,
    @param:Json(name = "initiator_role") val initiatorRole: String,
    @param:Json(name = "target_user_id") val targetUserId: String,
    @param:Json(name = "seller_id") val sellerId: String,
    @param:Json(name = "shop_name") val shopName: String,
    @param:Json(name = "customer_id") val customerId: String,
    @param:Json(name = "amount_minor") val amountMinor: Long,
    @param:Json(name = "type") val type: String,
    @param:Json(name = "description") val description: String? = null,
    @param:Json(name = "channel") val channel: String,
    @param:Json(name = "status") val status: String,
    @param:Json(name = "requested_at") val requestedAt: String
)

/**
 * POST /approvals — send a write for approval. When the target holds the app a PENDING
 * approval is created; when they do not, the server writes the ledger entry immediately
 * (the SMS-OTP branch), so this call answers with either shape.
 */
@JsonClass(generateAdapter = true)
data class ApprovalCreateDto(
    @param:Json(name = "seller_id") val sellerId: String,
    @param:Json(name = "customer_id") val customerId: String,
    @param:Json(name = "amount_minor") val amountMinor: Long,
    @param:Json(name = "type") val type: String,
    @param:Json(name = "description") val description: String? = null,
    @param:Json(name = "initiator_role") val initiatorRole: String,
    @param:Json(name = "target_user_id") val targetUserId: String
)

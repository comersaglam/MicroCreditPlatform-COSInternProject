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
    /** "POS" or "PHONE" — which device raised this. Decides gateway work on approval. */
    @param:Json(name = "origin") val origin: String = ORIGIN_PHONE,
    @param:Json(name = "status") val status: String,
    @param:Json(name = "requested_at") val requestedAt: String
)

/** Raised at a POS terminal, which hands its own intent to the gateway. */
const val ORIGIN_POS: String = "POS"

/** Raised on a phone, where nobody is standing at the gateway. */
const val ORIGIN_PHONE: String = "PHONE"

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
    @param:Json(name = "target_user_id") val targetUserId: String,

    /**
     * Which kind of device raised this: "POS" or "PHONE". It decides whether approving
     * leaves gateway work behind — a terminal is already in front of the PGW and fires
     * its own intent, so a job queued for it would hand the same receipt over twice.
     */
    @param:Json(name = "origin") val origin: String
)

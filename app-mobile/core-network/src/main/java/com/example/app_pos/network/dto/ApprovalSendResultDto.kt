package com.example.app_pos.network.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * The two shapes `POST /approvals` can answer with, in one type.
 *
 * The server returns **201 with an Approval** when the counterparty holds an account, and
 * **200 with a Transaction** when they do not (the SMS-OTP branch writes the entry there
 * and then). One endpoint, two bodies — the contract says so, and the client has to tell
 * them apart because they mean opposite things to the user: "waiting for them" versus
 * "already in the book".
 *
 * Discriminated by which id is present rather than by the status code, because the status
 * is lost by the time the body is deserialised (`apiCall` unwraps Retrofit's Response).
 * The ids are mutually exclusive and non-null in their own shape, so the field IS the
 * discriminator — no extra flag to keep in sync with the server.
 *
 * Every other field is nullable because only one of the two shapes fills it in. Reading
 * them directly would be a mistake; use [asApproval] / [asTransaction], which return null
 * for the branch that did not happen.
 */
@JsonClass(generateAdapter = true)
data class ApprovalSendResultDto(
    // --- present only on the 201 (approval raised) branch ---
    @param:Json(name = "approval_id") val approvalId: String? = null,
    @param:Json(name = "initiator_user_id") val initiatorUserId: String? = null,
    @param:Json(name = "initiator_role") val initiatorRole: String? = null,
    @param:Json(name = "target_user_id") val targetUserId: String? = null,
    @param:Json(name = "shop_name") val shopName: String? = null,
    @param:Json(name = "channel") val channel: String? = null,
    @param:Json(name = "status") val status: String? = null,
    @param:Json(name = "requested_at") val requestedAt: String? = null,

    // --- present only on the 200 (written immediately) branch ---
    @param:Json(name = "transaction_id") val transactionId: String? = null,
    @param:Json(name = "basket_id") val basketId: String? = null,
    @param:Json(name = "settled_via_pgw") val settledViaPgw: Boolean? = null,
    @param:Json(name = "receipt_no") val receiptNo: String? = null,
    @param:Json(name = "created_at") val createdAt: String? = null,

    // --- shared by both shapes ---
    @param:Json(name = "seller_id") val sellerId: String? = null,
    @param:Json(name = "customer_id") val customerId: String? = null,
    @param:Json(name = "amount_minor") val amountMinor: Long? = null,
    @param:Json(name = "type") val type: String? = null,
    @param:Json(name = "description") val description: String? = null
) {

    /** The raised approval, or null when the server took the immediate-write branch. */
    fun asApproval(): ApprovalDto? {
        val id = approvalId ?: return null
        return ApprovalDto(
            approvalId = id,
            initiatorUserId = initiatorUserId.orEmpty(),
            initiatorRole = initiatorRole.orEmpty(),
            targetUserId = targetUserId.orEmpty(),
            sellerId = sellerId.orEmpty(),
            shopName = shopName.orEmpty(),
            customerId = customerId.orEmpty(),
            amountMinor = amountMinor ?: 0L,
            type = type.orEmpty(),
            description = description,
            channel = channel.orEmpty(),
            status = status.orEmpty(),
            requestedAt = requestedAt.orEmpty()
        )
    }

    /** The written entry, or null when the server raised an approval instead. */
    fun asTransaction(): TransactionDto? {
        val id = transactionId ?: return null
        return TransactionDto(
            transactionId = id,
            sellerId = sellerId.orEmpty(),
            customerId = customerId.orEmpty(),
            amountMinor = amountMinor ?: 0L,
            type = type.orEmpty(),
            description = description.orEmpty(),
            basketId = basketId,
            settledViaPgw = settledViaPgw ?: false,
            receiptNo = receiptNo,
            createdAt = createdAt.orEmpty()
        )
    }
}

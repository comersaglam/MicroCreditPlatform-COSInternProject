package com.example.app_pos.network.mapper

import com.example.app_pos.model.ApprovalStatus
import com.example.app_pos.model.OrderBody
import com.example.app_pos.model.TransactionType
import com.example.app_pos.network.dto.ApprovalCreateDto
import com.example.app_pos.network.dto.ApprovalDto

/**
 * Approval ↔ wire.
 *
 * Both directions now: app-pos sends entries for approval AND renders an inbox (Turn 39),
 * so the response direction is no longer a skeleton. The unknown-value rule holds where it
 * is applied — a status this build does not recognise is dropped rather than guessed at,
 * because an approval rendered as the wrong thing is worse than one not shown.
 */

/**
 * Builds the "send for approval" body.
 *
 * [initiatorRole] says which of the three lines this is, and [targetUserId] is the party
 * who must answer. The rule the server also enforces: whoever starts a request is never
 * the one who approves it.
 */
fun approvalCreateDto(
    sellerId: String,
    customerId: String,
    amountMinor: Long,
    type: TransactionType,
    description: String?,
    initiatorRole: String,
    targetUserId: String,
    origin: String,
    orderBody: OrderBody? = null
): ApprovalCreateDto = ApprovalCreateDto(
    sellerId = sellerId,
    customerId = customerId,
    amountMinor = amountMinor,
    type = type.name,
    description = description,
    initiatorRole = initiatorRole,
    targetUserId = targetUserId,
    // Required rather than defaulted: which device raised the request decides whether the
    // server queues gateway work, and a wrong default would print a duplicate receipt in
    // one direction or drop one entirely in the other. Each caller states it.
    origin = origin,
    // Defaulted, unlike origin: a request with no basket is the ordinary case, not a
    // caller that forgot. Reuses the same toDto() the direct-write path uses, so a basket
    // is shaped identically whichever way it reaches the server.
    basket = orderBody?.toDto()
)

/**
 * The approval's status as a domain value, or null when this build does not recognise it.
 *
 * Null is a real answer here, not a parse failure to paper over: the caller is a sale
 * waiting at the till, and "I do not know what the server means" must keep it waiting
 * rather than resolve to APPROVED and print a receipt for something nobody agreed to.
 */
fun ApprovalDto.statusOrNull(): ApprovalStatus? =
    ApprovalStatus.entries.firstOrNull { it.name == status }

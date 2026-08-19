package com.example.app_pos.network.mapper

import com.example.app_pos.model.PendingApproval
import com.example.app_pos.model.TransactionType
import com.example.app_pos.network.dto.ApprovalCreateDto
import com.example.app_pos.network.dto.ApprovalDto

/**
 * Approval ↔ wire, both directions.
 *
 * The request direction (send for approval) is shared with app-pos. The response
 * direction is app-mobile's: this is the side that renders an inbox, so it is the side
 * that needs an ApprovalDto mapped onto the domain [PendingApproval].
 *
 * Unknown-value rule, as everywhere else: drop the row. An approval that cannot be
 * rendered correctly is worse than one that is not shown, because approving it writes a
 * ledger entry.
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
    origin: String
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
    origin = origin
)

/**
 * Wire → domain, for the inbox app-mobile renders.
 *
 * Null when the entry's type is unrecognised, matching TransactionMapper's rule and for
 * the same reason: the type carries the sign, so an approval that might be either a debt
 * or a payment must not be shown at all — approving it would write a signed amount nobody
 * could have read off the card.
 *
 * [counterpartyName] comes from the server's denormalised `shop_name`, which is what the
 * card needs when a SELLER raised the request. A buyer-initiated request names the customer
 * instead, so the caller passes what that side should read.
 */
fun ApprovalDto.toDomainOrNull(counterpartyName: String = shopName): PendingApproval? {
    val txType = type.toTransactionTypeOrNull() ?: return null
    return PendingApproval(
        approvalId = approvalId,
        sellerId = sellerId,
        counterpartyName = counterpartyName,
        // Who must ANSWER, which the server tracks as the target — never the initiator.
        approverUserId = targetUserId,
        customerId = customerId,
        amountMinor = amountMinor,
        type = txType,
        description = description.orEmpty(),
        requestedAt = requestedAt
    )
}

/** Maps an inbox response, skipping rows this client cannot interpret. */
fun List<ApprovalDto>.toDomainList(): List<PendingApproval> = mapNotNull { it.toDomainOrNull() }

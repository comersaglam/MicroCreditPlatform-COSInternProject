package com.example.app_pos.network.mapper

import com.example.app_pos.model.OrderBody
import com.example.app_pos.model.Transaction
import com.example.app_pos.network.dto.TransactionCreateDto
import com.example.app_pos.network.dto.TransactionDto

/**
 * Transaction ↔ wire.
 *
 * The domain Transaction is deliberately narrower than both the entity and the DTO: it
 * has no basketId / settledViaPgw / receiptNo. Those are storage and gateway concerns,
 * and the Room mapper already hardcodes them the same way. Keeping the asymmetry means
 * the domain model stays about the ledger itself.
 */

/**
 * Null when the entry's type is unrecognised, so the caller drops the row.
 *
 * Dropping is the right failure here precisely because the type carries the sign: an
 * entry that might be either a debt or a payment cannot be shown, and above all must not
 * reach a balance sum. Use [toDomainList] to apply this over a response array.
 */
fun TransactionDto.toDomainOrNull(): Transaction? {
    val txType = type.toTransactionTypeOrNull() ?: return null
    return Transaction(
        transactionId = transactionId,
        sellerId = sellerId,
        customerId = customerId,
        amountMinor = amountMinor,
        type = txType,
        description = description,
        createdAt = createdAt
    )
}

/** Maps a response array, silently skipping entries this client cannot interpret. */
fun List<TransactionDto>.toDomainList(): List<Transaction> = mapNotNull { it.toDomainOrNull() }

/**
 * Builds the create body. [orderBody] is attached only for a basket handoff; a money-only
 * entry sends none.
 *
 * sellerId is intentionally NOT sent: the server derives it from the bearer token and
 * must not trust a body field for ownership.
 */
fun Transaction.toCreateDto(orderBody: OrderBody? = null): TransactionCreateDto =
    TransactionCreateDto(
        transactionId = transactionId,
        customerId = customerId,
        amountMinor = amountMinor,
        type = type.name,
        description = description,
        basket = orderBody?.toDto()
    )

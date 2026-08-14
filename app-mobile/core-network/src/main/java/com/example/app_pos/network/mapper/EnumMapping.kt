package com.example.app_pos.network.mapper

import com.example.app_pos.model.ClaimStatus
import com.example.app_pos.model.TransactionType

/**
 * Wire enums arrive as plain Strings, and are widened to domain enums HERE rather than
 * being declared as enums on the DTOs.
 *
 * The reason is failure granularity: Moshi's enum adapter throws on an unrecognised
 * value, which fails the parse of the WHOLE response — so a backend that adds one new
 * transaction type would blank an entire ledger list mid-sale rather than lose one row.
 * Returning null instead lets each caller decide, per field, whether to drop the row or
 * fall back. This also matches the schema docs: enums are strings in all three
 * representations (domain, entity, wire).
 */

/**
 * DEBT / PAYMENT. Null when unrecognised — and callers must DROP such a row, never guess.
 * The type carries the sign of the amount, so an entry whose direction is unknown would
 * corrupt a balance if it were defaulted either way.
 */
internal fun String?.toTransactionTypeOrNull(): TransactionType? =
    TransactionType.entries.firstOrNull { it.name == this }

/**
 * UNCLAIMED / CLAIMED, defaulting to UNCLAIMED when unrecognised.
 *
 * Safe to default, unlike the ledger type: this only decides whether approval reaches the
 * person by push or by SMS, and UNCLAIMED is the conservative assumption (it does not
 * imply an account exists).
 */
internal fun String?.toClaimStatusOrUnclaimed(): ClaimStatus =
    ClaimStatus.entries.firstOrNull { it.name == this } ?: ClaimStatus.UNCLAIMED

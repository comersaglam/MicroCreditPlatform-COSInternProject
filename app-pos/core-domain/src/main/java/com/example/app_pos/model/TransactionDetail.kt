package com.example.app_pos.model

/**
 * One ledger entry together with the basket it was rung up from, if it had one.
 *
 * The two travel as a pair because the screen that shows them has to agree with itself.
 * Read separately -- the entry from one query, the basket from another -- there is a window
 * where the lines belong to one row and the amount above them to a different one, and a
 * receipt whose items do not add up to its total is worse than no receipt at all.
 *
 * [basket] is null for a legitimate and common case, not an error: a money-only handoff
 * carries no items, a payment is not a purchase, and an INDEXATION row is written by the
 * server with nothing bought. The screen says so in words rather than showing an empty list.
 */
data class TransactionDetail(
    val transaction: Transaction,
    val basket: OrderBody?
)

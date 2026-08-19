package com.example.app_pos.model

/**
 * One piece of work the terminal must hand to the payment gateway.
 *
 * These arrive from the server rather than from anything this device did, because the
 * sale that produced them started somewhere else — the seller's phone or the buyer's —
 * and only a POS can talk to the gateway.
 *
 * Not a Room entity, deliberately. A job is consumed the moment it is seen: fire the
 * intent, ack it, forget it. Storing one would create a second place where "have we
 * delivered this?" is recorded, and the two could disagree — with a duplicate receipt as
 * the visible result. The server's DELIVERED flag is the single answer.
 */
data class PgwJob(
    val jobId: String,
    val kind: PgwJobKind,
    val customerId: String,
    val amountMinor: Long,
    /**
     * The gateway's orderBody, verbatim. Present for [PgwJobKind.RECEIPT] and null for
     * [PgwJobKind.COLLECT], which lets the gateway build its own basket.
     */
    val orderBody: String?
)

/**
 * What the gateway is being asked to do — and the two are not interchangeable.
 *
 * RECEIPT accompanies money already in the ledger; COLLECT asks for money that has not
 * been taken yet. Firing the wrong one either prints a slip for a payment nobody made or
 * charges a card for a debt already recorded.
 */
enum class PgwJobKind { RECEIPT, COLLECT }

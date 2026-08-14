package com.example.app_pos.model

/**
 * A request, waiting on the counterparty's approval, to book a veresiye (DEBT) or a
 * payment (PAYMENT). Nothing reaches the ledger until it is approved — the single
 * write point. In production these arrive from the backend (the app polls; it never
 * listens); here they are stored locally.
 */
data class PendingApproval(
    val approvalId: String,
    val sellerId: String,
    /**
     * Who the approver sees on the card — the OTHER side, so it reads correctly in both
     * directions: the shop's name when a seller asks, the customer's name when a buyer
     * pays. (Denormalised: the card must render without a second lookup.)
     */
    val counterpartyName: String,
    /**
     * Who has to decide — the COUNTERPARTY, never the initiator. A seller writing to
     * their book needs the customer's approval; a buyer paying needs the shop's (they
     * confirm receipt). Not "the buyer": either side can be the approver.
     */
    val approverUserId: String,
    // Which ledger record this entry belongs to. Carried from the request rather than
    // resolved again at approval time: the requester already knows it, and a buyer may
    // hold several records (one per shop), so re-guessing could pick the wrong book.
    val customerId: String,
    val amountMinor: Long,
    val type: TransactionType,
    val description: String,
    val requestedAt: String
)

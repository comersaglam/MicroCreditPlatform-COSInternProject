package com.example.app_pos.model

/**
 * What actually happened when an entry was sent through the approval gate.
 *
 * These outcomes are deliberately distinct because they need different things from the
 * user, and collapsing them is how a screen ends up lying. The version before this type
 * returned `Unit` from requestApproval and an unconditional `true` from initiatePayment —
 * so a payment that reached nobody still showed "payment received", which is exactly the
 * failure a customer cannot recover from because they believe they are square.
 *
 * [SentForApproval] and [WrittenImmediately] are BOTH successes, and the difference matters
 * to the user: the first is a request the counterparty still has to accept, the second is
 * already in the book. Only the server decides which one applies (it knows whether the
 * counterparty holds an account); the client reports what it is told.
 */
sealed interface ApprovalOutcome {

    /** The counterparty holds the app: a pending card was raised, the ledger is untouched. */
    data object SentForApproval : ApprovalOutcome

    /**
     * The counterparty has no account, so nobody could tap approve. The server took the
     * SMS-OTP branch and booked the entry there and then.
     */
    data object WrittenImmediately : ApprovalOutcome

    /**
     * There is no shared record to write against — a buyer paying a shop they have no
     * history with. Nothing was written, and the screen should say so rather than
     * inventing a record in someone else's book.
     */
    data object NoCustomerRecord : ApprovalOutcome

    /**
     * The server could not be reached, so the entry was kept on this device only.
     *
     * A success for the user (their action was not lost) but NOT a completed one: the
     * counterparty has not seen it, and nothing reconciles the two copies yet. Kept
     * separate from the successes so the screen can be honest about that.
     */
    data object QueuedOffline : ApprovalOutcome

    /** The server refused it. [message] is its explanation when it gave one. */
    data class Failed(val message: String? = null) : ApprovalOutcome
}

/**
 * What a buyer-initiated payment is called in the ledger.
 *
 * Here rather than in either data-layer class because both the local source and the
 * composing repository write it, and two copies of a user-visible string drift.
 */
const val PAYMENT_DESCRIPTION: String = "Uygulamadan ödeme"

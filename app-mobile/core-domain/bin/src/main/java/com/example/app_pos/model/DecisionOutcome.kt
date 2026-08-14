package com.example.app_pos.model

/**
 * What happened when the user answered a pending approval (approve or reject).
 *
 * Separate from [ApprovalOutcome], which is about SENDING an entry for approval. Answering
 * one has different failure modes, and two of them are not really failures at all — they
 * mean the card should not have been on screen in the first place:
 *
 *  - [NotYours]: the approval is addressed to somebody else (the server answers 403)
 *  - [AlreadyDecided]: it was answered already, here or on another device (409)
 *
 * Both were found on a device: a seeded card belonging to another account sat in the list
 * and could be neither approved nor rejected, because the code treated every non-success
 * the same way — "leave it and let them retry" — and retrying could never work. The card
 * looked broken when the system was in fact behaving correctly.
 */
sealed interface DecisionOutcome {

    /** The server accepted the decision. For an approval, the entry is now in the ledger. */
    data object Applied : DecisionOutcome

    /**
     * Not this user's approval to answer. The card is stale — it is dropped locally rather
     * than left for a retry that would fail identically every time.
     */
    data object NotYours : DecisionOutcome

    /** Already approved or rejected. Also stale, and dropped for the same reason. */
    data object AlreadyDecided : DecisionOutcome

    /**
     * The server could not be reached. The card STAYS, because retrying is exactly the
     * right next move — unlike the two cases above.
     */
    data object Unreachable : DecisionOutcome

    /** The server refused for some other reason. [message] is its explanation, when given. */
    data class Failed(val message: String? = null) : DecisionOutcome
}

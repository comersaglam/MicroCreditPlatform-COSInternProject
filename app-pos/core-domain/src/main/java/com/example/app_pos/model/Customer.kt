package com.example.app_pos.model

/**
 * Whether this customer record is linked to a real user account.
 *
 * UNCLAIMED: created by the merchant with just a name; the customer has no app.
 *            Most customers start out this way in the real world.
 * CLAIMED:   the customer signed in on app-mobile with their phone number, so
 *            the record is now bound to their account.
 */
enum class ClaimStatus { UNCLAIMED, CLAIMED }

/**
 * A credit (veresiye) customer — the MERCHANT'S ledger entry, not an app account.
 * The account counterpart is [User]; the two are linked by claimedByUserId below.
 *
 * NOTE: balanceMinor is not stored anywhere — it is DERIVED from the customer's
 * ledger entries (append-only rule: a balance is never kept as a single mutable
 * number). FakeRepository computes it by summing the transactions.
 */
data class Customer(
    val customerId: String,
    val displayName: String,
    val phone: String?,              // the identity we track a customer by; in practice always set
    val claimStatus: ClaimStatus,
    val claimedByUserId: String?,    // the linked User's id when CLAIMED; null when UNCLAIMED
    // Which shop wrote this person down. Membership of a book normally comes from the
    // ledger ("we have an entry together"), but that leaves a customer who has been added
    // and not yet charged belonging to no book at all -- stored, and invisible on every
    // screen. This field is what carries them until their first entry exists.
    val createdBySellerId: String?,
    val balanceMinor: Long           // in minor units (kuruş); positive = owes money
)

/**
 * What happened when a customer was written down.
 *
 * A bare `String` id used to be returned here, and it could not say anything except "here
 * is an id" — so a creation that never reached the server was indistinguishable from one
 * that did. The id was minted locally, the veresiye written against it was queued, and the
 * server answered 404 because it had never heard of that customer. A 404 is not retryable,
 * so the outbox DROPPED the entry: the debt stayed on the merchant's screen and never
 * existed on the server. Same lesson as [OtpRequestResult] and [PullOutcome] — the reason
 * is what decides what happens next, and a type that cannot carry one hides the failure.
 *
 * The server mints the id, always. That is the invariant this type exists to protect.
 */
sealed interface CustomerCreateOutcome {

    /** The server created the record; [customerId] is ITS id, safe to write entries against. */
    data class Created(val customerId: String) : CustomerCreateOutcome

    /**
     * This phone is already in this seller's book (409). Not an error: the right record
     * exists, so [customerId] points at it and the caller carries on with it. Two rows for
     * one person would split their history.
     */
    data class AlreadyExists(val customerId: String) : CustomerCreateOutcome

    /**
     * The server was never reached, and NOTHING was written locally.
     *
     * Deliberately not an offline queue: the id has to come from the server, and a local
     * one is exactly what caused the silent loss described above. The cost is that a new
     * customer cannot be opened without signal — writing a veresiye for an EXISTING
     * customer still works offline, which is the common case on a shop floor.
     */
    data object Unreachable : CustomerCreateOutcome

    /** The server refused, and said why (an invalid number, not a seller, …). */
    data class Failed(val message: String) : CustomerCreateOutcome
}

/**
 * What a phone number means to one seller who is about to book an entry.
 *
 * A person exists once system-wide (phone = identity), but a seller "has" a customer
 * only through the ledger — so the same number is a different situation depending on
 * who is asking. Modelled as a sealed type so the caller cannot forget a case: the
 * three outcomes need three different screens.
 */
sealed interface CustomerLookup {
    /** Nobody holds this number: create a new record at write time. */
    object New : CustomerLookup

    /**
     * The person exists but has no entry with this seller. Reuse the existing record
     * (a second row would split their history) — [existing] carries the stored name,
     * which the merchant is shown before continuing.
     */
    data class KnownToOtherSeller(val existing: Customer) : CustomerLookup

    /** Already in this seller's own book — they should pick them from the list. */
    data class AlreadyMine(val existing: Customer) : CustomerLookup
}

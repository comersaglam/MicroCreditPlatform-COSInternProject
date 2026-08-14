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
    val balanceMinor: Long           // in minor units (kuruş); positive = owes money
)

/**
 * What a phone number means to one seller who is about to book an entry.
 *
 * A person exists once system-wide (phone = identity), but a seller "has" a customer
 * only through the ledger — so the same number is a different situation depending on
 * who is asking. Modelled as a sealed type so the caller cannot forget a case: the
 * three outcomes need three different screens.
 */
sealed interface CustomerLookup {
    /** Nobody holds this number: create a new record. */
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

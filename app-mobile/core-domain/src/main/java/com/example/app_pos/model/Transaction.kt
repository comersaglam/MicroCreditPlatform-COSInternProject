package com.example.app_pos.model

/**
 * Type of a ledger entry.
 *
 * DEBT:       credit extended by the merchant -> INCREASES the balance
 * PAYMENT:    money received from the customer -> DECREASES the balance
 * INDEXATION: the month's inflation on an outstanding balance -> INCREASES it
 *
 * INDEXATION is written ONLY by the server, never by a device. A shopkeeper lends money
 * that loses value while it is out, and this is how the debt keeps pace; carrying it as a
 * ledger row rather than as a factor in the balance formula is what lets the balance stay
 * a plain sum that both sides derive identically.
 *
 * It only ever runs one way. Nothing is indexed while a customer is square or in credit
 * -- a shop is not a bank and does not pay inflation on what it owes -- and a month whose
 * adjustment rounds to zero or less writes no row at all.
 *
 * An enum instead of a String: invalid values ("debt", "Debt", "borc") become
 * impossible at compile time. Adding a case here is deliberate leverage -- every
 * exhaustive `when` in both apps stops compiling until it has been considered.
 */
enum class TransactionType { DEBT, PAYMENT, INDEXATION }

/**
 * A single entry in the append-only ledger.
 *
 * These records are never updated or deleted, only appended. A customer's
 * balance is the sum of their entries. This gives us:
 *  - conflict-free sync: two devices writing at once just append, no clashes
 *  - idempotency via transactionId: a retried entry is not applied twice
 *  - auditability: who wrote what, and when, is always visible
 */
data class Transaction(
    val transactionId: String,       // UUID — idempotency key
    val sellerId: String,            // which merchant's ledger this entry belongs to
    val customerId: String,          // the buyer; balance is now per (seller, customer) pair
    val amountMinor: Long,           // in minor units (kuruş), always POSITIVE; type carries the sign
    val type: TransactionType,
    val description: String,         // "bread, milk" / "cash payment"
    val createdAt: String,           // ISO-8601 UTC: "2026-07-23T11:30:00Z" (sorts as text)

    /**
     * What was bought, when the sale came through the payment gateway. Null for a
     * money-only entry — most of them.
     *
     * This app never RAISES a basket (it has no gateway handoff), but it reads them: the
     * buyer is looking at the same entries the shop wrote, so the items have to survive
     * the trip to this side too.
     */
    val basket: OrderBody? = null
)

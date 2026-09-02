package com.example.app_pos.data.local

import com.example.app_pos.data.db.entity.ApprovalEntity
import com.example.app_pos.data.db.entity.OutboxEntity
import com.example.app_pos.model.FxSnapshot
import com.example.app_pos.model.Customer
import com.example.app_pos.model.PendingApproval
import com.example.app_pos.model.Repository
import com.example.app_pos.model.Transaction
import com.example.app_pos.model.User
import kotlinx.coroutines.flow.Flow

/**
 * What the composing repository needs from on-device storage.
 *
 * It is the full [Repository] surface plus [observeAllUsers]. The extra method exists
 * because OfflineFirstRepository resolves "who is signed in" from the PERSISTED session
 * rather than from the local source's own RAM stub, so it needs the user table unfiltered.
 *
 * Kept as an interface for one concrete reason: it makes the session logic testable without
 * Room. A JVM unit test can supply a trivial implementation and assert on the gate — the
 * thing this phase actually changes — instead of standing up a database to reach it.
 */
interface LocalSource : Repository {

    /** Every stored user, with no session filter applied. */
    fun observeAllUsers(): Flow<List<User>>

    /**
     * Writes a user EXACTLY as given, keeping the id the caller supplies.
     *
     * Distinct from [Repository.registerUser], which mints a fresh local UUID. That is
     * right when the device is the one inventing the account, and wrong when mirroring an
     * account the server already owns: the session carries the server's user id, and
     * observeCurrentUser matches the session against this table, so a locally invented id
     * would never match and the user would sign in to a blank profile.
     */
    suspend fun upsertUser(user: User)

    /**
     * Mirrors customer records the SERVER owns, keyed by its ids.
     *
     * The counterpart of [upsertUser] for the book, and needed for the same reason: since
     * customer records are created server-side, the only correct local copy is the one
     * written under the server's id. Upsert rather than insert — a name corrected on
     * another terminal, or a claim that has since happened, must not be silently dropped.
     */
    suspend fun storeCustomers(rows: List<Customer>)

    // --- the offline outbox ---------------------------------------------------

    /**
     * Books a ledger entry and queues it for the server in ONE database transaction, so a
     * crash can never leave an entry the server will not hear about (or a queued send for
     * an entry that was never booked).
     *
     * [sendPayload] is the serialised request body. It is built by the caller because
     * turning a domain object into a wire shape belongs to the network layer, not to
     * storage — this interface only promises to keep the string safe.
     */
    suspend fun addTransactionQueued(transaction: Transaction, sendPayload: String)

    /**
     * Mirrors an approval the SERVER raised, so the card shows on this device too.
     *
     * Separate from [Repository.requestApproval], which decides for itself whether to raise
     * a card or write the entry. Here the decision is already made and this only records it.
     */
    suspend fun insertPendingApproval(approval: PendingApproval, initiatorUserId: String)

    /**
     * Makes the local pending inbox match what the server just returned, in ONE database
     * transaction.
     *
     * Both halves are needed and neither is sufficient alone: [rows] adds the cards raised
     * on other devices, and the delete removes the ones answered on other devices. Doing
     * only the first is how a card outlives its own decision.
     *
     * One transaction because the two halves must not be observed apart — a Flow that
     * emitted after the delete but before the insert would blank the screen and then
     * repopulate it, which reads as a glitch on a poll that runs every fifteen seconds.
     *
     * Scoped to [targetUserId]: only rows awaiting THIS user are the server list's to
     * govern. Requests this user raised are pending elsewhere and must survive untouched.
     */
    suspend fun syncApprovals(rows: List<ApprovalEntity>, targetUserId: String)

    /**
     * Stores the ledger entries the server holds for this buyer, plus the customer records
     * they imply, in ONE transaction.
     *
     * The customer rows are DERIVED from the entries rather than fetched: `GET /customers`
     * is seller-scoped and a buyer may not call it, but an entry appearing in this buyer's
     * own history names a record that is theirs by definition. Without those rows every
     * buyer query returns nothing — they all filter through `claimedByUserId`.
     *
     * ADDITIVE on purpose: nothing is deleted. The ledger is append-only, so an entry
     * missing from a response was never withdrawn — the response was partial. (Approvals
     * are the opposite; see [syncApprovals].)
     */
    suspend fun storeBuyerLedger(entries: List<Transaction>, userId: String)

    /**
     * Stores ledger entries from THIS user's own book — the seller half of the pull.
     *
     * The mirror image of [storeBuyerLedger], and deliberately a separate method rather than
     * a flag on it. That one DERIVES a customer row per entry, because a buyer is never told
     * the record behind their own history; here the rows arrive properly filled from
     * `GET /customers` and were already written by [storeCustomers]. Deriving them again
     * would overwrite real names and numbers with blanks.
     *
     * ADDITIVE and keyed by the server's transaction id: re-pulling the same history is a
     * no-op rather than a duplicate, which is what makes polling safe. Nothing is deleted —
     * the ledger is append-only, so an entry missing from a response was never withdrawn.
     */
    suspend fun storeLedger(entries: List<Transaction>)

    /**
     * Records the shop name and phone carried on the debts response, so the Borçlarım list
     * can both label its rows and offer a way to call the shop.
     *
     * A buyer cannot read another account (`GET /users/{id}` does not exist), which is why
     * `/me/debts` denormalises `shop_name` — this stores it against a minimal seller row.
     */
    suspend fun storeShopNames(shopsBySellerId: Map<String, Pair<String, String?>>)

    /**
     * Which customer record this buyer holds in that seller's book, or null when they have
     * no shared history — there is nothing to pay against.
     */
    suspend fun customerIdForBuyerSeller(userId: String, sellerId: String): String?

    /**
     * Records that an approval was decided, WITHOUT touching the ledger.
     *
     * Distinct from [Repository.approvePending], which also books the entry. That is right
     * when the device itself is the authority (offline, tests), and wrong once the server
     * has already written its own copy: doing both writes the amount TWICE, and because the
     * two rows carry different generated ids, the ledger's insert-IGNORE cannot catch it.
     */
    suspend fun markApprovalDecided(approvalId: String, status: String)

    /**
     * Drops an approval this device should not be holding — the server says it belongs to
     * someone else, or was already answered elsewhere.
     *
     * Deleted rather than status-flipped because the real decision is not known here;
     * inventing one would put a false record in the trail. See ApprovalDao.delete.
     */
    suspend fun deleteApproval(approvalId: String)

    /** Rows still waiting to reach the server, oldest first. */
    suspend fun pendingOutbox(): List<OutboxEntity>

    /** Called when a write is accepted, or refused in a way retrying cannot fix. */
    suspend fun deleteOutbox(id: String)

    /** Bumps retryCount, so an entry that keeps failing becomes visible instead of silent. */
    suspend fun recordOutboxFailure(id: String)

    // observeUnsentCount() is inherited from Repository — the queue depth is a fact about
    // stored data, so the local source is where it is actually answered.

    /**
     * The most recent cached reading on or before [asOf], or null when there is none.
     *
     * The same fallback the server applies, so a cached Friday answers a Sunday without a
     * round trip. Rates for a past date never change, which is what makes caching them
     * indefinitely correct rather than merely convenient.
     */
    suspend fun cachedFxRate(asOf: String): FxSnapshot?

    /** Stores a reading under ITS OWN date, never under the date it was asked for. */
    suspend fun cacheFxRate(snapshot: FxSnapshot)
}

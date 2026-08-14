package com.example.app_pos.data.local

import com.example.app_pos.data.db.entity.ApprovalEntity
import com.example.app_pos.data.db.entity.OutboxEntity
import com.example.app_pos.model.Customer
import com.example.app_pos.model.OrderBody
import com.example.app_pos.model.Repository
import com.example.app_pos.model.Transaction
import com.example.app_pos.model.User
import kotlinx.coroutines.flow.Flow

/**
 * What the composing repository needs from on-device storage.
 *
 * It is the full [Repository] surface plus [observeAllUsers]. The extra method exists
 * because OfflineFirstRepository resolves "who is signed in" from the PERSISTED session
 * rather than from the local source's own RAM mock, so it needs the user table unfiltered.
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
     * would never match and the merchant would sign in to a blank profile.
     */
    suspend fun upsertUser(user: User)

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
    suspend fun addTransactionQueued(
        transaction: Transaction,
        orderBody: OrderBody?,
        sendPayload: String
    )

    // --- approvals (the incoming inbox) ----------------------------------------

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
     * repopulate it, which reads as a glitch on a poll.
     */
    suspend fun syncApprovals(rows: List<ApprovalEntity>, targetUserId: String)

    /**
     * Stores the customer records the server holds for this shop's book.
     *
     * Upsert, not insert-IGNORE: a name corrected on another device, or a claim that has
     * since happened, must overwrite the local copy. IGNORE would keep the stale row and the
     * correction would never appear.
     *
     * The balance carried on each record is deliberately NOT stored — it is derived from the
     * ledger everywhere in this app, and keeping a second copy is how two numbers start
     * disagreeing.
     */
    suspend fun storeCustomers(rows: List<Customer>)

    /**
     * Stores ledger entries the server holds.
     *
     * ADDITIVE and keyed by the server's transaction id: re-pulling the same history is a
     * no-op rather than a duplicate, which is what makes polling safe. Nothing is deleted —
     * the ledger is append-only, so an entry missing from a response was never withdrawn.
     */
    suspend fun storeLedger(entries: List<Transaction>)

    /**
     * Records that an approval was decided, WITHOUT touching the ledger.
     *
     * Distinct from [Repository.approvePending], which also books the entry. Doing both
     * writes the amount TWICE, and because the two rows carry different generated ids the
     * ledger's insert-IGNORE cannot catch it — the bug app-mobile shipped and fixed.
     */
    suspend fun markApprovalDecided(approvalId: String, status: String)

    /**
     * Drops an approval this device should not be holding — the server says it belongs to
     * someone else, or was already answered elsewhere.
     *
     * Deleted rather than status-flipped because the real decision is not known here;
     * inventing one would put a false record in the trail.
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
}

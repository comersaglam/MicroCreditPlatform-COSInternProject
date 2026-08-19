package com.example.app_pos.data.sync

import com.example.app_pos.data.db.toEntityOrNull
import com.example.app_pos.data.local.LocalSource
import com.example.app_pos.data.remote.RemoteDataSource
import com.example.app_pos.model.ROLE_SELLER
import com.example.app_pos.model.PullOutcome
import com.example.app_pos.network.ApiResult
import com.example.app_pos.network.isRetryable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads the server and makes local storage match — the mirror of [SyncEngine], which does
 * the same job in the write direction.
 *
 * Why this exists at all: until now the device only ever displayed rows it had written
 * itself, so a Room Flow was the whole read path. An approvals inbox breaks that — the rows
 * are authored on the COUNTERPARTY's device — and no amount of local observation can
 * discover them. Without a pull, "show the POS an approval raised on the phone" is not slow;
 * it is impossible.
 *
 * Identical to app-mobile's, deliberately: the pull rules do not depend on which app is
 * asking, and two divergent copies of this logic would be two chances to get rule 2 wrong.
 *
 * Three rules, and the second is the one that bites:
 *
 *  1. **The server's list is authoritative.** A row it does not return has been decided
 *     elsewhere and is deleted locally. Insert-only syncing is exactly how a card outlives
 *     its own decision and sits on screen forever.
 *  2. **Unreachable is NOT an empty list.** A failed request means this device does not
 *     know what changed, so local rows are left completely alone. Treating silence as "the
 *     server has nothing" would let a phone with no signal wipe its own inbox — the same
 *     mistake as inventing a decision nobody made.
 *  3. **A row this build cannot read is dropped, not guessed.** The type carries the sign
 *     of the amount, so an unreadable card must never become approvable.
 */
@Singleton
class PullEngine @Inject constructor(
    private val local: LocalSource,
    private val remote: RemoteDataSource
) {

    // One pull at a time. Two concurrent runs would each compute "what the server did not
    // return" from their own snapshot and then race to delete rows the other just inserted.
    private val mutex = Mutex()

    /**
     * Refreshes the pending inbox for [targetUserId].
     *
     * [targetUserId] is passed in rather than read here because the session lives in the
     * composing repository; this class only knows how to fetch and store. It also scopes
     * the delete: rows awaiting somebody ELSE are pending on their device, not stale here.
     *
     * Never throws — RemoteDataSource returns failures as values, and a poll that crashed
     * would take its caller's coroutine down every fifteen seconds.
     */
    suspend fun pullApprovals(targetUserId: String): PullOutcome = mutex.withLock {
        // SELLER only. One account holds both roles, so a shopkeeper who is also a customer
        // somewhere else has TWO inboxes, and only one of them is this terminal's business.
        // Without the filter, Ayşe Market asking this shop's OWNER to accept veresiye on
        // their personal account landed on the till — where answering it changed nothing
        // visible, because a personal debt appears on no POS screen.
        when (val result = remote.pendingApprovals(role = ROLE_SELLER)) {
            is ApiResult.Success -> {
                val rows = result.data.mapNotNull { it.toEntityOrNull() }
                local.syncApprovals(rows, targetUserId)
                PullOutcome.Refreshed(rows.size)
            }

            // Every branch below leaves storage untouched; they differ only in what the
            // caller should do next, which is why the outcome keeps them apart.
            is ApiResult.ApiError ->
                if (result.isRetryable()) PullOutcome.Unreachable
                else PullOutcome.Failed(result.message)

            is ApiResult.NetworkError -> PullOutcome.Unreachable

            // An answer we could not read. Not retryable, and nothing local should move on
            // the strength of a response this client did not understand.
            is ApiResult.UnexpectedError -> PullOutcome.Failed("Onaylar okunamadı")
        }
    }

    /**
     * Refreshes this shop's book: every customer in it, and their ledger entries.
     *
     * `GET /customers` already answers with a derived balance, but the entries are fetched
     * too — every screen past the list (a customer's history, the shop's total receivable)
     * is computed in SQL from the `transactions` rows, so storing only the summary would
     * leave those blank and place a server-computed number beside locally-computed ones that
     * disagree with it.
     *
     * **Additive, unlike [pullApprovals].** Nothing is deleted: the ledger is append-only, so
     * an entry missing from a response was not withdrawn — the response was partial.
     * Approvals are the opposite case, where absence IS the news.
     */
    suspend fun pullBook(): PullOutcome = mutex.withLock {
        val customers = when (val result = remote.customers()) {
            is ApiResult.Success -> result.data
            else -> return@withLock result.toFailureOutcome("Müşteriler okunamadı")
        }

        local.storeCustomers(customers)

        var stored = 0
        for (customer in customers) {
            when (val history = remote.transactionHistory(customer.customerId)) {
                is ApiResult.Success -> {
                    local.storeLedger(history.data)
                    stored += history.data.size
                }
                // Stop rather than report success: the customers already stored stay, and
                // the next poll finishes the job.
                else -> return@withLock history.toFailureOutcome("Geçmiş okunamadı")
            }
        }

        PullOutcome.Refreshed(customers.size + stored)
    }

    /**
     * Maps a failed call onto the outcome, keeping the distinction the whole design rests
     * on: retryable means "ask again later", anything else means the server refused.
     */
    private fun ApiResult<*>.toFailureOutcome(readFailedMessage: String): PullOutcome =
        when (this) {
            is ApiResult.ApiError ->
                if (isRetryable()) PullOutcome.Unreachable else PullOutcome.Failed(message)
            is ApiResult.NetworkError -> PullOutcome.Unreachable
            is ApiResult.UnexpectedError -> PullOutcome.Failed(readFailedMessage)
            // Success never reaches here; the callers branch on it first.
            is ApiResult.Success -> PullOutcome.Refreshed(0)
        }
}

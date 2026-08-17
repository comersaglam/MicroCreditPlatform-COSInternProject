package com.example.app_pos.data.sync

import com.example.app_pos.data.db.toEntityOrNull
import com.example.app_pos.data.local.LocalSource
import com.example.app_pos.data.remote.RemoteDataSource
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
        when (val result = remote.pendingApprovals()) {
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
     * Refreshes this user's OWN book: the customers in it and their ledger entries.
     *
     * The seller half of the pull, and the reason this app needed a second one at all. This
     * app is one account in two roles, and until now only the buyer half existed — so
     * Borçlarım was fed from the server while Müşterilerim read a table nothing ever filled.
     * The list showed only what this install had written itself, which is why it looked
     * empty on a clean device and half-full afterwards.
     *
     * Entries are fetched alongside the customers even though `GET /customers` already
     * answers with a balance: every screen past the list computes in SQL from `transactions`,
     * so storing only the summary leaves those blank and puts a server-computed number next
     * to locally-computed ones that disagree with it.
     *
     * **Additive, like [pullMyLedger] and unlike [pullApprovals].** Nothing is deleted: the
     * ledger is append-only, so an entry missing from a response was not withdrawn — the
     * response was partial.
     *
     * **A buyer-only account is not a failure.** The endpoint is a shop's book, so an account
     * that is not a seller gets 403 `not_a_seller` — the normal answer to a question it had
     * no business asking, not something to show the user. It reports [PullOutcome.Refreshed]
     * with nothing refreshed. Checking a local `isSeller` flag instead would be worse: a
     * stale flag would leave a newly-registered shop staring at an empty list until the next
     * sign-in.
     */
    suspend fun pullBook(): PullOutcome = mutex.withLock {
        val customers = when (val result = remote.customers()) {
            is ApiResult.Success -> result.data
            is ApiResult.ApiError ->
                if (result.code == CODE_NOT_A_SELLER) return@withLock PullOutcome.Refreshed(0)
                else return@withLock result.toFailureOutcome("Müşteriler okunamadı")
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
     * Refreshes what this buyer owes: the shops, their ledger entries, and the customer
     * records tying the two together.
     *
     * **Raw entries, not the `/me/debts` summary.** That endpoint answers with a balance per
     * shop, which would be enough to paint the Borçlarım list and nothing else — every other
     * buyer screen (a shop's history, the total, "which record do I hold here") is derived
     * in SQL from the `transactions` rows. Storing a summary would leave those screens empty
     * and put a server-computed number next to locally-computed ones that disagree with it.
     * So `/me/debts` is used only to LIST the shops, then each shop's entries are fetched.
     *
     * Customer rows are reconstructed from the entries rather than fetched: `GET /customers`
     * is seller-scoped and a buyer may not call it. The entries already name the record
     * (`customerId`), and a row appearing in this buyer's own history is by definition
     * theirs — which is exactly what the local queries need to know.
     *
     * **Additive, unlike [pullApprovals].** Nothing is deleted here: the ledger is
     * append-only, so an entry that vanished from a response was never withdrawn — the
     * response was partial. Approvals are the opposite (absence IS the news), and conflating
     * the two would have this method quietly erase ledger history on a truncated answer.
     */
    suspend fun pullMyLedger(userId: String): PullOutcome = mutex.withLock {
        val debts = when (val result = remote.myDebts()) {
            is ApiResult.Success -> result.data
            else -> return@withLock result.toFailureOutcome("Borçlar okunamadı")
        }

        // The shop NAMES come from this response, not from a user lookup: there is no
        // endpoint a buyer may call to read another account, and /me/debts already
        // denormalises shop_name for exactly this reason.
        local.storeShopNames(debts.associate { it.sellerId to (it.shopName to it.shopPhone) })

        var stored = 0
        for (debt in debts) {
            when (val history = remote.myHistoryWithSeller(debt.sellerId)) {
                is ApiResult.Success -> {
                    local.storeBuyerLedger(history.data, userId)
                    stored += history.data.size
                }
                // One unreachable shop must not discard the shops already stored, nor be
                // reported as success. Stop and let the next poll finish the job.
                else -> return@withLock history.toFailureOutcome("Borçlar okunamadı")
            }
        }

        PullOutcome.Refreshed(stored)
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

    private companion object {
        /** The server's refusal to a buyer-only account asking for a shop's book. */
        const val CODE_NOT_A_SELLER = "not_a_seller"
    }
}

package com.example.app_pos.model

import kotlinx.coroutines.flow.Flow

/**
 * The data-layer contract, in the pure domain module so both implementations satisfy
 * it: the in-memory [FakeRepository] (phase 1) and the Room-backed one (phase 3). The
 * app talks to THIS type, so swapping the fake for Room changes no ViewModel — the
 * whole point of keeping the surface identical.
 *
 * Methods mirror what app-pos's FakeRepository already exposes; only the backing
 * store changes. Reads return Flow so a write refreshes every screen at once.
 */
interface Repository {

    // --- session / auth ---
    fun isSessionValid(): Boolean
    fun currentSellerId(): String?
    fun observeCurrentUser(): Flow<User?>
    val isPairedWithApp: Flow<Boolean>
    /**
     * Asks the server to send a verification code. True when it accepted the number.
     *
     * Separate from [signIn] because the code arrives out of band: the merchant reads it
     * off their phone and types it back, so the two halves are two round trips with a
     * person in between.
     */
    suspend fun requestOtp(phone: String): OtpRequestResult

    /**
     * Verifies the code and persists the session.
     *
     * Whether the number has an account is the SERVER's answer, not a local lookup —
     * verify does not auto-register, so an unknown number comes back as
     * [SignInResult.NeedsRegister] and the caller registers as a separate step.
     */
    suspend fun signIn(phone: String, code: String): SignInResult

    suspend fun logout()
    suspend fun pairWithApp()

    // --- users ---
    suspend fun findUserByPhone(phone: String): User?
    suspend fun registerUser(phone: String, displayName: String, isSeller: Boolean = false): User
    suspend fun setSeller(userId: String, shopName: String, shopPhone: String? = null)
    suspend fun updateDisplayName(userId: String, displayName: String)
    suspend fun updateShopName(userId: String, shopName: String)

    // --- customers (seller-scoped) ---
    fun observeCustomers(sellerId: String): Flow<List<Customer>>
    suspend fun addCustomer(displayName: String, phone: String): String

    /**
     * What a phone number means to THIS seller, when they are about to book an entry.
     * A person is one Customer row system-wide (phone = identity), but ownership lives
     * in the ledger — so someone another shop knows is still new to this book and must
     * be reusable here rather than rejected.
     */
    suspend fun lookupCustomerForSeller(sellerId: String, phone: String): CustomerLookup
    suspend fun findCustomerById(sellerId: String, customerId: String): Customer?
    suspend fun findCustomerByPhone(sellerId: String, phone: String): Customer?

    // --- ledger ---
    fun observeTransactions(sellerId: String, customerId: String): Flow<List<Transaction>>
    fun observeTotalReceivableMinor(sellerId: String): Flow<Long>
    fun observeBalance(sellerId: String, customerId: String): Flow<Long>
    /**
     * Appends a ledger entry. If [orderBody] is present (a basket handoff), its basket
     * and items are stored and the entry is linked to them; a money-only entry passes
     * null. Idempotent by transactionId.
     *
     * Returns as soon as the entry is stored locally — never waits for the network. Where
     * a backend exists, the entry is also queued for it in the same database transaction
     * and delivered later by [syncNow].
     */
    suspend fun addTransaction(transaction: Transaction, orderBody: OrderBody? = null)

    // --- approvals (the incoming inbox: what is waiting on THIS shop's decision) ---

    /**
     * Requests awaiting this shop's answer — a buyer declaring a payment the shop must
     * confirm having received.
     *
     * The counterpart of the gate app-pos already writes THROUGH: until now the shop could
     * only send entries for approval, never answer one, so the buyer-initiated line had no
     * seller-side ending at all.
     */
    fun observePendingApprovals(userId: String): Flow<List<PendingApproval>>

    /**
     * Answers a pending approval. Approving is what writes the ledger entry on this path.
     *
     * Returns [DecisionOutcome] because not every refusal means "try again": a card that
     * belongs to someone else, or was already answered, can never succeed and is dropped
     * rather than left on screen forever.
     */
    suspend fun approvePending(approvalId: String): DecisionOutcome
    suspend fun rejectPending(approvalId: String): DecisionOutcome

    /**
     * Reads the pending approvals from the SERVER and makes the local table match.
     *
     * The first read path in this contract — every other read here is a Room Flow, which
     * worked only while the device was the sole author of what it displayed. An inbox is
     * written by the COUNTERPARTY's device, so it cannot be discovered by observing local
     * storage.
     *
     * The answer is authoritative: a row the server does not return has been decided
     * elsewhere and is removed locally. Safe to call repeatedly — polling does exactly that.
     */
    suspend fun refreshApprovals(): PullOutcome

    /**
     * Reads this shop's book from the server: the customers in it and their ledger entries.
     *
     * Separate from [refreshApprovals] because the two behave differently on a partial
     * answer. An approval missing from the server's list has been decided and is deleted
     * locally; a ledger entry missing from a response has NOT been withdrawn — the ledger is
     * append-only — so this one only ever adds. Sharing one method would turn that
     * distinction into a runtime flag instead of a compile-time one.
     */
    suspend fun refreshBook(): PullOutcome

    // --- sync ---

    /**
     * Pushes writes that have not reached the server yet, and reports what happened.
     *
     * Safe to call at any time: a no-op when nothing is queued, single-flight, and every
     * send is idempotent. Never throws — failures come back in the result, because the
     * caller that matters (the background sync) has to decide whether to try again.
     */
    suspend fun syncNow(): SyncOutcome

    /** How many writes are still unsent; 0 means the device and server agree. */
    fun observeUnsentCount(): Flow<Int>
}

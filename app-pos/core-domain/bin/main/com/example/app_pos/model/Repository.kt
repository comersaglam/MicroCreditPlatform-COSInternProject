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
    suspend fun login(phone: String?): Boolean
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

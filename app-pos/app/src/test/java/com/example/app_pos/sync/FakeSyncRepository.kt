package com.example.app_pos.sync

import com.example.app_pos.model.Customer
import com.example.app_pos.model.CustomerLookup
import com.example.app_pos.model.OrderBody
import com.example.app_pos.model.Repository
import com.example.app_pos.model.SignInResult
import com.example.app_pos.model.SyncOutcome
import com.example.app_pos.model.Transaction
import com.example.app_pos.model.User
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * A [Repository] that answers only what SyncWorker asks: is there a session, and what did a
 * drain achieve.
 *
 * :core-data has its own fakes, but they live in that module's test source set and are not
 * visible here. This one is deliberately smaller — the Worker is a decision table over two
 * inputs, and simulating a ledger would only obscure that.
 */
class FakeSyncRepository(
    private val sessionValid: Boolean = true,
    private val outcome: SyncOutcome = SyncOutcome(),
    /** When set, syncNow throws it — the "something went unexpectedly wrong" branch. */
    private val failWith: Throwable? = null
) : Repository {

    /** How many times a drain was requested. 0 proves the session guard short-circuited. */
    var syncCallCount = 0
        private set

    override fun isSessionValid(): Boolean = sessionValid

    override suspend fun syncNow(): SyncOutcome {
        syncCallCount++
        failWith?.let { throw it }
        return outcome
    }

    // --- not exercised by the Worker ------------------------------------------

    override fun currentSellerId(): String? = null
    override fun observeCurrentUser(): Flow<User?> = flowOf(null)
    override val isPairedWithApp: Flow<Boolean> = flowOf(false)
    override suspend fun requestOtp(phone: String): Boolean = false

    override suspend fun signIn(phone: String, code: String): SignInResult =
        SignInResult.Unreachable
    override suspend fun logout() = Unit
    override suspend fun pairWithApp() = Unit
    override suspend fun findUserByPhone(phone: String): User? = null
    override suspend fun registerUser(phone: String, displayName: String, isSeller: Boolean): User =
        error("not used")
    override suspend fun setSeller(userId: String, shopName: String, shopPhone: String?) = Unit
    override suspend fun updateDisplayName(userId: String, displayName: String) = Unit
    override suspend fun updateShopName(userId: String, shopName: String) = Unit
    override fun observeCustomers(sellerId: String): Flow<List<Customer>> = flowOf(emptyList())
    override suspend fun addCustomer(displayName: String, phone: String): String = ""
    override suspend fun lookupCustomerForSeller(sellerId: String, phone: String): CustomerLookup =
        CustomerLookup.New
    override suspend fun findCustomerById(sellerId: String, customerId: String): Customer? = null
    override suspend fun findCustomerByPhone(sellerId: String, phone: String): Customer? = null
    override fun observeTransactions(sellerId: String, customerId: String): Flow<List<Transaction>> =
        flowOf(emptyList())
    override fun observeTotalReceivableMinor(sellerId: String): Flow<Long> = flowOf(0L)
    override fun observeBalance(sellerId: String, customerId: String): Flow<Long> = flowOf(0L)
    override suspend fun addTransaction(transaction: Transaction, orderBody: OrderBody?) = Unit
    override fun observeUnsentCount(): Flow<Int> = flowOf(0)
}

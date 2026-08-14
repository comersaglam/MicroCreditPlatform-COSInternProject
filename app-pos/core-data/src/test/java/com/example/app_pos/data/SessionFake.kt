package com.example.app_pos.data

import com.example.app_pos.data.db.entity.ApprovalEntity
import com.example.app_pos.data.db.entity.OutboxEntity
import com.example.app_pos.data.local.LocalSource
import com.example.app_pos.model.Customer
import com.example.app_pos.model.DecisionOutcome
import com.example.app_pos.model.CustomerLookup
import com.example.app_pos.model.OrderBody
import com.example.app_pos.model.OtpRequestResult
import com.example.app_pos.model.PendingApproval
import com.example.app_pos.model.PullOutcome
import com.example.app_pos.model.SyncOutcome
import com.example.app_pos.model.Transaction
import com.example.app_pos.model.SignInResult
import com.example.app_pos.model.User
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf

/**
 * A [LocalSource] holding a fixed user list and nothing else.
 *
 * The session tests only exercise the gate — which user the persisted token resolves to —
 * so the ledger side is stubbed rather than simulated. Standing up Room to reach that logic
 * would test Room, not the composition this phase introduces.
 */
class FakeLocalSource(users: List<User> = emptyList()) : LocalSource {

    private val _users = MutableStateFlow(users)

    /** Set by [logout], so a test can assert the local half was notified too. */
    var loggedOut = false
        private set

    override fun observeAllUsers(): Flow<List<User>> = _users.asStateFlow()

    override suspend fun findUserByPhone(phone: String): User? =
        _users.value.firstOrNull { trailingDigits(it.phone) == trailingDigits(phone) }

    /**
     * The last 10 digits, so "+905554443322" and "05554443322" are the same person — the
     * Kotlin stand-in for the DAO's REPLACE-based digit match. Without it every sign-in
     * test would fail on a formatting difference rather than on the logic under test.
     */
    private fun trailingDigits(phone: String): String =
        phone.filter { it.isDigit() }.takeLast(10)

    override suspend fun logout() {
        loggedOut = true
    }

    // --- outbox ---------------------------------------------------------------
    // A list, not a database, but it keeps the two properties the drain depends on:
    // insertion order, and idempotency by id (re-queueing the same entry is a no-op).

    private val queue = mutableListOf<OutboxEntity>()

    /** Retry counts by entry id, so a test can assert a failure was recorded. */
    val retryCounts = mutableMapOf<String, Int>()

    override suspend fun addTransactionQueued(
        transaction: Transaction,
        orderBody: OrderBody?,
        sendPayload: String
    ) {
        if (queue.none { it.id == transaction.transactionId }) {
            queue += OutboxEntity(
                id = transaction.transactionId,
                transactionId = transaction.transactionId,
                payload = sendPayload,
                createdAt = transaction.createdAt,
                retryCount = 0
            )
        }
    }

    /** Seeds the queue directly, for tests about draining rather than about enqueueing. */
    fun enqueueRaw(entry: OutboxEntity) {
        queue += entry
    }

    // Sorted, not insertion-ordered: the real DAO is "ORDER BY createdAt", and a test that
    // relied on insertion order would pass here while the DAO did something else.
    override suspend fun pendingOutbox(): List<OutboxEntity> = queue.sortedBy { it.createdAt }

    override suspend fun deleteOutbox(id: String) {
        queue.removeAll { it.id == id }
    }

    override suspend fun recordOutboxFailure(id: String) {
        retryCounts[id] = (retryCounts[id] ?: 0) + 1
    }

    override fun observeUnsentCount(): Flow<Int> = flowOf(queue.size)

    // Draining needs a remote source; the composing repository overrides this.
    override suspend fun syncNow(): SyncOutcome = SyncOutcome()

    // --- approvals: out of scope for the session tests, but part of the contract ---

    /** The rows the last sync stored, or null when storage was never touched. */
    var syncedApprovals: List<ApprovalEntity>? = null
        private set

    override fun observePendingApprovals(userId: String): Flow<List<PendingApproval>> =
        flowOf(emptyList())

    override suspend fun approvePending(approvalId: String): DecisionOutcome =
        DecisionOutcome.Unreachable

    override suspend fun rejectPending(approvalId: String): DecisionOutcome =
        DecisionOutcome.Unreachable

    override suspend fun refreshApprovals(): PullOutcome = PullOutcome.Unreachable

    /** The rows the last storeCustomers stored, or null when it was never called. */
    var storedCustomers: List<Customer>? = null
        private set

    /** Entries the last storeLedger stored, or null when it was never called. */
    var storedLedger: List<Transaction>? = null
        private set

    override suspend fun refreshBook(): PullOutcome = PullOutcome.Unreachable

    override suspend fun storeCustomers(rows: List<Customer>) { storedCustomers = rows }

    override suspend fun storeLedger(entries: List<Transaction>) { storedLedger = entries }

    override suspend fun syncApprovals(rows: List<ApprovalEntity>, targetUserId: String) {
        syncedApprovals = rows
    }

    override suspend fun markApprovalDecided(approvalId: String, status: String) = Unit

    override suspend fun deleteApproval(approvalId: String) = Unit

    // --- the rest is out of scope for the session tests -----------------------

    override fun isSessionValid(): Boolean = false
    override fun currentSellerId(): String? = null
    override fun observeCurrentUser(): Flow<User?> = flowOf(null)
    override val isPairedWithApp: Flow<Boolean> = flowOf(false)
    override suspend fun requestOtp(phone: String): OtpRequestResult = OtpRequestResult.Unreachable

    override suspend fun signIn(phone: String, code: String): SignInResult =
        SignInResult.Unreachable
    override suspend fun upsertUser(user: User) {
        _users.value = _users.value.filterNot { it.userId == user.userId } + user
    }

    override suspend fun pairWithApp() = Unit
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
}

/** The seed's shopkeeper, the number the device tests sign in with. */
fun testUser(
    userId: String = "u_owner",
    phone: String = "+905554443322"
): User = User(
    userId = userId,
    phone = phone,
    displayName = "Ahmet Demirtaş",
    isBuyer = true,
    isSeller = true,
    email = null,
    sellerInfo = null,
    createdAt = "2026-07-01T09:00:00Z"
)

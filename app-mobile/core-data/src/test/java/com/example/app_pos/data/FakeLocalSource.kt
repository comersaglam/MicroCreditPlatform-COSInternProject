package com.example.app_pos.data

import com.example.app_pos.data.db.entity.OutboxEntity
import com.example.app_pos.data.local.LocalSource
import com.example.app_pos.model.ApprovalOutcome
import com.example.app_pos.model.Customer
import com.example.app_pos.model.CustomerLookup
import com.example.app_pos.model.DecisionOutcome
import com.example.app_pos.model.PendingApproval
import com.example.app_pos.model.SellerDebt
import com.example.app_pos.model.SignInResult
import com.example.app_pos.model.SyncOutcome
import com.example.app_pos.model.Transaction
import com.example.app_pos.model.TransactionType
import com.example.app_pos.model.User
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * An in-memory [LocalSource] that records what the composing repository asked it to store.
 *
 * The point is the ASSERTIONS it enables: [ledger] and [approvals] are plain lists, so a
 * test can say "exactly one entry was written" — which is the property the double-write bug
 * broke and the one a regression must catch. Standing up Room to check that would test
 * SQLite, not the decision.
 *
 * Only what these tests exercise is implemented; the rest satisfies the interface.
 */
class FakeLocalSource(
    private val customer: Customer? = null,
    private val buyerCustomerId: String? = null
) : LocalSource {

    /** Every entry booked, in order. Its SIZE is what the double-write test asserts on. */
    val ledger = mutableListOf<Transaction>()

    /** Approval cards mirrored locally. */
    val approvals = mutableListOf<PendingApproval>()

    /** Entries that were also queued for the server (addTransaction vs addTransactionQueued). */
    val queued = mutableListOf<Transaction>()

    /** approvalId to status, as recorded by markApprovalDecided. */
    val decided = mutableMapOf<String, String>()

    /** Cards dropped as stale — the server said they are not this user's to answer. */
    val deletedApprovals = mutableListOf<String>()

    /** Set when the local (offline) approval branch ran — it must not on the online path. */
    var localRequestApprovalCalls = 0
        private set

    override suspend fun addTransaction(transaction: Transaction) {
        ledger += transaction
    }

    override suspend fun addTransactionQueued(transaction: Transaction, sendPayload: String) {
        ledger += transaction
        queued += transaction
    }

    override suspend fun insertPendingApproval(approval: PendingApproval, initiatorUserId: String) {
        approvals += approval
    }

    override suspend fun markApprovalDecided(approvalId: String, status: String) {
        decided[approvalId] = status
    }

    override suspend fun deleteApproval(approvalId: String) {
        deletedApprovals += approvalId
    }

    override suspend fun customerIdForBuyerSeller(userId: String, sellerId: String): String? =
        buyerCustomerId

    override suspend fun findCustomerById(sellerId: String, customerId: String): Customer? = customer

    /**
     * The offline fallback. Books the entry the way the real one does, so a test that
     * expects the online path can prove this did NOT also run.
     */
    override suspend fun requestApproval(
        fromUserId: String,
        sellerId: String,
        customerId: String,
        amountMinor: Long,
        type: TransactionType,
        description: String
    ): ApprovalOutcome {
        localRequestApprovalCalls++
        return ApprovalOutcome.WrittenImmediately
    }

    /**
     * The version that ALSO writes the ledger — the one the composing repository must not
     * call after the server has already booked the entry.
     */
    override suspend fun approvePending(approvalId: String): DecisionOutcome {
        ledger += Transaction(
            transactionId = "local-$approvalId",
            sellerId = "s",
            customerId = "c",
            amountMinor = 1L,
            type = TransactionType.DEBT,
            description = "local approve",
            createdAt = "2026-08-13T00:00:00Z"
        )
        decided[approvalId] = "APPROVED"
        return DecisionOutcome.Applied
    }

    // --- not exercised --------------------------------------------------------

    override fun observeAllUsers(): Flow<List<User>> = flowOf(emptyList())
    override suspend fun upsertUser(user: User) = Unit
    override suspend fun pendingOutbox(): List<OutboxEntity> = emptyList()
    override suspend fun deleteOutbox(id: String) = Unit
    override suspend fun recordOutboxFailure(id: String) = Unit
    override fun observeUnsentCount(): Flow<Int> = flowOf(0)
    override suspend fun syncNow(): SyncOutcome = SyncOutcome()

    override fun isSessionValid(): Boolean = true
    override fun currentUserId(): String? = "u1"
    override fun observeCurrentUser(): Flow<User?> = flowOf(null)
    override val isPairedWithApp: Flow<Boolean> = flowOf(false)
    override suspend fun requestOtp(phone: String): Boolean = false
    override suspend fun signIn(phone: String, code: String): SignInResult = SignInResult.NeedsRegister
    override suspend fun logout() = Unit
    override suspend fun pairWithApp() = Unit

    override suspend fun findUserByPhone(phone: String): User? = null
    override suspend fun registerUser(phone: String, displayName: String, isSeller: Boolean): User =
        error("not used")
    override suspend fun setSeller(userId: String, shopName: String, shopPhone: String?) = Unit
    override suspend fun updateShopName(userId: String, shopName: String) = Unit
    override suspend fun updateDisplayName(userId: String, displayName: String) = Unit
    override suspend fun updateEmail(userId: String, email: String) = Unit
    override suspend fun shopNameOf(sellerId: String): String = "Shop"
    override suspend fun shopPhoneOf(sellerId: String): String? = null
    override suspend fun claimCustomerForUser(userId: String, phone: String): List<Customer> = emptyList()

    override fun observeCustomers(sellerId: String): Flow<List<Customer>> = flowOf(emptyList())
    override suspend fun addCustomer(displayName: String, phone: String): String = ""
    override suspend fun findCustomerByPhone(sellerId: String, phone: String): Customer? = null
    override suspend fun lookupCustomerForSeller(sellerId: String, phone: String): CustomerLookup =
        CustomerLookup.New

    override fun observeTransactions(sellerId: String, customerId: String): Flow<List<Transaction>> =
        flowOf(emptyList())
    override fun observeTotalReceivableMinor(sellerId: String): Flow<Long> = flowOf(0L)
    override fun observeMyDebtsBySeller(userId: String): Flow<List<SellerDebt>> = flowOf(emptyList())
    override fun observeMyTotalDebtMinor(userId: String): Flow<Long> = flowOf(0L)
    override fun observeMyTransactions(userId: String, sellerId: String): Flow<List<Transaction>> =
        flowOf(emptyList())
    override fun observeMyBalanceWithSeller(userId: String, sellerId: String): Flow<Long> = flowOf(0L)
    override fun observePendingApprovals(userId: String): Flow<List<PendingApproval>> = flowOf(emptyList())
    override suspend fun rejectPending(approvalId: String): DecisionOutcome =
        DecisionOutcome.Applied
    override suspend fun initiatePayment(
        userId: String,
        sellerId: String,
        amountMinor: Long
    ): ApprovalOutcome = ApprovalOutcome.NoCustomerRecord
}

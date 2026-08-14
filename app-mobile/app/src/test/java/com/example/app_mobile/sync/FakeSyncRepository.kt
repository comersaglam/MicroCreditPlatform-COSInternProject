package com.example.app_mobile.sync

import com.example.app_pos.model.ApprovalOutcome
import com.example.app_pos.model.Customer
import com.example.app_pos.model.CustomerLookup
import com.example.app_pos.model.DecisionOutcome
import com.example.app_pos.model.PendingApproval
import com.example.app_pos.model.PullOutcome
import com.example.app_pos.model.OtpRequestResult
import com.example.app_pos.model.Repository
import com.example.app_pos.model.SellerDebt
import com.example.app_pos.model.SignInResult
import com.example.app_pos.model.SyncOutcome
import com.example.app_pos.model.Transaction
import com.example.app_pos.model.TransactionType
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
 *
 * Mirrors app-pos's fake of the same name; the extra members are app-mobile's buyer-scoped
 * and approval surface, none of which the Worker touches.
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

    override fun currentUserId(): String? = null
    override fun observeCurrentUser(): Flow<User?> = flowOf(null)
    override val isPairedWithApp: Flow<Boolean> = flowOf(false)
    override suspend fun requestOtp(phone: String): OtpRequestResult = OtpRequestResult.Unreachable
    override suspend fun signIn(phone: String, code: String): SignInResult = SignInResult.Unreachable
    override suspend fun logout() = Unit
    override suspend fun pairWithApp() = Unit

    override suspend fun findUserByPhone(phone: String): User? = null
    override suspend fun registerUser(phone: String, displayName: String, isSeller: Boolean): User =
        error("not used")
    override suspend fun setSeller(userId: String, shopName: String, shopPhone: String?) = Unit
    override suspend fun updateShopName(userId: String, shopName: String) = Unit
    override suspend fun updateDisplayName(userId: String, displayName: String) = Unit
    override suspend fun updateEmail(userId: String, email: String) = Unit
    override suspend fun shopNameOf(sellerId: String): String = sellerId
    override suspend fun shopPhoneOf(sellerId: String): String? = null
    override suspend fun claimCustomerForUser(userId: String, phone: String): List<Customer> =
        emptyList()

    override fun observeCustomers(sellerId: String): Flow<List<Customer>> = flowOf(emptyList())
    override suspend fun addCustomer(displayName: String, phone: String): String = ""
    override suspend fun findCustomerById(sellerId: String, customerId: String): Customer? = null
    override suspend fun findCustomerByPhone(sellerId: String, phone: String): Customer? = null
    override suspend fun lookupCustomerForSeller(sellerId: String, phone: String): CustomerLookup =
        error("not used")

    override fun observeTransactions(sellerId: String, customerId: String): Flow<List<Transaction>> =
        flowOf(emptyList())
    override fun observeTotalReceivableMinor(sellerId: String): Flow<Long> = flowOf(0L)

    override fun observeMyDebtsBySeller(userId: String): Flow<List<SellerDebt>> = flowOf(emptyList())
    override fun observeMyTotalDebtMinor(userId: String): Flow<Long> = flowOf(0L)
    override fun observeMyTransactions(userId: String, sellerId: String): Flow<List<Transaction>> =
        flowOf(emptyList())
    override fun observeMyBalanceWithSeller(userId: String, sellerId: String): Flow<Long> = flowOf(0L)

    override fun observePendingApprovals(userId: String): Flow<List<PendingApproval>> =
        flowOf(emptyList())
    override suspend fun approvePending(approvalId: String): DecisionOutcome =
        DecisionOutcome.Applied
    override suspend fun rejectPending(approvalId: String): DecisionOutcome =
        DecisionOutcome.Applied
    override suspend fun requestApproval(
        fromUserId: String,
        sellerId: String,
        customerId: String,
        amountMinor: Long,
        type: TransactionType,
        description: String
    ): ApprovalOutcome = ApprovalOutcome.Failed()
    override suspend fun initiatePayment(
        userId: String,
        sellerId: String,
        amountMinor: Long
    ): ApprovalOutcome = ApprovalOutcome.NoCustomerRecord

    override suspend fun addTransaction(transaction: Transaction) = Unit
    override fun observeUnsentCount(): Flow<Int> = flowOf(0)

    // The SyncWorker drains only; pulling is a foreground job on this app, so nothing
    // under test here calls this.
    override suspend fun refreshApprovals(): PullOutcome = PullOutcome.Unreachable
    override suspend fun refreshMyLedger(): PullOutcome = PullOutcome.Unreachable
}

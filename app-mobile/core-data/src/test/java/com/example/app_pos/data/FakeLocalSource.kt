package com.example.app_pos.data

import com.example.app_pos.data.db.entity.ApprovalEntity
import com.example.app_pos.data.db.entity.OutboxEntity
import com.example.app_pos.data.local.LocalSource
import com.example.app_pos.model.FxSnapshot
import com.example.app_pos.model.CustomerCreateOutcome
import com.example.app_pos.model.ApprovalOutcome
import com.example.app_pos.model.Customer
import com.example.app_pos.model.CustomerLookup
import com.example.app_pos.model.DecisionOutcome
import com.example.app_pos.model.OtpRequestResult
import com.example.app_pos.model.PendingApproval
import com.example.app_pos.model.PullOutcome
import com.example.app_pos.model.SellerDebt
import com.example.app_pos.model.SignInResult
import com.example.app_pos.model.SyncOutcome
import com.example.app_pos.model.Transaction
import com.example.app_pos.model.TransactionDetail
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

    /**
     * The rows the last [syncApprovals] stored, or null when it was never called.
     *
     * Null and empty-list mean opposite things here and the pull tests turn on the
     * difference: empty is "the server says nothing is pending, clear the inbox", null is
     * "storage was never touched", which is the only correct behaviour when the server
     * could not be reached.
     */
    var syncedApprovals: List<ApprovalEntity>? = null
        private set

    /** Who the last sync was scoped to — rows awaiting anyone else must survive it. */
    var syncedForUserId: String? = null
        private set

    override suspend fun syncApprovals(rows: List<ApprovalEntity>, targetUserId: String) {
        syncedApprovals = rows
        syncedForUserId = targetUserId
    }

    /** Storage has no network; the composing repository is what actually pulls. */
    override suspend fun refreshApprovals(): PullOutcome = PullOutcome.Unreachable

    override suspend fun refreshMyLedger(): PullOutcome = PullOutcome.Unreachable

    override suspend fun refreshBook(): PullOutcome = PullOutcome.Unreachable

    /** Entries the last storeBuyerLedger stored, or null when it was never called. */
    var storedBuyerLedger: List<Transaction>? = null
        private set

    /** Shop names the last storeShopNames stored, or null when it was never called. */
    var storedShopNames: Map<String, Pair<String, String?>>? = null
        private set

    /**
     * Everything storeLedger has stored, ACCUMULATED across calls — the book pull calls it
     * once per customer, so overwriting would hide every customer but the last.
     */
    val storedLedger: MutableList<Transaction> = mutableListOf()

    override suspend fun storeBuyerLedger(entries: List<Transaction>, userId: String) {
        storedBuyerLedger = entries
    }

    override suspend fun storeLedger(entries: List<Transaction>) {
        storedLedger += entries
    }

    override suspend fun storeShopNames(shopsBySellerId: Map<String, Pair<String, String?>>) {
        storedShopNames = shopsBySellerId
    }

    /**
     * Served from what was actually stored, not from a canned value.
     *
     * That is the point: the real source keeps the basket in its own tables and rebuilds it
     * on the way out, so a fake that answered null here would let a test pass while the
     * pull went on dropping baskets -- which is the exact defect this turn exists to fix.
     */
    override suspend fun transactionDetail(transactionId: String): TransactionDetail? =
        (ledger + storedLedger + storedBuyerLedger.orEmpty())
            .firstOrNull { it.transactionId == transactionId }
            ?.let { TransactionDetail(it, it.basket) }

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

    /** Customer rows mirrored from the server — what a claim or a create stores. */
    val storedCustomers = mutableListOf<Customer>()

    override suspend fun storeCustomers(rows: List<Customer>) {
        storedCustomers += rows
    }
    override suspend fun pendingOutbox(): List<OutboxEntity> = emptyList()
    override suspend fun deleteOutbox(id: String) = Unit
    override suspend fun recordOutboxFailure(id: String) = Unit
    override fun observeUnsentCount(): Flow<Int> = flowOf(0)
    override suspend fun syncNow(): SyncOutcome = SyncOutcome()

    override fun isSessionValid(): Boolean = true
    override fun currentUserId(): String? = "u1"
    override fun observeCurrentUser(): Flow<User?> = flowOf(null)
    override val isPairedWithApp: Flow<Boolean> = flowOf(false)
    override suspend fun requestOtp(phone: String): OtpRequestResult = OtpRequestResult.Unreachable
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
    override fun observeShopPhone(sellerId: String): Flow<String?> = flowOf(null)
    override suspend fun claimCustomerForUser(userId: String, phone: String): List<Customer> = emptyList()

    override fun observeCustomers(sellerId: String): Flow<List<Customer>> = flowOf(emptyList())
    override suspend fun addCustomer(displayName: String, phone: String): CustomerCreateOutcome =
        CustomerCreateOutcome.Created("c_test")
    override suspend fun findCustomerByPhone(sellerId: String, phone: String): Customer? = null
    override suspend fun lookupCustomerForSeller(sellerId: String, phone: String): CustomerLookup =
        CustomerLookup.New

    override fun observeTransactions(sellerId: String, customerId: String): Flow<List<Transaction>> =
        flowOf(emptyList())
    override fun observeTotalReceivableMinor(sellerId: String): Flow<Long> = flowOf(0L)

    // Only the trend line reads these, and no test asserts on a sparkline.
    override fun observeAllForSeller(sellerId: String): Flow<List<Transaction>> =
        flowOf(emptyList())
    override fun observeMyDebtsBySeller(userId: String): Flow<List<SellerDebt>> = flowOf(emptyList())
    override fun observeMyTotalDebtMinor(userId: String): Flow<Long> = flowOf(0L)

    override fun observeAllForBuyer(userId: String): Flow<List<Transaction>> =
        flowOf(emptyList())
    override fun observeMyTransactions(userId: String, sellerId: String): Flow<List<Transaction>> =
        flowOf(emptyList())
    override fun observeMyBalanceWithSeller(userId: String, sellerId: String): Flow<Long> = flowOf(0L)
    override fun observePendingApprovals(userId: String): Flow<List<PendingApproval>> = flowOf(emptyList())
    override suspend fun rejectPending(approvalId: String): DecisionOutcome =
        DecisionOutcome.Applied
    /** Storage cannot reach a till; the composing repository sends this to the server. */
    override suspend fun collectAtTerminal(customerId: String, amountMinor: Long): Boolean = false

    override suspend fun initiatePayment(
        userId: String,
        sellerId: String,
        amountMinor: Long
    ): ApprovalOutcome = ApprovalOutcome.NoCustomerRecord

    // The fx cache is not what these tests are about; an empty series is the honest stub.
    override suspend fun fxRateAt(asOf: String): FxSnapshot? = null
    override suspend fun cachedFxRate(asOf: String): FxSnapshot? = null
    override suspend fun cacheFxRate(snapshot: FxSnapshot) = Unit
}

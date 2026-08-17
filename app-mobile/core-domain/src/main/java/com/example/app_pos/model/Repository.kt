package com.example.app_pos.model

import kotlinx.coroutines.flow.Flow

/**
 * The data-layer contract, in the pure domain module so the app talks to THIS type
 * rather than to a concrete store. Swapping the in-memory FakeRepository (phase 1)
 * for the Room-backed one (phase 3) therefore changes no ViewModel.
 *
 * app-mobile is one app in TWO roles, so the surface has two reading directions over
 * the same ledger: buyer-scoped (my debts, per shop) and seller-scoped (my customers).
 * Both are additive and never conflict.
 *
 * Reads return Flow so a write refreshes every screen at once. Writes are suspend
 * (they hit the database); the session pair below is deliberately NOT — see there.
 */
interface Repository {

    // --- session / auth ---
    /**
     * Session reads stay SYNCHRONOUS on purpose: they are served from a RAM cache primed
     * once at startup, not from disk or the network. MainActivity picks the nav graph's
     * start destination from it before the graph exists, which a suspend call could not
     * do without a login flash.
     */
    fun isSessionValid(): Boolean
    fun currentUserId(): String?
    fun observeCurrentUser(): Flow<User?>
    val isPairedWithApp: Flow<Boolean>

    /** Asks the server to send a code. False when it refused or could not be reached. */
    suspend fun requestOtp(phone: String): OtpRequestResult

    /**
     * Verifies the code and persists the session the server returns.
     *
     * Two steps rather than one `login(phone)` because the code is judged by the SERVER,
     * and so is whether the number has an account at all. [SignInResult] keeps those
     * outcomes apart: a wrong code is retried on the same screen, an unknown number opens
     * the sign-up prompt, and a dead network is nobody's mistake.
     */
    suspend fun signIn(phone: String, code: String): SignInResult

    suspend fun logout()
    suspend fun pairWithApp()

    // --- users / profile ---
    suspend fun findUserByPhone(phone: String): User?
    suspend fun registerUser(phone: String, displayName: String, isSeller: Boolean = false): User
    suspend fun setSeller(userId: String, shopName: String, shopPhone: String? = null)
    suspend fun updateShopName(userId: String, shopName: String)
    suspend fun updateDisplayName(userId: String, displayName: String)
    suspend fun updateEmail(userId: String, email: String)
    /** The shop's display name, falling back to the id when it has none yet. */
    suspend fun shopNameOf(sellerId: String): String
    /** The shop's contact number, or null when the seller has not set one. */
    suspend fun shopPhoneOf(sellerId: String): String?
    /**
     * The shop's contact number as a Flow, for screens that outlive the first read.
     *
     * The one-shot [shopPhoneOf] above is right for a caller that asks once and acts on the
     * answer. It is wrong for a screen: a buyer learns a shop's number from the ledger pull,
     * so opening the detail before that pull lands reads null and — with a one-shot read —
     * keeps showing nothing for as long as the screen stays open, no matter what arrives
     * afterwards. Observing the row instead lets the number appear when it does.
     */
    fun observeShopPhone(sellerId: String): Flow<String?>

    // --- claim (the bridge between a Customer record and a User account) ---
    /**
     * Links every UNCLAIMED customer record holding this phone to the user, so a
     * merchant's existing book (and its debt) is inherited on first sign-in.
     */
    suspend fun claimCustomerForUser(userId: String, phone: String): List<Customer>

    // --- customers (seller-scoped: the merchant surface) ---
    fun observeCustomers(sellerId: String): Flow<List<Customer>>
    /**
     * Opens a customer record. The SERVER mints the id, so this can fail.
     *
     * Returns an outcome rather than a String because "could not reach the server"
     * and "here is your customer" are different answers, and the caller must not
     * write a ledger entry on the strength of the first one.
     */
    suspend fun addCustomer(displayName: String, phone: String): CustomerCreateOutcome
    suspend fun findCustomerById(sellerId: String, customerId: String): Customer?
    suspend fun findCustomerByPhone(sellerId: String, phone: String): Customer?

    /**
     * What a phone number means to THIS seller, when they are about to book an entry.
     * A person is one Customer row system-wide (phone = identity), but ownership lives
     * in the ledger — so someone another shop knows is still new to this book and must
     * be reusable here rather than rejected.
     */
    suspend fun lookupCustomerForSeller(sellerId: String, phone: String): CustomerLookup

    // --- ledger (seller-scoped) ---
    fun observeTransactions(sellerId: String, customerId: String): Flow<List<Transaction>>
    fun observeTotalReceivableMinor(sellerId: String): Flow<Long>

    // --- ledger (buyer-scoped: the mirror of the reads above) ---
    /** This buyer's debts grouped by shop — one row per seller they owe. */
    fun observeMyDebtsBySeller(userId: String): Flow<List<SellerDebt>>
    fun observeMyTotalDebtMinor(userId: String): Flow<Long>
    fun observeMyTransactions(userId: String, sellerId: String): Flow<List<Transaction>>
    fun observeMyBalanceWithSeller(userId: String, sellerId: String): Flow<Long>

    // --- approvals (every write passes through here) ---
    fun observePendingApprovals(userId: String): Flow<List<PendingApproval>>

    /**
     * Reads the pending approvals from the SERVER and makes the local table match.
     *
     * The first read path in this contract: everything else here is a Room Flow, because
     * until now the device was the only author of what it displayed. An inbox breaks that
     * assumption — the rows are written by the OTHER party's device — so the screen cannot
     * be correct without asking.
     *
     * The answer is authoritative: a row the server does not return has been decided
     * somewhere else and is removed locally. That is what stops a card from lingering after
     * the counterparty already answered it.
     *
     * Safe to call repeatedly — a foreground poll does exactly that.
     */
    suspend fun refreshApprovals(): PullOutcome

    /**
     * Reads this buyer's ledger from the server: which shops they owe, and the entries
     * behind each balance.
     *
     * Separate from [refreshApprovals] because the two behave differently on a partial
     * answer. An approval missing from the server's list has been decided and is deleted
     * locally; a ledger entry missing from a response has NOT been withdrawn — the ledger is
     * append-only — so this one only ever adds. Sharing one method would make that
     * distinction a runtime flag instead of a compile-time one.
     */
    suspend fun refreshMyLedger(): PullOutcome

    /**
     * Reads this user's OWN book from the server: the customers in it and their entries.
     *
     * The seller-role counterpart of [refreshMyLedger]. This app is one account in two roles
     * and needs both verbs — the buyer one answers "what do I owe", this one answers "who
     * owes me". Only the buyer half existed, so Müşterilerim read a table that nothing
     * server-side ever wrote to and showed just the rows this install had created itself.
     *
     * Additive, for the same reason as [refreshMyLedger]: the ledger is append-only.
     *
     * A buyer-only account gets a refusal from the endpoint, which this reports as a
     * successful refresh of nothing — asking is harmless and the answer is not an error.
     *
     * Safe to call repeatedly — a foreground poll does exactly that.
     */
    suspend fun refreshBook(): PullOutcome

    /**
     * Answers a pending approval. Approving is what writes the ledger entry on this path.
     *
     * Returns [DecisionOutcome] because not every refusal means "try again": a card that
     * belongs to someone else, or was already answered, can never succeed and is dropped
     * instead of being left on screen forever.
     */
    suspend fun approvePending(approvalId: String): DecisionOutcome
    suspend fun rejectPending(approvalId: String): DecisionOutcome

    /**
     * Sends an entry for the counterparty's approval.
     *
     * The SERVER decides what happens: a pending approval when the counterparty holds the
     * app, or an immediate write when they do not (the SMS-OTP branch). The returned
     * [ApprovalOutcome] says which — the caller must not assume, because the two look the
     * same from here and only one of them means "it is in the book now".
     */
    suspend fun requestApproval(
        fromUserId: String,
        sellerId: String,
        customerId: String,
        amountMinor: Long,
        type: TransactionType,
        description: String
    ): ApprovalOutcome

    /**
     * A buyer pays a seller, through the same approval gate.
     *
     * Returns [ApprovalOutcome.NoCustomerRecord] when this buyer has no record with that
     * seller — there is no shared history to pay against, so nothing is written.
     */
    suspend fun initiatePayment(
        userId: String,
        sellerId: String,
        amountMinor: Long
    ): ApprovalOutcome

    /**
     * Appends one entry to the ledger (append-only). Idempotent by transactionId.
     *
     * Writes locally and queues the entry for the server; it does NOT wait for the network.
     * The screen updates from the database the moment this returns, whether or not there is
     * signal — sending inline would make an entry fail on a dead connection.
     */
    suspend fun addTransaction(transaction: Transaction)

    // --- sync (the queue that carries local writes to the server) ---
    /**
     * Pushes whatever is queued. Safe to call at any time: a no-op on an empty queue, only
     * one drain runs at a time, and every send is idempotent.
     */
    suspend fun syncNow(): SyncOutcome

    /** How many writes have not reached the server yet. */
    fun observeUnsentCount(): Flow<Int>
}

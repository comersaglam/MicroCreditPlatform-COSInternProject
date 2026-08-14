package com.example.app_pos.data

import com.example.app_pos.data.local.LocalSource
import com.example.app_pos.data.remote.RemoteDataSource
import com.example.app_pos.data.sync.PullEngine
import com.example.app_pos.data.sync.SyncEngine
import com.example.app_pos.model.ApprovalOutcome
import com.example.app_pos.model.Customer
import com.example.app_pos.model.CustomerLookup
import com.example.app_pos.model.DecisionOutcome
import com.example.app_pos.model.PAYMENT_DESCRIPTION
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
import com.example.app_pos.network.ApiResult
import com.example.app_pos.network.isRetryable
import com.example.app_pos.network.auth.TokenStore
import com.example.app_pos.network.dto.ApprovalDto
import com.example.app_pos.network.dto.TransactionCreateDto
import com.example.app_pos.network.dto.UserDto
import com.example.app_pos.network.mapper.toCreateDto
import com.example.app_pos.network.mapper.toDomainOrNull
import com.example.app_pos.network.mapper.toDomain
import com.squareup.moshi.Moshi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The [Repository] the app actually talks to: local storage, the backend and the session
 * store composed into one surface.
 *
 * The division of labour is the offline-first rule itself. Reads and writes go to Room, so
 * the app keeps working with no signal; the network is not on the critical path of any
 * screen. Writes reach the server afterwards, through the outbox that [syncEngine] drains.
 *
 * What DOES change here versus the local source: the session. It now lives in [TokenStore]
 * (DataStore-backed), so it survives the process dying — sign in once, and reopening the
 * app does not ask again.
 *
 * The session reads stay SYNCHRONOUS because MainActivity chooses the navigation start
 * destination in onCreate, before the graph exists, and cannot await anything. TokenStore
 * serves them from a RAM cache primed once at startup, so this costs no disk I/O on the
 * main thread. See TokenStore's own note.
 *
 * Mirrors app-pos's repository of the same name. The differences are app-mobile's alone:
 * this app is a BUYER first, so it also owns the approval inbox and the buyer-scoped reads.
 */
@Singleton
class OfflineFirstRepository @Inject constructor(
    private val local: LocalSource,
    private val remote: RemoteDataSource,
    private val syncEngine: SyncEngine,
    private val pullEngine: PullEngine,
    private val tokens: TokenStore,
    moshi: Moshi
) : Repository {

    // Serialises the queued request body. Held here rather than built per write: adapter
    // creation is reflection-backed work that would otherwise repeat on every entry.
    private val payloadAdapter = moshi.adapter(TransactionCreateDto::class.java)

    // --- session -------------------------------------------------------------

    override fun isSessionValid(): Boolean = tokens.isValid()

    override fun currentUserId(): String? = tokens.currentUserIdOrNull()

    /** Asks the server to send a code. False when it refused or could not be reached. */
    override suspend fun requestOtp(phone: String): OtpRequestResult =
        when (val result = remote.requestOtp(phone)) {
            // The server answers `sent: false` when it declines the number itself.
            is ApiResult.Success ->
                if (result.data) OtpRequestResult.Sent else OtpRequestResult.Refused
            // Never reached. Reported apart from a refusal so the screen does not blame the
            // number for a dropped connection.
            is ApiResult.NetworkError -> OtpRequestResult.Unreachable
            is ApiResult.ApiError ->
                if (result.isRetryable()) OtpRequestResult.Unreachable else OtpRequestResult.Refused
            is ApiResult.UnexpectedError -> OtpRequestResult.Unreachable
        }

    /**
     * Verifies the code, PERSISTS the returned session, and mirrors the user into Room.
     *
     * Whether the number has an account is the server's answer, never a local lookup. On a
     * fresh install Room is empty, so asking it first would send every number to the
     * sign-up prompt — including people who already have an account. This is the exact bug
     * app-pos hit in Turn 34.
     *
     * The user row is mirrored into Room because [observeCurrentUser] resolves the
     * session's user id against the local user table; without the row the user would sign
     * in successfully and land on a blank profile.
     */
    override suspend fun signIn(phone: String, code: String): SignInResult =
        when (val result = remote.verifyOtp(phone, code)) {
            is ApiResult.Success -> {
                mirrorUser(result.data.user)
                tokens.save(result.data)
                SignInResult.Success
            }

            is ApiResult.ApiError -> when (result.code) {
                // The server does not auto-register; this is a branch, not a failure.
                CODE_USER_NOT_FOUND -> SignInResult.NeedsRegister
                CODE_INVALID_CODE -> SignInResult.InvalidCode
                else -> SignInResult.Failed(result.message)
            }

            is ApiResult.NetworkError -> SignInResult.Unreachable
            is ApiResult.UnexpectedError -> SignInResult.Failed()
        }

    /**
     * Keeps the local user row in step with the account the server just authenticated.
     *
     * [LocalSource.upsertUser] rather than registerUser because the SERVER's user id has to
     * survive: the session stores that id, and observeCurrentUser matches it against the
     * local table. registerUser mints a fresh UUID, so the two would never line up.
     */
    private suspend fun mirrorUser(user: UserDto) = local.upsertUser(user.toDomain())

    override suspend fun logout() {
        // Revoke server-side first, but clear locally NO MATTER WHAT it answers: a failed
        // revoke (no signal, an expired token) must not strand the user in a shell that
        // still looks signed in. The local clear is the one that decides the UI.
        runCatching { remote.logout() }
        tokens.clear()
        local.logout()
    }

    override fun observeCurrentUser(): Flow<User?> =
        // Re-reads on either input: a profile edit changes the user row, a sign-in or
        // sign-out changes the session. Emitting null on sign-out is what clears the
        // profile screen without any screen having to listen for logout separately.
        combine(local.observeAllUsers(), tokens.observeSession()) { users, session ->
            val userId = session?.takeIf { it.isValid(System.currentTimeMillis()) }?.userId
            userId?.let { id -> users.firstOrNull { it.userId == id } }
        }

    // --- users / profile -----------------------------------------------------

    override val isPairedWithApp: Flow<Boolean> get() = local.isPairedWithApp

    override suspend fun pairWithApp() = local.pairWithApp()

    override suspend fun findUserByPhone(phone: String): User? = local.findUserByPhone(phone)

    /**
     * Creates the account ON THE SERVER, then mirrors it locally.
     *
     * The server has to own the user id: it is what the session carries and what every
     * row is keyed by. Registering only locally would mint a UUID the server has never
     * heard of, and the very next sign-in would replace it.
     *
     * Registration is idempotent by phone server-side, so a retry returns the existing
     * account. If the server cannot be reached the local row is still written, which keeps
     * the user moving; the next successful sign-in re-mirrors the authoritative copy.
     */
    override suspend fun registerUser(phone: String, displayName: String, isSeller: Boolean): User =
        when (val result = remote.register(phone, displayName, isSeller)) {
            is ApiResult.Success -> result.data.also { local.upsertUser(it) }
            else -> local.registerUser(phone, displayName, isSeller)
        }

    override suspend fun setSeller(userId: String, shopName: String, shopPhone: String?) =
        local.setSeller(userId, shopName, shopPhone)

    override suspend fun updateShopName(userId: String, shopName: String) =
        local.updateShopName(userId, shopName)

    override suspend fun updateDisplayName(userId: String, displayName: String) =
        local.updateDisplayName(userId, displayName)

    override suspend fun updateEmail(userId: String, email: String) =
        local.updateEmail(userId, email)

    override suspend fun shopNameOf(sellerId: String): String = local.shopNameOf(sellerId)

    override suspend fun shopPhoneOf(sellerId: String): String? = local.shopPhoneOf(sellerId)

    override suspend fun claimCustomerForUser(userId: String, phone: String): List<Customer> =
        local.claimCustomerForUser(userId, phone)

    // --- customers / ledger reads (local; the pull path is Turn 39) -----------

    override fun observeCustomers(sellerId: String): Flow<List<Customer>> =
        local.observeCustomers(sellerId)

    override suspend fun addCustomer(displayName: String, phone: String): String =
        local.addCustomer(displayName, phone)

    override suspend fun findCustomerById(sellerId: String, customerId: String): Customer? =
        local.findCustomerById(sellerId, customerId)

    override suspend fun findCustomerByPhone(sellerId: String, phone: String): Customer? =
        local.findCustomerByPhone(sellerId, phone)

    override suspend fun lookupCustomerForSeller(sellerId: String, phone: String): CustomerLookup =
        local.lookupCustomerForSeller(sellerId, phone)

    override fun observeTransactions(sellerId: String, customerId: String): Flow<List<Transaction>> =
        local.observeTransactions(sellerId, customerId)

    override fun observeTotalReceivableMinor(sellerId: String): Flow<Long> =
        local.observeTotalReceivableMinor(sellerId)

    override fun observeMyDebtsBySeller(userId: String): Flow<List<SellerDebt>> =
        local.observeMyDebtsBySeller(userId)

    override fun observeMyTotalDebtMinor(userId: String): Flow<Long> =
        local.observeMyTotalDebtMinor(userId)

    override fun observeMyTransactions(userId: String, sellerId: String): Flow<List<Transaction>> =
        local.observeMyTransactions(userId, sellerId)

    override fun observeMyBalanceWithSeller(userId: String, sellerId: String): Flow<Long> =
        local.observeMyBalanceWithSeller(userId, sellerId)

    // --- approvals -----------------------------------------------------------

    override fun observePendingApprovals(userId: String): Flow<List<PendingApproval>> =
        // Still reads the local table — and now something FILLS it from the server. The
        // Flow is the right shape for that: refreshApprovals writes Room, and every screen
        // observing this re-emits without knowing a network call happened.
        local.observePendingApprovals(userId)

    /**
     * Pulls the server's inbox into the local table. See [Repository.refreshApprovals].
     *
     * Needs a session: the endpoint answers "what is waiting on YOU", so without a signed-in
     * user there is no question to ask. Reported as [PullOutcome.Unreachable] rather than a
     * failure because a poll started just before sign-out is a race, not a fault.
     */
    override suspend fun refreshApprovals(): PullOutcome {
        val userId = tokens.currentUserIdOrNull() ?: return PullOutcome.Unreachable
        return pullEngine.pullApprovals(userId)
    }

    /** Pulls this buyer's debts and their entries. See [Repository.refreshMyLedger]. */
    override suspend fun refreshMyLedger(): PullOutcome {
        val userId = tokens.currentUserIdOrNull() ?: return PullOutcome.Unreachable
        return pullEngine.pullMyLedger(userId)
    }

    /**
     * Approves on the SERVER, which is what writes the ledger entry on this path, then
     * mirrors the result locally.
     *
     * Deliberately NOT offline-tolerant: an approval is a decision the counterparty is
     * waiting on, so recording it only on this device would show "approved" here while the
     * other side still sees a pending card. When the server cannot be reached the local
     * row is left untouched, so the card stays and the user can try again.
     */
    override suspend fun approvePending(approvalId: String): DecisionOutcome =
        when (val result = remote.approve(approvalId)) {
            is ApiResult.Success -> {
                // The server booked the entry; mirror it WITHOUT queueing, or the outbox
                // would send a second copy of a write the server already has.
                result.data?.let { local.addTransaction(it) }
                // markApprovalDecided, NOT local.approvePending — the latter books the
                // entry itself, which on this path is the SECOND copy of an amount the
                // server already wrote. The two rows carry different generated ids, so the
                // ledger's insert-IGNORE cannot deduplicate them and the balance is simply
                // wrong. (Found on device: a 75 TL approval landed twice.)
                local.markApprovalDecided(approvalId, STATUS_APPROVED)
                DecisionOutcome.Applied
            }

            is ApiResult.ApiError -> staleCardOutcome(approvalId, result)
                ?: DecisionOutcome.Failed(result.message)

            // Keep the card: retrying IS the right next move when nothing was reached.
            is ApiResult.NetworkError -> DecisionOutcome.Unreachable
            is ApiResult.UnexpectedError -> DecisionOutcome.Failed()
        }

    override suspend fun rejectPending(approvalId: String): DecisionOutcome =
        when (val result = remote.reject(approvalId)) {
            // Nothing is written on this path, so the local method is safe to reuse.
            is ApiResult.Success -> {
                local.rejectPending(approvalId)
                DecisionOutcome.Applied
            }

            is ApiResult.ApiError -> staleCardOutcome(approvalId, result)
                ?: DecisionOutcome.Failed(result.message)

            is ApiResult.NetworkError -> DecisionOutcome.Unreachable
            is ApiResult.UnexpectedError -> DecisionOutcome.Failed()
        }

    /**
     * Recognises the refusals that mean "this card should not be here", and clears it.
     *
     * A 403 (addressed to someone else) or a 409 (already answered) cannot be fixed by
     * trying again — the answer will be identical every time. Leaving the row pending
     * therefore strands it on screen forever, which is exactly what a seeded card belonging
     * to another account did on a test device.
     *
     * The local row is marked with the status that made the server refuse, so the same
     * card cannot come back on the next read. Returns null when the error is something
     * else, leaving the decision to the caller.
     */
    private suspend fun staleCardOutcome(
        approvalId: String,
        error: ApiResult.ApiError
    ): DecisionOutcome? = when (error.code) {
        // Not ours to answer, so it is not ours to keep either.
        CODE_FORBIDDEN -> {
            local.deleteApproval(approvalId)
            DecisionOutcome.NotYours
        }
        // Answered already — here after a double tap, or on another device.
        CODE_ALREADY_DECIDED -> {
            local.deleteApproval(approvalId)
            DecisionOutcome.AlreadyDecided
        }
        else -> null
    }

    /**
     * Sends an entry for the counterparty's approval, through the server's single gate.
     *
     * **The server owns the branch, and this method APPLIES its answer** rather than
     * guessing: 201 (an approval id) means a pending card was raised and the ledger is
     * untouched; 200 (a transaction id) means the counterparty has no account and the entry
     * was booked there and then.
     *
     * Deciding locally — which this used to do, by reading `claimedByUserId` — is wrong for
     * two reasons. The device's copy of that field can be stale or, as happened here, never
     * set at all because the claim silently missed; and a client that writes to the ledger
     * on its own authority produces entries the server never agreed to.
     */
    override suspend fun requestApproval(
        fromUserId: String,
        sellerId: String,
        customerId: String,
        amountMinor: Long,
        type: TransactionType,
        description: String
    ): ApprovalOutcome {
        val targetUserId = local.findCustomerById(sellerId, customerId)?.claimedByUserId
        val result = remote.sendForApproval(
            sellerId = sellerId,
            customerId = customerId,
            amountMinor = amountMinor,
            type = type,
            description = description,
            initiatorRole = if (fromUserId == sellerId) ROLE_SELLER else ROLE_BUYER,
            targetUserId = targetUserId.orEmpty()
        )

        return when (result) {
            is ApiResult.Success -> {
                val approval = result.data.asApproval()
                val transaction = result.data.asTransaction()
                when {
                    // Raised for the counterparty to answer: mirror the card locally.
                    // The card names the OTHER side, so a seller-initiated request shows
                    // the shop and a buyer-initiated one shows the customer.
                    approval != null -> {
                        val card = approval.toDomainOrNull(
                            counterpartyName = counterpartyNameFor(fromUserId, sellerId, approval)
                        )
                        card?.let { local.insertPendingApproval(it, fromUserId) }
                        if (card != null) ApprovalOutcome.SentForApproval
                        else ApprovalOutcome.Failed()
                    }
                    // Nobody could tap approve, so the server wrote it. Mirror WITHOUT
                    // queueing — the server already has this entry, and the outbox would
                    // send a second copy of it.
                    transaction != null -> {
                        transaction.toDomainOrNull()?.let { local.addTransaction(it) }
                        ApprovalOutcome.WrittenImmediately
                    }
                    // A 2xx we cannot read. Treat as failed rather than inventing a write.
                    else -> ApprovalOutcome.Failed()
                }
            }

            // Offline: keep the user's action on this device so it is not lost, and say so.
            // The local row can disagree with the server until the pull path (Turn 39)
            // reconciles them — the known "approval gate vs offline-first" tension.
            is ApiResult.NetworkError -> {
                local.requestApproval(
                    fromUserId, sellerId, customerId, amountMinor, type, description
                )
                ApprovalOutcome.QueuedOffline
            }

            is ApiResult.ApiError -> ApprovalOutcome.Failed(result.message)
            is ApiResult.UnexpectedError -> ApprovalOutcome.Failed()
        }
    }

    /**
     * A buyer pays a seller, through the same gate as everything else.
     *
     * Resolving WHICH customer record to pay against is a local lookup (the buyer's own
     * history with that shop), but the send itself goes through [requestApproval] above —
     * so this path reaches the server like every other write. It used to delegate wholesale
     * to the local source, which meant a payment never left the device while the screen
     * reported success.
     */
    override suspend fun initiatePayment(
        userId: String,
        sellerId: String,
        amountMinor: Long
    ): ApprovalOutcome {
        val customerId = local.customerIdForBuyerSeller(userId, sellerId)
            ?: return ApprovalOutcome.NoCustomerRecord
        return requestApproval(
            fromUserId = userId,
            sellerId = sellerId,
            customerId = customerId,
            amountMinor = amountMinor,
            type = TransactionType.PAYMENT,
            description = PAYMENT_DESCRIPTION
        )
    }

    // --- ledger writes -------------------------------------------------------

    /**
     * Books the entry locally and queues it for the server — atomically, in one database
     * transaction (see LocalSource.addTransactionQueued).
     *
     * Deliberately does NOT wait for the network. The screen updates from Room the moment
     * this returns, whether or not there is signal; SyncEngine delivers the entry
     * afterwards. The wire body is serialised HERE and stored verbatim, so what eventually
     * reaches the server is exactly what was confirmed.
     */
    override suspend fun addTransaction(transaction: Transaction) {
        val payload = payloadAdapter.toJson(transaction.toCreateDto(orderBody = null))
        local.addTransactionQueued(transaction, payload)
    }

    /**
     * Pushes whatever is queued. Safe to call at any time: a no-op on an empty queue, only
     * one drain runs at a time, and every send is idempotent.
     */
    override suspend fun syncNow(): SyncOutcome = syncEngine.drainOutbox()

    override fun observeUnsentCount(): Flow<Int> = local.observeUnsentCount()

    /**
     * Whose name the approval card should carry: always the side that did NOT ask.
     *
     * The server denormalises the shop's name onto the approval, which is right when a
     * seller raised it. When a BUYER raised it the approver is the shop, and the card they
     * see should name the customer instead — so that case is resolved locally.
     */
    private suspend fun counterpartyNameFor(
        fromUserId: String,
        sellerId: String,
        approval: ApprovalDto
    ): String =
        if (fromUserId == sellerId) approval.shopName
        else local.findCustomerById(sellerId, approval.customerId)?.displayName
            ?: approval.shopName

    private companion object {
        // Error codes the sign-in flow branches on, exactly as the contract spells them.
        const val CODE_USER_NOT_FOUND = "user_not_found"
        const val CODE_INVALID_CODE = "invalid_code"

        // Who started the request, in the contract's vocabulary.
        const val ROLE_SELLER = "SELLER"
        const val ROLE_BUYER = "BUYER"

        /** Approval states, as the approvals table stores them. */
        const val STATUS_APPROVED = "APPROVED"

        // Refusals that mean the card is stale rather than retryable, spelled as the
        // contract does (backend/app/routers/approvals.py).
        const val CODE_FORBIDDEN = "forbidden"
        const val CODE_ALREADY_DECIDED = "already_decided"
    }
}

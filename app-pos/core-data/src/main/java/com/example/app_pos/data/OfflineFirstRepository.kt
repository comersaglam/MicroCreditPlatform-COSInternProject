package com.example.app_pos.data

import com.example.app_pos.data.local.LocalSource
import com.example.app_pos.data.remote.RemoteDataSource
import com.example.app_pos.data.sync.PullEngine
import com.example.app_pos.data.sync.SyncEngine
import com.example.app_pos.model.ROLE_SELLER
import com.example.app_pos.model.SyncOutcome
import com.example.app_pos.network.dto.TransactionCreateDto
import com.example.app_pos.network.mapper.toCreateDto
import com.squareup.moshi.Moshi
import com.example.app_pos.model.ApprovalOutcome
import com.example.app_pos.model.ApprovalStatus
import com.example.app_pos.model.Customer
import com.example.app_pos.model.CustomerCreateOutcome
import com.example.app_pos.model.SellerInfo
import com.example.app_pos.model.DecisionOutcome
import com.example.app_pos.model.CustomerLookup
import com.example.app_pos.model.OrderBody
import com.example.app_pos.model.PendingApproval
import com.example.app_pos.model.PgwDispatcher
import com.example.app_pos.model.PgwJob
import com.example.app_pos.model.PgwJobKind
import com.example.app_pos.model.PullOutcome
import com.example.app_pos.model.OtpRequestResult
import com.example.app_pos.model.Repository
import com.example.app_pos.model.SignInResult
import com.example.app_pos.model.Transaction
import com.example.app_pos.model.TransactionType
import com.example.app_pos.model.User
import com.example.app_pos.network.ApiResult
import com.example.app_pos.network.isRetryable
import com.example.app_pos.network.auth.TokenStore
import com.example.app_pos.network.dto.UserDto
import com.example.app_pos.network.mapper.toDomain
import com.example.app_pos.network.mapper.toDomainOrNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The [Repository] the app actually talks to: local storage, the backend and the session
 * store composed into one surface.
 *
 * The division of labour is the offline-first rule itself. Reads and writes go to Room, so
 * a POS with no signal keeps working; the network is not on the critical path of any screen
 * — phase 8 adds an outbox that drains [remote] in the background, which is why [remote] is
 * held but not yet called.
 *
 * What DOES change here versus the local source: the session. It now lives in [TokenStore]
 * (DataStore-backed), so it survives the process dying. That is the whole user-visible
 * payoff of this step — sign in once, and reopening the app does not ask again.
 *
 * The session reads stay SYNCHRONOUS because MainActivity chooses the navigation start
 * destination in onCreate, before the graph exists, and cannot await anything. TokenStore
 * serves them from a RAM cache primed once at startup, so this costs no disk I/O on the
 * main thread. See TokenStore's own note.
 */
@Singleton
class OfflineFirstRepository @Inject constructor(
    private val local: LocalSource,
    private val remote: RemoteDataSource,
    private val syncEngine: SyncEngine,
    private val pullEngine: PullEngine,
    private val tokens: TokenStore,
    moshi: Moshi
) : Repository, PgwDispatcher {

    // Serialises the queued request body. Held here rather than built per write: adapter
    // creation is reflection-backed work that would otherwise repeat on every sale.
    private val payloadAdapter = moshi.adapter(TransactionCreateDto::class.java)

    // --- session -------------------------------------------------------------

    override fun isSessionValid(): Boolean = tokens.isValid()

    override fun currentSellerId(): String? = tokens.currentUserIdOrNull()

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
     * The session is now the SERVER's: a real token with a real expiry and a refresh
     * token, which is what makes TokenAuthenticator's 401 -> refresh -> retry path work at
     * all. Only the token's provenance changed — it still lands in the same TokenStore the
     * mock wrote to, so every screen reading the session is untouched.
     *
     * Whether the number has an account is the server's answer, never a local lookup. On a
     * fresh install Room is empty, so asking it first would send every number to the
     * sign-up prompt — including merchants who already have an account.
     *
     * The user row is mirrored into Room because [observeCurrentUser] resolves the
     * session's user id against the local user table; without the row the shopkeeper would
     * sign in successfully and land on a blank profile.
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
     * local table. registerUser mints a fresh UUID, so the two would never line up and the
     * merchant would sign in successfully to an empty profile.
     */
    private suspend fun mirrorUser(user: UserDto) = local.upsertUser(user.toDomain())

    override suspend fun logout() {
        // Revoke server-side first, but clear locally NO MATTER WHAT it answers: a failed
        // revoke (no signal, an expired token) must not strand the merchant in a shell
        // that still looks signed in. The local clear is the one that decides the UI.
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

    // --- everything else is local; the outbox (phase 8) is what will involve remote ---

    override val isPairedWithApp: Flow<Boolean> get() = local.isPairedWithApp

    override suspend fun pairWithApp() = local.pairWithApp()

    override suspend fun findUserByPhone(phone: String): User? = local.findUserByPhone(phone)

    /**
     * Creates the account ON THE SERVER, then mirrors it locally.
     *
     * The server has to own the user id: it is what the session carries and what every
     * seller-scoped row is keyed by. Registering only locally would mint a UUID the server
     * has never heard of, and the very next sign-in would replace it — stranding anything
     * written under the old id.
     *
     * Registration is idempotent by phone server-side, so a retry returns the existing
     * account rather than failing. If the server cannot be reached the local row is still
     * written, which keeps the merchant moving; the next successful sign-in re-mirrors the
     * authoritative copy over it.
     */
    override suspend fun registerUser(phone: String, displayName: String, isSeller: Boolean): User =
        when (val result = remote.register(phone, displayName, isSeller)) {
            is ApiResult.Success -> result.data.also { local.upsertUser(it) }
            else -> local.registerUser(phone, displayName, isSeller)
        }

    /**
     * Profile writes go to the server first, then mirror what it answers.
     *
     * Unlike [addCustomer] these stay offline-tolerant: on failure the local row is still
     * written and the merchant keeps moving. That is safe here in a way it is not there —
     * nothing is keyed by a display name, so a local edit that has not synced yet is a
     * stale field, not an id the server will later reject.
     */
    override suspend fun setSeller(userId: String, shopName: String, shopPhone: String?) =
        when (val result = remote.becomeSeller(SellerInfo(shopName, shopPhone))) {
            is ApiResult.Success -> local.upsertUser(result.data)
            else -> local.setSeller(userId, shopName, shopPhone)
        }

    override suspend fun updateDisplayName(userId: String, displayName: String) =
        when (val result = remote.updateProfile(displayName = displayName)) {
            is ApiResult.Success -> local.upsertUser(result.data)
            else -> local.updateDisplayName(userId, displayName)
        }

    /**
     * Renaming reuses become-seller, which is how the server spells it.
     *
     * The existing shop phone is READ FIRST and sent along. BecomeSeller carries both
     * fields and the server assigns both, so sending the name alone would blank a number
     * the shop had already set — a rename quietly deleting a phone number.
     */
    override suspend fun updateShopName(userId: String, shopName: String) {
        // Read from the local mirror of this account rather than asking the server again:
        // it is the same row /users/me would return, and a rename should not need two
        // round-trips. Null only when the shop never set a number, which is what to send.
        val keptPhone = local.observeAllUsers().first()
            .firstOrNull { it.userId == userId }?.sellerInfo?.shopPhone
        when (val result = remote.becomeSeller(SellerInfo(shopName, keptPhone))) {
            is ApiResult.Success -> local.upsertUser(result.data)
            else -> local.updateShopName(userId, shopName)
        }
    }

    override fun observeCustomers(sellerId: String): Flow<List<Customer>> =
        local.observeCustomers(sellerId)

    /**
     * Opens the record ON THE SERVER, then mirrors it locally. The server owns the id.
     *
     * Server-first, and not offline-tolerant — the one write on this path that is not.
     * A local UUID is what caused a veresiye to vanish: the id reached the server attached
     * to a ledger entry, the server had never heard of that customer, and the resulting 404
     * is not retryable, so the outbox DROPPED the entry. The debt stayed on the merchant's
     * screen and existed nowhere else. Refusing to invent an id is what closes that hole.
     *
     * The cost is bounded on purpose: only OPENING a record needs signal. Writing a
     * veresiye for a customer who already exists still queues offline, which is the case
     * that actually happens on a shop floor.
     */
    override suspend fun addCustomer(displayName: String, phone: String): CustomerCreateOutcome =
        when (val result = remote.createCustomer(displayName, phone)) {
            is ApiResult.Success -> {
                local.storeCustomers(listOf(result.data))
                CustomerCreateOutcome.Created(result.data.customerId)
            }

            is ApiResult.ApiError ->
                if (result.code == CODE_CUSTOMER_EXISTS) {
                    // Not a failure: the server is saying this phone is ALREADY in this
                    // seller's book. The right record exists, so find it and carry on with
                    // it — a second row would split one person's history in two. The
                    // endpoint takes no idempotency key, so a 409 is also what a retry
                    // after a lost 201 looks like; reconciling covers both.
                    existingCustomerFor(phone)
                } else {
                    CustomerCreateOutcome.Failed(result.message)
                }

            // Nothing written, locally or remotely. Saying so is the whole point: the
            // caller must not go on to write an entry against a customer that does not
            // exist. ("Unreachable is not empty" — the same rule the pull path follows.)
            is ApiResult.NetworkError -> CustomerCreateOutcome.Unreachable
            is ApiResult.UnexpectedError -> CustomerCreateOutcome.Failed(UNREADABLE_ANSWER)
        }

    /**
     * Resolves the record a 409 refers to, so the caller gets a usable id instead of an
     * error. Mirrors it locally too — this device evidently did not have it.
     */
    private suspend fun existingCustomerFor(phone: String): CustomerCreateOutcome =
        when (val lookup = remote.lookupCustomerByPhone(phone)) {
            is ApiResult.Success -> {
                local.storeCustomers(listOf(lookup.data))
                CustomerCreateOutcome.AlreadyExists(lookup.data.customerId)
            }
            // The row is known to exist (that is what the 409 said), so failing to read it
            // back is a transport problem, not an absent customer.
            is ApiResult.NetworkError -> CustomerCreateOutcome.Unreachable
            is ApiResult.ApiError -> CustomerCreateOutcome.Failed(lookup.message)
            is ApiResult.UnexpectedError -> CustomerCreateOutcome.Failed(UNREADABLE_ANSWER)
        }

    override suspend fun lookupCustomerForSeller(sellerId: String, phone: String): CustomerLookup =
        local.lookupCustomerForSeller(sellerId, phone)

    override suspend fun findCustomerById(sellerId: String, customerId: String): Customer? =
        local.findCustomerById(sellerId, customerId)

    override suspend fun findCustomerByPhone(sellerId: String, phone: String): Customer? =
        local.findCustomerByPhone(sellerId, phone)

    override fun observeTransactions(sellerId: String, customerId: String): Flow<List<Transaction>> =
        local.observeTransactions(sellerId, customerId)

    override fun observeTotalReceivableMinor(sellerId: String): Flow<Long> =
        local.observeTotalReceivableMinor(sellerId)

    override fun observeBalance(sellerId: String, customerId: String): Flow<Long> =
        local.observeBalance(sellerId, customerId)

    /**
     * Books the entry locally and queues it for the server — atomically, in one database
     * transaction (see LocalSource.addTransactionQueued).
     *
     * Deliberately does NOT wait for the network. The merchant's screen updates from Room
     * the moment this returns, whether or not there is signal; SyncEngine delivers the
     * entry afterwards. Sending inline would make a veresiye fail on a dead connection,
     * which on a shop floor is the common case, not the edge case.
     *
     * The wire body is serialised HERE, at approval time, and stored verbatim. So what
     * eventually reaches the server is exactly what was confirmed.
     */
    override suspend fun addTransaction(transaction: Transaction, orderBody: OrderBody?) {
        val payload = payloadAdapter.toJson(transaction.toCreateDto(orderBody))
        local.addTransactionQueued(transaction, orderBody, payload)
    }

    // --- approvals (the incoming inbox) --------------------------------------

    override fun observePendingApprovals(userId: String): Flow<List<PendingApproval>> =
        // Reads the local table, which refreshApprovals FILLS from the server. The Flow is
        // the right shape for that: the pull writes Room, and this screen re-emits without
        // knowing a network call happened.
        local.observePendingApprovals(userId)

    /**
     * Pulls the server's inbox into the local table. See [Repository.refreshApprovals].
     *
     * Needs a session: the endpoint answers "what is waiting on YOU", so without a signed-in
     * seller there is no question to ask.
     */
    override suspend fun refreshApprovals(): PullOutcome {
        val userId = tokens.currentUserIdOrNull() ?: return PullOutcome.Unreachable
        return pullEngine.pullApprovals(userId)
    }

    /** Pulls this shop's customers and their entries. See [Repository.refreshBook]. */
    override suspend fun refreshBook(): PullOutcome {
        // A session is required: /customers answers "the signed-in seller's book".
        if (tokens.currentUserIdOrNull() == null) return PullOutcome.Unreachable
        return pullEngine.pullBook()
    }

    /**
     * Sends an entry for the customer's approval. See [Repository.requestApproval].
     *
     * Deliberately NOT offline-tolerant, and this is the one place app-pos departs from
     * the offline-first rule it follows everywhere else. The point of the gate is that the
     * customer agrees; with no signal nobody can be asked, and writing the entry anyway
     * would be the very unilateral booking the gate exists to prevent. So an unreachable
     * server stops the sale instead of quietly completing it.
     *
     * Nothing is mirrored locally on the 201 branch either — the pending card belongs to
     * the CUSTOMER's inbox, not this shop's, and a copy here would show the till a request
     * it is not being asked to answer.
     */
    override suspend fun requestApproval(
        sellerId: String,
        customerId: String,
        amountMinor: Long,
        type: TransactionType,
        description: String,
        origin: String
    ): ApprovalOutcome {
        // Who must answer. Null means the customer holds no account, which the server
        // reads as the SMS-OTP branch and writes immediately.
        val targetUserId = local.findCustomerById(sellerId, customerId)?.claimedByUserId

        val result = remote.sendForApproval(
            sellerId = sellerId,
            customerId = customerId,
            amountMinor = amountMinor,
            type = type,
            description = description,
            // The till is always the shop side of this line.
            initiatorRole = ROLE_SELLER,
            targetUserId = targetUserId.orEmpty(),
            origin = origin
        )

        return when (result) {
            is ApiResult.Success -> {
                val approval = result.data.asApproval()
                val transaction = result.data.asTransaction()
                when {
                    // Raised: the ledger is untouched until the customer answers, so the
                    // sale waits. The id travels back so the screen can poll for it.
                    approval != null -> ApprovalOutcome.SentForApproval(approval.approvalId)

                    // Nobody could tap approve, so the server booked it. Mirror WITHOUT
                    // queueing — the server already holds this entry, and the outbox would
                    // deliver a second copy of it.
                    transaction != null -> {
                        transaction.toDomainOrNull()?.let { local.addTransaction(it) }
                        ApprovalOutcome.WrittenImmediately
                    }

                    // A 2xx we cannot read. Treat as failed rather than inventing a write.
                    else -> ApprovalOutcome.Failed()
                }
            }

            // No signal: the customer cannot be asked, so nothing is written anywhere.
            is ApiResult.NetworkError -> ApprovalOutcome.Unreachable

            is ApiResult.ApiError -> ApprovalOutcome.Failed(result.message)
            is ApiResult.UnexpectedError -> ApprovalOutcome.Failed()
        }
    }

    // --- payment-gateway dispatch (PgwDispatcher) ----------------------------

    /**
     * Work waiting for this till. See [PgwDispatcher.pendingJobs].
     *
     * An unreachable server answers the empty list, same as "nothing to do". The two are
     * genuinely equivalent to the caller — fire no intent — and the jobs are safe on the
     * server, so the next poll picks them up. This is the one place where collapsing
     * "unreachable" into "empty" is right, and it is right because NOTHING IS DELETED here:
     * the rule it would otherwise break (silence must not wipe local rows) has nothing to
     * act on, since jobs are never stored on the device.
     */
    override suspend fun pendingJobs(): List<PgwJob> =
        when (val result = remote.pgwJobs()) {
            is ApiResult.Success -> result.data
            else -> emptyList()
        }

    override suspend fun acknowledge(jobId: String) {
        // Failures are deliberately swallowed: the job stays PENDING and comes back on the
        // next poll, which fires the intent again. That is the safe direction — the
        // alternative ordering loses receipts outright.
        remote.ackPgwJob(jobId)
    }

    override suspend fun queueCollect(customerId: String, amountMinor: Long): Boolean =
        remote.createPgwJob(PgwJobKind.COLLECT, customerId, amountMinor) is ApiResult.Success

    /**
     * Asks the server what became of a raised approval. See [Repository.approvalStatus].
     *
     * Reads the server directly rather than the local table: the row lives in the
     * CUSTOMER's inbox and this device never stored a copy of it, so there is nothing
     * local to observe.
     */
    override suspend fun approvalStatus(approvalId: String): ApprovalStatus? =
        when (val result = remote.approvalById(approvalId)) {
            is ApiResult.Success -> result.data
            // Unreachable or refused: unknown, not decided. The caller keeps waiting,
            // which is the only safe reading while a receipt is pending at the gateway.
            else -> null
        }

    /**
     * Approves on the SERVER, which is what writes the ledger entry on this path, then
     * mirrors the result locally.
     *
     * Deliberately NOT offline-tolerant: an approval is a decision the counterparty is
     * waiting on, so recording it only on this device would show "approved" here while the
     * other side still sees a pending card. When the server cannot be reached the local row
     * is left untouched, so the card stays and the merchant can try again.
     */
    override suspend fun approvePending(approvalId: String): DecisionOutcome =
        when (val result = remote.approve(approvalId)) {
            is ApiResult.Success -> {
                // Mirror the server's entry WITHOUT queueing it: the server already holds
                // this write, and addTransaction would put a second copy in the outbox.
                result.data?.let { local.addTransaction(it) }
                // markApprovalDecided, NOT local.approvePending — the latter books the
                // entry itself, which here is a SECOND copy of an amount the server already
                // wrote. The two rows carry different generated ids, so the ledger's
                // insert-IGNORE cannot deduplicate them and the balance is simply wrong.
                // (Found on an app-mobile device: a 75 TL approval landed twice.)
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
            is ApiResult.Success -> {
                // Nothing is written on this path, so only the status changes.
                local.markApprovalDecided(approvalId, STATUS_REJECTED)
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
     * trying again — the answer is identical every time. Leaving the row pending strands it
     * on screen forever, which is exactly what a stale card did on an app-mobile test device.
     *
     * DELETED, not marked: this device does not know the real decision, and writing one
     * would invent a record. The true trail lives on the server.
     *
     * Returns null when the error is something else, leaving the decision to the caller.
     */
    private suspend fun staleCardOutcome(
        approvalId: String,
        error: ApiResult.ApiError
    ): DecisionOutcome? = when (error.code) {
        CODE_FORBIDDEN -> {
            local.deleteApproval(approvalId)
            DecisionOutcome.NotYours
        }
        CODE_ALREADY_DECIDED -> {
            local.deleteApproval(approvalId)
            DecisionOutcome.AlreadyDecided
        }
        else -> null
    }

    /**
     * Pushes whatever is queued. Safe to call at any time: it is a no-op on an empty queue,
     * only one drain runs at a time, and every send is idempotent.
     *
     * Called on sign-in and on app start (see App.onCreate). WorkManager-driven background
     * syncing is a separate step; this covers the case that matters most — a terminal that
     * was offline is opened again once there is signal.
     */
    override suspend fun syncNow(): SyncOutcome = syncEngine.drainOutbox()

    /** How many writes have not reached the server yet. */
    override fun observeUnsentCount(): Flow<Int> = local.observeUnsentCount()

    // --- helpers -------------------------------------------------------------

    /**
     * A locally-minted session in the SERVER's shape, so TokenStore stores and expires it
     * with exactly the code path a real one takes. No refresh token: nothing can renew a
     * token no server issued, and pretending otherwise would exercise a branch that cannot
     * work.
     */

    private companion object {
        // Which side of the approval line this device is. A terminal is always the shop --
        // there is no buyer-initiated path from a till -- so unlike app-mobile, which
        // carries both roles in one account, this never has to be decided at runtime.

        // Error codes the sign-in flow branches on, exactly as the contract spells them.
        const val CODE_USER_NOT_FOUND = "user_not_found"
        const val CODE_INVALID_CODE = "invalid_code"

        // Refusals that mean the card is stale rather than the request being wrong.
        const val CODE_FORBIDDEN = "forbidden"
        const val CODE_ALREADY_DECIDED = "already_decided"

        // "This phone is already in your book" — a 409 that means reconcile, not fail.
        const val CODE_CUSTOMER_EXISTS = "customer_exists"

        // An answer this build could not read. Not a server message -- there is none --
        // so the user is told the truth in the one language the screen speaks.
        const val UNREADABLE_ANSWER = "Sunucu yanıtı okunamadı"

        const val STATUS_APPROVED = "APPROVED"
        const val STATUS_REJECTED = "REJECTED"

        /**
         * A new formatter per call: SimpleDateFormat is not thread-safe. ISO-8601 UTC is
         * the contract's timestamp format, and the one DataStoreTokenStore parses back.
         */
        fun isoFormat(): SimpleDateFormat =
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
    }
}

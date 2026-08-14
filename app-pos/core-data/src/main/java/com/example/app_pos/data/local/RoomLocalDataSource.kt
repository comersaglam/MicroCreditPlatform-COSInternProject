package com.example.app_pos.data.local

import androidx.room.withTransaction
import com.example.app_pos.data.db.AppDatabase
import com.example.app_pos.data.db.entity.ApprovalEntity
import com.example.app_pos.data.db.entity.CustomerEntity
import com.example.app_pos.data.db.entity.OutboxEntity
import com.example.app_pos.data.db.toBasketEntity
import com.example.app_pos.data.db.toDomain
import com.example.app_pos.data.db.toEntity
import com.example.app_pos.data.db.toItemEntities
import com.example.app_pos.model.Customer
import com.example.app_pos.model.DecisionOutcome
import com.example.app_pos.model.CustomerLookup
import com.example.app_pos.model.OrderBody
import com.example.app_pos.model.PendingApproval
import com.example.app_pos.model.PhoneFormat
import com.example.app_pos.model.OtpRequestResult
import com.example.app_pos.model.PullOutcome
import com.example.app_pos.model.Repository
import com.example.app_pos.model.SignInResult
import com.example.app_pos.model.SyncOutcome
import com.example.app_pos.model.SellerInfo
import com.example.app_pos.model.Transaction
import com.example.app_pos.model.User
import com.example.app_pos.model.balanceOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.util.UUID

/**
 * The LOCAL half of the data layer: everything this device knows without a network.
 *
 * It still satisfies [Repository] in full, which is what keeps this step behaviour-neutral
 * — OfflineFirstRepository composes it with the remote source and the token store, and
 * only the session bodies move out. Reads that survive a flight-mode POS stay here.
 *
 * What persists: users, customers, the append-only ledger, baskets. Balances are never
 * stored; they are summed from the ledger (DAO SUM or the pure balanceOf).
 *
 * The session + pairing flag below are still RAM-only mocks. They move to the persisted
 * TokenStore in the composing repository, which is why they stay untouched here.
 */
class RoomLocalDataSource(private val db: AppDatabase) : LocalSource {

    private val users = db.userDao()
    private val customers = db.customerDao()
    private val transactions = db.transactionDao()
    private val baskets = db.basketDao()
    private val approvals = db.approvalDao()
    private val outbox = db.outboxDao()

    // --- session + pairing (RAM, mock — see class doc) -----------------------
    private data class Session(val userId: String, val token: String, val expiresAt: Long)
    private val session = MutableStateFlow<Session?>(null)
    private val _isPairedWithApp = MutableStateFlow(false)
    override val isPairedWithApp: Flow<Boolean> = _isPairedWithApp.asStateFlow()

    override fun isSessionValid(): Boolean =
        session.value?.let { it.expiresAt > System.currentTimeMillis() } == true

    override fun currentSellerId(): String? =
        session.value?.takeIf { it.expiresAt > System.currentTimeMillis() }?.userId

    /**
     * Never reached in the app: OfflineFirstRepository owns sign-in and does not delegate
     * it here. Kept because LocalSource extends Repository, and answering false is the
     * honest response — this class cannot verify a code, only the server can.
     */
    override suspend fun requestOtp(phone: String): OtpRequestResult = OtpRequestResult.Unreachable

    override suspend fun signIn(phone: String, code: String): SignInResult =
        SignInResult.Unreachable

    override suspend fun logout() {
        session.value = null
        _isPairedWithApp.value = false
    }

    override suspend fun pairWithApp() {
        _isPairedWithApp.value = true
    }

    override fun observeCurrentUser(): Flow<User?> =
        // Re-query whenever the user table or the session changes, so a profile edit or
        // a login/logout is reflected live (mirrors FakeRepository's combine).
        combine(users.observeAll(), session) { list, s ->
            val valid = s != null && s.expiresAt > System.currentTimeMillis()
            if (!valid) null else list.firstOrNull { it.userId == s!!.userId }?.toDomain()
        }

    /**
     * Every stored user, with no session filter applied.
     *
     * OfflineFirstRepository needs this because it resolves "who is signed in" from the
     * PERSISTED session, not from this class's RAM mock — combining the table with its own
     * stale session here would always yield null.
     */
    override fun observeAllUsers(): Flow<List<User>> =
        users.observeAll().map { list -> list.map { it.toDomain() } }

    override suspend fun upsertUser(user: User) = users.upsert(user.toEntity())

    // --- users ---------------------------------------------------------------

    override suspend fun findUserByPhone(phone: String): User? =
        users.findByPhone(storedPhone(phone))?.toDomain()

    override suspend fun registerUser(phone: String, displayName: String, isSeller: Boolean): User {
        findUserByPhone(phone)?.let { return it }
        val user = User(
            userId = UUID.randomUUID().toString(),
            phone = storedPhone(phone),
            displayName = displayName.trim(),
            isBuyer = true,
            isSeller = isSeller,
            email = null,
            sellerInfo = null,
            createdAt = nowStamp()
        )
        users.insert(user.toEntity())
        return user
    }

    override suspend fun setSeller(userId: String, shopName: String, shopPhone: String?) {
        val existing = users.findById(userId) ?: return
        users.update(existing.copy(isSeller = true, shopName = shopName.trim(), shopPhone = shopPhone))
    }

    override suspend fun updateDisplayName(userId: String, displayName: String) {
        val existing = users.findById(userId) ?: return
        users.update(existing.copy(displayName = displayName.trim()))
    }

    override suspend fun updateShopName(userId: String, shopName: String) {
        val existing = users.findById(userId) ?: return
        users.update(existing.copy(isSeller = true, shopName = shopName.trim()))
    }

    // --- customers (seller-scoped) -------------------------------------------

    override fun observeCustomers(sellerId: String): Flow<List<Customer>> =
        // Balance each listed customer against this seller's ledger. Kept reactive by
        // combining the seller's customer rows with the whole ledger.
        combine(customers.observeForSeller(sellerId), transactions.observeAll()) { rows, ledgerRaw ->
            val ledger = ledgerRaw.map { it.toDomain() }
            rows.map { it.toDomain(balanceOf(sellerId, it.customerId, ledger)) }
        }

    override suspend fun addCustomer(displayName: String, phone: String): String {
        val id = UUID.randomUUID().toString()
        customers.insert(
            com.example.app_pos.data.db.entity.CustomerEntity(
                customerId = id,
                displayName = displayName.trim(),
                phone = storedPhone(phone),
                claimStatus = com.example.app_pos.model.ClaimStatus.UNCLAIMED.name,
                claimedByUserId = null,
                createdAt = nowStamp()
            )
        )
        return id
    }

    override suspend fun lookupCustomerForSeller(sellerId: String, phone: String): CustomerLookup {
        val stored = storedPhone(phone)
        val row = customers.findByPhone(stored) ?: return CustomerLookup.New
        val existing = row.toDomain(balanceOfCustomer(sellerId, row.customerId))
        // Mine = we already share at least one ledger entry. Otherwise the person is
        // simply known to another shop, and this seller may add them to their own book.
        return if (customers.countForSellerByPhone(sellerId, stored) > 0) {
            CustomerLookup.AlreadyMine(existing)
        } else {
            CustomerLookup.KnownToOtherSeller(existing)
        }
    }

    override suspend fun findCustomerById(sellerId: String, customerId: String): Customer? {
        val row = customers.findById(customerId) ?: return null
        return row.toDomain(balanceOfCustomer(sellerId, customerId))
    }

    override suspend fun findCustomerByPhone(sellerId: String, phone: String): Customer? {
        val row = customers.findByPhone(storedPhone(phone)) ?: return null
        return row.toDomain(balanceOfCustomer(sellerId, row.customerId))
    }

    // --- ledger --------------------------------------------------------------

    override fun observeTransactions(sellerId: String, customerId: String): Flow<List<Transaction>> =
        transactions.observeForSellerCustomer(sellerId, customerId).map { list -> list.map { it.toDomain() } }

    override fun observeTotalReceivableMinor(sellerId: String): Flow<Long> =
        transactions.observeTotalReceivable(sellerId)

    override fun observeBalance(sellerId: String, customerId: String): Flow<Long> =
        transactions.observeBalance(sellerId, customerId)

    /**
     * Books a ledger entry AND queues it for the server, atomically.
     *
     * The two have to happen together or not at all. Written separately, a process death
     * in between leaves either an entry the server will never hear about, or a queued send
     * for an entry that was never booked — and neither is detectable afterwards.
     * withTransaction makes the pair a single unit: the database either has both rows or
     * neither.
     *
     * The queue row carries a ready-made request body ([sendPayload]), not a reference to
     * the ledger row. Two reasons: the server must receive exactly what was agreed at
     * approval time, and serialising the wire shape is the network layer's job — this class
     * stores the string without knowing what is in it.
     *
     * The Repository interface's addTransaction delegates here with the payload it built,
     * so the domain contract stays free of any mention of a queue.
     */
    override suspend fun addTransactionQueued(
        transaction: Transaction,
        orderBody: OrderBody?,
        sendPayload: String
    ) {
        db.withTransaction {
            // When a basket rode along on the handoff, persist it first and link the entry;
            // a money-only entry links no basket (basketId = null).
            val basketId = orderBody?.let { ob ->
                baskets.insertBasket(ob.toBasketEntity(transaction.createdAt))
                baskets.insertItems(ob.toItemEntities())
                ob.basketId
            }
            transactions.insert(transaction.toEntity(basketId))
            outbox.insert(
                OutboxEntity(
                    // The transaction id IS the queue id, which is what makes enqueueing
                    // idempotent (see OutboxDao.insert) and matches the Idempotency-Key the
                    // send will carry.
                    id = transaction.transactionId,
                    transactionId = transaction.transactionId,
                    payload = sendPayload,
                    createdAt = transaction.createdAt,
                    retryCount = 0
                )
            )
        }
    }

    /**
     * Books a ledger entry WITHOUT queueing it — the plain contract method.
     *
     * Nothing in app-pos calls this any more (the repository always queues), but it keeps
     * this class a complete Repository on its own, which is what lets the session tests
     * substitute a local source without knowing about the outbox.
     */
    override suspend fun addTransaction(transaction: Transaction, orderBody: OrderBody?) {
        db.withTransaction {
            val basketId = orderBody?.let { ob ->
                baskets.insertBasket(ob.toBasketEntity(transaction.createdAt))
                baskets.insertItems(ob.toItemEntities())
                ob.basketId
            }
            transactions.insert(transaction.toEntity(basketId))
        }
    }

    // --- approvals (the incoming inbox) --------------------------------------

    override fun observePendingApprovals(userId: String): Flow<List<PendingApproval>> =
        approvals.observePendingFor(userId).map { list -> list.map { it.toDomain() } }

    /**
     * Local-only fallbacks. Answering an approval is a decision the COUNTERPARTY is waiting
     * on, so the composing repository sends it to the server and overrides both of these;
     * they exist because LocalSource carries the whole Repository surface.
     */
    override suspend fun approvePending(approvalId: String): DecisionOutcome =
        DecisionOutcome.Unreachable

    override suspend fun rejectPending(approvalId: String): DecisionOutcome =
        DecisionOutcome.Unreachable

    /** Storage cannot pull — there is no network here. See [syncNow] for the same shape. */
    override suspend fun refreshApprovals(): PullOutcome = PullOutcome.Unreachable

    /** Same as above: storage has no network. */
    override suspend fun refreshBook(): PullOutcome = PullOutcome.Unreachable

    override suspend fun syncApprovals(rows: List<ApprovalEntity>, targetUserId: String) {
        db.withTransaction {
            // Delete first, then insert: the reverse order would briefly hold rows the
            // server just returned AND rows it dropped.
            if (rows.isEmpty()) {
                approvals.deleteAllPendingFor(targetUserId)
            } else {
                approvals.deletePendingNotIn(targetUserId, rows.map { it.approvalId })
            }
            // REPLACE, so a row whose status changed server-side overwrites the stale copy.
            rows.forEach { approvals.insert(it) }
        }
    }

    override suspend fun storeCustomers(rows: List<Customer>) {
        db.withTransaction {
            rows.forEach { customer ->
                customers.upsert(
                    CustomerEntity(
                        customerId = customer.customerId,
                        displayName = customer.displayName,
                        phone = customer.phone.orEmpty(),
                        claimStatus = customer.claimStatus.name,
                        claimedByUserId = customer.claimedByUserId,
                        // The server does not send a created-at for customers, and this
                        // column only orders local lists. An existing row keeps whatever it
                        // had; a new one is stamped now.
                        createdAt = customers.findById(customer.customerId)?.createdAt ?: nowStamp()
                    )
                )
            }
        }
        // The balance the server sent is intentionally dropped: every screen derives it from
        // the ledger, and a stored second copy is how two numbers begin to disagree.
    }

    override suspend fun storeLedger(entries: List<Transaction>) {
        db.withTransaction {
            // insert-IGNORE keyed by the server's transaction id, so re-pulling the same
            // history is a no-op rather than a duplicate.
            entries.forEach { transactions.insert(it.toEntity(basketId = null)) }
        }
    }

    override suspend fun markApprovalDecided(approvalId: String, status: String) {
        approvals.setStatus(approvalId, status)
    }

    override suspend fun deleteApproval(approvalId: String) {
        approvals.delete(approvalId)
    }

    /**
     * No-op here: this class is the LOCAL half and owns no network. Draining the queue
     * needs a remote source, so it belongs to the composing repository — which overrides
     * this. Present only because LocalSource extends the full Repository contract.
     */
    override suspend fun syncNow(): SyncOutcome = SyncOutcome()

    /** Rows still waiting to reach the server, oldest first. */
    override suspend fun pendingOutbox(): List<OutboxEntity> = outbox.all()

    override suspend fun deleteOutbox(id: String) = outbox.delete(id)

    override suspend fun recordOutboxFailure(id: String) = outbox.recordFailure(id)

    /** How many writes are unsent; drives a sync indicator later. */
    override fun observeUnsentCount(): Flow<Int> = outbox.observeCount()

    // --- helpers -------------------------------------------------------------

    private suspend fun balanceOfCustomer(sellerId: String, customerId: String): Long {
        // A one-shot balance for a point read (find*). Reactive reads use the DAO SUM Flow.
        val ledger = transactions.allOnce().map { it.toDomain() }
        return balanceOf(sellerId, customerId, ledger)
    }

    /**
     * The ONE canonical form, for storing AND for querying.
     *
     * Delegates to PhoneFormat so the data layer cannot drift from the rest of the system —
     * a second implementation here is precisely what produced the bug this replaced. The
     * digits-only fallback keeps a number PhoneFormat rejects (a landline, a foreign
     * number) storable and findable, since both sides now go through this same function.
     */
    private fun storedPhone(input: String): String =
        PhoneFormat.toStored(input) ?: input.filter { it.isDigit() }

    // ISO-8601 UTC, the format the wire contract uses and the one the DAOs sort on.
    // SimpleDateFormat rather than java.time because minSdk is 24 and core library
    // desugaring is deliberately not enabled (see the module build file).
    private fun nowStamp(): String = isoFormat().format(java.util.Date())

    private companion object {
        const val SESSION_TTL_MILLIS = 7L * 24 * 60 * 60 * 1000

        /**
         * A new formatter per call: SimpleDateFormat is not thread-safe and writes here
         * arrive on whichever coroutine dispatcher the caller used. Locale.ROOT keeps the
         * digits ASCII regardless of the device locale.
         */
        fun isoFormat(): java.text.SimpleDateFormat =
            java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.ROOT).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }
    }
}

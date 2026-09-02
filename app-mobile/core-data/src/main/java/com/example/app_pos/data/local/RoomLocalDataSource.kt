package com.example.app_pos.data.local

import androidx.room.withTransaction
import com.example.app_pos.data.ApprovalService
import com.example.app_pos.data.db.AppDatabase
import com.example.app_pos.data.db.entity.ApprovalEntity
import com.example.app_pos.data.db.entity.CustomerEntity
import com.example.app_pos.data.db.entity.FxRateEntity
import com.example.app_pos.data.db.entity.OutboxEntity
import com.example.app_pos.data.db.entity.UserEntity
import com.example.app_pos.data.db.toBasketEntity
import com.example.app_pos.data.db.toDomain
import com.example.app_pos.data.db.toEntity
import com.example.app_pos.data.db.toItemEntities
import com.example.app_pos.model.ApprovalOutcome
import com.example.app_pos.model.ClaimStatus
import com.example.app_pos.model.Customer
import com.example.app_pos.model.CustomerCreateOutcome
import com.example.app_pos.model.CustomerLookup
import com.example.app_pos.model.DecisionOutcome
import com.example.app_pos.model.FxSnapshot
import com.example.app_pos.model.PendingApproval
import com.example.app_pos.model.PAYMENT_DESCRIPTION
import com.example.app_pos.model.PhoneFormat
import com.example.app_pos.model.OtpRequestResult
import com.example.app_pos.model.PullOutcome
import com.example.app_pos.model.Repository
import com.example.app_pos.model.SellerDebt
import com.example.app_pos.model.SignInResult
import com.example.app_pos.model.SyncOutcome
import com.example.app_pos.model.Transaction
import com.example.app_pos.model.TransactionDetail
import com.example.app_pos.model.TransactionType
import com.example.app_pos.model.User
import com.example.app_pos.model.balanceOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.util.UUID

/**
 * Room-backed storage: everything the app keeps on the device.
 *
 * What persists: users, customers, the append-only ledger, approvals, and the outbox.
 * Balances are never stored; they are summed from the ledger (DAO SUM or the pure balanceOf).
 *
 * The session members here are a RAM-only stub that [com.example.app_pos.data.OfflineFirstRepository]
 * OVERRIDES with the persisted TokenStore. They stay because this type implements the whole
 * [Repository] surface — which is what lets a JVM test swap it in without Room — but nothing
 * in the running app reads them.
 */
class RoomLocalDataSource(private val db: AppDatabase) : LocalSource {

    private val users = db.userDao()
    private val customers = db.customerDao()
    private val transactions = db.transactionDao()
    private val approvals = db.approvalDao()
    private val baskets = db.basketDao()
    private val outbox = db.outboxDao()
    private val fxRates = db.fxRateDao()

    // --- session + pairing (RAM stub — the composing repository overrides these) ----

    private data class Session(val userId: String, val token: String, val expiresAt: Long)
    private val session = MutableStateFlow<Session?>(null)
    private val _isPairedWithApp = MutableStateFlow(false)
    override val isPairedWithApp: Flow<Boolean> = _isPairedWithApp.asStateFlow()

    override fun isSessionValid(): Boolean =
        session.value?.let { it.expiresAt > System.currentTimeMillis() } == true

    override fun currentUserId(): String? =
        session.value?.takeIf { it.expiresAt > System.currentTimeMillis() }?.userId

    /** No local OTP: only the server can send one. Always false here. */
    override suspend fun requestOtp(phone: String): OtpRequestResult = OtpRequestResult.Unreachable

    /**
     * Storage cannot verify a code — that is the server's job. Reports the number as
     * unregistered so a caller that reached this stub fails loudly rather than appearing
     * to sign someone in.
     */
    override suspend fun signIn(phone: String, code: String): SignInResult =
        SignInResult.NeedsRegister

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

    // --- users / profile -----------------------------------------------------

    override fun observeAllUsers(): Flow<List<User>> =
        users.observeAll().map { list -> list.map { it.toDomain() } }

    override suspend fun upsertUser(user: User) = users.upsert(user.toEntity())

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

    override suspend fun updateShopName(userId: String, shopName: String) {
        // Keeps any existing shopPhone (unlike setSeller, which replaces the whole info).
        val existing = users.findById(userId) ?: return
        users.update(existing.copy(isSeller = true, shopName = shopName.trim()))
    }

    override suspend fun updateDisplayName(userId: String, displayName: String) {
        val existing = users.findById(userId) ?: return
        users.update(existing.copy(displayName = displayName.trim()))
    }

    override suspend fun updateEmail(userId: String, email: String) {
        val existing = users.findById(userId) ?: return
        users.update(existing.copy(email = email.trim().ifBlank { null }))
    }

    override suspend fun shopNameOf(sellerId: String): String =
        users.findById(sellerId)?.shopName ?: sellerId

    override suspend fun shopPhoneOf(sellerId: String): String? =
        users.findById(sellerId)?.shopPhone

    // Room re-emits when the row lands, which is the whole point: the shop's number is
    // written by the ledger pull, often after the screen observing it is already open.
    override fun observeShopPhone(sellerId: String): Flow<String?> =
        users.observeById(sellerId).map { it?.shopPhone }

    // --- claim ---------------------------------------------------------------

    override suspend fun claimCustomerForUser(userId: String, phone: String): List<Customer> {
        // Both sides canonical: the stored column is E.164 and so is this argument, so the
        // comparison is a plain equality. Comparing loosely-normalised digits here is what
        // used to make the claim miss every row without saying so.
        customers.claimByPhone(userId, storedPhone(phone))
        // The claimed records carry no balance here: the claim is seller-independent
        // (it links an identity), and every screen derives balances per seller anyway.
        return customers.claimedBy(userId).map { it.toDomain(0L) }
    }

    // --- customers (seller-scoped) -------------------------------------------

    override fun observeCustomers(sellerId: String): Flow<List<Customer>> =
        // Balance each listed customer against this seller's ledger. Kept reactive by
        // combining the seller's customer rows with the whole ledger.
        combine(customers.observeForSeller(sellerId), transactions.observeAll()) { rows, ledgerRaw ->
            val ledger = ledgerRaw.map { it.toDomain() }
            rows.map { it.toDomain(balanceOf(sellerId, it.customerId, ledger)) }
        }

    /**
     * Storage cannot open a customer record: the SERVER mints the id.
     *
     * This used to mint a local UUID the server had never heard of — an id that any entry
     * written against it would be rejected for. The composing repository overrides this
     * with the real thing; reported as Unreachable rather than inventing a row, so a caller
     * wired only to storage cannot quietly reintroduce the same bug.
     */
    override suspend fun addCustomer(
        displayName: String,
        phone: String
    ): CustomerCreateOutcome = CustomerCreateOutcome.Unreachable

    override suspend fun storeCustomers(rows: List<Customer>) {
        db.withTransaction {
            rows.forEach { customer ->
                customers.upsert(
                    CustomerEntity(
                        customerId = customer.customerId,
                        displayName = customer.displayName,
                        phone = customer.phone.orEmpty(),
                        claimStatus = customer.claimStatus.name,
                        // Kept ONLY when that user row exists locally: customers has a FK to
                        // users on this column, and this device holds a user row for its own
                        // account and the shops it owes — not for other people. On the claim
                        // path the id IS this user, so it survives; a record claimed by
                        // somebody else would otherwise crash the write on a FK violation.
                        // claimStatus still carries "this belongs to an account".
                        claimedByUserId = customer.claimedByUserId
                            ?.takeIf { users.findById(it) != null },
                        createdBySellerId = customer.createdBySellerId,
                        // The server sends no created-at for customers, and this column only
                        // orders local lists. An existing row keeps what it had.
                        createdAt = customers.findById(customer.customerId)?.createdAt ?: nowStamp()
                    )
                )
            }
        }
        // The server's balance is intentionally dropped: every screen derives it from the
        // ledger, and a stored second copy is how two numbers begin to disagree.
    }

    override suspend fun findCustomerById(sellerId: String, customerId: String): Customer? {
        val row = customers.findById(customerId) ?: return null
        return row.toDomain(balanceOfCustomer(sellerId, customerId))
    }

    override suspend fun findCustomerByPhone(sellerId: String, phone: String): Customer? {
        val row = customers.findByPhone(storedPhone(phone)) ?: return null
        return row.toDomain(balanceOfCustomer(sellerId, row.customerId))
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

    // --- ledger (seller-scoped) ----------------------------------------------

    override fun observeTransactions(sellerId: String, customerId: String): Flow<List<Transaction>> =
        transactions.observeForSellerCustomer(sellerId, customerId).map { list -> list.map { it.toDomain() } }

    override fun observeTotalReceivableMinor(sellerId: String): Flow<Long> =
        transactions.observeTotalReceivable(sellerId)

    override fun observeAllForSeller(sellerId: String): Flow<List<Transaction>> =
        transactions.observeAllForSeller(sellerId).map { list -> list.map { it.toDomain() } }

    // --- ledger (buyer-scoped) -----------------------------------------------

    override fun observeMyDebtsBySeller(userId: String): Flow<List<SellerDebt>> =
        transactions.observeDebtsBySeller(userId).map { rows ->
            // Sorted here rather than in SQL so the shop-name fallback and the ordering
            // stay in one place (mirrors the fake's sortedByDescending).
            rows.map { it.toDomain() }.sortedByDescending { it.balanceMinor }
        }

    override fun observeAllForBuyer(userId: String): Flow<List<Transaction>> =
        transactions.observeAllForBuyer(userId).map { list -> list.map { it.toDomain() } }

    /**
     * The entry and its basket, read together.
     *
     * Inside one db.withTransaction so all three reads see the same database. They are
     * reads on an append-only table and the risk is small, but a receipt is exactly the
     * place where "the lines came from a slightly different moment than the total" is worst.
     *
     * A null basketId is the ordinary case: money-only handoffs, payments, and the
     * server-written indexation rows all have nothing to itemise.
     */
    override suspend fun transactionDetail(transactionId: String): TransactionDetail? =
        db.withTransaction {
            val entity = transactions.findById(transactionId) ?: return@withTransaction null
            val basket = entity.basketId?.let { basketId ->
                baskets.header(basketId)?.toDomain(baskets.itemsFor(basketId))
            }
            TransactionDetail(entity.toDomain(), basket)
        }

    /**
     * Storage has no network, and the fx table is a cache the repository fills.
     *
     * Null here rather than a read of fx_rates: this class is also the substitute a JVM
     * test swaps in, and answering from a table nothing has populated would look like a
     * series with no readings rather than like storage that was never asked to keep one.
     * The composing repository owns the cache and overrides this.
     */
    override suspend fun fxRateAt(asOf: String): FxSnapshot? = null

    /**
     * The fx cache. Reference data the repository fills from the server, kept so a screen
     * that showed context once can show it again with no signal.
     *
     * A past date's rate never changes, so there is nothing to invalidate: a row here is
     * correct for as long as the table survives.
     */
    override suspend fun cachedFxRate(asOf: String): FxSnapshot? =
        fxRates.nearest(asOf)?.let {
            FxSnapshot(
                asOf = it.asOf,
                usdMinor = it.usdMinor,
                eurMinor = it.eurMinor,
                goldMinor = it.goldMinor
            )
        }

    override suspend fun cacheFxRate(snapshot: FxSnapshot) =
        fxRates.insert(
            FxRateEntity(
                asOf = snapshot.asOf,
                usdMinor = snapshot.usdMinor,
                eurMinor = snapshot.eurMinor,
                goldMinor = snapshot.goldMinor
            )
        )

    override fun observeMyTotalDebtMinor(userId: String): Flow<Long> =
        transactions.observeBuyerTotalDebt(userId)

    override fun observeMyTransactions(userId: String, sellerId: String): Flow<List<Transaction>> =
        transactions.observeForBuyerSeller(userId, sellerId).map { list -> list.map { it.toDomain() } }

    override fun observeMyBalanceWithSeller(userId: String, sellerId: String): Flow<Long> =
        transactions.observeBuyerBalanceWithSeller(userId, sellerId)

    // --- approvals -----------------------------------------------------------

    override fun observePendingApprovals(userId: String): Flow<List<PendingApproval>> =
        approvals.observePendingFor(userId).map { list -> list.map { it.toDomain() } }

    override suspend fun approvePending(approvalId: String): DecisionOutcome {
        val approval = approvals.findById(approvalId) ?: return DecisionOutcome.AlreadyDecided
        addTransaction(
            Transaction(
                transactionId = UUID.randomUUID().toString(),
                sellerId = approval.sellerId,
                // The record the request was raised against — not re-resolved here, so
                // the entry always lands in the same book the requester intended.
                customerId = approval.customerId,
                amountMinor = approval.amountMinor,
                type = TransactionType.valueOf(approval.type),
                description = approval.description.orEmpty(),
                createdAt = nowStamp()
            )
        )
        // Decided rows are kept (status change, not delete) so the trail survives; the
        // pending query filters on status, so the buyer's list looks the same as before.
        approvals.setStatus(approvalId, "APPROVED")
        return DecisionOutcome.Applied
    }

    override suspend fun markApprovalDecided(approvalId: String, status: String) {
        approvals.setStatus(approvalId, status)
    }

    override suspend fun deleteApproval(approvalId: String) {
        approvals.delete(approvalId)
    }

    override suspend fun insertPendingApproval(approval: PendingApproval, initiatorUserId: String) {
        approvals.insert(approval.toEntity(initiatorUserId))
    }

    /**
     * Storage cannot pull — there is no network down here. The composing repository
     * overrides this with the real thing, exactly as it does for the session members.
     * Reported as unreachable rather than a success so a caller wired only to storage never
     * concludes the inbox is empty.
     */
    override suspend fun refreshApprovals(): PullOutcome = PullOutcome.Unreachable

    /** Same as above: storage has no network. */
    override suspend fun refreshMyLedger(): PullOutcome = PullOutcome.Unreachable

    /** Same as above: storage has no network. */
    override suspend fun refreshBook(): PullOutcome = PullOutcome.Unreachable

    override suspend fun syncApprovals(rows: List<ApprovalEntity>, targetUserId: String) {
        db.withTransaction {
            // Delete first, then insert: the reverse order would briefly hold rows the
            // server just returned AND rows it dropped, and a NOT IN over the incoming ids
            // is cheaper to reason about before anything is added.
            if (rows.isEmpty()) {
                approvals.deleteAllPendingFor(targetUserId)
            } else {
                approvals.deletePendingNotIn(targetUserId, rows.map { it.approvalId })
            }
            // REPLACE, so a row whose status changed server-side overwrites the stale copy
            // instead of being ignored.
            rows.forEach { approvals.insert(it) }
        }
    }

    override suspend fun storeBuyerLedger(entries: List<Transaction>, userId: String) {
        db.withTransaction {
            // The customer rows FIRST: the ledger queries filter through them, so entries
            // written without them would be stored and still invisible on every screen.
            //
            // These rows carry only an id and an owner — the buyer cannot read a seller's
            // book, so the server never tells them the name or phone on the shop's copy.
            //
            // insert-IGNORE, and that is load-bearing: this app is ALSO a seller, and its
            // Müşterilerim screen reads the same table. An upsert here would overwrite a
            // real customer record — name, phone and all — with these blanks whenever the
            // same person appears on both sides. (Seen on a device: a customer rendered
            // with an empty name and no number.) Existing rows are left exactly as they are.
            entries.map { it.customerId }.distinct().forEach { customerId ->
                customers.insert(
                    CustomerEntity(
                        customerId = customerId,
                        // A placeholder the UI can recognise, rather than a blank line.
                        displayName = "",
                        phone = "",
                        claimStatus = ClaimStatus.CLAIMED.name,
                        claimedByUserId = userId,
                        // NULL, deliberately: this is the buyer's stub for a record kept in
                        // somebody ELSE's book. Naming this device's user here would list
                        // the row on their own Müşterilerim, which is the same leak the
                        // claimedByUserId exclusion in observeForSeller exists to stop.
                        createdBySellerId = null,
                        createdAt = nowStamp()
                    )
                )
            }

            // insert-IGNORE, keyed by the server's transaction id: re-pulling the same
            // history is a no-op rather than a duplicate, which is what makes polling this
            // safe. The ledger is append-only, so a row already here is already correct.
            entries.forEach { transactions.insert(it.toEntity(storeBasket(it))) }
        }
    }

    override suspend fun storeLedger(entries: List<Transaction>) {
        db.withTransaction {
            // No customer rows are derived here, unlike storeBuyerLedger: the seller pull
            // already stored them from GET /customers, filled in. Deriving blanks on top
            // would undo that.
            //
            // insert-IGNORE keyed by the server's transaction id, so re-pulling the same
            // history is a no-op rather than a duplicate.
            entries.forEach { transactions.insert(it.toEntity(storeBasket(it))) }
        }
    }

    /**
     * Writes a pulled entry's basket, if it brought one, and reports the id to link.
     *
     * This app raises no baskets of its own, but the shop's ride along on every entry the
     * server hands back, and until now they were parsed off the wire and then dropped here.
     *
     * Ordered basket-first so the entry's foreign key has something to point at. Both
     * inserts IGNORE, which is what makes re-pulling the same history harmless -- and see
     * OrderBody.toItemEntities for why the line ids have to be derived rather than random
     * for that to be true.
     *
     * Call inside an existing db.withTransaction: a basket committed apart from its entry
     * could outlive one that failed to write.
     */
    private suspend fun storeBasket(transaction: Transaction): String? =
        transaction.basket?.let { basket ->
            baskets.insertBasket(basket.toBasketEntity(transaction.createdAt))
            baskets.insertItems(basket.toItemEntities())
            basket.basketId
        }

    override suspend fun storeShopNames(shopsBySellerId: Map<String, Pair<String, String?>>) {
        db.withTransaction {
            shopsBySellerId.forEach { (sellerId, shop) ->
                val (shopName, shopPhone) = shop
                val existing = users.findById(sellerId)
                if (existing == null) {
                    // A shop this device has never seen. Only the name and number are
                    // known — a buyer cannot read another account — and only those render.
                    users.insert(
                        UserEntity(
                            userId = sellerId,
                            phone = shopPhone.orEmpty(),
                            displayName = shopName,
                            isBuyer = false,
                            isSeller = true,
                            email = null,
                            shopName = shopName,
                            shopPhone = shopPhone,
                            createdAt = nowStamp()
                        )
                    )
                } else {
                    // Update ONLY the shop fields. A blanket upsert would overwrite the
                    // signed-in user's own row with these values when they are also a
                    // seller — wiping their profile.
                    users.update(
                        existing.copy(
                            shopName = shopName,
                            // Keep a number already known locally: the seller's own copy of
                            // their profile is better than this denormalised fallback.
                            shopPhone = existing.shopPhone ?: shopPhone,
                            isSeller = true
                        )
                    )
                }
            }
        }
    }

    override suspend fun customerIdForBuyerSeller(userId: String, sellerId: String): String? =
        transactions.customerIdForBuyerSeller(userId, sellerId)

    override suspend fun rejectPending(approvalId: String): DecisionOutcome {
        approvals.setStatus(approvalId, "REJECTED")
        return DecisionOutcome.Applied
    }

    override suspend fun requestApproval(
        fromUserId: String,
        sellerId: String,
        customerId: String,
        amountMinor: Long,
        type: TransactionType,
        description: String
    ): ApprovalOutcome {
        // Reports the miss instead of returning silently: a caller that got Unit here could
        // not tell "written" from "nothing happened", and the payment screen said success
        // either way.
        val row = customers.findById(customerId) ?: return ApprovalOutcome.NoCustomerRecord
        ApprovalService.requestApproval(fromUserId, row.phone, amountMinor, type.name)

        // The COUNTERPARTY approves, never the initiator — that is the whole point of the
        // gate. Which side that is depends on who started it: a seller writing to their
        // book needs the customer's approval; a buyer paying needs the seller's (they
        // confirm receipt). Deriving it from the customer record alone would send a
        // buyer-initiated payment back to the buyer.
        val approverUserId =
            if (fromUserId == sellerId) row.claimedByUserId   // seller → the customer
            else sellerId                                     // buyer  → the shop
        if (approverUserId != null) {
            // Has the app: raise a pending approval; nothing reaches the ledger yet.
            // The card names the OTHER side, so it reads right whichever way it points.
            val counterpartyName =
                if (fromUserId == sellerId) shopNameOf(sellerId) else row.displayName
            // Held rather than inlined: the outcome carries it back, so a caller that has
            // to wait for the answer can ask about this exact request.
            val approvalId = UUID.randomUUID().toString()
            approvals.insert(
                PendingApproval(
                    approvalId = approvalId,
                    sellerId = sellerId,
                    counterpartyName = counterpartyName,
                    approverUserId = approverUserId,
                    customerId = customerId,
                    amountMinor = amountMinor,
                    type = type,
                    description = description,
                    requestedAt = nowStamp()
                ).toEntity(initiatorUserId = fromUserId)
            )
            return ApprovalOutcome.SentForApproval(approvalId)
        }

        // No app (UNCLAIMED): SMS-OTP case, mocked true → write immediately.
        //
        // Only reached OFFLINE now. When the server is reachable the composing repository
        // applies ITS branch instead, because this one reads a local claimedByUserId that
        // can be stale — and a stale null wrote entries straight to the ledger with no
        // approval at all.
        addTransaction(
            Transaction(
                transactionId = UUID.randomUUID().toString(),
                sellerId = sellerId,
                customerId = customerId,
                amountMinor = amountMinor,
                type = type,
                description = description,
                createdAt = nowStamp()
            )
        )
        return ApprovalOutcome.WrittenImmediately
    }

    /**
     * Storage cannot reach a till. Answering false rather than pretending: the composing
     * repository sends this to the server, and a caller wired only to storage must not be
     * told a terminal was notified when nothing left the device.
     */
    override suspend fun collectAtTerminal(customerId: String, amountMinor: Long): Boolean = false

    override suspend fun initiatePayment(
        userId: String,
        sellerId: String,
        amountMinor: Long
    ): ApprovalOutcome {
        // No shared record with this seller means there is nothing to pay against; the
        // caller reports that rather than guessing at another shop's record.
        val customerId = transactions.customerIdForBuyerSeller(userId, sellerId)
            ?: return ApprovalOutcome.NoCustomerRecord
        // Returns what the approval actually did, instead of the unconditional `true` that
        // let the screen announce a payment nothing had recorded.
        return requestApproval(
            fromUserId = userId,
            sellerId = sellerId,
            customerId = customerId,
            amountMinor = amountMinor,
            type = TransactionType.PAYMENT,
            description = PAYMENT_DESCRIPTION
        )
    }

    /**
     * Books a ledger entry WITHOUT queueing it — the plain contract method.
     *
     * Used by the approval path, which reaches the server through /approvals rather than
     * through the outbox: by the time an approval is granted the server has already written
     * its own copy, so queueing a second send would book the entry twice.
     */
    override suspend fun addTransaction(transaction: Transaction) {
        // Wrapped, now that a basket may be written alongside: the two rows have to land
        // together or not at all. Nothing on this side attaches a basket here today -- the
        // approval path books money-only entries -- but writing `null` in place of the call
        // would hardcode that assumption one layer below where it is actually decided.
        db.withTransaction {
            transactions.insert(transaction.toEntity(storeBasket(transaction)))
        }
    }

    // --- the offline outbox --------------------------------------------------

    override suspend fun addTransactionQueued(transaction: Transaction, sendPayload: String) {
        // ONE transaction for both writes. Written separately, a process death in between
        // would leave either an entry the server never hears about, or a queued send for an
        // entry that was never booked — and neither is detectable afterwards.
        db.withTransaction {
            transactions.insert(transaction.toEntity(storeBasket(transaction)))
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

    override suspend fun pendingOutbox(): List<OutboxEntity> = outbox.all()

    override suspend fun deleteOutbox(id: String) = outbox.delete(id)

    override suspend fun recordOutboxFailure(id: String) = outbox.recordFailure(id)

    /** How many writes are unsent; drives a sync indicator later. */
    override fun observeUnsentCount(): Flow<Int> = outbox.observeCount()

    /**
     * Storage does not reach the network, so there is nothing to drain here. The composing
     * repository delegates to SyncEngine instead.
     */
    override suspend fun syncNow(): SyncOutcome = SyncOutcome()

    // --- helpers -------------------------------------------------------------

    private suspend fun balanceOfCustomer(sellerId: String, customerId: String): Long {
        // A one-shot balance for a point read (find*). Reactive reads use the DAO SUM Flow.
        val ledger = transactions.allOnce().map { it.toDomain() }
        return balanceOf(sellerId, customerId, ledger)
    }

    /**
     * The one conversion used for BOTH storing and querying, delegated to [PhoneFormat] so
     * this layer cannot drift from it.
     *
     * A number the canonical form rejects (not a valid TR number) falls back to its bare
     * digits. That keeps an odd entry storable and findable — but only by an identical odd
     * entry, since the fallback is applied on both sides too.
     */
    private fun storedPhone(input: String): String =
        PhoneFormat.toStored(input) ?: input.filter { it.isDigit() }

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

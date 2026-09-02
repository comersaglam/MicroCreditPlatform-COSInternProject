package com.example.app_pos.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import com.example.app_pos.data.db.entity.ApprovalEntity
import com.example.app_pos.data.db.entity.AuditLogEntity
import com.example.app_pos.data.db.entity.BasketEntity
import com.example.app_pos.data.db.entity.BasketItemEntity
import com.example.app_pos.data.db.entity.CreditOfferEntity
import com.example.app_pos.data.db.entity.CustomerEntity
import com.example.app_pos.data.db.entity.DeviceEntity
import com.example.app_pos.data.db.entity.FxRateEntity
import com.example.app_pos.data.db.entity.OutboxEntity
import com.example.app_pos.data.db.entity.TransactionEntity
import com.example.app_pos.data.db.entity.UserEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAOs — one query per read/write FakeRepository exposes today. Reads return Flow, so
 * a write is seen by every screen at once (exactly how FakeRepository's StateFlows
 * behave, which is why the ViewModels do not change when Room replaces the fake).
 *
 * The ledger is append-only: TransactionDao has @Insert but NO update/delete. Balances
 * are computed with SUM queries, never stored.
 */

// createdAt is ISO-8601 UTC ("2026-07-20T09:15:00Z"), which sorts chronologically as
// plain text — most significant field first. That is why these queries can ORDER BY the
// column directly; the earlier "dd.MM.yyyy HH:mm" format needed a substr rebuild because
// it compared the day before the year.

@Dao
interface UserDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(user: UserEntity)

    /**
     * Writes the row whether or not it exists, unlike [insert]'s IGNORE.
     *
     * Used to mirror the account the server just authenticated: the server owns the id and
     * the profile, so a local copy that silently refused to update would drift from it.
     */
    @Upsert
    suspend fun upsert(user: UserEntity)

    @Update
    suspend fun update(user: UserEntity)

    @Query("SELECT * FROM users WHERE userId = :userId")
    fun observeById(userId: String): Flow<UserEntity?>

    @Query("SELECT * FROM users")
    fun observeAll(): Flow<List<UserEntity>>

    @Query("SELECT * FROM users WHERE userId = :userId")
    suspend fun findById(userId: String): UserEntity?

    /**
     * Exact match on the canonical E.164 form, which callers produce with
     * `PhoneFormat.toStored` BEFORE querying.
     *
     * Was a REPLACE + `LIKE '%..%'` scan, which quietly disagreed with CustomerDao's exact
     * comparison below: sign-in "worked" because a substring matched, while the claim found
     * nothing and left customers UNCLAIMED. Normalising in ONE place and comparing plainly
     * removes that whole class of mismatch — and lets the column's index be used.
     */
    @Query("SELECT * FROM users WHERE phone = :stored LIMIT 1")
    suspend fun findByPhone(stored: String): UserEntity?
}

@Dao
interface CustomerDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(customer: CustomerEntity)

    /**
     * Writes a record the SERVER owns, overwriting any local copy.
     *
     * [insert] above is IGNORE, which is right when this terminal is inventing a record and
     * wrong for a pull: a name corrected elsewhere, or a claim that has since happened,
     * would be silently discarded and the stale local row kept.
     */
    @Upsert
    suspend fun upsert(customer: CustomerEntity)

    @Query("SELECT * FROM customers WHERE customerId = :id")
    suspend fun findById(id: String): CustomerEntity?

    @Query("SELECT * FROM customers")
    fun observeAll(): Flow<List<CustomerEntity>>

    /**
     * The seller's customers: everyone they have an entry with, PLUS everyone they wrote
     * down and have not charged yet.
     *
     * The second half is not a convenience. Ledger membership alone means a customer only
     * joins the book on their first entry, so the person just added vanished from the list
     * that added them — they were stored, and invisible, until money changed hands. The
     * server draws the same union for the same reason (backend `_book_customer_ids`).
     */
    @Query(
        "SELECT * FROM customers WHERE customerId IN " +
            "(SELECT DISTINCT customerId FROM transactions WHERE sellerId = :sellerId) " +
            "OR createdBySellerId = :sellerId"
    )
    fun observeForSeller(sellerId: String): Flow<List<CustomerEntity>>

    /**
     * Is this phone already in THIS seller's book? Ownership lives in the ledger, not
     * on the customer row (docs/architecture-pos.md §4), so "my customer" means "we
     * have at least one entry together". A person known to another seller is NOT in
     * this seller's book and can be added to it.
     */
    @Query(
        "SELECT COUNT(*) FROM customers c WHERE c.phone = :stored AND " +
            "EXISTS (SELECT 1 FROM transactions t WHERE t.customerId = c.customerId " +
            "AND t.sellerId = :sellerId)"
    )
    suspend fun countForSellerByPhone(sellerId: String, stored: String): Int

    /** Exact match on the canonical form — see UserDao.findByPhone. */
    @Query("SELECT * FROM customers WHERE phone = :stored LIMIT 1")
    suspend fun findByPhone(stored: String): CustomerEntity?

    /** The claim bridge: link every UNCLAIMED record with this phone to a user. */
    @Query(
        "UPDATE customers SET claimStatus = 'CLAIMED', claimedByUserId = :userId " +
            "WHERE claimStatus = 'UNCLAIMED' AND phone = :stored"
    )
    suspend fun claimByPhone(userId: String, stored: String): Int
}

@Dao
interface TransactionDao {
    // Append-only: insert only. A replayed transactionId is ignored (idempotency).
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(transaction: TransactionEntity)

    @Query(
        "SELECT * FROM transactions WHERE sellerId = :sellerId AND customerId = :customerId " +
            "ORDER BY createdAt DESC"
    )
    fun observeForSellerCustomer(sellerId: String, customerId: String): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE customerId = :customerId ORDER BY createdAt DESC")
    fun observeForCustomer(customerId: String): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions")
    fun observeAll(): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions")
    suspend fun allOnce(): List<TransactionEntity>

    /**
     * One entry by id, for the screen that shows what was in it.
     *
     * A read, not a mutation -- the append-only rule above is about what this DAO refuses
     * to write, and looking a row up does not touch it. Null when the id is not on this
     * device, which is a real state and not an error: another device's row, or one from
     * before the last rebuild.
     */
    @Query("SELECT * FROM transactions WHERE transactionId = :transactionId")
    suspend fun findById(transactionId: String): TransactionEntity?

    /**
     * Every entry in this seller's book, across all customers. Feeds the trend line beside
     * the book's total, which sums every customer and so needs a history that does too.
     */
    @Query("SELECT * FROM transactions WHERE sellerId = :sellerId ORDER BY createdAt")
    fun observeAllForSeller(sellerId: String): Flow<List<TransactionEntity>>

    /**
     * The (seller, customer) balance: DEBT and INDEXATION add, PAYMENT subtracts. Never
     * stored.
     *
     * The three cases are spelled out rather than left to an ELSE, and that is the whole
     * point: this CASE used to read `ELSE -amountMinor`, which counted anything that was
     * not a DEBT as money coming in. The day INDEXATION rows started arriving from the
     * server, that ELSE would have SUBTRACTED each month's inflation instead of adding it
     * -- and silently, since a query lives in a string no compiler checks.
     *
     * Must stay identical to backend/app/ledger.py::_SIGNED_AMOUNT. The shopkeeper reads
     * this number off the terminal and compares it with the server's; any drift between
     * the two reads as money going missing.
     */
    @Query(
        "SELECT COALESCE(SUM(CASE WHEN type = 'DEBT' THEN amountMinor " +
            "WHEN type = 'INDEXATION' THEN amountMinor " +
            "WHEN type = 'PAYMENT' THEN -amountMinor ELSE 0 END), 0) " +
            "FROM transactions WHERE sellerId = :sellerId AND customerId = :customerId"
    )
    fun observeBalance(sellerId: String, customerId: String): Flow<Long>

    /** Total the seller is owed across their own customers. */
    @Query(
        "SELECT COALESCE(SUM(CASE WHEN type = 'DEBT' THEN amountMinor " +
            "WHEN type = 'INDEXATION' THEN amountMinor " +
            "WHEN type = 'PAYMENT' THEN -amountMinor ELSE 0 END), 0) " +
            "FROM transactions WHERE sellerId = :sellerId"
    )
    fun observeTotalReceivable(sellerId: String): Flow<Long>
}

@Dao
interface BasketDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertBasket(basket: BasketEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertItems(items: List<BasketItemEntity>)

    /**
     * The basket's own row: which of the PGW's flags it carried.
     *
     * itemsFor alone cannot rebuild an OrderBody -- createInvoice, documentType and isVoid
     * live here, not on the lines -- so the detail screen reads both and joins them in the
     * mapper.
     */
    @Query("SELECT * FROM baskets WHERE basketId = :basketId")
    suspend fun header(basketId: String): BasketEntity?

    /**
     * ORDER BY id, and it has to be something: the ids are "<basketId>#<index>", so this
     * hands the lines back in the order the till rang them up. Without it SQLite is free to
     * return rowid order, which after a re-pull is not the same thing.
     *
     * This sorts as TEXT, which is why the index is zero-padded where it is minted (see
     * OrderBody.toItemEntities): unpadded, "#10" would sort before "#2".
     */
    @Query("SELECT * FROM basket_items WHERE basketId = :basketId ORDER BY id")
    suspend fun itemsFor(basketId: String): List<BasketItemEntity>
}

@Dao
interface ApprovalDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(approval: ApprovalEntity)

    // Newest first, matching GET /approvals. Without the ORDER BY the cards came back in
    // rowid order, which was invisible while one device wrote every row — and would start
    // reshuffling the list under the user the moment a poll rewrote the table.
    @Query(
        "SELECT * FROM approvals WHERE targetUserId = :userId AND status = 'PENDING' " +
            "ORDER BY requestedAt DESC"
    )
    fun observePendingFor(userId: String): Flow<List<ApprovalEntity>>

    @Query("SELECT * FROM approvals WHERE approvalId = :id")
    suspend fun findById(id: String): ApprovalEntity?

    @Query("UPDATE approvals SET status = :status WHERE approvalId = :id")
    suspend fun setStatus(id: String, status: String)

    /**
     * Removes a card the server will not let this user answer — it is addressed to somebody
     * else (403) or was already decided elsewhere (409).
     *
     * Deleted rather than given a status, because this device does not KNOW the real one:
     * writing APPROVED or REJECTED here would invent a decision, and the trail those
     * statuses exist for lives on the server anyway.
     */
    @Query("DELETE FROM approvals WHERE approvalId = :id")
    suspend fun delete(id: String)

    /**
     * Drops the pending cards addressed to this user that the server did NOT return.
     *
     * The other half of a pull: inserting what came back only adds, and a card answered on
     * the counterparty's device would otherwise sit here forever. The server's list is
     * authoritative, so absence from it is itself the news.
     *
     * Scoped to `targetUserId` on purpose — rows this shop RAISED are pending on somebody
     * else's device and are none of this query's business.
     */
    @Query(
        "DELETE FROM approvals WHERE targetUserId = :userId AND status = 'PENDING' " +
            "AND approvalId NOT IN (:keepIds)"
    )
    suspend fun deletePendingNotIn(userId: String, keepIds: List<String>)

    /** Same, for the case where the server returned nothing at all. */
    @Query("DELETE FROM approvals WHERE targetUserId = :userId AND status = 'PENDING'")
    suspend fun deleteAllPendingFor(userId: String)
}

// ---------------------------------------------------------------------------
// FORWARD-PHASE DAOs (skeleton): compile now, unused until their phase. Minimal
// insert/select bodies so the surface exists; no read is wired into the UI yet.
// ---------------------------------------------------------------------------

/**
 * The offline sync queue: one row per write that still has to reach the server.
 *
 * No longer a skeleton — RoomLocalDataSource enqueues here inside the same database
 * transaction as the ledger insert, and SyncEngine drains it.
 */
@Dao
interface OutboxDao {

    /**
     * IGNORE, not the default ABORT. The row's id is the transaction id, so re-enqueueing
     * an entry that is already queued is a no-op instead of an exception — which is what
     * a replayed write (a retry, a resumed handoff) does. With ABORT the drain would throw
     * exactly when the queue was doing its job.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entry: OutboxEntity)

    /** Oldest first: the ledger is a story, and it should reach the server in order. */
    @Query("SELECT * FROM outbox ORDER BY createdAt")
    suspend fun all(): List<OutboxEntity>

    /** How many writes are still unsent — drives a "not synced yet" indicator later. */
    @Query("SELECT COUNT(*) FROM outbox")
    fun observeCount(): Flow<Int>

    /** Called once a write is accepted (or permanently refused). */
    @Query("DELETE FROM outbox WHERE id = :id")
    suspend fun delete(id: String)

    /**
     * Records a failed attempt. Kept separate from delete so a retryable failure leaves a
     * trail: a row whose retryCount keeps climbing is how a poison entry becomes visible
     * instead of silently cycling forever.
     */
    @Query("UPDATE outbox SET retryCount = retryCount + 1 WHERE id = :id")
    suspend fun recordFailure(id: String)
}

@Dao
interface FxRateDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(rate: FxRateEntity)
    @Query("SELECT * FROM fx_rates WHERE asOf <= :asOf ORDER BY asOf DESC LIMIT 1")
    suspend fun nearest(asOf: String): FxRateEntity?
}

@Dao
interface CreditOfferDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(offer: CreditOfferEntity)
    @Query("SELECT * FROM credit_offers WHERE userId = :userId") fun observeFor(userId: String): Flow<List<CreditOfferEntity>>
}

@Dao
interface AuditLogDao {
    @Insert suspend fun insert(entry: AuditLogEntity)
    @Query("SELECT * FROM audit_log WHERE entity = :entity AND entityId = :entityId ORDER BY at")
    suspend fun forEntity(entity: String, entityId: String): List<AuditLogEntity>
}

@Dao
interface DeviceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(device: DeviceEntity)
}

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

/** One row of the buyer's debt list: the GROUP BY + JOIN result, mapped to SellerDebt. */
data class SellerDebtRow(
    val sellerId: String,
    val shopName: String?,      // null when the seller has not named their shop yet
    val balanceMinor: Long
)

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
     * Exact match on the canonical form. The caller converts with PhoneFormat.toStored
     * first, so both sides are already E.164 and no SQL-side normalisation is needed.
     *
     * Was a REPLACE + `LIKE '%..%'` scan, which quietly disagreed with CustomerDao's exact
     * match and let a substring pass as a hit — see PhoneFormat's note.
     */
    @Query("SELECT * FROM users WHERE phone = :stored LIMIT 1")
    suspend fun findByPhone(stored: String): UserEntity?
}

@Dao
interface CustomerDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(customer: CustomerEntity)

    @Query("SELECT * FROM customers WHERE customerId = :id")
    suspend fun findById(id: String): CustomerEntity?

    @Query("SELECT * FROM customers")
    fun observeAll(): Flow<List<CustomerEntity>>

    /** The seller's customers: everyone they have at least one ledger entry with. */
    @Query(
        "SELECT * FROM customers WHERE customerId IN " +
            "(SELECT DISTINCT customerId FROM transactions WHERE sellerId = :sellerId)"
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

    /**
     * The claim bridge: link every UNCLAIMED record with this phone to a user.
     *
     * Returns how many rows were linked, which the caller checks — a claim that matches
     * nothing used to look identical to one that worked.
     */
    @Query(
        "UPDATE customers SET claimStatus = 'CLAIMED', claimedByUserId = :userId " +
            "WHERE claimStatus = 'UNCLAIMED' AND phone = :stored"
    )
    suspend fun claimByPhone(userId: String, stored: String): Int

    /** Every record this user has claimed — their identity across all the shops. */
    @Query("SELECT * FROM customers WHERE claimedByUserId = :userId")
    suspend fun claimedBy(userId: String): List<CustomerEntity>
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

    // --- buyer-scoped reads: the same ledger read from the customer's side ---------
    // "My records" = the customer rows claimed by this user (one per shop), so these
    // mirror the seller queries above with the scope inverted.

    @Query(
        "SELECT * FROM transactions WHERE sellerId = :sellerId AND customerId IN " +
            "(SELECT customerId FROM customers WHERE claimedByUserId = :userId) " +
            "ORDER BY createdAt DESC"
    )
    fun observeForBuyerSeller(userId: String, sellerId: String): Flow<List<TransactionEntity>>

    /** One buyer↔seller balance. */
    @Query(
        "SELECT COALESCE(SUM(CASE WHEN type = 'DEBT' THEN amountMinor ELSE -amountMinor END), 0) " +
            "FROM transactions WHERE sellerId = :sellerId AND customerId IN " +
            "(SELECT customerId FROM customers WHERE claimedByUserId = :userId)"
    )
    fun observeBuyerBalanceWithSeller(userId: String, sellerId: String): Flow<Long>

    /** What this buyer owes across every shop. */
    @Query(
        "SELECT COALESCE(SUM(CASE WHEN type = 'DEBT' THEN amountMinor ELSE -amountMinor END), 0) " +
            "FROM transactions WHERE customerId IN " +
            "(SELECT customerId FROM customers WHERE claimedByUserId = :userId)"
    )
    fun observeBuyerTotalDebt(userId: String): Flow<Long>

    /**
     * The buyer's debts grouped by shop, with the shop name joined in. LEFT JOIN so a
     * seller who has not named their shop still yields a row (the mapper falls back to
     * the id). One round-trip, and the shape the backend endpoint will return.
     */
    @Query(
        "SELECT t.sellerId AS sellerId, u.shopName AS shopName, " +
            "SUM(CASE WHEN t.type = 'DEBT' THEN t.amountMinor ELSE -t.amountMinor END) AS balanceMinor " +
            "FROM transactions t LEFT JOIN users u ON u.userId = t.sellerId " +
            "WHERE t.customerId IN (SELECT customerId FROM customers WHERE claimedByUserId = :userId) " +
            "GROUP BY t.sellerId"
    )
    fun observeDebtsBySeller(userId: String): Flow<List<SellerDebtRow>>

    /**
     * This buyer's customer id in ONE seller's book, or null when they share no
     * history. Deliberately no "any record of mine" fallback: a buyer may hold several
     * records (one per shop), and picking the wrong one would book the entry into
     * another shop's ledger.
     */
    @Query(
        "SELECT t.customerId FROM transactions t WHERE t.sellerId = :sellerId AND t.customerId IN " +
            "(SELECT customerId FROM customers WHERE claimedByUserId = :userId) LIMIT 1"
    )
    suspend fun customerIdForBuyerSeller(userId: String, sellerId: String): String?

    @Query("SELECT * FROM transactions")
    fun observeAll(): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions")
    suspend fun allOnce(): List<TransactionEntity>

    /** The (seller, customer) balance: DEBT adds, PAYMENT subtracts. Never stored. */
    @Query(
        "SELECT COALESCE(SUM(CASE WHEN type = 'DEBT' THEN amountMinor ELSE -amountMinor END), 0) " +
            "FROM transactions WHERE sellerId = :sellerId AND customerId = :customerId"
    )
    fun observeBalance(sellerId: String, customerId: String): Flow<Long>

    /** Total the seller is owed across their own customers. */
    @Query(
        "SELECT COALESCE(SUM(CASE WHEN type = 'DEBT' THEN amountMinor ELSE -amountMinor END), 0) " +
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

    @Query("SELECT * FROM basket_items WHERE basketId = :basketId")
    suspend fun itemsFor(basketId: String): List<BasketItemEntity>
}

@Dao
interface ApprovalDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(approval: ApprovalEntity)

    // Newest first, matching GET /approvals. Without the ORDER BY the cards came back in
    // rowid order, so a poll that rewrote the table could reshuffle the list under the user.
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
     * statuses exist for lives on the server anyway. What is wrong is only that the row is
     * on this device at all — it came from a local seed, not from the server.
     */
    @Query("DELETE FROM approvals WHERE approvalId = :id")
    suspend fun delete(id: String)
}

// ---------------------------------------------------------------------------
// FORWARD-PHASE DAOs (skeleton): compile now, unused until their phase. Minimal
// insert/select bodies so the surface exists; no read is wired into the UI yet.
// ---------------------------------------------------------------------------

@Dao
interface OutboxDao {
    /**
     * IGNORE, not the default ABORT. The row id IS the transaction id, so a second attempt
     * to enqueue the same entry (a retry, a resumed flow) must be a no-op — with ABORT it
     * would throw, i.e. the queue would break precisely while doing its job.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entry: OutboxEntity)

    @Query("SELECT * FROM outbox ORDER BY createdAt") suspend fun all(): List<OutboxEntity>

    /** Queue depth, for a "not sent yet" indicator. */
    @Query("SELECT COUNT(*) FROM outbox") fun observeCount(): Flow<Int>

    @Query("DELETE FROM outbox WHERE id = :id") suspend fun delete(id: String)

    /** Bumps the counter so an entry that keeps failing becomes visible instead of silent. */
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

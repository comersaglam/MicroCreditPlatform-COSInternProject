package com.example.app_pos.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entities — the on-device (SQLite) shape of the data. These are the STORAGE
 * representation; the domain models (:core-domain) are separate on purpose (their
 * reasons to change differ), and mappers convert between them.
 *
 * Column names match docs/db-schema.md (snake_case). Money is a Long in minor units
 * (kuruş). The ledger (transactions) is append-only — there is no update/delete DAO.
 * Balances are never stored; they are summed from the ledger.
 */

/** users — an app account. SellerInfo is flattened into shop_* columns (the domain
 *  keeps it nested; the mapper rebuilds it when isSeller and shopName are present). */
@Entity(
    tableName = "users",
    indices = [Index(value = ["phone"], unique = true)]
)
data class UserEntity(
    @PrimaryKey val userId: String,
    val phone: String,
    val displayName: String,
    val isBuyer: Boolean,
    val isSeller: Boolean,
    val email: String?,
    val shopName: String?,
    val shopPhone: String?,
    val createdAt: String
)

/** customers — the merchant's ledger record for a person. Balance is NOT stored. */
@Entity(
    tableName = "customers",
    foreignKeys = [
        ForeignKey(
            entity = UserEntity::class,
            parentColumns = ["userId"],
            childColumns = ["claimedByUserId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [Index("phone"), Index("claimedByUserId")]
)
data class CustomerEntity(
    @PrimaryKey val customerId: String,
    val displayName: String,
    val phone: String,
    val claimStatus: String,          // UNCLAIMED | CLAIMED
    val claimedByUserId: String?,
    // Which seller wrote this person down. Mirrors the server's column, and it is what
    // puts a just-added customer in a book: CustomerDao.observeForSeller otherwise scopes
    // purely by ledger membership, so somebody added and not yet charged was stored and
    // invisible on the very screen that added them.
    val createdBySellerId: String?,
    val createdAt: String
)

/** transactions — the append-only ledger. Only ever inserted. */
@Entity(
    tableName = "transactions",
    foreignKeys = [
        ForeignKey(
            entity = BasketEntity::class,
            parentColumns = ["basketId"],
            childColumns = ["basketId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [Index(value = ["sellerId", "customerId"]), Index("customerId"), Index("basketId")]
)
data class TransactionEntity(
    @PrimaryKey val transactionId: String,
    val sellerId: String,
    val customerId: String,
    val amountMinor: Long,
    val type: String,                 // DEBT | PAYMENT
    val description: String,
    val basketId: String?,            // set when the handoff carried an orderBody
    val settledViaPgw: Boolean,       // PAYMENT settled through the PGW? (FAZ 8)
    val receiptNo: String?,           // PGW receipt when settled (FAZ 8)
    val createdAt: String
)

/** baskets — the orderBody header handed over by the PGW (mock-pos). */
@Entity(tableName = "baskets")
data class BasketEntity(
    @PrimaryKey val basketId: String,
    val createInvoice: Boolean,
    val documentType: Int,
    val isVoid: Boolean,
    val createdAt: String
)

/** basket_items — the orderBody items[]. quantity & taxPercent are ×1000-scaled. */
@Entity(
    tableName = "basket_items",
    foreignKeys = [
        ForeignKey(
            entity = BasketEntity::class,
            parentColumns = ["basketId"],
            childColumns = ["basketId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("basketId")]
)
data class BasketItemEntity(
    @PrimaryKey val id: String,
    val basketId: String,
    val name: String,
    val priceMinor: Long,
    val quantity: Long,               // ×1000 (1000 = 1 unit)
    val taxPercent: Long,             // ×1000 (1000 = 10%)
    val sectionNo: Int,
    val status: Int,
    val type: Int,
    val itemLimit: Long
)

/**
 * approvals — a pending veresiye/payment awaiting the counterparty's decision.
 *
 * The direction fields (initiatorRole/targetUserId/channel) model all three approval
 * lines; on app-pos this table is SKELETON for now (the incoming-approval UI is a
 * separate turn). shopName + requestedAt mirror app-mobile's real PendingApproval.
 */
@Entity(
    tableName = "approvals",
    indices = [Index(value = ["targetUserId", "status"])]
)
data class ApprovalEntity(
    @PrimaryKey val approvalId: String,
    val initiatorUserId: String,
    val initiatorRole: String,        // BUYER | SELLER
    val targetUserId: String,
    val sellerId: String,
    val shopName: String,
    val customerId: String,
    val amountMinor: Long,
    val type: String,                 // DEBT | PAYMENT
    val description: String?,
    val channel: String,              // APP_PUSH | SMS_OTP
    val status: String,               // PENDING | APPROVED | REJECTED
    val requestedAt: String
)

// ---------------------------------------------------------------------------
// FORWARD-PHASE entities (docs/db-schema.md Bölüm B): written now so the schema
// and DAOs exist, but NOT wired into any UI/sync path yet. Each carries the field
// it will need when its phase arrives; today they compile and sit unused — the same
// "signature ready, body later" pattern as OtpService.
// ---------------------------------------------------------------------------

/** outbox — the offline sync queue (FAZ 4). WorkManager drains it later. */
@Entity(tableName = "outbox")
data class OutboxEntity(
    @PrimaryKey val id: String,
    val transactionId: String,
    val payload: String,
    val createdAt: String,
    val retryCount: Int
)

/** fx_rates — USD/EUR/gold at a point in time, for later inflation/credit maths.
 *  Filled by a web-fetch job in a later phase; placeholder for now. */
@Entity(tableName = "fx_rates")
data class FxRateEntity(
    @PrimaryKey val asOf: String,
    val usdMinor: Long,
    val eurMinor: Long,
    val goldMinor: Long
)

/** credit_offers — micro-credit offers to a user (FAZ 2/8). apr is ×100 (1900 = 19%). */
@Entity(tableName = "credit_offers")
data class CreditOfferEntity(
    @PrimaryKey val offerId: String,
    val userId: String,
    val limitMinor: Long,
    val apr: Int,
    val termDays: Int,
    val status: String,
    val createdAt: String
)

/** audit_log — an immutable who-did-what-when trail (FAZ 7, regulation). */
@Entity(tableName = "audit_log")
data class AuditLogEntity(
    @PrimaryKey val id: String,
    val actorUserId: String,
    val action: String,
    val entity: String,
    val entityId: String,
    val at: String
)

/** devices — a registered FCM token for push (FAZ 8). */
@Entity(tableName = "devices")
data class DeviceEntity(
    @PrimaryKey val deviceId: String,
    val userId: String,
    val fcmToken: String,
    val platform: String,
    val updatedAt: String
)

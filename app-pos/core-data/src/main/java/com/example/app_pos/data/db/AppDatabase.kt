package com.example.app_pos.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.example.app_pos.data.db.dao.ApprovalDao
import com.example.app_pos.data.db.dao.AuditLogDao
import com.example.app_pos.data.db.dao.BasketDao
import com.example.app_pos.data.db.dao.CreditOfferDao
import com.example.app_pos.data.db.dao.CustomerDao
import com.example.app_pos.data.db.dao.DeviceDao
import com.example.app_pos.data.db.dao.FxRateDao
import com.example.app_pos.data.db.dao.OutboxDao
import com.example.app_pos.data.db.dao.TransactionDao
import com.example.app_pos.data.db.dao.UserDao
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

/**
 * The on-device SQLite database. Bumps [version] whenever the entity set changes;
 * migrations arrive with the backend phase (for now a destructive fallback is fine
 * since the data is still seeded/mock).
 *
 * Forward-phase entities (outbox, fx_rates, credit_offers, audit_log, devices) are
 * registered so their tables exist, even though nothing reads them yet.
 */
@Database(
    entities = [
        UserEntity::class,
        CustomerEntity::class,
        TransactionEntity::class,
        BasketEntity::class,
        BasketItemEntity::class,
        ApprovalEntity::class,
        // forward-phase (unused surface, real tables)
        OutboxEntity::class,
        FxRateEntity::class,
        CreditOfferEntity::class,
        AuditLogEntity::class,
        DeviceEntity::class
    ],
    // v2: createdAt switched from "dd.MM.yyyy HH:mm" to ISO-8601 UTC. The column type is
    // unchanged, but stored values are not comparable across the two formats, so the
    // destructive fallback rebuilds the seeded database rather than migrating it.
    // v3: customers.createdBySellerId added, so a customer who has been written down but
    // not yet charged still belongs to a book. Dropping the local copy costs nothing now:
    // the server owns every row and the next pull restores them.
    // v4: basket_items ids became deterministic ("<basketId>#<index>"). They used to be a
    // fresh UUID per call, so insert-IGNORE never matched anything and re-pulling a basket
    // appended its lines again -- invisible while nothing read them back, and Turn 45 is
    // what starts reading them back. Rows already on disk carry the old random ids and the
    // duplicates they caused; rebuilding is the only way to be rid of both.
    version = 4,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun userDao(): UserDao
    abstract fun customerDao(): CustomerDao
    abstract fun transactionDao(): TransactionDao
    abstract fun basketDao(): BasketDao
    abstract fun approvalDao(): ApprovalDao
    // forward-phase
    abstract fun outboxDao(): OutboxDao
    abstract fun fxRateDao(): FxRateDao
    abstract fun creditOfferDao(): CreditOfferDao
    abstract fun auditLogDao(): AuditLogDao
    abstract fun deviceDao(): DeviceDao
}

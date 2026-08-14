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
 * registered so their tables exist, even though nothing reads them yet. baskets /
 * basket_items are in the same group here: app-mobile has no PGW handoff, but the
 * schema is kept identical to app-pos's so both sides stay one design.
 *
 * v2 — createdAt moved from "dd.MM.yyyy HH:mm" (local) to ISO-8601 UTC, the format the
 * wire contract publishes. The two are not comparable as text, so the rows are rebuilt
 * rather than migrated; app-pos made the same jump for the same reason.
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
    version = 2,
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

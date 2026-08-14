package com.example.app_pos.data.db

import com.example.app_pos.data.db.dao.SellerDebtRow
import com.example.app_pos.data.db.entity.ApprovalEntity
import com.example.app_pos.data.db.entity.CustomerEntity
import com.example.app_pos.data.db.entity.TransactionEntity
import com.example.app_pos.data.db.entity.UserEntity
import com.example.app_pos.model.ClaimStatus
import com.example.app_pos.model.Customer
import com.example.app_pos.model.PendingApproval
import com.example.app_pos.model.SellerDebt
import com.example.app_pos.model.SellerInfo
import com.example.app_pos.model.Transaction
import com.example.app_pos.model.TransactionType
import com.example.app_pos.model.User
import com.example.app_pos.network.dto.ApprovalDto

/**
 * Entity <-> domain mappers. The storage shape (Room entity) and the domain model
 * change for different reasons, so they are separate types with explicit conversion
 * here (docs/db-schema.md "üç temsil"). Enums cross as their names (strings on disk).
 */

// --- User ---

fun UserEntity.toDomain(): User = User(
    userId = userId,
    phone = phone,
    displayName = displayName,
    isBuyer = isBuyer,
    isSeller = isSeller,
    email = email,
    // Rebuild the nested SellerInfo the domain uses from the flattened shop_* columns.
    sellerInfo = if (isSeller && shopName != null) SellerInfo(shopName, shopPhone) else null,
    createdAt = createdAt
)

fun User.toEntity(): UserEntity = UserEntity(
    userId = userId,
    phone = phone,
    displayName = displayName,
    isBuyer = isBuyer,
    isSeller = isSeller,
    email = email,
    shopName = sellerInfo?.shopName,
    shopPhone = sellerInfo?.shopPhone,
    createdAt = createdAt
)

// --- Customer (balance is passed in; it is always derived from the ledger) ---

fun CustomerEntity.toDomain(balanceMinor: Long): Customer = Customer(
    customerId = customerId,
    displayName = displayName,
    phone = phone,
    claimStatus = ClaimStatus.valueOf(claimStatus),
    claimedByUserId = claimedByUserId,
    balanceMinor = balanceMinor
)

fun CustomerEntity.toRawDomain(): Customer = toDomain(0L)

// --- Transaction ---

fun TransactionEntity.toDomain(): Transaction = Transaction(
    transactionId = transactionId,
    sellerId = sellerId,
    customerId = customerId,
    amountMinor = amountMinor,
    type = TransactionType.valueOf(type),
    description = description,
    createdAt = createdAt
)

// app-mobile has no PGW handoff, so an entry never carries a basket (basketId = null).
fun Transaction.toEntity(): TransactionEntity = TransactionEntity(
    transactionId = transactionId,
    sellerId = sellerId,
    customerId = customerId,
    amountMinor = amountMinor,
    type = type.name,
    description = description,
    basketId = null,
    settledViaPgw = false,
    receiptNo = null,
    createdAt = createdAt
)

// --- Approval ---

fun ApprovalEntity.toDomain(): PendingApproval = PendingApproval(
    approvalId = approvalId,
    sellerId = sellerId,
    counterpartyName = shopName,
    // targetUserId IS the approver — the counterparty of whoever started the request.
    approverUserId = targetUserId,
    customerId = customerId,
    amountMinor = amountMinor,
    type = TransactionType.valueOf(type),
    description = description.orEmpty(),
    requestedAt = requestedAt
)

/**
 * Wire → entity, for a row the SERVER owns.
 *
 * Deliberately NOT routed through [PendingApproval] and [toEntity] below. That pair is
 * built for a row this device is inventing, so it fills the direction fields in by
 * guessing: it derives `initiatorRole` from whether the initiator happens to be the
 * seller, hardcodes `channel` to APP_PUSH, and defaults `status` to PENDING. Every one of
 * those is a fact the server has already stated, and the entire point of a pull is to
 * store what the server said rather than a local re-derivation of it.
 *
 * Returns null for a type this build cannot read — the same rule the rest of the mapping
 * layer follows, and for the sharpest reason here: the type carries the sign, so a card
 * nobody can read correctly must not be approvable.
 */
fun ApprovalDto.toEntityOrNull(): ApprovalEntity? {
    // Parsed only to validate; the entity stores the wire string as-is.
    if (runCatching { TransactionType.valueOf(type) }.getOrNull() == null) return null

    return ApprovalEntity(
        approvalId = approvalId,
        initiatorUserId = initiatorUserId,
        initiatorRole = initiatorRole,
        targetUserId = targetUserId,
        sellerId = sellerId,
        shopName = shopName,
        customerId = customerId,
        amountMinor = amountMinor,
        type = type,
        description = description,
        channel = channel,
        status = status,
        requestedAt = requestedAt
    )
}

/**
 * Domain → entity, filling the three-line direction fields.
 *
 * [initiatorUserId] decides the direction: matching the sellerId means the shop started
 * it (they need the customer's approval), otherwise a buyer did (the shop confirms
 * receipt). A row only exists for a CLAIMED counterparty — the app-less case writes to
 * the ledger instead — so the channel is always app-push here.
 */
fun PendingApproval.toEntity(
    initiatorUserId: String,
    status: String = "PENDING"
): ApprovalEntity = ApprovalEntity(
    approvalId = approvalId,
    initiatorUserId = initiatorUserId,
    initiatorRole = if (initiatorUserId == sellerId) "SELLER" else "BUYER",
    targetUserId = approverUserId,
    sellerId = sellerId,
    shopName = counterpartyName,
    customerId = customerId,
    amountMinor = amountMinor,
    type = type.name,
    description = description,
    channel = "APP_PUSH",
    status = status,
    requestedAt = requestedAt
)

// --- SellerDebt (the buyer's debt list: a GROUP BY + JOIN projection) ---

/**
 * What a shop is called before its name is known.
 *
 * The list is a JOIN of two separately-written tables: the ledger pull stores the entries,
 * `storeShopNames` stores the names, and between those two writes a row genuinely has a
 * balance and no name. The old fallback put the raw seller id on screen for that window
 * ("u_market"), which is an internal key the buyer has no way to interpret — worse than
 * saying nothing, because it looks like data. A neutral word carries the same "not known
 * yet" without pretending to be the shop's name.
 */
private const val UNNAMED_SHOP = "Dükkan"

fun SellerDebtRow.toDomain(): SellerDebt = SellerDebt(
    sellerId = sellerId,
    // Blank counts as absent too: an empty name renders as an empty line, which reads as
    // a broken row rather than a pending one.
    shopName = shopName?.takeIf { it.isNotBlank() } ?: UNNAMED_SHOP,
    balanceMinor = balanceMinor
)

package com.example.app_pos.data.db

import com.example.app_pos.data.db.entity.ApprovalEntity
import com.example.app_pos.data.db.entity.BasketEntity
import com.example.app_pos.data.db.entity.BasketItemEntity
import com.example.app_pos.data.db.entity.CustomerEntity
import com.example.app_pos.data.db.entity.TransactionEntity
import com.example.app_pos.data.db.entity.UserEntity
import com.example.app_pos.model.ClaimStatus
import com.example.app_pos.model.Customer
import com.example.app_pos.model.OrderBody
import com.example.app_pos.model.PendingApproval
import com.example.app_pos.model.SellerInfo
import com.example.app_pos.model.Transaction
import com.example.app_pos.model.TransactionType
import com.example.app_pos.model.User
import com.example.app_pos.network.dto.ApprovalDto
import java.util.UUID

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

fun Transaction.toEntity(basketId: String?): TransactionEntity = TransactionEntity(
    transactionId = transactionId,
    sellerId = sellerId,
    customerId = customerId,
    amountMinor = amountMinor,
    type = type.name,
    description = description,
    basketId = basketId,
    settledViaPgw = false,
    receiptNo = null,
    createdAt = createdAt
)

// --- OrderBody -> Basket + items (for the optional basket on a write) ---

fun OrderBody.toBasketEntity(createdAt: String): BasketEntity = BasketEntity(
    basketId = basketId,
    createInvoice = createInvoice,
    documentType = documentType,
    isVoid = isVoid,
    createdAt = createdAt
)

fun OrderBody.toItemEntities(): List<BasketItemEntity> = items.map { item ->
    BasketItemEntity(
        id = UUID.randomUUID().toString(),
        basketId = basketId,
        name = item.name,
        priceMinor = item.price,
        quantity = item.quantity,
        taxPercent = item.taxPercent,
        sectionNo = item.sectionNo,
        status = item.status,
        type = item.type,
        itemLimit = item.limit
    )
}

// --- Approval (the incoming inbox; app-pos grew this side in Turn 39) ---

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
 * Stores what the server SAID rather than a local re-derivation of it: the direction fields
 * (initiatorRole, channel, status) are facts it has already stated, and guessing them again
 * here would quietly contradict the very answer the pull went to fetch.
 *
 * Returns null for a type this build cannot read — the type carries the sign of the amount,
 * so a card nobody can read correctly must never become approvable.
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

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
import com.example.app_pos.model.OrderItem
import com.example.app_pos.model.PendingApproval
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
    createdBySellerId = createdBySellerId,
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

/**
 * The lines, with ids derived from where they sit in the basket.
 *
 * These used to be a fresh UUID.randomUUID() per line, which quietly defeated the whole
 * point of insertItems being insert-IGNORE: a conflict can only be ignored if the second
 * write presents the same key, and a random one never does. So re-pulling a basket -- which
 * the buyer's app does every fifteen seconds -- appended its lines again, and again. It was
 * invisible only because nothing read them back.
 *
 * The index is zero-padded because BasketDao.itemsFor orders by this column as TEXT, where
 * "#10" sorts before "#2". Four digits is far past any real till receipt.
 */
fun OrderBody.toItemEntities(): List<BasketItemEntity> = items.mapIndexed { index, item ->
    BasketItemEntity(
        id = "$basketId#${index.toString().padStart(4, '0')}",
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

// --- Basket + items -> OrderBody (the way back, for the detail screen) ---

/**
 * Rebuilds the basket from its two tables.
 *
 * The lines are passed in rather than read here: a mapper that queried would need a DAO,
 * and this file deliberately knows only about shapes. The caller reads both and joins them.
 *
 * Note this is NOT wired into TransactionEntity.toDomain(). The ledger list flows re-emit
 * on every write, and making that mapper basket-aware would put two queries per row behind
 * every emission -- for a field only one screen ever looks at. It reads the basket by id
 * instead, once, when that screen opens.
 */
fun BasketEntity.toDomain(items: List<BasketItemEntity>): OrderBody = OrderBody(
    basketId = basketId,
    createInvoice = createInvoice,
    documentType = documentType,
    isVoid = isVoid,
    items = items.map { it.toDomain() }
)

/** One line back. The ×1000 scales are carried across untouched; only the display divides. */
fun BasketItemEntity.toDomain(): OrderItem = OrderItem(
    name = name,
    price = priceMinor,
    quantity = quantity,
    taxPercent = taxPercent,
    sectionNo = sectionNo,
    status = status,
    type = type,
    limit = itemLimit
)

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

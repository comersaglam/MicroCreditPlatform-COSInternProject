package com.example.app_pos.network.mapper

import com.example.app_pos.model.OrderBody
import com.example.app_pos.model.OrderItem
import com.example.app_pos.network.dto.OrderBodyDto
import com.example.app_pos.network.dto.OrderItemDto

/**
 * OrderBody ↔ wire — the basket handed over by Token's payment gateway.
 *
 * The scaled fields (quantity and taxPercent, both ×1000) pass through untouched: they
 * mean the same thing on both sides, and the only place that de-scales them is the
 * domain's lineTotalMinor. Converting here would double-apply it.
 *
 * The one genuine rename is the last field: `item_limit` on the wire, `limit` in the
 * domain, `itemLimit` in the database (SQL reserves `limit`).
 */

fun OrderBodyDto.toDomain(): OrderBody = OrderBody(
    basketId = basketId,
    createInvoice = createInvoice,
    documentType = documentType,
    isVoid = isVoid,
    items = items.map { it.toDomain() }
)

fun OrderBody.toDto(): OrderBodyDto = OrderBodyDto(
    basketId = basketId,
    createInvoice = createInvoice,
    documentType = documentType,
    isVoid = isVoid,
    items = items.map { it.toDto() }
)

fun OrderItemDto.toDomain(): OrderItem = OrderItem(
    name = name,
    price = price,
    quantity = quantity,
    taxPercent = taxPercent,
    sectionNo = sectionNo,
    status = status,
    type = type,
    limit = itemLimit
)

fun OrderItem.toDto(): OrderItemDto = OrderItemDto(
    name = name,
    price = price,
    quantity = quantity,
    taxPercent = taxPercent,
    sectionNo = sectionNo,
    status = status,
    type = type,
    itemLimit = limit
)

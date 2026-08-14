package com.example.app_pos.network.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/** Wire shapes for the basket that Token's payment gateway hands over. */

@JsonClass(generateAdapter = true)
data class OrderBodyDto(
    @param:Json(name = "basket_id") val basketId: String,
    @param:Json(name = "create_invoice") val createInvoice: Boolean = false,
    @param:Json(name = "document_type") val documentType: Int = 0,
    @param:Json(name = "is_void") val isVoid: Boolean = false,
    @param:Json(name = "items") val items: List<OrderItemDto> = emptyList()
)

/**
 * One basket line. Two fields are scaled by 1000 on the wire, matching the gateway:
 * [quantity] (1000 = 1 unit) and [taxPercent] (1000 = 10%). [price] is per-unit in minor
 * units. The line total is computed in the domain model, in one place, never here.
 *
 * The last field is `item_limit` on the wire and `limit` in the domain — renamed because
 * `limit` is a reserved word in SQL, so the column could not use it. Three names, one value.
 */
@JsonClass(generateAdapter = true)
data class OrderItemDto(
    @param:Json(name = "name") val name: String,
    @param:Json(name = "price") val price: Long,
    @param:Json(name = "quantity") val quantity: Long,
    @param:Json(name = "tax_percent") val taxPercent: Long,
    @param:Json(name = "section_no") val sectionNo: Int = 1,
    @param:Json(name = "status") val status: Int = 1,
    @param:Json(name = "type") val type: Int = 0,
    @param:Json(name = "item_limit") val itemLimit: Long = 0L
)

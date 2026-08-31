package com.example.app_pos.network.mapper

import com.example.app_pos.model.OrderBody
import com.example.app_pos.model.OrderItem
import com.example.app_pos.model.TransactionType
import com.example.app_pos.network.dto.ApprovalCreateDto
import com.example.app_pos.network.dto.ORIGIN_POS
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the basket's other route to the server.
 *
 * A veresiye that starts at the gateway never reaches POST /transactions: it is raised for
 * the customer's approval, and the server writes the entry only once they agree. So the
 * basket has to travel on THIS body, and TransactionMapperTest -- which covers the direct
 * write -- says nothing about it. For several turns the two halves of this path were both
 * written and simply not joined, with every test still green (docs/deferred.md J).
 */
class ApprovalMapperTest {

    private val moshi = Moshi.Builder().build()

    /** Serialises through Moshi, then reads the result back as a plain map of wire keys. */
    private fun wireKeysOf(dto: ApprovalCreateDto): Set<String> {
        val json = moshi.adapter(ApprovalCreateDto::class.java).toJson(dto)
        val mapType = Types.newParameterizedType(
            Map::class.java, String::class.java, Any::class.java
        )
        val map: Map<String, Any?> = moshi.adapter<Map<String, Any?>>(mapType).fromJson(json)!!
        return map.keys
    }

    @Test
    fun `a money-only request carries no basket`() {
        assertNull(sampleRequest(orderBody = null).basket)
    }

    @Test
    fun `a gateway handoff carries its basket to the approval endpoint`() {
        val basket = sampleRequest(orderBody = sampleBasket()).basket!!

        assertEquals("b-uuid", basket.basketId)
        assertEquals("Ekmek", basket.items.single().name)
    }

    @Test
    fun `the basket keeps the gateway's scaled fields untouched`() {
        val item = sampleRequest(orderBody = sampleBasket()).basket!!.items.single()

        // quantity and tax_percent stay ×1000 on the wire; only the domain de-scales them.
        assertEquals(2000L, item.quantity)
        assertEquals(1000L, item.taxPercent)
        // `limit` in the domain is `item_limit` on the wire.
        assertEquals(5L, item.itemLimit)
    }

    @Test
    fun `the basket travels under the key the server reads`() {
        val keys = wireKeysOf(sampleRequest(orderBody = sampleBasket()))

        // The name from shared-contracts/openapi.yaml (ApprovalCreate.basket). A wrong
        // @Json name compiles and serialises fine -- the server just ignores the field.
        assertTrue("basket" in keys)
    }

    @Test
    fun `a null basket is left out of the body entirely`() {
        assertTrue("basket" !in wireKeysOf(sampleRequest(orderBody = null)))
    }

    private fun sampleRequest(orderBody: OrderBody?) = approvalCreateDto(
        sellerId = "u_owner",
        customerId = "c1",
        amountMinor = 3000,
        type = TransactionType.DEBT,
        description = "veresiye",
        initiatorRole = "SELLER",
        targetUserId = "u_buyer",
        origin = ORIGIN_POS,
        orderBody = orderBody
    )

    private fun sampleBasket() = OrderBody(
        basketId = "b-uuid",
        createInvoice = false,
        documentType = 0,
        isVoid = false,
        items = listOf(
            OrderItem(
                name = "Ekmek", price = 1500, quantity = 2000, taxPercent = 1000,
                sectionNo = 1, status = 1, type = 0, limit = 5
            )
        )
    )
}

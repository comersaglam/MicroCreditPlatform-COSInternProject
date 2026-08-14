package com.example.app_pos.network.mapper

import com.example.app_pos.model.OrderBody
import com.example.app_pos.model.OrderItem
import com.example.app_pos.model.TransactionType
import com.example.app_pos.network.dto.TransactionCreateDto
import com.example.app_pos.network.dto.TransactionDto
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the wire contract itself. A wrong or missing @Json name compiles fine and only
 * surfaces when a real backend rejects the body, so these tests assert the exact
 * snake_case keys from shared-contracts/openapi.yaml.
 */
class TransactionMapperTest {

    private val moshi = Moshi.Builder().build()

    /** Serialises through Moshi, then reads the result back as a plain map of wire keys. */
    private fun wireKeysOf(dto: TransactionCreateDto): Set<String> {
        val json = moshi.adapter(TransactionCreateDto::class.java).toJson(dto)
        val mapType = Types.newParameterizedType(
            Map::class.java, String::class.java, Any::class.java
        )
        val map: Map<String, Any?> = moshi.adapter<Map<String, Any?>>(mapType).fromJson(json)!!
        return map.keys
    }

    @Test
    fun `create body serialises to the contract's snake_case keys`() {
        assertEquals(
            setOf("transaction_id", "customer_id", "amount_minor", "type", "description"),
            wireKeysOf(sampleTransaction().toCreateDto())
        )
    }

    @Test
    fun `create body omits seller_id because the server takes it from the token`() {
        assertTrue(
            "seller_id must never be sent",
            "seller_id" !in wireKeysOf(sampleTransaction().toCreateDto())
        )
    }

    @Test
    fun `a money-only entry carries no basket`() {
        val dto = sampleTransaction().toCreateDto(orderBody = null)
        assertNull(dto.basket)
    }

    @Test
    fun `a basket handoff keeps the gateway's scaled fields untouched`() {
        val dto = sampleTransaction().toCreateDto(orderBody = sampleBasket())
        val item = dto.basket!!.items.single()

        // quantity and tax_percent stay ×1000 on the wire; only the domain de-scales them.
        assertEquals(2000L, item.quantity)
        assertEquals(1000L, item.taxPercent)
        // `limit` in the domain is `item_limit` on the wire.
        assertEquals(5L, item.itemLimit)
    }

    @Test
    fun `a response entry round-trips back to the domain`() {
        val domain = TransactionDto(
            transactionId = "t1",
            sellerId = "u_owner",
            customerId = "c1",
            amountMinor = 5000,
            type = "DEBT",
            description = "Ekmek, süt",
            createdAt = "2026-07-20T06:15:00Z"
        ).toDomainOrNull()!!

        assertEquals("t1", domain.transactionId)
        assertEquals("u_owner", domain.sellerId)
        assertEquals(TransactionType.DEBT, domain.type)
        assertEquals(5000L, domain.amountMinor)
    }

    @Test
    fun `an entry with an unknown type is dropped, not guessed`() {
        // The type carries the sign, so a row whose direction is unknown must never
        // reach a balance sum — and one bad row must not fail the whole response.
        val list = listOf(
            TransactionDto("t1", "u_owner", "c1", 5000, "DEBT", "ok", createdAt = STAMP),
            TransactionDto("t2", "u_owner", "c1", 3000, "ADJUSTMENT", "future", createdAt = STAMP)
        )

        val mapped = list.toDomainList()

        assertEquals(1, mapped.size)
        assertEquals("t1", mapped.single().transactionId)
    }

    private fun sampleTransaction() = com.example.app_pos.model.Transaction(
        transactionId = "t-uuid",
        sellerId = "u_owner",
        customerId = "c1",
        amountMinor = 5000,
        type = TransactionType.DEBT,
        description = "Veresiye",
        createdAt = STAMP
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

    private companion object {
        const val STAMP = "2026-07-20T06:15:00Z"
    }
}

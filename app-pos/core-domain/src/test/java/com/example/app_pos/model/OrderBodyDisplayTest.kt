package com.example.app_pos.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The PGW's two ×1000 fields, and what they look like on screen.
 *
 * These are the only places in the app where a scaled figure is turned back into something
 * a person reads, and both are the kind of mistake that ships: a wrong tax rate or a wrong
 * quantity is a plausible-looking number, not a crash. Nothing downstream would catch it,
 * which is why the conversions live in the domain -- where a test can reach them -- instead
 * of inside a ViewHolder.
 */
class OrderBodyDisplayTest {

    private fun item(
        name: String = "Ekmek",
        price: Long = 1000L,
        quantity: Long = 1000L,
        taxPercent: Long = 1000L
    ) = OrderItem(
        name = name,
        price = price,
        quantity = quantity,
        taxPercent = taxPercent,
        sectionNo = 1,
        status = 0,
        type = 0,
        limit = 0L
    )

    // --- the tax divisor: the one this file mainly exists for ---------------

    /**
     * mock-pos's default is taxPercent = 1000, and it means 10%.
     *
     * Dividing by QUANTITY_SCALE -- the constant the field is scaled by, and so the obvious
     * one to reach for -- gives "1". This assertion is what stands between that and a demo
     * showing every line at one percent.
     */
    @Test
    fun `taxPercent of 1000 is ten percent, not one`() {
        assertEquals("10", item(taxPercent = 1000L).taxPercentDisplay())
    }

    @Test
    fun `taxPercent of 1800 is eighteen percent`() {
        assertEquals("18", item(taxPercent = 1800L).taxPercentDisplay())
    }

    @Test
    fun `a money-only handoff carries no tax`() {
        // MockBasket writes taxPercent = 0 for the single synthetic "Veresiye" line.
        assertEquals("0", item(taxPercent = 0L).taxPercentDisplay())
    }

    @Test
    fun `a fractional rate keeps its half`() {
        assertEquals("18,5", item(taxPercent = 1850L).taxPercentDisplay())
    }

    // --- quantity ------------------------------------------------------------

    @Test
    fun `a whole quantity shows no decimals`() {
        assertEquals("1", item(quantity = 1000L).quantityDisplay())
        assertEquals("2", item(quantity = 2000L).quantityDisplay())
    }

    @Test
    fun `a fractional quantity keeps only the digits it needs`() {
        assertEquals("2,5", item(quantity = 2500L).quantityDisplay())
        assertEquals("0,5", item(quantity = 500L).quantityDisplay())
        assertEquals("1,25", item(quantity = 1250L).quantityDisplay())
    }

    // --- line totals: the arithmetic the screen must not redo -----------------

    @Test
    fun `a line total scales the quantity down`() {
        // 2.5 units at 10,00 TL = 25,00 TL. Multiplying price by the raw 2500 would give
        // 250,00 TL -- the mistake lineTotalMinor exists to make impossible.
        assertEquals(2500L, item(price = 1000L, quantity = 2500L).lineTotalMinor())
    }

    @Test
    fun `the basket total is the sum of its lines`() {
        val basket = OrderBody(
            basketId = "b1",
            createInvoice = false,
            documentType = 0,
            isVoid = false,
            items = listOf(
                item(name = "Ekmek", price = 1500L, quantity = 2000L),   // 30,00
                item(name = "Süt", price = 3200L, quantity = 1000L),     // 32,00
                item(name = "Peynir", price = 9000L, quantity = 500L)    // 45,00
            )
        )
        assertEquals(10700L, basket.totalMinor())
    }

    /**
     * Tax is shown but never added. The lines have to sum to the ledger entry above them:
     * a screen that displayed 18% and then a total including it would be describing an
     * amount the ledger does not hold.
     */
    @Test
    fun `tax does not enter the total`() {
        val untaxed = OrderBody("b1", false, 0, false, listOf(item(price = 1000L, taxPercent = 0L)))
        val taxed = OrderBody("b2", false, 0, false, listOf(item(price = 1000L, taxPercent = 1800L)))
        assertEquals(untaxed.totalMinor(), taxed.totalMinor())
    }
}

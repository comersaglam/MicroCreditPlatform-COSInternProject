package com.example.app_pos.model

/**
 * The order payload that Token's payment gateway (PGW) hands to us.
 *
 * In the real world a basket app builds a barcode, sends the basket (amount,
 * quantities, items) to the PGW, and the PGW forwards this `orderBody` to us. We
 * have no basket app, so mock-pos plays the PGW and hands this straight to
 * app-pos over an Intent (see MainActivity).
 *
 * This is a PURE domain model — it knows nothing about JSON. Parsing the actual
 * PGW JSON into this shape is the parser's job (OrderBodyParser), keeping the
 * wire format out of the domain.
 *
 * Money is always minor units (kuruş) as a Long — never floating point. Two of
 * the PGW's item fields are scaled by 1000 (see OrderItem), which is why the line
 * total is computed in one place (lineTotalMinor / totalMinor) instead of ad hoc.
 */
data class OrderBody(
    val basketId: String,        // UUID from the PGW; ties the ledger entry to this basket
    val createInvoice: Boolean,
    val documentType: Int,
    val isVoid: Boolean,
    val items: List<OrderItem>
) {
    /**
     * The whole basket's total in minor units: the sum of every line total.
     * This is the amount that flows into the veresiye (credit) entry. Works for a
     * money-only handoff too — that is just a basket with a single synthetic item.
     */
    fun totalMinor(): Long = items.sumOf { it.lineTotalMinor() }
}

/**
 * One line of the basket, in the PGW's field shape.
 *
 * SCALING (the two gotchas, handled only inside lineTotalMinor):
 *  - price:    per-unit amount in minor units (kuruş). 10000 = 100,00 TL.
 *  - quantity: scaled by 1000. 1000 means 1.000 units; 2500 means 2.5 units.
 *  - taxPercent: scaled by 1000. 1000 means 10%, 1800 means 18%. NOT applied to the
 *    ledger amount -- the line total is price × quantity, tax included in the price
 *    the way a till receipt shows it. Displayed on the detail screen as a rate, never
 *    added to a total, so the lines always sum to the entry above them.
 */
data class OrderItem(
    val name: String,
    val price: Long,         // per-unit, minor units (kuruş)
    val quantity: Long,      // scaled by 1000 (1000 = 1 unit)
    val taxPercent: Long,    // scaled by 1000 (1000 = 10%)
    val sectionNo: Int,
    val status: Int,
    val type: Int,
    val limit: Long
) {
    /** price × (quantity / 1000), kept integer: price × quantity / 1000. */
    fun lineTotalMinor(): Long = price * quantity / QUANTITY_SCALE

    /**
     * How many units, for a human: 1000 -> "1", 2500 -> "2,5", 500 -> "0,5".
     *
     * Trailing zeros are dropped, because "2,000 ekmek" reads as a quantity someone
     * measured to three decimals rather than two loaves.
     */
    fun quantityDisplay(): String {
        val whole = quantity / QUANTITY_SCALE
        val fraction = quantity % QUANTITY_SCALE
        if (fraction == 0L) return whole.toString()
        return "$whole,${fraction.toString().padStart(3, '0').trimEnd('0')}"
    }

    /**
     * The tax rate as a percentage: 1000 -> "10", 1800 -> "18", 0 -> "0".
     *
     * THE DIVISOR IS 100, NOT 1000, and the difference is the trap this function exists to
     * close. taxPercent is scaled ×1000 like quantity, but it is a PERCENTAGE: 1800 means
     * 18%, which is 1800/100. Reaching for QUANTITY_SCALE here -- the obvious thing, given
     * the field is scaled by it -- renders 18% as "%1". Plausible enough to survive a
     * glance, and nothing else in the app would catch it.
     *
     * Halves are kept (1850 -> "18,5") because reduced Turkish rates are not all integers.
     */
    fun taxPercentDisplay(): String {
        val whole = taxPercent / PERCENT_SCALE
        val fraction = taxPercent % PERCENT_SCALE
        if (fraction == 0L) return whole.toString()
        return "$whole,${fraction.toString().padStart(2, '0').trimEnd('0')}"
    }

    companion object {
        /** The PGW scales quantity and taxPercent by this factor. */
        const val QUANTITY_SCALE = 1000L

        /**
         * What to divide taxPercent by to get a percentage.
         *
         * Named apart from [QUANTITY_SCALE] deliberately, even though the field carries the
         * same ×1000: these are two different questions. Quantity asks "how many units",
         * tax asks "what percent", and the same scale factor answers them differently.
         */
        const val PERCENT_SCALE = 100L
    }
}

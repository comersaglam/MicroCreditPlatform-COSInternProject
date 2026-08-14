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
 *  - taxPercent: scaled by 1000. 1000 means 10%, 1800 means 18%. Kept as-is for
 *    now (tax is not applied to the ledger amount yet); stored for later.
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

    companion object {
        /** The PGW scales quantity and taxPercent by this factor. */
        const val QUANTITY_SCALE = 1000L
    }
}

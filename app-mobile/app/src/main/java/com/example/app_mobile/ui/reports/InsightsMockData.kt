package com.example.app_mobile.ui.reports

/**
 * ⚠️ EVERY FIGURE ON THE INSIGHTS SCREEN COMES FROM HERE. NOTHING IS READ FROM THE LEDGER.
 *
 * This is a deliberate mock (Turn 46, decision 46.1) and deferred.md §L.17 says so. The
 * screen it feeds is a picture of what insights will look like, not a working analysis —
 * the presentation needed the argument visible, and the argument does not depend on the
 * arithmetic being live.
 *
 * WHY IT IS MOCK RATHER THAN COMPUTED. The money side could genuinely have been computed:
 * observeAllForSeller / observeAllForBuyer already return every entry, reactively, from
 * Room. What stopped it was measured, not assumed:
 *
 *   - The inflation card is the product's headline argument, and INDEXATION rows exist for
 *     NO demo account (all 20 in the database belong to the seed's u_owner/u_market).
 *     Indexing is lazy, so the figure would have rendered 0,00 TL on every account anyone
 *     would demo with — a picture of the argument failing.
 *   - Item-level "what did they buy" is not drawable at all: 1 transaction in 5801 has a
 *     basket, and the bulk reads drop it anyway.
 *
 * So the numbers here are INVENTED BUT PROPORTIONATE — derived from the real shape of the
 * demo database so that nothing on screen is implausible:
 *   ~1,1M TL booked, ~907k TL collected across 13 months, 8 shops, 107 customers,
 *   58% cumulative CPI over the window, ten purchase categories.
 *
 * ⚠️ THE MONTH LABELS ARE FROZEN TOO, and that is on purpose. Deriving them from today's
 * date (the way BalanceSeries does for real data) would leave the labels drifting forward
 * while these fixed values stayed put, and by next spring the chart would claim a summer
 * that is not in it.
 *
 * What a real implementation needs is written down in deferred.md §L.17: aggregation
 * functions in core-domain beside Ledger.kt (pure, testable), an indexation warm-up for
 * the demo accounts, and — for item-level detail — a basket aggregate query plus seed work.
 */
object InsightsMockData {

    /** Twelve months, oldest first. Fixed, for the reason in the class comment. */
    val MONTHS = listOf(
        "Eyl", "Eki", "Kas", "Ara", "Oca", "Şub",
        "Mar", "Nis", "May", "Haz", "Tem", "Ağu"
    )

    /**
     * The shopkeeper's side: what was booked, what came back, and what inflation took.
     *
     * The monthly series follow the seed's real seasonal wave — a slow autumn climb, a
     * March peak, collection catching up over the summer — so the chart tells a story
     * somebody could recognise rather than a flat line of round numbers.
     */
    val seller = RoleInsights(
        // The two figures the split bar is drawn from. Booked includes indexation, which
        // is why it exceeds the sum of purchases: inflation is part of what is owed.
        billedMinor = 1_116_744_00L,
        collectedMinor = 906_656_00L,

        monthlyDebt = listOf(
            69_825_00L, 86_429_00L, 88_106_00L, 103_665_00L, 107_306_00L, 97_634_00L,
            121_753_00L, 97_494_00L, 85_077_00L, 81_821_00L, 89_758_00L, 87_876_00L
        ),
        monthlyPayment = listOf(
            7_454_00L, 51_643_00L, 52_593_00L, 81_599_00L, 66_056_00L, 58_505_00L,
            70_546_00L, 114_977_00L, 112_524_00L, 84_611_00L, 94_767_00L, 111_381_00L
        ),

        // The headline. What indexing recovered that a plain notebook would have lost:
        // roughly 58% CPI over the window applied to the balances that were outstanding.
        inflationMinor = 118_430_00L,

        // From the ledger's description field in the real data — the ten categories the
        // seed actually uses, in the proportions it actually produces.
        categories = listOf(
            "Toplu alışveriş" to 297_354_00L,
            "Et, tavuk" to 208_368_00L,
            "Market alışverişi" to 171_671_00L,
            "Kuruyemiş" to 101_070_00L,
            "Peynir, zeytin" to 80_534_00L,
            "Kahvaltılık" to 66_704_00L
        ),

        // Who owes the most. Names from the seed's own customer list.
        ranked = listOf(
            "Hasan Öztürk" to 34_820_00L,
            "Fatma Şahin" to 21_450_00L,
            "Ayşe Demir" to 16_500_00L,
            "Mehmet Kaya" to 12_180_00L,
            "Ahmet Yılmaz" to 6_657_00L
        )
    )

    /**
     * The shopper's side: the same ledger read from the other end of the counter.
     *
     * Smaller by an order of magnitude, which is the point — one person's year against a
     * shop's. The story inverts too: inflation is something taken from them, not saved.
     */
    val buyer = RoleInsights(
        billedMinor = 34_180_00L,
        collectedMinor = 26_640_00L,

        monthlyDebt = listOf(
            1_840_00L, 2_310_00L, 2_580_00L, 3_940_00L, 3_120_00L, 2_460_00L,
            4_180_00L, 2_890_00L, 2_240_00L, 2_610_00L, 3_050_00L, 2_960_00L
        ),
        monthlyPayment = listOf(
            0L, 1_500_00L, 1_800_00L, 2_400_00L, 2_900_00L, 1_600_00L,
            2_200_00L, 3_600_00L, 2_800_00L, 2_140_00L, 2_700_00L, 3_000_00L
        ),

        // The mirror of the seller's headline: what waiting to pay actually cost.
        inflationMinor = 3_620_00L,

        categories = listOf(
            "Market alışverişi" to 9_420_00L,
            "Et, tavuk" to 7_180_00L,
            "Meyve sebze" to 5_240_00L,
            "Ekmek, süt" to 4_860_00L,
            "Çay, şeker" to 3_940_00L,
            "Deterjan, temizlik" to 3_540_00L
        ),

        // Which shops, rather than which customers. Shop names from the seed.
        ranked = listOf(
            "Yıldız Bakkal" to 3_180_00L,
            "Ahmet Bakkal" to 2_460_00L,
            "Ayşe Market" to 1_900_00L
        )
    )
}

/**
 * One role's numbers. Identical in shape for buyer and seller, because the two screens are
 * the same six cards read from opposite ends — what a shop is owed is what a shopper owes.
 */
data class RoleInsights(
    val billedMinor: Long,
    val collectedMinor: Long,
    val monthlyDebt: List<Long>,
    val monthlyPayment: List<Long>,
    val inflationMinor: Long,
    /** Label to amount, already sorted descending. */
    val categories: List<Pair<String, Long>>,
    /** Riskiest customers (seller) or shops owed (buyer), sorted descending. */
    val ranked: List<Pair<String, Long>>
) {
    /** What share of everything booked has come back, 0..100. */
    val collectionPercent: Int
        get() = if (billedMinor <= 0L) 0 else (collectedMinor * 100 / billedMinor).toInt()

    /** The busiest month's label and amount — the argmax of [monthlyDebt]. */
    val busiestMonth: Pair<String, Long>?
        get() {
            val index = monthlyDebt.indices.maxByOrNull { monthlyDebt[it] } ?: return null
            return InsightsMockData.MONTHS[index] to monthlyDebt[index]
        }
}

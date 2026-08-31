package com.example.app_mobile.util

import com.example.app_pos.model.Transaction
import com.example.app_pos.model.TransactionType

/**
 * A monthly balance series, derived from ledger entries the screen already holds.
 *
 * This is what the sparkline draws. No endpoint and no query: the balance is a sum over
 * these rows, so its history is a running sum over the same rows, and asking the server
 * for something the device can already work out would be a third place for the two to
 * disagree.
 *
 * RUNNING, not per-month. Each point is what was owed AT THE END of that month, which is
 * the shape someone actually recognises: a debt that grows and is paid down. Per-month
 * totals would draw activity instead, and spike on the month of a large purchase even if
 * it was settled the same week.
 */
object BalanceSeries {

    /** How many months the sparkline shows. A year reads as a season; more is noise. */
    const val MONTHS = 12

    /**
     * The closing balance for each of the last [MONTHS] months, oldest first.
     *
     * Returns an empty list when there is nothing to draw. The view already declines to
     * paint fewer than two points, but a caller may want to hide the whole strip.
     */
    fun monthly(transactions: List<Transaction>, months: Int = MONTHS): List<Long> {
        if (transactions.isEmpty()) return emptyList()

        // createdAt is ISO-8601 UTC and sorts as text, which is the whole reason that
        // format was chosen. "2026-08" is its first seven characters.
        val sorted = transactions.sortedBy { it.createdAt }
        val lastMonth = sorted.last().createdAt.take(7)

        val buckets = monthsUpTo(lastMonth, months)
        if (buckets.isEmpty()) return emptyList()

        val series = ArrayList<Long>(buckets.size)
        var running = 0L
        var index = 0

        // Entries older than the window still count toward the opening balance -- they
        // are just not drawn. Dropping them would make the line start from zero and
        // invent a debt that was taken on in one step.
        for (bucket in buckets) {
            while (index < sorted.size && sorted[index].createdAt.take(7) <= bucket) {
                running += signed(sorted[index])
                index++
            }
            series += running
        }

        return series
    }

    /** DEBT and INDEXATION add, PAYMENT subtracts -- the same rule as balanceOf. */
    private fun signed(tx: Transaction): Long = when (tx.type) {
        TransactionType.DEBT -> tx.amountMinor
        TransactionType.INDEXATION -> tx.amountMinor
        TransactionType.PAYMENT -> -tx.amountMinor
    }

    /**
     * The [count] month keys ending at [last], oldest first, as "yyyy-MM".
     *
     * Stepping back by month rather than by days: subtracting 30 days repeatedly drifts,
     * and February would eventually be skipped entirely.
     */
    private fun monthsUpTo(last: String, count: Int): List<String> {
        val year = last.substring(0, 4).toIntOrNull() ?: return emptyList()
        val month = last.substring(5, 7).toIntOrNull() ?: return emptyList()

        val total = year * 12 + (month - 1)
        return (total - count + 1..total)
            .filter { it >= 0 }
            .map { "%04d-%02d".format(it / 12, it % 12 + 1) }
    }
}

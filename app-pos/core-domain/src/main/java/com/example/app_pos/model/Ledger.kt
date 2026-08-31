package com.example.app_pos.model

/**
 * The append-only rule as a pure function: a (seller, customer) balance is the sum
 * of that pair's entries, DEBT and INDEXATION adding and PAYMENT subtracting. Lives in
 * the domain module so both the fake and the Room repository derive balances the same way
 * (and so nothing is ever tempted to store a balance).
 *
 * INDEXATION adds for the same reason DEBT does: it is part of what the customer owes.
 * The server writes those rows monthly (see backend/app/indexation.py) and they arrive
 * here through the ordinary ledger pull, so this device never computes inflation -- it
 * only has to agree about which way the row points.
 *
 * The `when` is exhaustive on purpose. It is the compiler's hold on every place that
 * splits entries by type, and it is what turned adding a third type into a list of sites
 * to review rather than a bug to find later.
 */
fun balanceOf(sellerId: String, customerId: String, ledger: List<Transaction>): Long =
    ledger
        .filter { it.sellerId == sellerId && it.customerId == customerId }
        .sumOf { tx ->
            when (tx.type) {
                TransactionType.DEBT -> tx.amountMinor
                TransactionType.INDEXATION -> tx.amountMinor
                TransactionType.PAYMENT -> -tx.amountMinor
            }
        }

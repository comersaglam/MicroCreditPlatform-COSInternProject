package com.example.app_pos.model

/**
 * The append-only rule as a pure function: a (seller, customer) balance is the sum
 * of that pair's entries, DEBT adding and PAYMENT subtracting. Lives in the domain
 * module so both the fake and the Room repository derive balances the same way (and
 * so nothing is ever tempted to store a balance).
 */
fun balanceOf(sellerId: String, customerId: String, ledger: List<Transaction>): Long =
    ledger
        .filter { it.sellerId == sellerId && it.customerId == customerId }
        .sumOf { tx ->
            when (tx.type) {
                TransactionType.DEBT -> tx.amountMinor
                TransactionType.PAYMENT -> -tx.amountMinor
            }
        }

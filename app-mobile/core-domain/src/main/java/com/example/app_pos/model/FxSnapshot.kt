package com.example.app_pos.model

/**
 * What a lira was worth on one day, in whole currencies rather than in the ledger's terms.
 *
 * Reference data, and deliberately kept OUT of the balance: indexation reaches the device
 * as ledger rows already denominated in lira, so no screen has to convert anything to know
 * what is owed. This exists for the screens that put a figure in context — what a debt
 * bought the day it was taken on, against what it buys now.
 *
 * [asOf] is the date of the reading actually used, which may be earlier than the day asked
 * for: the series has gaps, and a weekend answers with Friday. Naming the real date is what
 * keeps the screen from claiming a precision the data does not have.
 *
 * Every figure is minor units per unit of currency: usdMinor = 3350 means one dollar cost
 * 33,50 TL. Long, like all money here; the division into something a person reads happens
 * at the edge, once, and never comes back.
 */
data class FxSnapshot(
    val asOf: String,
    val usdMinor: Long,
    val eurMinor: Long,
    val goldMinor: Long
)

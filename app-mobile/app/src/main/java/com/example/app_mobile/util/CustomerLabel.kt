package com.example.app_mobile.util

import android.content.Context
import com.example.app_mobile.R
import com.example.app_pos.model.Customer

/**
 * How a customer is NAMED on screen when the shopkeeper never typed a name.
 *
 * A blank name used to draw a blank row, which reads as missing DATA rather than a missing
 * field — the shopkeeper sees an empty line where a person should be and cannot tell
 * whether the record is broken. Only one field is actually absent, and the phone number
 * identifies the person perfectly well, so it takes the title and the row says plainly
 * that the name was never entered.
 *
 * Where the blanks come from: registration sends `displayName = ""` (LoginViewModel), so an
 * account opened with nothing but a phone number genuinely has no name. That is a real
 * record, not a stub — the buyer-side stub rows are excluded in SQL before they ever reach
 * a list — which is why showing them properly is the right fix rather than a cosmetic one
 * papering over a data gap.
 */
fun Customer.titleFor(context: Context): String =
    displayName.takeIf { it.isNotBlank() }
        ?: phone?.takeIf { it.isNotBlank() }
        ?: context.getString(R.string.customer_unnamed)

/**
 * Whether this customer matches a search query.
 *
 * Matches the PHONE as well as the name, which the name-only filter could not: a customer
 * with no name was unreachable through search, so the one record hardest to recognise in a
 * list was also the one that could not be looked up.
 */
fun Customer.matchesQuery(query: String): Boolean {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return true
    return displayName.contains(trimmed, ignoreCase = true) ||
        phone?.contains(trimmed, ignoreCase = true) == true
}

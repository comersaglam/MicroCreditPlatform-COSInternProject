package com.example.app_pos.model

/**
 * Turns what the merchant types into one canonical phone format, so every number in
 * the system looks the same and two entries for one person always match.
 *
 * Input rule: a Turkish mobile number as digits, 10 long, starting with 0
 * (e.g. "0555 444 3322" — spaces are ignored). Anything else is invalid.
 *
 * Stored form: E.164 with the country code, leading 0 dropped:
 * "0555 444 3322" -> "+905554443322".
 *
 * **This is the system's ONE canonical phone form.** Every number is converted here before
 * it is stored OR queried, so the database can compare with a plain `phone = :stored` and
 * two entries for one person always match.
 *
 * That rule was previously broken and cost a real bug: the queries normalised with SQL
 * `REPLACE`, one of them matched with `LIKE '%..%'` and another with `=`, and neither
 * handled the country code. Typing "05554443322" produced "05554443322" while the stored
 * value reduced to "905554443322" — so sign-in (substring) worked while the claim (exact)
 * silently did nothing, leaving customers UNCLAIMED. Normalising in ONE place, before the
 * query, is what prevents that class of mismatch; it also lets the column's index be used.
 *
 * Lives in :core-domain (not :app) precisely so the data layer can reach it — the layer
 * that talks to the database is the one that must not invent its own comparison.
 *
 * Moved here from :app in Turn 39, when app-pos gained a server read path: rows now
 * arrive carrying the server's canonical E.164, and a loose local comparison would
 * disagree with them.
 */
object PhoneFormat {

    /** Preview shown in the input field so the expected shape is obvious. */
    const val HINT = "0555 444 3322"

    /**
     * Returns the canonical "+90…" form, or null if the input is not a valid
     * 10-digit number starting with 0. Callers use null to show a format error.
     */
    fun toStored(input: String): String? {
        val digits = input.filter { it.isDigit() }
        // Idempotent: an already-stored number ("+905554443322" -> "905554443322",
        // 12 digits starting with 90) is returned as-is, so calling toStored twice is
        // safe. This prevents a re-conversion from wrongly rejecting a stored number.
        if (digits.length == 12 && digits.startsWith("90")) return "+$digits"
        // A raw local number: 11 digits starting with 0, e.g. "0555 444 3322".
        if (digits.length != 11 || !digits.startsWith("0")) return null
        // Drop the leading 0, prepend the country code.
        return "+90" + digits.substring(1)
    }
}

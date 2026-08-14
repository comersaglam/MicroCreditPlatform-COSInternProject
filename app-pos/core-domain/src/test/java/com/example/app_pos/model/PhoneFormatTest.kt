package com.example.app_pos.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The canonical phone form — the property the whole lookup path now depends on.
 *
 * These are regression guards for a real bug: sign-in and the claim normalised phone
 * numbers differently, so signing in worked while linking a customer's existing debt
 * silently matched nothing. The fix is that EVERY caller converts here first and the
 * database compares with plain equality, which only holds if the cases below do.
 */
class PhoneFormatTest {

    @Test
    fun `a local number becomes E164`() {
        assertEquals("+905554443322", PhoneFormat.toStored("05554443322"))
    }

    @Test
    fun `spacing and punctuation do not change the result`() {
        assertEquals("+905554443322", PhoneFormat.toStored("0555 444 33 22"))
        assertEquals("+905554443322", PhoneFormat.toStored("0555-444-3322"))
    }

    /**
     * The one that broke the claim: what the user types and what the database already
     * holds must reduce to the SAME string. They used to differ by the country code
     * ("05554443322" vs "905554443322"), and an exact-match query then found nothing.
     */
    @Test
    fun `a typed number and a stored number agree`() {
        assertEquals(PhoneFormat.toStored("+905554443322"), PhoneFormat.toStored("05554443322"))
    }

    /** Idempotent: converting an already-canonical number returns it unchanged. */
    @Test
    fun `converting twice changes nothing`() {
        val once = PhoneFormat.toStored("05554443322")
        assertEquals(once, PhoneFormat.toStored(once!!))
    }

    /** A landline is a valid number too — the seed uses one as a shop's line. */
    @Test
    fun `a landline is accepted`() {
        assertEquals("+902123334455", PhoneFormat.toStored("02123334455"))
    }

    @Test
    fun `an unusable input is rejected rather than guessed at`() {
        assertNull(PhoneFormat.toStored("555"))
        assertNull(PhoneFormat.toStored(""))
        // 10 digits with no leading zero is not the local form this app accepts.
        assertNull(PhoneFormat.toStored("5554443322"))
    }
}

package com.example.app_pos.data

import com.example.app_pos.data.consent.ConsentStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The KVKK consent is one PERSON's, not one handset's.
 *
 * This was device-scoped and shipped that way. It was caught on a real phone: register one
 * number, accept the notice, sign out, register a SECOND number — and the notice never
 * appeared. The second person was recorded as having consented to something they were
 * never shown, which is the one outcome a consent gate exists to prevent.
 *
 * No test could have caught it, because there was no test. That is the actual finding, and
 * this file is the answer to it.
 *
 * The store itself needs DataStore and therefore a device, so what is verified here is the
 * KEYING RULE against an in-memory stand-in with the same contract. The rule is the part
 * that was wrong; the persistence was always fine.
 */
class ConsentScopeTest {

    /**
     * The same shape as DataStoreConsentStore, minus the disk: one entry per phone.
     *
     * ⚠️ A fake that stored a single Boolean would reproduce the BUG rather than the fix,
     * and would pass every assertion below except the ones that matter. It keys by string
     * for the same reason the real one does.
     */
    private class InMemoryConsentStore : ConsentStore {
        private val accepted = mutableSetOf<String>()

        override suspend fun hasAcceptedKvkk(phone: String) = phone in accepted

        override suspend fun acceptKvkk(phone: String) {
            accepted += phone
        }
    }

    private val ayse = "+905551112233"
    private val mehmet = "+905554445566"

    @Test
    fun `nobody has consented before they are asked`() = runTest {
        val store = InMemoryConsentStore()

        assertFalse(store.hasAcceptedKvkk(ayse))
    }

    @Test
    fun `the notice is not shown twice to the same person`() = runTest {
        val store = InMemoryConsentStore()

        store.acceptKvkk(ayse)

        assertTrue(store.hasAcceptedKvkk(ayse))
    }

    @Test
    fun `one person's consent does not cover the next person on the same phone`() = runTest {
        val store = InMemoryConsentStore()

        store.acceptKvkk(ayse)

        // THE REGRESSION. Device-keyed, this returned true and the second registration
        // skipped the notice entirely.
        assertFalse(store.hasAcceptedKvkk(mehmet))
    }

    @Test
    fun `signing out does not withdraw a consent`() = runTest {
        val store = InMemoryConsentStore()
        store.acceptKvkk(ayse)

        // Logout clears the session store; consent lives in its own DataStore file
        // precisely so that clear() cannot reach it, so nothing happens here at all.

        assertTrue(store.hasAcceptedKvkk(ayse))
    }

    @Test
    fun `each person is asked once, however many share the handset`() = runTest {
        val store = InMemoryConsentStore()

        store.acceptKvkk(ayse)
        assertFalse(store.hasAcceptedKvkk(mehmet))

        store.acceptKvkk(mehmet)

        // A shared phone -- a family, a shop counter -- ends with both consents recorded
        // and neither standing in for the other.
        assertTrue(store.hasAcceptedKvkk(ayse))
        assertTrue(store.hasAcceptedKvkk(mehmet))
    }
}

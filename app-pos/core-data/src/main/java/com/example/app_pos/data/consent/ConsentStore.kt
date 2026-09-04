package com.example.app_pos.data.consent

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

/**
 * Whether the person behind a phone number has been shown the KVKK notice and accepted it.
 *
 * ⚠️ PER PERSON, NOT PER DEVICE — and it was per device until this was corrected on a real
 * phone. Consent under KVKK is given by a data subject about their own data; one person
 * accepting on a handset cannot stand in for the next person to use it. The first version
 * keyed nothing at all, so registering a second number on the same phone skipped the
 * notice entirely: the second person was recorded as consenting to something they were
 * never shown.
 *
 * The number is the identity the rest of this app already uses — one canonical E.164 form,
 * normalised in exactly one place — so it is the right key here too. It is available at
 * every gate for the same reason the gate exists at all: consent is asked BEFORE a user
 * row exists, and at that moment the phone number is the only thing we know about the
 * person.
 *
 * A shared handset (a family, a shop counter) is therefore handled correctly by
 * construction rather than by a special case.
 */
interface ConsentStore {

    suspend fun hasAcceptedKvkk(phone: String): Boolean

    suspend fun acceptKvkk(phone: String)
}

/**
 * ⚠️ Its OWN DataStore file, and that is the whole design.
 *
 * The obvious home was TokenStore — the plan said so. But DataStoreTokenStore.clear() is
 * `sessionDataStore.edit { it.clear() }`, which wipes that entire file on every logout, so
 * consent stored beside the session would be forgotten each time somebody signed out and
 * the notice would reappear forever. A separate `preferencesDataStore(name = ...)` is a
 * separate file on disk; clear() cannot reach it.
 *
 * That separation matters more now that consent is per person: signing out must NOT
 * withdraw a consent, and signing in as someone else must NOT inherit one. Those are two
 * different requirements and the key below is what keeps them apart — logout leaves every
 * entry alone, and a different number simply misses.
 *
 * `adb uninstall` still removes the file, which is what device verification wants, and
 * allowBackup="false" means a reinstall genuinely starts clean rather than restoring a
 * consent the user never gave on this install.
 *
 * No prime()/in-memory cache, deliberately. TokenStore has that machinery because
 * MainActivity picks a start destination in onCreate and cannot await anything; nothing
 * here runs before a coroutine is available, so copying the cache would be copying a
 * workaround for a constraint that does not apply.
 */
private val Context.consentDataStore by preferencesDataStore(name = "consent")

class DataStoreConsentStore(private val context: Context) : ConsentStore {

    override suspend fun hasAcceptedKvkk(phone: String): Boolean =
        context.consentDataStore.data.first()[keyFor(phone)] == true

    override suspend fun acceptKvkk(phone: String) {
        context.consentDataStore.edit { it[keyFor(phone)] = true }
    }

    private companion object {
        /**
         * One key per number.
         *
         * Write-once in practice: there is no withdrawal path in this phase, and a real one
         * would be a product decision (what happens to the account?) rather than a flag
         * flip.
         *
         * ⚠️ The caller passes the number in the SAME canonical form the rest of the app
         * stores it in (E.164, normalised in one place). A raw "0555 444 3322" and a
         * "+905554443322" would key two different entries for one person, and the notice
         * would reappear for someone who had already accepted it — the mirror of the bug
         * this keying fixes.
         */
        fun keyFor(phone: String) = booleanPreferencesKey("kvkk_accepted_$phone")
    }
}

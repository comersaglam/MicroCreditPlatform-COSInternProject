package com.example.app_pos.data.consent

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

/**
 * Whether this device has been shown the KVKK notice and had it accepted.
 *
 * A device-level fact, not an account-level one, and that is why it is not a column on the
 * user: the gate fires BEFORE a user row exists (registration is what it guards), and the
 * same person signing in again on the same phone has already read the text.
 */
interface ConsentStore {

    suspend fun hasAcceptedKvkk(): Boolean

    suspend fun acceptKvkk()
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
 * `adb uninstall` still removes it, which is what the turn's verification wants, and
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

    override suspend fun hasAcceptedKvkk(): Boolean =
        context.consentDataStore.data.first()[KEY_KVKK_ACCEPTED] == true

    override suspend fun acceptKvkk() {
        context.consentDataStore.edit { it[KEY_KVKK_ACCEPTED] = true }
    }

    private companion object {
        // Write-once in practice: there is no withdrawal path in this phase, and a real one
        // would be a product decision (what happens to the account?) rather than a flag flip.
        val KEY_KVKK_ACCEPTED = booleanPreferencesKey("kvkk_accepted")
    }
}

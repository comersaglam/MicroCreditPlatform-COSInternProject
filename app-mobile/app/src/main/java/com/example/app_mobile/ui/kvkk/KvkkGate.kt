package com.example.app_mobile.ui.kvkk

import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.app_mobile.R
import com.example.app_mobile.databinding.DialogKvkkBinding
import com.example.app_pos.data.consent.ConsentStore
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

/**
 * Runs [onGranted] only once this device has accepted the KVKK notice, asking for it first
 * if it has not.
 *
 * ONE implementation for THREE entry points — registration on both apps, and "Satıcı ol" on
 * this one. Their call shapes all differ (`register(code)`, `register()`,
 * `becomeSeller(shopName)`), which is exactly why the gate takes a lambda: the differences
 * are captured at the call site and never reach here. A checkbox added separately to each
 * of the three dialogs would be three implementations and three chances to leave one out,
 * which is the mistake Turn 41 paid for — a rule that holds in one place and not another
 * is not a rule.
 *
 * Already accepted means no dialog at all — but "already" is about THIS PERSON, keyed by
 * [phone], not about this handset. Consent is given by a data subject about their own
 * data; one person accepting on a phone cannot stand in for the next person to use it.
 * Keyed by device, registering a second number skipped the notice entirely and recorded
 * the second person as consenting to something they were never shown.
 *
 * The notice is a thing you read once, not a confirmation step on every action.
 *
 * @param phone the number being registered, in the app's canonical E.164 form — the same
 * string the rest of the app stores. Two spellings of one number would key two entries and
 * show the notice again to someone who had already accepted it.
 *
 * @return the dialog when one was shown, so the caller can dismiss it in onDestroyView.
 * A dialog that outlives its fragment is a leaked window.
 */
fun Fragment.requireKvkkConsent(
    consentStore: ConsentStore,
    phone: String,
    onShown: (AlertDialog) -> Unit = {},
    onGranted: () -> Unit
) {
    viewLifecycleOwner.lifecycleScope.launch {
        if (consentStore.hasAcceptedKvkk(phone)) {
            onGranted()
            return@launch
        }

        val binding = DialogKvkkBinding.inflate(layoutInflater)

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.kvkk_title)
            .setView(binding.root)
            .setPositiveButton(R.string.kvkk_continue) { _, _ ->
                // Persisted BEFORE the caller's work, so a failure in that work does not
                // cost the user a consent they already gave.
                viewLifecycleOwner.lifecycleScope.launch {
                    consentStore.acceptKvkk(phone)
                    onGranted()
                }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()

        // Disabled AFTER show(), because that is when the button exists: an AlertDialog's
        // buttons are not created until then, and there is no builder-level way to start
        // one disabled.
        val positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        positive.isEnabled = false
        binding.kvkkAcceptCheck.setOnCheckedChangeListener { _, checked ->
            positive.isEnabled = checked
        }

        onShown(dialog)
    }
}

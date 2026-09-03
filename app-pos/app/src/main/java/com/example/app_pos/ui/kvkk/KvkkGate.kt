package com.example.app_pos.ui.kvkk

import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.app_pos.R
import com.example.app_pos.databinding.DialogKvkkBinding
import com.example.app_pos.data.consent.ConsentStore
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

/**
 * Runs [onGranted] only once this device has accepted the KVKK notice, asking for it first
 * if it has not.
 *
 * ONE implementation for THREE entry points across the two apps: registration here,
 * registration on app-mobile, and "Satıcı ol" on app-mobile. Only the first of those is on
 * this side, but the file is kept identical to app-mobile's so the rule is provably the
 * same rule and not two that merely resemble each other.
 *
 * Their call shapes all differ (`register()` here, `register(code)` and
 * `becomeSeller(shopName)` there), which is exactly why the gate takes a lambda: the
 * differences are captured at the call site and never reach here. A checkbox added
 * separately to each of the three dialogs would be three implementations and three chances
 * to leave one out, which is the mistake Turn 41 paid for — a rule that holds in one place
 * and not another is not a rule.
 *
 * Already accepted means no dialog at all. The notice is a thing you read once, not a
 * confirmation step on every action.
 *
 * @return the dialog when one was shown, so the caller can dismiss it in onDestroyView.
 * A dialog that outlives its fragment is a leaked window.
 */
fun Fragment.requireKvkkConsent(
    consentStore: ConsentStore,
    onShown: (AlertDialog) -> Unit = {},
    onGranted: () -> Unit
) {
    viewLifecycleOwner.lifecycleScope.launch {
        if (consentStore.hasAcceptedKvkk()) {
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
                    consentStore.acceptKvkk()
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

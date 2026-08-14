package com.example.app_pos.ui.sale

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.navigation.navGraphViewModels
import com.example.app_pos.R
import com.example.app_pos.model.Repository
import com.example.app_pos.databinding.FragmentPhoneBinding
import com.example.app_pos.model.Customer
import com.example.app_pos.model.CustomerLookup
import com.example.app_pos.model.PhoneFormat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * New-customer step: capture name + phone, then continue to confirm.
 *
 * Phone is the system's identity for a customer, so it is required and must be
 * unique — a duplicate is rejected here rather than creating a second record for
 * the same number. Only reached for a brand-new customer; picking an existing one
 * goes straight to confirm.
 */
@AndroidEntryPoint
class PhoneFragment : Fragment() {

    private var _binding: FragmentPhoneBinding? = null
    private val binding get() = _binding!!

    private val args: PhoneFragmentArgs by navArgs()
    private val saleViewModel: SaleViewModel by navGraphViewModels(R.id.saleFlow)
    @Inject lateinit var repo: Repository

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPhoneBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        installCancelGuard()
        // Show the expected phone shape as a placeholder inside the field.
        binding.phoneLayout.placeholderText = PhoneFormat.HINT
        // The name typed in the search box seeds the field, still editable here.
        if (savedInstanceState == null) {
            binding.nameInput.setText(args.initialName)
        }
        binding.btnContinue.setOnClickListener { onContinue() }
    }

    private fun onContinue() {
        val name = binding.nameInput.text?.toString()?.trim().orEmpty()
        val rawPhone = binding.phoneInput.text?.toString()?.trim().orEmpty()

        if (name.isEmpty()) {
            binding.nameLayout.error = getString(R.string.phone_name_hint)
            return
        }
        binding.nameLayout.error = null

        // Normalize to the stored "+90…" form; null means the format is wrong.
        val phone = PhoneFormat.toStored(rawPhone)
        if (phone == null) {
            binding.phoneLayout.error = getString(R.string.msg_phone_invalid)
            return
        }

        // What this number means to THIS seller decides the next step. A person known
        // to another shop is not a duplicate — ownership lives in the ledger, so they
        // are reused here rather than rejected. DB read (suspend) → coroutine.
        viewLifecycleOwner.lifecycleScope.launch {
            val sellerId = repo.currentSellerId() ?: return@launch
            when (val lookup = repo.lookupCustomerForSeller(sellerId, phone)) {
                // Already in this seller's own book: a second record would split the
                // history, so send them back to pick the existing one.
                is CustomerLookup.AlreadyMine -> {
                    binding.phoneLayout.error = getString(R.string.msg_phone_exists)
                }
                // Known to another shop: confirm, then reuse that record.
                is CustomerLookup.KnownToOtherSeller -> {
                    binding.phoneLayout.error = null
                    confirmKnownCustomer(lookup.existing)
                }
                // Brand new: created at write time (addCustomer), after OTP.
                CustomerLookup.New -> {
                    binding.phoneLayout.error = null
                    selectAndContinue(customerId = "", displayName = name, phone = phone, isNew = true)
                }
            }
        }
    }

    /**
     * Asks before adopting a record another shop created. The stored name is used (not
     * what was typed): it is the same person, and the merchant sees who that is before
     * the entry is booked.
     */
    private fun confirmKnownCustomer(existing: Customer) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.known_customer_title)
            .setMessage(getString(R.string.known_customer_message, existing.displayName))
            .setNegativeButton(R.string.cancel_dialog_dismiss, null)
            .setPositiveButton(R.string.known_customer_continue) { _, _ ->
                // isNew = false: the record exists, so the write only appends a ledger
                // entry for this seller — which is what puts them in this book.
                selectAndContinue(
                    customerId = existing.customerId,
                    displayName = existing.displayName,
                    phone = existing.phone.orEmpty(),
                    isNew = false
                )
            }
            .show()
    }

    private fun selectAndContinue(
        customerId: String,
        displayName: String,
        phone: String,
        isNew: Boolean
    ) {
        saleViewModel.onCustomerSelected(customerId, displayName, phone, isNew)
        findNavController().navigate(R.id.action_phone_to_confirm)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

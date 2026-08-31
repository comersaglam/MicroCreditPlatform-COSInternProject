package com.example.app_mobile.ui.customers

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.app_mobile.R
import com.example.app_mobile.databinding.FragmentCustomersBinding
import com.example.app_pos.model.PhoneFormat
import com.example.app_mobile.util.toTlString
import com.example.app_pos.model.Customer
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/** Müşterilerim — the seller's customer list (only reachable once isSeller). */
@AndroidEntryPoint
class CustomersFragment : Fragment() {

    private var _binding: FragmentCustomersBinding? = null
    private val binding get() = _binding!!

    private val viewModel: CustomersViewModel by viewModels()
    private val adapter = CustomerAdapter(onClick = ::openCustomerDetail)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCustomersBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.customerList.layoutManager = LinearLayoutManager(requireContext())
        binding.customerList.adapter = adapter

        binding.searchInput.doAfterTextChanged { viewModel.onSearchChanged(it?.toString().orEmpty()) }
        binding.fabAddCustomer.setOnClickListener { showAddCustomerDialog() }
        binding.filterGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            val filter = if (checkedIds.firstOrNull() == R.id.chipWithDebt)
                CustomerFilter.WITH_DEBT else CustomerFilter.ALL
            viewModel.onFilterChanged(filter)
        }

        binding.totalAmount.format = { it.toTlString() }
        // Always money coming IN on this screen -- it lists what the shop is owed -- so
        // the direction is fixed rather than read off the sign.
        binding.totalAmount.setGradientColors(
            requireContext().getColor(R.color.credit_grad_top),
            requireContext().getColor(R.color.credit_grad_bottom),
        )

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.customers.collect { customers ->
                        adapter.submitList(customers)
                        binding.emptyView.visibility =
                            if (customers.isEmpty()) View.VISIBLE else View.GONE
                    }
                }
                launch {
                    viewModel.totalReceivableMinor.collect { binding.totalAmount.setAmount(it) }
                }
                launch {
                    viewModel.totalSeries.collect { binding.totalSpark.values = it }
                }
                // Keeps the book fed from the server while the screen is open. Cancelled
                // with the lifecycle, so a backgrounded app stops asking.
                launch { viewModel.poll() }
            }
        }
    }

    /**
     * Add-to-book popup. The dialog is kept open on a bad phone number (inline error)
     * so the merchant can correct it, which is why the positive button is rebound
     * instead of using the builder's auto-dismissing listener.
     */
    private fun showAddCustomerDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_add_customer, null)
        val nameLayout = view.findViewById<TextInputLayout>(R.id.nameLayout)
        val nameInput = view.findViewById<TextInputEditText>(R.id.nameInput)
        val phoneLayout = view.findViewById<TextInputLayout>(R.id.phoneLayout)
        val phoneInput = view.findViewById<TextInputEditText>(R.id.phoneInput)
        phoneLayout.placeholderText = PhoneFormat.HINT

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.add_customer_title)
            .setView(view)
            .setPositiveButton(R.string.add_customer_positive, null)
            .setNegativeButton(R.string.pay_dialog_negative, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = nameInput.text?.toString()?.trim().orEmpty()
                if (name.isEmpty()) {
                    nameLayout.error = getString(R.string.msg_name_required)
                    return@setOnClickListener
                }
                nameLayout.error = null

                viewModel.addCustomer(name, phoneInput.text?.toString().orEmpty()) { result ->
                    if (context == null) return@addCustomer
                    when (result) {
                        // Stay open with an inline error so the number can be fixed.
                        CustomersViewModel.AddCustomerResult.InvalidPhone ->
                            phoneLayout.error = getString(R.string.msg_phone_invalid)
                        CustomersViewModel.AddCustomerResult.AlreadyMine ->
                            phoneLayout.error = getString(R.string.msg_phone_exists)
                        // A record already exists elsewhere: reuse it rather than
                        // creating a second one, once the merchant confirms who it is.
                        is CustomersViewModel.AddCustomerResult.ConfirmKnown -> {
                            dialog.dismiss()
                            confirmKnownCustomer(result.existing)
                        }
                        is CustomersViewModel.AddCustomerResult.Added -> {
                            dialog.dismiss()
                            openCustomerDetail(result.customerId, result.displayName)
                        }
                        // Nothing was created. The dialog stays open with what was typed
                        // still in it, so the merchant can simply retry once there is
                        // signal — closing it would make them enter everything again.
                        CustomersViewModel.AddCustomerResult.Unreachable ->
                            phoneLayout.error = getString(R.string.msg_customer_unreachable)
                        is CustomersViewModel.AddCustomerResult.Failed ->
                            phoneLayout.error = result.message
                    }
                }
            }
        }
        dialog.show()
    }

    /** Asks before adopting a record another shop created; the stored name is used. */
    private fun confirmKnownCustomer(existing: Customer) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.known_customer_title)
            .setMessage(getString(R.string.known_customer_message, existing.displayName))
            .setNegativeButton(R.string.pay_dialog_negative, null)
            .setPositiveButton(R.string.known_customer_continue) { _, _ ->
                openCustomerDetail(existing.customerId, existing.displayName)
            }
            .show()
    }

    // The customer only appears in this list once a ledger entry exists, so the detail
    // screen (with its veresiye popup) is where the book entry actually starts.
    private fun openCustomerDetail(customerId: String, customerName: String) {
        findNavController().navigate(
            CustomersFragmentDirections.actionCustomersToCustomerDetail(
                customerId = customerId,
                customerName = customerName
            )
        )
    }

    private fun openCustomerDetail(customer: Customer) =
        openCustomerDetail(customer.customerId, customer.displayName)

    override fun onDestroyView() {
        super.onDestroyView()
        binding.customerList.adapter = null
        _binding = null
    }
}

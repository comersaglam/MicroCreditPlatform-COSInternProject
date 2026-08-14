package com.example.app_pos.ui.sale

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.navigation.navGraphViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.app_pos.R
import com.example.app_pos.databinding.FragmentCustomerSelectBinding
import com.example.app_pos.model.Customer
import com.example.app_pos.ui.dashboard.customers.CustomerAdapter
import com.example.app_pos.util.toTlString
import kotlinx.coroutines.launch
import dagger.hilt.android.AndroidEntryPoint

/**
 * Step 2 of the sale flow: choose the customer a credit entry is written to.
 *
 * Only a selector — the book is listed so the merchant can pick from it, but the
 * totals and filters of the management view stay in the dashboard, because at the
 * till the single open question is who owes this amount.
 *
 * Two ways to identify the customer:
 *   • "Uygulama ile bağlan" — the customer's app hands over a credential
 *     (QR/NFC, FAZ 8); mocked for now.
 *   • search by name — the fallback when the customer has no app on them.
 */
@AndroidEntryPoint
class CustomerSelectFragment : Fragment() {

    private var _binding: FragmentCustomerSelectBinding? = null
    private val binding get() = _binding!!

    // Search state is this screen's own.
    private val viewModel: CustomerSelectViewModel by viewModels()

    // The amount and txType are already in the flow ViewModel (set by the keypad
    // entry, which forwards DEBT here with the amount already loaded).
    private val saleViewModel: SaleViewModel by navGraphViewModels(R.id.saleFlow)

    // Same adapter as the dashboard list — only the click behaviour differs,
    // which is why it takes the callback as a constructor parameter.
    private val adapter = CustomerAdapter(onClick = ::onCustomerPicked)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCustomerSelectBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        installCancelGuard()
        setupList()
        setupSearch()
        setupConnectButton()
        setupCreateButton()
        observeState()
    }

    private fun setupList() {
        binding.matchList.layoutManager = LinearLayoutManager(requireContext())
        binding.matchList.adapter = adapter
    }

    private fun setupSearch() {
        binding.searchInput.doAfterTextChanged { text ->
            viewModel.onSearchChanged(text?.toString().orEmpty())
        }
    }

    private fun setupConnectButton() {
        binding.btnConnectApp.setOnClickListener {
            toast(getString(R.string.msg_connect_mocked))
        }
    }

    private fun setupCreateButton() {
        binding.btnCreateCustomer.setOnClickListener { onCreateCustomer() }
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    saleViewModel.amountMinor.collect { amount ->
                        binding.amountText.text = amount.toTlString()
                    }
                }
                launch {
                    viewModel.matches.collect { matches ->
                        adapter.submitList(matches)
                        renderHint(matches.isEmpty(), viewModel.query.value)
                    }
                }
                // Drive both the button's visibility and its label off the query
                // itself: canCreate only flips on the empty↔non-empty edge, so the
                // label would stick at the first letter ("a" for "ali"). The query
                // emits on every keystroke, so the name stays in sync.
                launch {
                    viewModel.query.collect { query ->
                        val show = query.isNotEmpty()
                        binding.btnCreateCustomer.visibility =
                            if (show) View.VISIBLE else View.GONE
                        if (show) {
                            binding.btnCreateCustomer.text =
                                getString(R.string.create_customer_label, query)
                        }
                    }
                }
            }
        }
    }

    /**
     * The hint only appears when the list is empty, and says WHY it is empty: the book
     * itself has nobody in it yet, or the search matched nothing.
     *
     * With a blank query the list now shows every customer, so an empty list at that point
     * means an empty book — not "you have not searched yet", which is what it used to say.
     */
    private fun renderHint(noMatches: Boolean, query: String) {
        binding.hintView.visibility = if (noMatches) View.VISIBLE else View.GONE
        binding.hintView.setText(
            if (query.isEmpty()) R.string.no_customers_yet else R.string.no_customer_found
        )
    }

    /** Existing customer: phone is already on record, so go straight to confirm. */
    private fun onCustomerPicked(customer: Customer) {
        saleViewModel.onCustomerSelected(
            customerId = customer.customerId,
            displayName = customer.displayName,
            phone = customer.phone.orEmpty(),
            isNew = false
        )
        findNavController().navigate(R.id.action_customerSelect_to_confirm)
    }

    /**
     * New customer: the phone screen captures the number (identity) before the
     * entry can be written. The typed name seeds that screen.
     */
    private fun onCreateCustomer() {
        val name = viewModel.query.value
        if (name.isEmpty()) return
        val action = CustomerSelectFragmentDirections.actionCustomerSelectToPhone(name)
        findNavController().navigate(action)
    }

    private fun toast(message: String) =
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()

    override fun onDestroyView() {
        super.onDestroyView()
        binding.matchList.adapter = null
        _binding = null
    }
}

package com.example.app_pos.ui.dashboard.customers

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.app_pos.R
import com.example.app_pos.databinding.FragmentCustomersBinding
import com.example.app_pos.model.Customer
import com.example.app_pos.util.toTlString
import kotlinx.coroutines.launch
import dagger.hilt.android.AndroidEntryPoint

/**
 * Dashboard's first tab: who owes what, and how much in total.
 *
 * This screen used to sit in the middle of the sale flow, which mixed a
 * management view into the moment of a sale. It now belongs to the dashboard
 * only — hence no SaleViewModel and no "amount to be charged" line here.
 */
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
        setupList()
        setupSearch()
        setupFilters()
        observeState()
    }

    private fun setupList() {
        binding.customerList.layoutManager = LinearLayoutManager(requireContext())
        binding.customerList.adapter = adapter
    }

    private fun setupSearch() {
        binding.searchInput.doAfterTextChanged { text ->
            viewModel.onSearchChanged(text?.toString().orEmpty())
        }
    }

    private fun setupFilters() {
        binding.filterGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            val filter = when (checkedIds.firstOrNull()) {
                R.id.chipWithDebt -> CustomerFilter.WITH_DEBT
                else -> CustomerFilter.ALL
            }
            viewModel.onFilterChanged(filter)
        }
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Each collect() suspends forever, so every flow needs its own
                // launch or only the first one would ever run.
                launch {
                    viewModel.customers.collect { customers ->
                        adapter.submitList(customers)
                        binding.emptyView.visibility =
                            if (customers.isEmpty()) View.VISIBLE else View.GONE
                    }
                }
                launch {
                    viewModel.totalReceivableMinor.collect { total ->
                        binding.totalAmount.text = total.toTlString()
                    }
                }
                // Keeps the book fed from the server while the screen is open. Cancelled
                // with the lifecycle, so a backgrounded terminal stops asking.
                launch { viewModel.poll() }
            }
        }
    }

    private fun openCustomerDetail(customer: Customer) {
        // Safe Args: arguments are typed, so a missing or misspelled one is a
        // compile error rather than a null at runtime.
        val direction = CustomersFragmentDirections
            .actionCustomersToCustomerDetail(
                customerId = customer.customerId,
                customerName = customer.displayName
            )
        // findNavController() resolves to the dashboard's inner controller,
        // since this fragment is hosted by the dashboard's nav host — the
        // detail screen is pushed inside the tab and the bottom bar stays.
        findNavController().navigate(direction)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // The RecyclerView outlives nothing here, but clearing the adapter
        // reference keeps the destroyed view tree from being retained.
        binding.customerList.adapter = null
        _binding = null
    }
}

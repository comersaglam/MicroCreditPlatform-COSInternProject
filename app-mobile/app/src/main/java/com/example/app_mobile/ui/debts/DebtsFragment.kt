package com.example.app_mobile.ui.debts

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.app_pos.model.SellerDebt
import com.example.app_mobile.databinding.FragmentDebtsBinding
import com.example.app_mobile.util.toTlString
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/** Borçlarım — the buyer's home: the shops they owe and the grand total. */
@AndroidEntryPoint
class DebtsFragment : Fragment() {

    private var _binding: FragmentDebtsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: DebtsViewModel by viewModels()
    private val adapter = SellerDebtAdapter(onClick = ::openSellerDetail)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDebtsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.debtList.layoutManager = LinearLayoutManager(requireContext())
        binding.debtList.adapter = adapter

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.debts.collect { debts ->
                        adapter.submitList(debts)
                        binding.emptyView.visibility = if (debts.isEmpty()) View.VISIBLE else View.GONE
                    }
                }
                launch {
                    viewModel.totalDebtMinor.collect { total ->
                        binding.totalAmount.text = total.toTlString()
                    }
                }
            }
        }
    }

    private fun openSellerDetail(debt: SellerDebt) {
        findNavController().navigate(
            DebtsFragmentDirections.actionDebtsToSellerDetail(debt.sellerId, debt.shopName)
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding.debtList.adapter = null
        _binding = null
    }
}

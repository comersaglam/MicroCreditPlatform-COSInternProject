package com.example.app_mobile.ui.customerdetail

import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.app_mobile.R
import com.example.app_mobile.util.message
import com.example.app_mobile.databinding.FragmentCustomerDetailBinding
import com.example.app_mobile.ui.sellerdetail.TransactionAdapter
import com.example.app_pos.model.ClaimStatus
import com.example.app_pos.model.TransactionType
import com.example.app_mobile.util.toTlString
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import kotlin.math.roundToLong

/**
 * The seller's view of one customer: ledger history + balance, and two write actions
 * ([Veresiye Yaz] / [Ödeme Al]) that each open an amount popup and go through the
 * approval gate. Mirrors app-pos's CustomerDetailFragment, but
 * the write is a popup instead of the keypad sale flow.
 */
@AndroidEntryPoint
class CustomerDetailFragment : Fragment() {

    private var _binding: FragmentCustomerDetailBinding? = null
    private val binding get() = _binding!!

    private val args: CustomerDetailFragmentArgs by navArgs()

    // No factory: Hilt builds the ViewModel and its SavedStateHandle carries `customerId`
    // straight from the nav arguments.
    private val viewModel: CustomerDetailViewModel by viewModels()

    private val adapter = TransactionAdapter()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCustomerDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.detailName.text = args.customerName

        binding.transactionList.layoutManager = LinearLayoutManager(requireContext())
        binding.transactionList.adapter = adapter

        setupFilters()
        binding.btnDebt.setOnClickListener { showAmountDialog(TransactionType.DEBT) }
        binding.btnPay.setOnClickListener { showAmountDialog(TransactionType.PAYMENT) }
        observePhone()
        observeBalance()
        observeTransactions()
    }

    private fun setupFilters() {
        binding.txFilterGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            val filter = when (checkedIds.firstOrNull()) {
                R.id.chipTxDebt -> TransactionFilter.DEBT
                R.id.chipTxPayment -> TransactionFilter.PAYMENT
                else -> TransactionFilter.ALL
            }
            viewModel.onFilterChanged(filter)
        }
    }

    /**
     * Amount popup; lira parsed, stored as kuruş.
     *
     * The two buttons no longer share an ending, which is the whole point of paths 3 and 4.
     * A veresiye needs the customer's agreement, so it goes through the approval gate and
     * the server queues the receipt once they accept. A payment needs a CARD, which only
     * the till can take — so it is sent to the terminal and nothing is booked here, because
     * nobody has paid yet.
     */
    private fun showAmountDialog(type: TransactionType) {
        val input = EditText(requireContext()).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = getString(R.string.pay_dialog_hint)
        }
        val titleRes = if (type == TransactionType.DEBT)
            R.string.detail_write_debt else R.string.detail_take_payment
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(titleRes)
            .setView(input)
            .setPositiveButton(R.string.pay_dialog_positive) { _, _ ->
                val lira = input.text.toString().replace(',', '.').toDoubleOrNull() ?: return@setPositiveButton
                val amountMinor = (lira * 100).roundToLong()
                if (amountMinor <= 0) return@setPositiveButton
                when (type) {
                    TransactionType.DEBT -> writeDebt(amountMinor)
                    TransactionType.PAYMENT -> collectAtTerminal(amountMinor)
                }
            }
            .setNegativeButton(R.string.pay_dialog_negative, null)
            .show()
    }

    /** Path 3: through the approval gate, then the server tells the till to print. */
    private fun writeDebt(amountMinor: Long) {
        // Wait for the actual result instead of guessing from isClaimed: that flag starts
        // false while its lookup is in flight, so a claimed customer was being told the
        // entry had been written when it was awaiting approval.
        viewModel.writeDebt(amountMinor, DEBT_DESCRIPTION) { outcome ->
            val ctx = context ?: return@writeDebt
            Toast.makeText(ctx, outcome.message(ctx), Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Path 4: the till is asked to take the payment by card.
     *
     * Says the terminal was NOTIFIED, never that money arrived — the customer still has to
     * present a card at the till, and claiming otherwise would let a shopkeeper believe a
     * debt was settled when nothing has happened yet.
     */
    private fun collectAtTerminal(amountMinor: Long) {
        viewModel.collectAtTerminal(amountMinor) { queued ->
            val ctx = context ?: return@collectAtTerminal
            val message =
                if (queued) R.string.collect_sent_to_terminal else R.string.collect_not_sent
            Toast.makeText(ctx, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun observePhone() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.phone.collect { binding.detailPhone.text = it }
            }
        }
    }

    private fun observeBalance() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.balanceMinor.collect { balance ->
                    binding.detailBalance.text = balance.toTlString()
                    val colorRes = if (balance > 0) R.color.balance_due else R.color.payment_received
                    binding.detailBalance.setTextColor(requireContext().getColor(colorRes))
                }
            }
        }
    }

    private fun observeTransactions() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.transactions.collect { txs ->
                    adapter.submitList(txs)
                    binding.emptyView.visibility = if (txs.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding.transactionList.adapter = null
        _binding = null
    }

    private companion object {
        /**
         * What a veresiye written from the shopkeeper's phone is called in the ledger.
         * A constant rather than an inline literal: it reaches the customer's approval
         * card, so it is user-visible text with exactly one source.
         */
        const val DEBT_DESCRIPTION = "Veresiye"
    }
}

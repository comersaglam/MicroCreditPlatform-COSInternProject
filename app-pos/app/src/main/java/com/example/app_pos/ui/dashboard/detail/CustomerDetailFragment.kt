package com.example.app_pos.ui.dashboard.detail

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.viewModels
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.app_pos.R
import com.example.app_pos.databinding.DialogCollectAmountBinding
import com.example.app_pos.databinding.FragmentCustomerDetailBinding
import com.example.app_pos.pgw.PgwBridge
import com.example.app_pos.util.toTlString
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import dagger.hilt.android.AndroidEntryPoint
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Third screen: one customer's ledger history and current balance.
 */
@AndroidEntryPoint
class CustomerDetailFragment : Fragment() {

    private var _binding: FragmentCustomerDetailBinding? = null
    private val binding get() = _binding!!

    // Safe Args reads the typed arguments declared in nav_graph.xml.
    private val args: CustomerDetailFragmentArgs by navArgs()

    // No factory: Hilt builds the ViewModel and hands it the customerId through the
    // SavedStateHandle, which Navigation populates from this destination's arguments.
    private val viewModel: CustomerDetailViewModel by viewModels()

    private val adapter = TransactionAdapter { transaction ->
        findNavController().navigate(
            CustomerDetailFragmentDirections
                .actionCustomerDetailToTransactionDetail(transaction.transactionId)
        )
    }

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

        setupList()
        setupFilters()
        setupPayButton()
        observePhone()
        observeBalance()
        observeCardExtras()
        observeTransactions()
        observeGatewayRequests()
    }

    /** The phone is a suspend lookup, exposed by the ViewModel as a Flow; shown as it
     *  arrives. Nothing is kept for the pay path any more — that used to carry the number
     *  into the OTP screen, which this flow no longer has. */
    private fun observePhone() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.phone.collect { binding.detailPhone.text = it }
            }
        }
    }

    /**
     * Path 2 — TAHSİLAT. Asks for the amount and goes straight to the gateway.
     *
     * This used to enter saleFlow, which meant the till's own keypad screen, then confirm,
     * then an OTP screen, and only then the gateway. Those three screens are gone: the
     * customer approves this payment by presenting their card AT the gateway, so a second
     * approval at the till was ceremony — and the OTP step verified nothing, accepting any
     * code. What remains is the one question the gateway cannot answer for us, the amount.
     *
     * The veresiye path (path 1) still goes through saleFlow and still waits for the
     * customer's approval; only the payment entry point changed.
     */
    private fun setupPayButton() {
        binding.btnPay.setOnClickListener { showCollectAmountDialog() }
    }

    /**
     * One field, one button: the amount, then the gateway.
     *
     * The input is read in LİRA and converted to kuruş here, because that is what the
     * merchant types on a terminal and what the field's decimal keyboard offers. Everything
     * below this line is minor units, as the rest of the ledger is.
     */
    private fun showCollectAmountDialog() {
        val dialogBinding = DialogCollectAmountBinding.inflate(layoutInflater)
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.collect_dialog_title)
            .setView(dialogBinding.root)
            .setNegativeButton(R.string.confirm_cancel, null)
            .setPositiveButton(R.string.collect_dialog_confirm, null)
            .create()

        // The positive button is wired AFTER show() so a bad amount can keep the dialog
        // open; the default listener dismisses no matter what the field holds.
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val amountMinor = parseAmountMinor(dialogBinding.collectAmountInput.text?.toString())
                if (amountMinor == null || amountMinor <= 0L) {
                    dialogBinding.collectAmountLayout.error =
                        getString(R.string.msg_enter_amount)
                    return@setOnClickListener
                }
                dialog.dismiss()
                viewModel.collectPayment(amountMinor)
            }
        }
        dialog.show()
    }

    /**
     * Lira text to kuruş, or null when it is not a usable amount.
     *
     * Both separators are accepted: the terminal's keyboard offers a dot while Turkish
     * writes a comma, and rejecting the one the merchant actually typed would be an
     * invented failure. BigDecimal rather than Double — money scaled by a binary float is
     * how a 55,55 becomes 5554 kuruş.
     */
    private fun parseAmountMinor(text: String?): Long? {
        val normalized = text?.trim()?.replace(',', '.').orEmpty()
        if (normalized.isEmpty()) return null
        return try {
            BigDecimal(normalized).movePointRight(2).setScale(0, RoundingMode.HALF_UP).toLong()
        } catch (_: NumberFormatException) {
            null
        }
    }

    /**
     * Fires the gateway request once the payment is in the ledger.
     *
     * Collected here rather than in the ViewModel because starting an activity needs a
     * Context, and the data layer must not hold one — the same split OtpFragment uses.
     */
    private fun observeGatewayRequests() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.collectAtGateway.collect { request ->
                    Toast.makeText(
                        requireContext(),
                        getString(
                            R.string.msg_payment_written,
                            request.customerName ?: args.customerName,
                            request.amountMinor.toTlString()
                        ),
                        Toast.LENGTH_SHORT
                    ).show()
                    val opened = PgwBridge.collectPayment(
                        context = requireContext(),
                        amountMinor = request.amountMinor,
                        customerName = request.customerName
                    )
                    if (!opened) {
                        Toast.makeText(requireContext(), R.string.msg_pgw_missing, Toast.LENGTH_LONG)
                            .show()
                    }
                }
            }
        }
    }

    private fun observeBalance() {
        binding.detailBalance.format = { it.toTlString() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.balanceMinor.collect { balance ->
                    // Three states, not two. Zero used to take the same green as a fresh
                    // payment, announcing an event that had not happened; nothing
                    // outstanding is neither a debt nor a credit.
                    val (top, bottom) = when {
                        balance > 0 -> R.color.debt_grad_top to R.color.debt_grad_bottom
                        balance < 0 -> R.color.credit_grad_top to R.color.credit_grad_bottom
                        else -> R.color.neutral_grad_top to R.color.neutral_grad_bottom
                    }
                    binding.detailBalance.setGradientColors(
                        requireContext().getColor(top),
                        requireContext().getColor(bottom),
                    )
                    binding.detailBalance.setAmount(balance)
                }
            }
        }
    }

    /** The trend line and the inflation share — the focus card's two ornaments. */
    private fun observeCardExtras() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.balanceSeries.collect { binding.balanceSpark.values = it }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.indexationMinor.collect { indexation ->
                    binding.detailIndexationNote.visibility =
                        if (indexation > 0) View.VISIBLE else View.GONE
                    if (indexation > 0) {
                        binding.detailIndexationNote.text =
                            getString(R.string.detail_indexation_note, indexation.toTlString())
                    }
                }
            }
        }
    }

    private fun setupList() {
        binding.transactionList.layoutManager = LinearLayoutManager(requireContext())
        binding.transactionList.adapter = adapter
    }

    private fun setupFilters() {
        binding.txFilterGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            val filter = when (checkedIds.firstOrNull()) {
                R.id.chipTxDebt -> TransactionFilter.DEBT
                R.id.chipTxPayment -> TransactionFilter.PAYMENT
                R.id.chipTxIndexation -> TransactionFilter.INDEXATION
                else -> TransactionFilter.ALL
            }
            viewModel.onFilterChanged(filter)
        }
    }

    private fun observeTransactions() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.transactions.collect(adapter::submitList)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding.transactionList.adapter = null
        _binding = null
    }
}

package com.example.app_pos.ui.sale

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.navigation.navGraphViewModels
import com.example.app_pos.R
import com.example.app_pos.databinding.FragmentKeypadBinding
import com.example.app_pos.model.TransactionType
import com.example.app_pos.util.toTlString
import kotlinx.coroutines.launch
import dagger.hilt.android.AndroidEntryPoint

/**
 * First screen of the in-app PAYMENT flow: key in the amount the customer is
 * paying, then continue to confirm. The customer is already known (opened from
 * their detail screen), so this screen only asks "how much".
 *
 * The keypad logic lives in the flow-scoped SaleViewModel, so the amount is
 * already in place for the confirm step without any hand-off.
 */
@AndroidEntryPoint
class KeypadFragment : Fragment() {

    private var _binding: FragmentKeypadBinding? = null
    private val binding get() = _binding!!

    private val saleViewModel: SaleViewModel by navGraphViewModels(R.id.saleFlow)
    private val args: KeypadFragmentArgs by navArgs()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentKeypadBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // saleFlow's start destination serves both flows; the amount arg tells
        // them apart. Do this once, before touching the keypad UI.
        if (savedInstanceState == null && routeByEntry()) return

        installCancelGuard()
        bindKeypad()
        binding.btnContinue.setOnClickListener { onContinue() }
        observeAmount()
    }

    /**
     * Splits the two entries:
     *  - amount already set (>0) => DEBT from the payment app: load it and forward
     *    to customer select; the keypad is skipped. Returns true (leaving).
     *  - amount 0 => PAYMENT for a known customer: seed the flow and stay on the
     *    keypad so the merchant keys the amount in. Returns false.
     *
     * NOTE: nothing enters this flow as a PAYMENT any more. Path 2 was moved off saleFlow
     * onto the customer's detail screen (CustomerDetailFragment.showCollectAmountDialog),
     * which asks the amount in one dialog and calls the gateway directly — the keypad,
     * confirm and OTP screens were ceremony for a payment the customer approves with their
     * card. The branch is kept because the flow's PAYMENT half (confirm's summary,
     * OtpViewModel.collectPayment) is still wired and a second payment entry point may want
     * it; delete all of it together, not piecemeal.
     */
    private fun routeByEntry(): Boolean {
        return if (args.amountMinor > 0L) {
            saleViewModel.setTxType(TransactionType.DEBT)
            saleViewModel.setAmount(args.amountMinor)
            findNavController().navigate(R.id.action_keypad_to_customerSelect)
            true
        } else {
            saleViewModel.setTxType(TransactionType.PAYMENT)
            saleViewModel.onClear()
            saleViewModel.onCustomerSelected(
                customerId = args.payCustomerId,
                displayName = args.payCustomerName,
                phone = args.payCustomerPhone,
                isNew = false
            )
            false
        }
    }

    private fun bindKeypad() = with(binding) {
        key0.setOnClickListener { saleViewModel.onDigit(0) }
        key1.setOnClickListener { saleViewModel.onDigit(1) }
        key2.setOnClickListener { saleViewModel.onDigit(2) }
        key3.setOnClickListener { saleViewModel.onDigit(3) }
        key4.setOnClickListener { saleViewModel.onDigit(4) }
        key5.setOnClickListener { saleViewModel.onDigit(5) }
        key6.setOnClickListener { saleViewModel.onDigit(6) }
        key7.setOnClickListener { saleViewModel.onDigit(7) }
        key8.setOnClickListener { saleViewModel.onDigit(8) }
        key9.setOnClickListener { saleViewModel.onDigit(9) }
        keyClear.setOnClickListener { saleViewModel.onClear() }
        keyBackspace.setOnClickListener { saleViewModel.onBackspace() }
    }

    private fun observeAmount() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                saleViewModel.amountMinor.collect { amount ->
                    binding.amountText.text = amount.toTlString()
                }
            }
        }
    }

    private fun onContinue() {
        if (!saleViewModel.hasAmount()) {
            Toast.makeText(requireContext(), R.string.msg_enter_amount, Toast.LENGTH_SHORT).show()
            return
        }
        findNavController().navigate(R.id.action_keypad_to_confirm)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

package com.example.app_pos.ui.sale

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.navigation.navGraphViewModels
import com.example.app_pos.MainActivity
import com.example.app_pos.R
import com.example.app_pos.model.Repository
import com.example.app_pos.databinding.FragmentOtpBinding
import com.example.app_pos.model.ClaimStatus
import com.example.app_pos.pgw.PgwBridge
import com.example.app_pos.model.TransactionType
import com.example.app_pos.util.toTlString
import kotlinx.coroutines.launch
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Customer-approval step. Requests an OTP on entry and, once the merchant enters
 * the code, verifies it and — only on success — writes the entry. This is the
 * gate that stops the merchant booking anything without the customer's approval.
 * Verification is mocked (any code) until the backend lands.
 */
@AndroidEntryPoint
class OtpFragment : Fragment() {

    private var _binding: FragmentOtpBinding? = null
    private val binding get() = _binding!!

    private val saleViewModel: SaleViewModel by navGraphViewModels(R.id.saleFlow)
    private val viewModel: OtpViewModel by viewModels()
    @Inject lateinit var repo: Repository

    // Whether the customer has the app: routes app-push vs SMS (both mocked). Resolved
    // once (the lookup is a suspend DB read now) and cached; a new customer has no app.
    private var hasApp: Boolean = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentOtpBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        installCancelGuard()
        val phone = saleViewModel.selectedCustomer.value?.phone.orEmpty()
        binding.otpPrompt.text = getString(R.string.otp_prompt, phone)
        binding.btnVerify.setOnClickListener { onVerify() }
        observeStatus()
        observeGatewayRequests()
        // Resolve hasApp (a suspend lookup) first, then kick off the request once.
        if (savedInstanceState == null) {
            viewLifecycleOwner.lifecycleScope.launch {
                hasApp = resolveHasApp()
                viewModel.sendOtp(phone, hasApp)
            }
        }
    }

    /** Whether the selected customer is CLAIMED (has the app). New customers do not. */
    private suspend fun resolveHasApp(): Boolean {
        val sel = saleViewModel.selectedCustomer.value ?: return false
        if (sel.isNew) return false
        // Only claimStatus matters here (app vs SMS), seller-independent; the seller
        // scope just satisfies the balance-aware lookup signature.
        val sellerId = repo.currentSellerId() ?: return false
        return repo.findCustomerByPhone(sellerId, sel.phone)?.claimStatus == ClaimStatus.CLAIMED
    }

    private fun observeStatus() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.status.collect(::renderStatus)
            }
        }
    }

    private fun renderStatus(status: OtpStatus) = with(binding) {
        when (status) {
            OtpStatus.SENDING -> {
                statusText.visibility = View.VISIBLE
                statusText.setText(R.string.otp_sending)
                btnVerify.isEnabled = false
            }
            OtpStatus.READY -> {
                statusText.visibility = View.GONE
                btnVerify.isEnabled = true
            }
            OtpStatus.VERIFYING -> {
                statusText.visibility = View.VISIBLE
                statusText.setText(R.string.otp_verifying)
                btnVerify.isEnabled = false
            }
            OtpStatus.ERROR -> {
                statusText.visibility = View.GONE
                btnVerify.isEnabled = true
                Toast.makeText(requireContext(), R.string.msg_otp_failed, Toast.LENGTH_SHORT).show()
            }
            // Nothing was written, here or on the server. Said plainly, because the sale
            // can be retried the moment there is signal — and because blaming the merchant
            // for a dropped connection is the mistake ss6 already taught us.
            OtpStatus.CUSTOMER_UNREACHABLE -> {
                statusText.visibility = View.GONE
                btnVerify.isEnabled = true
                Toast.makeText(
                    requireContext(),
                    R.string.msg_customer_unreachable,
                    Toast.LENGTH_LONG
                ).show()
            }
            // The request is with the customer. The verify button stays disabled: there is
            // nothing left for the merchant to do here except wait or back out, and an
            // enabled button would invite a second request for the same sale.
            OtpStatus.AWAITING_APPROVAL -> {
                statusText.visibility = View.VISIBLE
                statusText.setText(R.string.otp_awaiting_approval)
                btnVerify.isEnabled = false
            }
            // The customer said no. The gateway is told the sale failed, so it does not
            // print a slip for a debt that was refused.
            OtpStatus.REJECTED -> {
                statusText.visibility = View.GONE
                btnVerify.isEnabled = false
                Toast.makeText(requireContext(), R.string.msg_approval_rejected, Toast.LENGTH_LONG)
                    .show()
                finishHandoff(success = false)
            }
            // No signal, so nobody could be asked and nothing was written anywhere. The
            // merchant can retry the moment there is a connection.
            OtpStatus.APPROVAL_UNREACHABLE -> {
                statusText.visibility = View.GONE
                btnVerify.isEnabled = true
                Toast.makeText(
                    requireContext(),
                    R.string.msg_approval_unreachable,
                    Toast.LENGTH_LONG
                ).show()
            }
            OtpStatus.DONE -> Unit // handled in the write callback
        }
    }

    /**
     * Fires the gateway request a completed payment asks for (path 2).
     *
     * Collected here rather than in the ViewModel because starting an activity needs a
     * Context, and the data layer must not hold one.
     */
    private fun observeGatewayRequests() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.collectAtGateway.collect { amountMinor ->
                    amountMinor ?: return@collect
                    val opened = PgwBridge.collectPayment(
                        context = requireContext(),
                        amountMinor = amountMinor,
                        customerName = saleViewModel.selectedCustomer.value?.displayName
                    )
                    if (!opened) {
                        Toast.makeText(requireContext(), R.string.msg_pgw_missing, Toast.LENGTH_LONG)
                            .show()
                    }
                }
            }
        }
    }

    private fun onVerify() {
        val sel = saleViewModel.selectedCustomer.value ?: return
        val code = binding.codeInput.text?.toString()?.trim().orEmpty()
        val amount = saleViewModel.amountMinor.value
        val type = saleViewModel.txType
        viewModel.verifyAndWrite(
            phone = sel.phone,
            code = code,
            hasApp = hasApp,
            isNew = sel.isNew,
            displayName = sel.displayName,
            knownCustomerId = sel.customerId,
            amountMinor = amount,
            type = type,
            // The basket handed over by the PGW, if any (DEBT from mock-pos). When set,
            // its items are persisted with the entry; a PAYMENT / money-only DEBT is null.
            orderBody = saleViewModel.orderBody,
        ) { onWritten(sel.displayName, amount, type) }
    }

    private fun onWritten(name: String, amount: Long, type: TransactionType) {
        val msg = when (type) {
            TransactionType.DEBT -> getString(R.string.msg_credit_written, name, amount.toTlString())
            TransactionType.PAYMENT -> getString(R.string.msg_payment_written, name, amount.toTlString())
            // Unreachable: this flow only ever writes the two entries a cashier can start.
            TransactionType.INDEXATION -> return
        }
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
        finishHandoff(success = true, type = type)
    }

    /**
     * Ends the sale, telling the payment gateway what happened when it started this.
     *
     * The result is what the gateway is waiting on: it launched us for a veresiye and
     * cannot decide whether to print a slip until it hears back. Reporting success for a
     * refused approval would print a receipt for a debt nobody agreed to, which is the
     * whole reason this now carries an outcome rather than just closing.
     *
     * A PAYMENT never hands back — it always started inside app-pos, so there is nobody
     * waiting — and lands on the dashboard instead.
     */
    private fun finishHandoff(success: Boolean, type: TransactionType = TransactionType.DEBT) {
        val isHandoffFlow = type == TransactionType.DEBT
        val finished =
            (activity as? MainActivity)?.finishCreditHandoff(isHandoffFlow, success) ?: false
        if (!finished) {
            findNavController().navigate(R.id.action_global_dashboard)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

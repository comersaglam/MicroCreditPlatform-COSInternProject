package com.example.app_mobile.ui.sellerdetail

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.fragment.app.setFragmentResult
import com.example.app_mobile.databinding.SheetPaymentMethodBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * How the customer wants to pay: one working route and three that are pictures of routes.
 *
 * The mocks are the point of the screen — a lender's rails and a payment provider's are
 * what turn a ledger into credit, and the sheet says who those would be. What they must
 * not do is get in the way of the one that works.
 *
 * ⚠️ THE MOCKS DO NOT DISMISS. They reveal a line in place and leave the sheet open. If
 * they closed it and returned nothing, the caller would see a dismissal with no result,
 * the amount dialog would never open, and the working payment path would look broken —
 * exactly what deferred.md §L.3 exists to keep from happening.
 *
 * The choice travels back through the Fragment Result API rather than a constructor
 * callback: a lambda held across a configuration change leaks, and does not survive process
 * death. The framework already answers this and needs no new dependency.
 */
class PaymentMethodSheet : BottomSheetDialogFragment() {

    private var _binding: SheetPaymentMethodBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = SheetPaymentMethodBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // The only route that reports a result and closes. Everything the amount flow needs
        // already lives in SellerDetailFragment; this sheet just says which door was used.
        binding.methodNormal.setOnClickListener {
            setFragmentResult(REQUEST_KEY, bundleOf(KEY_METHOD to METHOD_NORMAL))
            dismiss()
        }

        val showSoon = View.OnClickListener {
            binding.methodSoonText.visibility = View.VISIBLE
        }
        binding.methodTokenflex.setOnClickListener(showSoon)
        binding.methodOdero.setOnClickListener(showSoon)
        binding.methodYapikredi.setOnClickListener(showSoon)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "PaymentMethodSheet"

        const val REQUEST_KEY = "payment_method_request"
        const val KEY_METHOD = "method"

        /** The working route: hand back to the existing amount dialog untouched. */
        const val METHOD_NORMAL = "normal"
    }
}

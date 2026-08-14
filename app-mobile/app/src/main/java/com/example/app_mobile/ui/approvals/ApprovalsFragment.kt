package com.example.app_mobile.ui.approvals

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
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.app_mobile.R
import com.example.app_pos.model.PendingApproval
import com.example.app_mobile.util.message
import com.example.app_mobile.databinding.FragmentApprovalsBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * Onaylar — pending veresiye/payment requests waiting on ME, in either role: grouped
 * into "as a seller" and "as a customer" sections, each row an Approve/Reject card
 * (no OTP code: an app-holding counterparty approves in-app).
 */
@AndroidEntryPoint
class ApprovalsFragment : Fragment() {

    private var _binding: FragmentApprovalsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ApprovalsViewModel by viewModels()
    private val adapter = ApprovalAdapter(onApprove = ::approve, onReject = ::reject)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentApprovalsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.approvalList.layoutManager = LinearLayoutManager(requireContext())
        binding.approvalList.adapter = adapter

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.items.collect { items ->
                    adapter.submitList(items)
                    // Headers only exist for non-empty sections, so an empty list still
                    // means "nothing pending".
                    binding.emptyView.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        }

        // The screen is the poll's lifetime. repeatOnLifecycle CANCELS this when the
        // fragment stops and starts it again on return, so the app never asks the server
        // for an inbox nobody is looking at — and a returning user gets a fresh answer
        // immediately rather than after the next interval.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.poll()
            }
        }
    }

    // Both report what the server actually answered. Showing "approved" the moment the
    // button is tapped is a guess, and it was wrong for any card the server refuses.
    private fun approve(approval: PendingApproval) {
        viewModel.approve(approval.approvalId) { outcome ->
            val ctx = context ?: return@approve
            Toast.makeText(ctx, outcome.message(ctx, R.string.approval_approved), Toast.LENGTH_SHORT)
                .show()
        }
    }

    private fun reject(approval: PendingApproval) {
        viewModel.reject(approval.approvalId) { outcome ->
            val ctx = context ?: return@reject
            Toast.makeText(ctx, outcome.message(ctx, R.string.approval_rejected), Toast.LENGTH_SHORT)
                .show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding.approvalList.adapter = null
        _binding = null
    }
}

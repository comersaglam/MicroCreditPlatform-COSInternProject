package com.example.app_pos.ui.dashboard.approvals

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
import com.example.app_pos.R
import com.example.app_pos.databinding.FragmentApprovalsBinding
import com.example.app_pos.model.PendingApproval
import com.example.app_pos.util.message
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * Onaylar — the requests waiting on this shop's answer.
 *
 * The screen that makes the buyer-initiated approval line finish: a customer declaring a
 * payment now has somebody on this side to confirm it, which until now nothing in app-pos
 * could do.
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
                    binding.emptyView.visibility =
                        if (items.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        }

        // The screen is the poll's lifetime. repeatOnLifecycle CANCELS this when the
        // fragment stops and starts it again on return, so the terminal never asks for an
        // inbox nobody is looking at — and a returning merchant gets a fresh answer at once
        // rather than after the next interval.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.poll()
            }
        }
    }

    // Both report what the SERVER actually answered. Showing "approved" the moment the
    // button is tapped is a guess, and it is wrong for any card the server refuses.
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
        // The adapter outlives the view; clearing it here prevents the RecyclerView from
        // holding a destroyed binding.
        binding.approvalList.adapter = null
        _binding = null
    }
}

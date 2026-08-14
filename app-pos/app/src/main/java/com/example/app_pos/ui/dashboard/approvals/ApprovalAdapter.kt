package com.example.app_pos.ui.dashboard.approvals

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.app_pos.R
import com.example.app_pos.databinding.ItemApprovalBinding
import com.example.app_pos.model.PendingApproval
import com.example.app_pos.model.TransactionType
import com.example.app_pos.util.toTlString

/**
 * Draws the pending cards. Every decision is made upstream; this only renders.
 *
 * One view type, unlike app-mobile's two: a POS is always the shop, so the list needs no
 * section headers to say which role a card puts you in.
 */
class ApprovalAdapter(
    private val onApprove: (PendingApproval) -> Unit,
    private val onReject: (PendingApproval) -> Unit
) : ListAdapter<PendingApproval, ApprovalAdapter.CardVH>(DIFF) {

    class CardVH(
        private val binding: ItemApprovalBinding,
        private val onApprove: (PendingApproval) -> Unit,
        private val onReject: (PendingApproval) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(approval: PendingApproval) = with(binding) {
            approvalCustomer.text = approval.counterpartyName
            val kindRes = if (approval.type == TransactionType.DEBT)
                R.string.approval_debt_label else R.string.approval_payment_label
            approvalKind.text = root.context.getString(kindRes)
            approvalAmount.text = approval.amountMinor.toTlString()

            approvalDescription.text = approval.description
            // Views are recycled: without the else branch a blank description would keep
            // showing the previous row's text.
            approvalDescription.visibility =
                if (approval.description.isBlank()) View.GONE else View.VISIBLE

            btnApprove.setOnClickListener { onApprove(approval) }
            btnReject.setOnClickListener { onReject(approval) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CardVH =
        CardVH(
            ItemApprovalBinding.inflate(LayoutInflater.from(parent.context), parent, false),
            onApprove,
            onReject
        )

    override fun onBindViewHolder(holder: CardVH, position: Int) = holder.bind(getItem(position))

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<PendingApproval>() {
            override fun areItemsTheSame(old: PendingApproval, new: PendingApproval) =
                old.approvalId == new.approvalId

            // A poll rewrites the whole table, so identical rows are re-submitted
            // constantly. Comparing contents is what stops the list flickering every
            // fifteen seconds.
            override fun areContentsTheSame(old: PendingApproval, new: PendingApproval) =
                old == new
        }
    }
}

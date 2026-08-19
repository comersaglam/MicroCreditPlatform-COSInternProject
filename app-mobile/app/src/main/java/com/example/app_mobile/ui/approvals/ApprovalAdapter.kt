package com.example.app_mobile.ui.approvals

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.app_mobile.R
import com.example.app_mobile.databinding.ItemApprovalBinding
import com.example.app_mobile.databinding.ItemApprovalHeaderBinding
import com.example.app_mobile.util.toTlString
import com.example.app_pos.model.PendingApproval
import com.example.app_pos.model.TransactionType
import com.google.android.material.card.MaterialCardView

/**
 * Renders the approvals list: section headers plus Approve/Reject cards.
 *
 * Two view types rather than two adapters, so one submitList drives the whole screen
 * and DiffUtil handles a section appearing or emptying on its own. Every decision
 * (grouping, colour, phone) is made in the ViewModel — this only draws.
 */
class ApprovalAdapter(
    private val onApprove: (PendingApproval) -> Unit,
    private val onReject: (PendingApproval) -> Unit
) : ListAdapter<ApprovalListItem, RecyclerView.ViewHolder>(DIFF) {

    class HeaderVH(private val binding: ItemApprovalHeaderBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: ApprovalListItem.Header) {
            binding.sectionTitle.setText(item.titleRes)
        }
    }

    class CardVH(
        private val binding: ItemApprovalBinding,
        private val onApprove: (PendingApproval) -> Unit,
        private val onReject: (PendingApproval) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: ApprovalListItem.Card) = with(binding) {
            val approval = item.approval
            approvalShop.text = approval.counterpartyName
            // The wording is chosen in the ViewModel: it depends on my role on this card,
            // and the same request reads oppositely from each end.
            approvalKind.text = root.context.getString(item.kindRes, approval.counterpartyName)
            approvalAmount.text = approval.amountMinor.toTlString()
            approvalDescription.text = approval.description

            approvalPhone.text = item.counterpartyPhone
            approvalPhone.visibility =
                if (item.counterpartyPhone.isBlank()) View.GONE else View.VISIBLE

            // Every branch sets both colours: views are recycled, so a missing case
            // would leave the previous row's tint on this card.
            val (fill, stroke) = when (item.tone) {
                ApprovalTone.INCOMING_FAINT -> R.color.credit_container_faint to R.color.credit_stroke_faint
                ApprovalTone.INCOMING_STRONG -> R.color.credit_container to R.color.credit_stroke
                ApprovalTone.OUTGOING_FAINT -> R.color.debt_container_faint to R.color.debt_stroke_faint
                ApprovalTone.OUTGOING_STRONG -> R.color.debt_container to R.color.debt_stroke
            }
            (root as MaterialCardView).apply {
                setCardBackgroundColor(context.getColor(fill))
                setStrokeColor(context.getColor(stroke))
            }

            btnApprove.setOnClickListener { onApprove(approval) }
            btnReject.setOnClickListener { onReject(approval) }
        }
    }

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is ApprovalListItem.Header -> TYPE_HEADER
        is ApprovalListItem.Card -> TYPE_CARD
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderVH(ItemApprovalHeaderBinding.inflate(inflater, parent, false))
        } else {
            CardVH(ItemApprovalBinding.inflate(inflater, parent, false), onApprove, onReject)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is ApprovalListItem.Header -> (holder as HeaderVH).bind(item)
            is ApprovalListItem.Card -> (holder as CardVH).bind(item)
        }
    }

    private companion object {
        const val TYPE_HEADER = 0
        const val TYPE_CARD = 1

        val DIFF = object : DiffUtil.ItemCallback<ApprovalListItem>() {
            override fun areItemsTheSame(oldItem: ApprovalListItem, newItem: ApprovalListItem) =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: ApprovalListItem, newItem: ApprovalListItem) =
                oldItem == newItem
        }
    }
}

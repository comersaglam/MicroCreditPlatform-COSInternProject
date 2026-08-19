package com.example.app_mobile.ui.debts

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.app_mobile.R
import com.example.app_pos.model.SellerDebt
import com.example.app_mobile.databinding.ItemSellerDebtBinding
import com.example.app_mobile.util.toTlString

/**
 * Renders the buyer's debt list (one row per shop). ListAdapter diffs on a
 * background thread and animates only changed rows — the app-pos CustomerAdapter
 * pattern.
 */
class SellerDebtAdapter(
    private val onClick: (SellerDebt) -> Unit
) : ListAdapter<SellerDebt, SellerDebtAdapter.VH>(DIFF) {

    class VH(
        private val binding: ItemSellerDebtBinding,
        private val onClick: (SellerDebt) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(debt: SellerDebt) = with(binding) {
            shopName.text = debt.shopName
            balance.text = debt.balanceMinor.toTlString()
            // Red while still owed, green once the shop owes money back. Settled is
            // NEITHER: zero used to take the "payment received" green, which announced an
            // event that had not happened — the row simply has nothing outstanding.
            val colorRes = when {
                debt.balanceMinor > 0 -> R.color.balance_due
                debt.balanceMinor == 0L -> R.color.balance_settled
                else -> R.color.payment_received
            }
            balance.setTextColor(root.context.getColor(colorRes))
            root.setOnClickListener { onClick(debt) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemSellerDebtBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding, onClick)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<SellerDebt>() {
            override fun areItemsTheSame(oldItem: SellerDebt, newItem: SellerDebt) =
                oldItem.sellerId == newItem.sellerId

            override fun areContentsTheSame(oldItem: SellerDebt, newItem: SellerDebt) =
                oldItem == newItem
        }
    }
}

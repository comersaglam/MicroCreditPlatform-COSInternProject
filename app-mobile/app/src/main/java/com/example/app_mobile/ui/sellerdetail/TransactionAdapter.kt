package com.example.app_mobile.ui.sellerdetail

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.app_mobile.R
import com.example.app_mobile.databinding.ItemTransactionBinding
import com.example.app_pos.model.Transaction
import com.example.app_pos.model.TransactionType
import com.example.app_mobile.util.toDisplayDateTime
import com.example.app_mobile.util.toTlString

/** Renders the buyer's ledger entries with one seller, newest first. Copied from
 *  app-pos's TransactionAdapter (same append-only display rules). */
class TransactionAdapter :
    ListAdapter<Transaction, TransactionAdapter.VH>(DIFF) {

    class VH(private val binding: ItemTransactionBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(transaction: Transaction) = with(binding) {
            txDescription.text = transaction.description
            // Stored as ISO-8601 UTC; shown in the device's own time zone.
            txDate.text = transaction.createdAt.toDisplayDateTime()

            // Stored amount is always positive; the sign comes from the entry type.
            val isDebt = transaction.type == TransactionType.DEBT
            val prefix = if (isDebt) "+" else "-"
            txAmount.text = prefix + transaction.amountMinor.toTlString()

            val colorRes = if (isDebt) R.color.balance_due else R.color.payment_received
            txAmount.setTextColor(root.context.getColor(colorRes))
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemTransactionBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<Transaction>() {
            override fun areItemsTheSame(oldItem: Transaction, newItem: Transaction) =
                oldItem.transactionId == newItem.transactionId

            override fun areContentsTheSame(oldItem: Transaction, newItem: Transaction) =
                oldItem == newItem
        }
    }
}

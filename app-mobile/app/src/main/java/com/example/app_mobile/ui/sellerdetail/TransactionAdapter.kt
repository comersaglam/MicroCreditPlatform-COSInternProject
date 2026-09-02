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

/**
 * Renders one account's ledger entries, newest first. Copied from app-pos's
 * TransactionAdapter (same append-only display rules).
 *
 * ⚠️ USED BY TWO SCREENS: SellerDetailFragment (what this user owes a shop) and
 * CustomerDetailFragment (what a customer owes this user). It lives under sellerdetail for
 * historical reasons only — a change here lands on both, so both have to be considered.
 *
 * [onClick] opens what the entry was made of. It has no default value on purpose: a
 * default would let one of those two screens keep compiling with dead rows, and a tap that
 * does nothing is indistinguishable from a screen that has not been wired yet. EVERY row
 * is tappable, including the ones with no basket — whether an entry has items is not
 * visible from the row, so a list where some taps did nothing would read as broken.
 */
class TransactionAdapter(
    private val onClick: (Transaction) -> Unit
) : ListAdapter<Transaction, TransactionAdapter.VH>(DIFF) {

    class VH(private val binding: ItemTransactionBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(transaction: Transaction, onClick: (Transaction) -> Unit) = with(binding) {
            root.setOnClickListener { onClick(transaction) }

            txDescription.text = transaction.description
            // Stored as ISO-8601 UTC; shown in the device's own time zone.
            txDate.text = transaction.createdAt.toDisplayDateTime()

            // Stored amount is always positive; the sign comes from the entry type.
            //
            // A `when` over all three types rather than the boolean this used to be. The
            // boolean read "DEBT or not", which quietly made an INDEXATION row look like a
            // payment -- minus sign, green -- while the balance above it went up. Nothing
            // would have failed to compile.
            val prefix = when (transaction.type) {
                TransactionType.PAYMENT -> "-"
                else -> "+"
            }
            txAmount.text = prefix + transaction.amountMinor.toTlString()

            val colorRes = when (transaction.type) {
                TransactionType.DEBT -> R.color.balance_due
                // Same direction as a debt, muted: nothing was bought and no money moved.
                TransactionType.INDEXATION -> R.color.balance_indexation
                TransactionType.PAYMENT -> R.color.payment_received
            }
            txAmount.setTextColor(root.context.getColor(colorRes))
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemTransactionBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) =
        holder.bind(getItem(position), onClick)

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<Transaction>() {
            override fun areItemsTheSame(oldItem: Transaction, newItem: Transaction) =
                oldItem.transactionId == newItem.transactionId

            override fun areContentsTheSame(oldItem: Transaction, newItem: Transaction) =
                oldItem == newItem
        }
    }
}

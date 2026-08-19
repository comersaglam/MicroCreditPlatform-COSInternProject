package com.example.app_mobile.ui.customers

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.app_mobile.R
import com.example.app_mobile.databinding.ItemCustomerBinding
import com.example.app_pos.model.ClaimStatus
import com.example.app_pos.model.Customer
import com.example.app_mobile.util.titleFor
import com.example.app_mobile.util.toTlString

/** Renders the seller's customer list. Copied from app-pos's CustomerAdapter. */
class CustomerAdapter(
    private val onClick: (Customer) -> Unit
) : ListAdapter<Customer, CustomerAdapter.VH>(DIFF) {

    class VH(
        private val binding: ItemCustomerBinding,
        private val onClick: (Customer) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(customer: Customer) = with(binding) {
            val context = root.context
            // A nameless customer is titled by their PHONE, not left blank — a blank row
            // reads as missing data when only one field is absent. See titleFor.
            customerName.text = customer.titleFor(context)
            customerBalance.text = customer.balanceMinor.toTlString()

            val statusRes = when (customer.claimStatus) {
                ClaimStatus.CLAIMED -> R.string.status_claimed
                ClaimStatus.UNCLAIMED -> R.string.status_unclaimed
            }
            val status = context.getString(statusRes)
            // The secondary line carries whichever identifying detail the title did NOT.
            // Repeating the phone under a phone-titled row would say nothing; saying the
            // name was never entered tells the shopkeeper what to fix.
            val detail = if (customer.displayName.isBlank()) {
                context.getString(R.string.customer_name_missing)
            } else {
                customer.phone?.takeIf { it.isNotBlank() }
            }
            customerStatus.text = detail?.let { "$status · $it" } ?: status

            val colorRes = if (customer.balanceMinor > 0) R.color.balance_due else R.color.payment_received
            customerBalance.setTextColor(root.context.getColor(colorRes))

            root.setOnClickListener { onClick(customer) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemCustomerBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding, onClick)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<Customer>() {
            override fun areItemsTheSame(oldItem: Customer, newItem: Customer) =
                oldItem.customerId == newItem.customerId
            override fun areContentsTheSame(oldItem: Customer, newItem: Customer) =
                oldItem == newItem
        }
    }
}

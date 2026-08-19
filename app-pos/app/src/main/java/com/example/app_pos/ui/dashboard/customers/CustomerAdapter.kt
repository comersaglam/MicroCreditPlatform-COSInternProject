package com.example.app_pos.ui.dashboard.customers

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.app_pos.R
import com.example.app_pos.databinding.ItemCustomerBinding
import com.example.app_pos.model.ClaimStatus
import com.example.app_pos.model.Customer
import com.example.app_pos.util.titleFor
import com.example.app_pos.util.toTlString

/**
 * Renders the customer list.
 *
 * ListAdapter (rather than plain RecyclerView.Adapter) diffs the old and new
 * lists on a background thread and animates only the rows that actually
 * changed — no manual notifyDataSetChanged() calls.
 */
class CustomerAdapter(
    private val onClick: (Customer) -> Unit
) : ListAdapter<Customer, CustomerAdapter.CustomerViewHolder>(DIFF) {

    /**
     * Holds the views of one row so they are looked up once, not on every bind.
     * This is what makes recycling cheap.
     */
    class CustomerViewHolder(
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
            // Phone alongside the status so two same-named customers are distinct — unless
            // the phone is already the title, in which case repeating it says nothing and
            // the missing name is the useful thing to report.
            val status = context.getString(statusRes)
            val detail = if (customer.displayName.isBlank()) {
                context.getString(R.string.customer_name_missing)
            } else {
                customer.phone?.takeIf { it.isNotBlank() }
            }
            customerStatus.text = detail?.let { "$status · $it" } ?: status

            // Red for an outstanding debt (>0 = still owed), green once it is
            // settled or overpaid (<=0), so a paid-off customer reads as "all good".
            val colorRes = if (customer.balanceMinor > 0) R.color.balance_due else R.color.payment_received
            customerBalance.setTextColor(root.context.getColor(colorRes))

            root.setOnClickListener { onClick(customer) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CustomerViewHolder {
        val binding = ItemCustomerBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return CustomerViewHolder(binding, onClick)
    }

    override fun onBindViewHolder(holder: CustomerViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<Customer>() {
            // Same row (identity)?
            override fun areItemsTheSame(oldItem: Customer, newItem: Customer) =
                oldItem.customerId == newItem.customerId

            // Same content? data class equals() compares every field for us.
            override fun areContentsTheSame(oldItem: Customer, newItem: Customer) =
                oldItem == newItem
        }
    }
}

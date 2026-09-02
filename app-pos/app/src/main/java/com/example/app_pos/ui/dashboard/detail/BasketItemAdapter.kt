package com.example.app_pos.ui.dashboard.detail

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.app_pos.R
import com.example.app_pos.databinding.ItemBasketRowBinding
import com.example.app_pos.model.OrderItem
import com.example.app_pos.util.toTlString

/**
 * The lines of one basket: what was bought, how much of it, and what that came to.
 *
 * All three scaled figures are asked of the domain rather than divided here
 * (quantityDisplay / taxPercentDisplay / lineTotalMinor). The two ×1000 fields are the
 * kind of mistake that renders a plausible wrong number, so the conversions live where a
 * unit test can reach them — see OrderBodyDisplayTest.
 */
class BasketItemAdapter : ListAdapter<OrderItem, BasketItemAdapter.VH>(DIFF) {

    class VH(private val binding: ItemBasketRowBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: OrderItem) = with(binding) {
            itemName.text = item.name

            // The arithmetic spelled out, so the figure on the right can be checked rather
            // than taken on trust.
            //
            // Two strings rather than one with an empty tail: a zero-rated line — which is
            // every money-only handoff — would otherwise trail a separator with nothing
            // after it, and "KDV %0" reads as a claim where the truth is that no rate came.
            itemQuantity.text = if (item.taxPercent == 0L) {
                root.context.getString(
                    R.string.basket_line_pricing,
                    item.quantityDisplay(),
                    item.price.toTlString()
                )
            } else {
                root.context.getString(
                    R.string.basket_line_pricing_tax,
                    item.quantityDisplay(),
                    item.price.toTlString(),
                    item.taxPercentDisplay()
                )
            }

            // Tax is shown on the line above and deliberately not added in: the ledger
            // holds price × quantity, so a line total including tax would not sum to the
            // entry this basket belongs to.
            itemLineTotal.text = item.lineTotalMinor().toTlString()
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemBasketRowBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    private companion object {
        /**
         * Basket lines carry no id of their own, so identity is the whole line.
         *
         * That is sound here in a way it would not be for the ledger: a basket is read once
         * and never updated, so this list is submitted a single time and DiffUtil has
         * nothing to reconcile. Two identical lines (the same item rung up twice) compare
         * equal, and it does not matter which is which.
         */
        val DIFF = object : DiffUtil.ItemCallback<OrderItem>() {
            override fun areItemsTheSame(oldItem: OrderItem, newItem: OrderItem) =
                oldItem == newItem

            override fun areContentsTheSame(oldItem: OrderItem, newItem: OrderItem) =
                oldItem == newItem
        }
    }
}

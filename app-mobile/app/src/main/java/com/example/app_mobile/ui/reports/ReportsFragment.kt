package com.example.app_mobile.ui.reports

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.app_mobile.R
import com.example.app_mobile.databinding.FragmentReportsBinding
import com.example.app_mobile.databinding.ItemInsightRowBinding
import com.example.app_mobile.util.toTlString
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * "Detaylı Bilgiler" — six cards making one argument, from either side of the counter.
 *
 * ⚠️ EVERY FIGURE IS MOCK. It comes from [InsightsMockData], not from the ledger, and
 * deferred.md §L.17 records why and what a real version would need. The screen even says so
 * at the bottom, because somebody presenting it should not have to remember which numbers
 * are live.
 *
 * The ViewModel exists for exactly one thing: whether this account is also a seller, which
 * decides if the role chooser appears at all. Everything else on the screen is a constant,
 * so there is nothing else to load and nothing that can fail. Which role is *selected* is
 * held by the ChipGroup, which already survives rotation on its own.
 */
@AndroidEntryPoint
class ReportsFragment : Fragment() {

    private var _binding: FragmentReportsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ReportsViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentReportsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Read from the group rather than assumed: on rotation the ChipGroup restores its
        // own checked state and fires this listener, so seeding from a constant would let
        // the screen and the chips disagree about which role is showing.
        binding.roleGroup.setOnCheckedStateChangeListener { group, _ ->
            render(group.checkedChipId == R.id.chipRoleSeller)
        }

        observeRole()
        render(binding.roleGroup.checkedChipId == R.id.chipRoleSeller)
    }

    /**
     * The chooser appears only for an account that is also a seller.
     *
     * A pure buyer has one reading of this screen and no choice to make; offering a toggle
     * whose other half is empty would be offering a door to nowhere.
     */
    private fun observeRole() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.isSeller.collect { seller ->
                    binding.roleGroup.visibility = if (seller) View.VISIBLE else View.GONE
                }
            }
        }
    }

    private fun render(seller: Boolean) {
        val data = if (seller) InsightsMockData.seller else InsightsMockData.buyer

        renderRate(data, seller)
        renderTrend(data, seller)
        renderCompare(data, seller)
        renderInflation(data, seller)
        renderCategories(data, seller)
        renderRanked(data, seller)
    }

    private fun renderRate(data: RoleInsights, seller: Boolean) = with(binding) {
        rateLabel.setText(
            if (seller) R.string.insights_rate_label_seller else R.string.insights_rate_label_buyer
        )
        rateValue.text = getString(R.string.insights_percent, data.collectionPercent)
        rateDetail.text = getString(
            if (seller) R.string.insights_rate_detail_seller else R.string.insights_rate_detail_buyer,
            data.collectedMinor.toTlString(),
            data.billedMinor.toTlString()
        )

        // The split bar, as two weights. Set from code so the pair always sums to 100 —
        // hardcoding both halves in XML is how they drift apart.
        val paid = data.collectionPercent.coerceIn(0, 100)
        rateBarPaid.setWeight(paid.toFloat())
        rateBarRest.setWeight((100 - paid).toFloat())
    }

    private fun renderTrend(data: RoleInsights, seller: Boolean) = with(binding) {
        trendLabel.setText(
            if (seller) R.string.insights_trend_label_seller else R.string.insights_trend_label_buyer
        )
        data.busiestMonth?.let { (month, amount) ->
            trendBusiest.text = getString(R.string.insights_busiest, month, amount.toTlString())
        }
        trendChart.showMonthly(data.monthlyDebt, InsightsMockData.MONTHS)
    }

    private fun renderCompare(data: RoleInsights, seller: Boolean) = with(binding) {
        compareLabel.setText(
            if (seller) R.string.insights_compare_label_seller else R.string.insights_compare_label_buyer
        )
        legendDebt.setText(
            if (seller) R.string.insights_legend_debt_seller else R.string.insights_legend_debt_buyer
        )
        legendPayment.setText(
            if (seller) R.string.insights_legend_payment_seller else R.string.insights_legend_payment_buyer
        )
        compareChart.showPaired(data.monthlyDebt, data.monthlyPayment, InsightsMockData.MONTHS)
    }

    private fun renderInflation(data: RoleInsights, seller: Boolean) = with(binding) {
        inflationLabel.setText(
            if (seller) R.string.insights_inflation_label_seller
            else R.string.insights_inflation_label_buyer
        )
        inflationValue.text = data.inflationMinor.toTlString()
        inflationDetail.setText(
            if (seller) R.string.insights_inflation_detail_seller
            else R.string.insights_inflation_detail_buyer
        )
    }

    private fun renderCategories(data: RoleInsights, seller: Boolean) = with(binding) {
        categoryLabel.setText(
            if (seller) R.string.insights_category_label_seller
            else R.string.insights_category_label_buyer
        )
        fillRows(categoryRows, data.categories, R.color.primary)
    }

    private fun renderRanked(data: RoleInsights, seller: Boolean) = with(binding) {
        rankedLabel.setText(
            if (seller) R.string.insights_ranked_label_seller else R.string.insights_ranked_label_buyer
        )
        fillRows(rankedRows, data.ranked, R.color.balance_due)
    }

    /**
     * Rebuilds a ranked list: label, a bar as long as its share of the largest, amount.
     *
     * Rows are removed and re-added rather than recycled — six of them, twice per screen,
     * and a RecyclerView for a list that never scrolls would be more machinery than the
     * thing it manages.
     */
    private fun fillRows(
        container: ViewGroup,
        rows: List<Pair<String, Long>>,
        colorRes: Int
    ) {
        container.removeAllViews()
        // Guarded, so an empty list cannot divide by zero. Nothing produces one today —
        // the data is a constant — but the next version of this screen reads real rows,
        // and a fresh account has none.
        val max = rows.maxOfOrNull { it.second }?.takeIf { it > 0L } ?: return

        rows.forEach { (label, amount) ->
            val row = ItemInsightRowBinding.inflate(layoutInflater, container, false)
            row.rowLabel.text = label
            row.rowAmount.text = amount.toTlString()
            row.rowBar.setBackgroundResource(colorRes)

            val share = (amount.toFloat() / max).coerceIn(0f, 1f)
            row.rowBar.setWeight(share)
            row.rowBarRest.setWeight(1f - share)

            container.addView(row.root)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

/** Sets a LinearLayout weight in place, for the bars drawn out of plain Views. */
private fun View.setWeight(weight: Float) {
    val params = layoutParams as android.widget.LinearLayout.LayoutParams
    params.weight = weight
    layoutParams = params
}

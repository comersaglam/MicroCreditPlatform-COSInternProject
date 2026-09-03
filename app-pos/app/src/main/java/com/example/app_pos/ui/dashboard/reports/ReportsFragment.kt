package com.example.app_pos.ui.dashboard.reports

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.example.app_pos.R
import com.example.app_pos.databinding.FragmentReportsBinding
import com.example.app_pos.databinding.ItemInsightRowBinding
import com.example.app_pos.util.toTlString

/**
 * "Detaylı Bilgiler" — the shopkeeper's six cards.
 *
 * ⚠️ EVERY FIGURE IS MOCK. It comes from [InsightsMockData], not from the ledger, and
 * deferred.md §L.17 records why and what a real version would need. The screen says so at
 * the bottom too, because whoever presents it should not have to remember which numbers are
 * live.
 *
 * app-mobile's copy of this screen carries a buyer/seller chooser; this one does not. The
 * till is single-role by construction, so there is no second reading to switch to — and no
 * ViewModel either, because with no role to resolve there is nothing left to ask.
 */
class ReportsFragment : Fragment() {

    private var _binding: FragmentReportsBinding? = null
    private val binding get() = _binding!!

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
        render()
    }

    private fun render() = with(binding) {
        val data = InsightsMockData.seller

        rateLabel.setText(R.string.insights_rate_label_seller)
        rateValue.text = getString(R.string.insights_percent, data.collectionPercent)
        rateDetail.text = getString(
            R.string.insights_rate_detail_seller,
            data.collectedMinor.toTlString(),
            data.billedMinor.toTlString()
        )
        // Set from code so the two halves always sum to 100 — hardcoding both in XML is how
        // they drift apart.
        val paid = data.collectionPercent.coerceIn(0, 100)
        rateBarPaid.setWeight(paid.toFloat())
        rateBarRest.setWeight((100 - paid).toFloat())

        trendLabel.setText(R.string.insights_trend_label_seller)
        data.busiestMonth?.let { (month, amount) ->
            trendBusiest.text = getString(R.string.insights_busiest, month, amount.toTlString())
        }
        trendChart.showMonthly(data.monthlyDebt, InsightsMockData.MONTHS)

        compareLabel.setText(R.string.insights_compare_label_seller)
        legendDebt.setText(R.string.insights_legend_debt_seller)
        legendPayment.setText(R.string.insights_legend_payment_seller)
        compareChart.showPaired(data.monthlyDebt, data.monthlyPayment, InsightsMockData.MONTHS)

        inflationLabel.setText(R.string.insights_inflation_label_seller)
        inflationValue.text = data.inflationMinor.toTlString()
        inflationDetail.setText(R.string.insights_inflation_detail_seller)

        categoryLabel.setText(R.string.insights_category_label_seller)
        fillRows(categoryRows, data.categories, R.color.primary)

        rankedLabel.setText(R.string.insights_ranked_label_seller)
        fillRows(rankedRows, data.ranked, R.color.balance_due)
    }

    /**
     * Rebuilds a ranked list: label, a bar as long as its share of the largest, amount.
     *
     * Plain weighted Views rather than a chart — for a sorted list of six the proportion is
     * the whole message, and there is nothing to draw or invalidate.
     */
    private fun fillRows(
        container: ViewGroup,
        rows: List<Pair<String, Long>>,
        colorRes: Int
    ) {
        container.removeAllViews()
        // Guarded so an empty list cannot divide by zero. Nothing produces one today — the
        // data is a constant — but the version of this screen that reads real rows will,
        // on a book with no entries yet.
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

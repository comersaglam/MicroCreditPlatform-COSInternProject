package com.example.app_mobile.ui.reports

import android.content.Context
import androidx.core.content.ContextCompat
import com.example.app_mobile.R
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.charts.Chart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter

/**
 * MPAndroidChart, dressed for this theme.
 *
 * The library's defaults are built for a light background: axis labels, the legend and the
 * grid all paint near-black, which on #0B0D12 is invisible. Every chart therefore needs the
 * same dozen lines of configuration, and they live here rather than in nine card bindings —
 * one place to change when the theme does, and no chance of two charts disagreeing about
 * what a gridline looks like.
 *
 * What is switched off is as deliberate as what is coloured. The library draws a
 * "Description" watermark, a legend, a y-axis, gridlines and per-bar value labels by
 * default; on a card the size of a phone's width that is five kinds of ink competing with
 * the bars. What survives is the bars and the month names.
 */
private fun Chart<*>.applyFidesBase(context: Context) {
    // The library's own credit line, bottom-right, in English. Not on a Turkish card.
    description.isEnabled = false
    legend.isEnabled = false
    setNoDataText("")
    // Charts sit inside a scrolling column; letting them take the gesture makes the page
    // feel stuck wherever a finger happens to land.
    setTouchEnabled(false)
}

/**
 * A month-by-month bar chart with the labels underneath.
 *
 * [values] are kuruş and [labels] are what goes under each bar; they must be the same
 * length, and the caller is the one that knows that — mock data ships them as a pair.
 */
fun BarChart.showMonthly(
    values: List<Long>,
    labels: List<String>,
    colorRes: Int = R.color.debt_grad_top
) {
    applyFidesBase(context)

    val entries = values.mapIndexed { index, value ->
        // Kuruş to lira on the way in: a chart axis in kuruş would read in the millions and
        // the numbers are the one thing here that has to be legible at a glance.
        BarEntry(index.toFloat(), value / 100f)
    }

    val set = BarDataSet(entries, "").apply {
        color = ContextCompat.getColor(context, colorRes)
        // Per-bar numbers on twelve bars at this width overlap into a smear.
        setDrawValues(false)
        highLightAlpha = 0
    }

    data = BarData(set).apply { barWidth = 0.6f }

    xAxis.apply {
        position = XAxis.XAxisPosition.BOTTOM
        setDrawGridLines(false)
        setDrawAxisLine(false)
        textColor = ContextCompat.getColor(context, R.color.on_surface_variant)
        textSize = 9f
        granularity = 1f
        valueFormatter = IndexAxisValueFormatter(labels)
    }

    // Both y-axes off. The bars are compared against each other, not read off a scale, and
    // the card states the total in words above them.
    axisLeft.isEnabled = false
    axisRight.isEnabled = false

    setExtraOffsets(0f, 4f, 0f, 0f)
    invalidate()
}

/**
 * Two series side by side, month for month — booked against collected.
 *
 * The chart that carries the argument: where the red bar outruns the green one, the shop is
 * lending faster than it is being paid back. A single series cannot say that.
 *
 * ⚠️ The group geometry has to add up exactly. barWidth * 2 + barSpace * 2 + groupSpace must
 * equal 1, or the bars drift out of their month's slot and stop lining up with the label
 * underneath — a chart that quietly attributes June's figure to July.
 */
fun BarChart.showPaired(
    first: List<Long>,
    second: List<Long>,
    labels: List<String>
) {
    applyFidesBase(context)

    fun set(values: List<Long>, colorRes: Int) = BarDataSet(
        values.mapIndexed { index, value -> BarEntry(index.toFloat(), value / 100f) },
        ""
    ).apply {
        color = ContextCompat.getColor(context, colorRes)
        setDrawValues(false)
        highLightAlpha = 0
    }

    val barWidth = 0.38f
    val barSpace = 0.02f
    val groupSpace = 1f - (barWidth + barSpace) * 2

    data = BarData(
        set(first, R.color.balance_due),
        set(second, R.color.payment_received)
    ).apply { this.barWidth = barWidth }

    xAxis.apply {
        position = XAxis.XAxisPosition.BOTTOM
        setDrawGridLines(false)
        setDrawAxisLine(false)
        textColor = ContextCompat.getColor(context, R.color.on_surface_variant)
        textSize = 9f
        granularity = 1f
        setCenterAxisLabels(true)
        valueFormatter = IndexAxisValueFormatter(labels)
        axisMinimum = 0f
        axisMaximum = labels.size.toFloat()
    }

    axisLeft.isEnabled = false
    axisRight.isEnabled = false

    // Must come after the axis bounds above: grouping reads them.
    groupBars(0f, groupSpace, barSpace)
    setExtraOffsets(0f, 4f, 0f, 0f)
    invalidate()
}

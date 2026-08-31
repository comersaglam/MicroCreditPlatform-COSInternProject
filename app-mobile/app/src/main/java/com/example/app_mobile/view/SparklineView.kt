package com.example.app_mobile.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.example.app_mobile.R

/**
 * The shape of a balance over time, with no axes and no labels.
 *
 * It answers one question -- is this going up or down -- and leaves the exact figures to
 * the number above it. Anything more would make it a chart, and a chart on a detail
 * screen asks to be studied rather than glanced at.
 *
 * NO CHARTING LIBRARY. MPAndroidChart would bring roughly 800 KB and a great deal of
 * configuration for what is, here, two Paths.
 *
 * The data comes from entries the screen already has: a monthly balance series is derived
 * from the ledger rows in memory, the same way the balance itself is. No new endpoint.
 */
class SparklineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    /** Oldest to newest. Fewer than two points draws nothing -- a dot is not a trend. */
    var values: List<Long> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    // Built once. onDraw can run 60 times a second, and allocating there feeds the
    // garbage collector until scrolling stutters.
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = ContextCompat.getColor(context, R.color.spark_line)
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.spark_line)
    }

    private val linePath = Path()
    private val fillPath = Path()
    private val density = resources.displayMetrics.density

    init {
        linePaint.strokeWidth = 1.5f * density
    }

    /**
     * The fill gradient is bound to the view's height, so it is built once that is known.
     * Building it in init would size it to zero and the fill would never appear.
     */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fillPaint.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            ContextCompat.getColor(context, R.color.spark_fill_top),
            ContextCompat.getColor(context, R.color.spark_fill_bottom),
            Shader.TileMode.CLAMP,
        )
    }

    override fun onDraw(canvas: Canvas) {
        if (values.size < 2) return

        val w = width.toFloat()
        val h = height.toFloat()
        // Keep the stroke off the edges; half of it would be clipped otherwise.
        val pad = linePaint.strokeWidth

        val min = values.min()
        val max = values.max()
        // A flat series would divide by zero. Drawn down the middle instead, which is
        // what "no change" looks like.
        val span = (max - min).takeIf { it != 0L }

        val stepX = w / (values.size - 1)

        linePath.reset()
        fillPath.reset()

        var lastX = 0f
        var lastY = 0f

        values.forEachIndexed { index, value ->
            val x = index * stepX
            val y = if (span == null) {
                h / 2f
            } else {
                // Screen Y grows downward, so the fraction is inverted to put the larger
                // value higher up.
                pad + (1f - (value - min).toFloat() / span) * (h - 2 * pad)
            }

            if (index == 0) {
                linePath.moveTo(x, y)
                fillPath.moveTo(x, h)
                fillPath.lineTo(x, y)
            } else {
                linePath.lineTo(x, y)
                fillPath.lineTo(x, y)
            }

            lastX = x
            lastY = y
        }

        // Close the fill down to the baseline so it becomes a shape rather than a line.
        fillPath.lineTo(w, h)
        fillPath.close()

        canvas.drawPath(fillPath, fillPaint)
        canvas.drawPath(linePath, linePaint)
        // The latest point, marked: this is where the series stands now.
        canvas.drawCircle(lastX, lastY, 2.5f * density, dotPaint)
    }
}

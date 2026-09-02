package com.example.app_pos.view

import android.content.Context
import android.graphics.LinearGradient
import android.graphics.Shader
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import com.example.app_pos.R

/**
 * Text painted with the brand gradient, top to bottom.
 *
 * The same treatment the focus card's stripe gets, applied to the wordmark so the two read
 * as one identity rather than as a logo next to a label.
 *
 * The gradient runs across the TEXT's height, not the view's: including padding would start
 * it above the glyphs and leave the cap line washed out.
 */
class GradientText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = android.R.attr.textViewStyle,
) : AppCompatTextView(context, attrs, defStyle) {

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val metrics = paint.fontMetrics
        paint.shader = LinearGradient(
            0f, 0f, 0f, metrics.bottom - metrics.top,
            ContextCompat.getColor(context, R.color.brand_text_top),
            ContextCompat.getColor(context, R.color.brand_text_bottom),
            Shader.TileMode.CLAMP,
        )
    }
}

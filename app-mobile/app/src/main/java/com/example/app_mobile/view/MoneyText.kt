package com.example.app_mobile.view

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.LinearGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.animation.DecelerateInterpolator
import androidx.appcompat.widget.AppCompatTextView

/**
 * The one large figure on a screen: it counts up on arrival, then takes a gradient.
 *
 * WHY BOTH EFFECTS LIVE IN ONE CLASS
 * Separately each is a few lines. Together they fight: the gradient is a shader sized to
 * the text, and while the number is counting its width changes every frame. Rebuilding
 * the shader per frame is both wasteful and a source of flicker.
 *
 * So they are SEQUENCED rather than combined. Flat colour while counting, gradient once
 * it lands. Nobody reads the colour of a moving number -- they watch it move -- and the
 * gradient appears exactly when the figure is still and meant to be read.
 *
 * WHEN IT COUNTS
 * Only the FIRST time a value arrives. Later updates are written straight in. A balance
 * that re-counts after a payment makes the screen look like it is still working out the
 * answer, at the very moment the answer needs to look settled.
 *
 * WHERE IT IS USED
 * Only for a large figure standing on its own -- a balance, a total. Never in a list row:
 * a RecyclerView rebinds on every recycle, so each scroll would restart the animation and
 * the whole list would twitch.
 */
class MoneyText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = android.R.attr.textViewStyle,
) : AppCompatTextView(context, attrs, defStyle) {

    /**
     * How a raw kuruş amount becomes text. Supplied by the caller because the formatting
     * rules already live in util/MoneyFormat and must not be duplicated here.
     */
    var format: (Long) -> CharSequence = { it.toString() }

    private var gradientTop = currentTextColor
    private var gradientBottom = currentTextColor

    private var animator: ValueAnimator? = null
    private var hasShownValue = false

    /**
     * The two colours the figure fades between.
     *
     * Set separately from the amount because the DIRECTION picks them: debt reads red,
     * credit green, a settled account neutral. That is the pocket test from Tur 25b, and
     * it belongs to the screen, not to this view.
     */
    fun setGradientColors(topColor: Int, bottomColor: Int) {
        gradientTop = topColor
        gradientBottom = bottomColor
        if (animator?.isRunning != true) applyGradient()
    }

    /** Show an amount. Counts on the first call, writes directly on the rest. */
    fun setAmount(amountMinor: Long) {
        animator?.cancel()

        if (hasShownValue) {
            text = format(amountMinor)
            applyGradient()
            return
        }
        hasShownValue = true

        // Flat colour while the width is still moving; the shader would have to be
        // rebuilt on every frame otherwise.
        paint.shader = null
        setTextColor(gradientTop)

        animator = ValueAnimator.ofInt(0, amountMinor.toInt()).apply {
            duration = COUNT_MS
            // Fast, then easing to a stop -- the way a counter settles. Linear would
            // read as mechanical.
            interpolator = DecelerateInterpolator()
            addUpdateListener { text = format((it.animatedValue as Int).toLong()) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    // Written again from the source value: the animator works in Int and
                    // the last frame can land a kuruş short of the real figure.
                    text = format(amountMinor)
                    applyGradient()
                }
            })
            start()
        }
    }

    /**
     * The shader spans the text's own height, not the view's: including padding would
     * start the gradient above the glyphs and leave the top of the number washed out.
     */
    private fun applyGradient() {
        if (height == 0) {
            // Nothing has been measured yet; try again once it has.
            post { applyGradient() }
            return
        }

        val metrics = paint.fontMetrics
        paint.shader = LinearGradient(
            0f, 0f, 0f, metrics.bottom - metrics.top,
            gradientTop, gradientBottom,
            Shader.TileMode.CLAMP,
        )
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Height changes with the system font scale or a rotation, and the shader is
        // bound to it.
        if (h != oldh && animator?.isRunning != true) applyGradient()
    }

    override fun onDetachedFromWindow() {
        // A running animator holds the view alive; leaving one behind on a closing
        // screen leaks it.
        animator?.cancel()
        super.onDetachedFromWindow()
    }

    private companion object {
        const val COUNT_MS = 600L
    }
}

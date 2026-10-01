package com.example.myapplication

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.ProgressBar
import kotlin.math.abs
import kotlin.math.min

// Lightweight pull-to-refresh container: dragging down while the content is scrolled
// to the top reveals a spinner; releasing past the threshold triggers onRefresh.
class PullRefreshLayout(context: Context) : FrameLayout(context) {
    private val density = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val indicatorSize = (40 * density).toInt()
    private val triggerDistance = 80 * density
    private val maxDistance = 130 * density
    private val restingOffset = 56 * density
    private var startY = 0f
    private var startX = 0f
    private var dragging = false
    private var onRefresh: (() -> Unit)? = null

    private val indicator = ProgressBar(context).apply {
        isIndeterminate = true
        val padding = (8 * density).toInt()
        setPadding(padding, padding, padding, padding)
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL }
        elevation = 6 * density
        alpha = 0f
        visibility = View.GONE
    }

    var isRefreshing: Boolean = false
        set(value) {
            field = value
            if (value) showIndicator(restingOffset, 1f) else hideIndicator()
        }

    init {
        addView(indicator, LayoutParams(indicatorSize, indicatorSize, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
    }

    fun setColors(spinner: Int, background: Int) {
        indicator.indeterminateTintList = ColorStateList.valueOf(spinner)
        (indicator.background as GradientDrawable).setColor(background)
    }

    fun setOnRefreshListener(listener: () -> Unit) {
        onRefresh = listener
    }

    override fun onViewAdded(child: View?) {
        super.onViewAdded(child)
        if (child !== indicator) indicator.bringToFront()
    }

    private fun content(): View? = (0 until childCount).map(::getChildAt).firstOrNull { it !== indicator }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || isRefreshing) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startY = event.y
                startX = event.x
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = event.y - startY
                val dx = event.x - startX
                if (dy > touchSlop && dy > abs(dx) * 1.5f && content()?.canScrollVertically(-1) != true) {
                    dragging = true
                    startY = event.y
                }
            }
        }
        return dragging
    }

    // Only consumes pull-down drags (never clicks), so there is no click to forward.
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!dragging) return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val distance = min((event.y - startY) * 0.6f, maxDistance).coerceAtLeast(0f)
                showIndicator(distance, min(distance / triggerDistance, 1f))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                val distance = min((event.y - startY) * 0.6f, maxDistance)
                if (event.actionMasked == MotionEvent.ACTION_UP && distance >= triggerDistance) {
                    isRefreshing = true
                    onRefresh?.invoke()
                } else {
                    hideIndicator()
                }
            }
        }
        return true
    }

    private fun showIndicator(offset: Float, progress: Float) {
        indicator.visibility = View.VISIBLE
        indicator.animate().cancel()
        indicator.translationY = offset - indicatorSize / 2f
        indicator.alpha = progress
        indicator.rotation = progress * 270f
    }

    private fun hideIndicator() {
        indicator.animate().alpha(0f).translationY(-indicatorSize.toFloat()).setDuration(180)
            .withEndAction { indicator.visibility = View.GONE }
            .start()
    }
}

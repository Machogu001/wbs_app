package com.example.myapplication

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import kotlin.random.Random

/**
 * Theme-aware page background: a soft vertical gradient scattered with translucent
 * water drops and ripples. Colours come from values/ and values-night/ so the drops
 * stay subtle enough for text to remain readable in both light and dark mode.
 */
class WaterDropsDrawable(context: Context) : Drawable() {
    private val density = context.resources.displayMetrics.density
    private val topColor = ContextCompat.getColor(context, R.color.page_background)
    private val bottomColor = ContextCompat.getColor(context, R.color.page_background_alt)
    private val backgroundPaint = Paint()
    private val dropPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.water_drop)
    }
    private val dropStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * density
        color = ContextCompat.getColor(context, R.color.water_drop_stroke)
    }
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.water_drop_highlight)
    }
    private val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = ContextCompat.getColor(context, R.color.water_drop_stroke)
    }
    private val dropPath = Path()
    private val oval = RectF()

    private data class Drop(val x: Float, val y: Float, val sizeDp: Float, val ripple: Boolean)

    // Fixed seed keeps the pattern identical between screens, so navigation doesn't flicker.
    private val drops: List<Drop> = Random(42).let { random ->
        List(22) {
            Drop(
                x = random.nextFloat(),
                y = random.nextFloat(),
                sizeDp = 10f + random.nextFloat() * 34f,
                ripple = random.nextInt(4) == 0
            )
        }
    }

    override fun onBoundsChange(bounds: android.graphics.Rect) {
        super.onBoundsChange(bounds)
        backgroundPaint.shader = LinearGradient(
            0f, bounds.top.toFloat(), 0f, bounds.bottom.toFloat(),
            topColor, bottomColor, Shader.TileMode.CLAMP
        )
    }

    override fun draw(canvas: Canvas) {
        val area = bounds
        canvas.drawRect(area, backgroundPaint)
        val width = area.width().toFloat()
        val height = area.height().toFloat()
        drops.forEach { drop ->
            val size = drop.sizeDp * density
            val cx = area.left + drop.x * width
            val cy = area.top + drop.y * height
            if (drop.ripple) {
                oval.set(cx - size * 1.3f, cy + size * 0.55f, cx + size * 1.3f, cy + size * 0.95f)
                canvas.drawOval(oval, ripplePaint)
                oval.set(cx - size * 0.85f, cy + size * 0.65f, cx + size * 0.85f, cy + size * 0.85f)
                canvas.drawOval(oval, ripplePaint)
            }
            drawDrop(canvas, cx, cy, size)
        }
    }

    // Teardrop: pointed tip at the top, round body below, with a small glossy highlight.
    private fun drawDrop(canvas: Canvas, cx: Float, cy: Float, size: Float) {
        val radius = size / 2f
        val tipY = cy - size * 0.95f
        dropPath.reset()
        dropPath.moveTo(cx, tipY)
        dropPath.cubicTo(cx + radius * 0.35f, tipY + size * 0.35f, cx + radius, cy - radius * 0.25f, cx + radius, cy + radius * 0.1f)
        dropPath.arcTo(cx - radius, cy - radius * 0.9f, cx + radius, cy + radius * 1.1f, 0f, 180f, false)
        dropPath.cubicTo(cx - radius, cy - radius * 0.25f, cx - radius * 0.35f, tipY + size * 0.35f, cx, tipY)
        dropPath.close()
        canvas.drawPath(dropPath, dropPaint)
        canvas.drawPath(dropPath, dropStrokePaint)
        oval.set(cx - radius * 0.55f, cy - radius * 0.35f, cx - radius * 0.2f, cy + radius * 0.2f)
        canvas.drawOval(oval, highlightPaint)
    }

    override fun setAlpha(alpha: Int) {
        backgroundPaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        dropPaint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}

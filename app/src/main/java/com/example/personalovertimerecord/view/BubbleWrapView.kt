package com.example.personalovertimerecord.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.example.personalovertimerecord.R
import kotlin.math.hypot
import kotlin.random.Random

/**
 * 泡泡纸
 *
 * 按屏宽铺一张泡泡网格，按下或滑动经过即"捏爆"：泡体消失、留下一圈纸痕，
 * 同时播放扩散涟漪与轻触感。全部捏完后回调 [onAllPopped]。
 * 绘制复用 Paint，无每帧对象分配。
 */
class BubbleWrapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private data class Bubble(
        val cx: Float,
        val cy: Float,
        val radius: Float,
        val color: Int,
        var popped: Boolean = false,
        var poppedAt: Long = 0L
    )

    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val popPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
    }

    private var palette = intArrayOf(
        ContextCompat.getColor(context, R.color.relax_phase_inhale),
        ContextCompat.getColor(context, R.color.relax_phase_hold),
        ContextCompat.getColor(context, R.color.relax_phase_exhale)
    )
    private val bubbles = ArrayList<Bubble>(128)
    private val random = Random(SystemClock.elapsedRealtime())
    private val popDurationMs = 420L

    private var poppedCount = 0

    /** 进度回调：已捏数量 / 泡泡总数 */
    var onProgressChanged: ((popped: Int, total: Int) -> Unit)? = null

    /** 全部捏完回调 */
    var onAllPopped: (() -> Unit)? = null

    /** 应用节日活动配色，并按新配色重新铺一张 */
    fun setPalette(colors: IntArray) {
        if (colors.size < 2) return
        palette = colors.copyOf()
        if (width > 0 && height > 0) {
            buildBubbles()
            invalidate()
        }
    }

    /** 重新铺一张泡泡纸 */
    fun newSheet() {
        buildBubbles()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        buildBubbles()
    }

    private fun buildBubbles() {
        bubbles.clear()
        poppedCount = 0
        if (width == 0 || height == 0) return

        val cell = dp(52f)
        val cols = (width / cell).toInt().coerceAtLeast(1)
        val rows = (height / cell).toInt().coerceAtLeast(1)
        val offsetX = (width - cols * cell) / 2f
        val offsetY = (height - rows * cell) / 2f
        val jitter = cell * 0.07f

        for (row in 0 until rows) {
            for (col in 0 until cols) {
                val cx = offsetX + cell * col + cell / 2f + (random.nextFloat() * 2f - 1f) * jitter
                val cy = offsetY + cell * row + cell / 2f + (random.nextFloat() * 2f - 1f) * jitter
                val radius = cell * 0.40f + random.nextFloat() * cell * 0.03f
                val color = palette[(row * cols + col) % palette.size]
                bubbles.add(Bubble(cx, cy, radius, color))
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> popAt(event.x, event.y)
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.historySize) {
                    popAt(event.getHistoricalX(i), event.getHistoricalY(i))
                }
                popAt(event.x, event.y)
            }
        }
        return true
    }

    private fun popAt(x: Float, y: Float) {
        var target: Bubble? = null
        var nearestDistance = Float.MAX_VALUE
        for (bubble in bubbles) {
            if (bubble.popped) continue
            val distance = hypot(x - bubble.cx, y - bubble.cy)
            if (distance <= bubble.radius * 1.15f && distance < nearestDistance) {
                target = bubble
                nearestDistance = distance
            }
        }
        val bubble = target ?: return

        bubble.popped = true
        bubble.poppedAt = SystemClock.elapsedRealtime()
        poppedCount++
        performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        onProgressChanged?.invoke(poppedCount, bubbles.size)
        if (poppedCount >= bubbles.size) onAllPopped?.invoke()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = SystemClock.elapsedRealtime()
        var needsFrame = false

        for (bubble in bubbles) {
            if (!bubble.popped) {
                bubblePaint.color = bubble.color
                bubblePaint.alpha = 240
                canvas.drawCircle(bubble.cx, bubble.cy, bubble.radius, bubblePaint)

                // 高光与内圈光泽，让泡体更饱满
                sheenPaint.color = Color.WHITE
                sheenPaint.alpha = 70
                canvas.drawCircle(
                    bubble.cx - bubble.radius * 0.28f,
                    bubble.cy - bubble.radius * 0.30f,
                    bubble.radius * 0.30f,
                    sheenPaint
                )
                sheenPaint.alpha = 24
                canvas.drawCircle(bubble.cx, bubble.cy, bubble.radius * 0.58f, sheenPaint)
                continue
            }

            val elapsed = now - bubble.poppedAt
            if (elapsed < popDurationMs) {
                val progress = elapsed.toFloat() / popDurationMs
                popPaint.color = bubble.color
                popPaint.alpha = ((1f - progress) * 190f).toInt()
                canvas.drawCircle(bubble.cx, bubble.cy, bubble.radius * (1f + progress * 0.7f), popPaint)
                needsFrame = true
            } else {
                // 捏爆后留下的纸面凹痕
                bubblePaint.color = bubble.color
                bubblePaint.alpha = 30
                canvas.drawCircle(bubble.cx, bubble.cy, bubble.radius * 0.86f, bubblePaint)
            }
        }

        if (needsFrame) postInvalidateOnAnimation()
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}

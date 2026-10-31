package com.example.personalovertimerecord.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.example.personalovertimerecord.R

/** 呼吸节奏阶段：吸气 / 屏息 / 呼气 */
enum class BreathPhase { INHALE, HOLD, EXHALE }

/**
 * 呼吸引导圆环
 *
 * 只负责绘制：调用方按阶段传入进度（0f~1f），本视图据此计算圆的大小与进度环，
 * 不做任何计时。绘制过程复用 Paint 与 RectF，避免每帧分配对象。
 */
class BreathCircleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(3f)
    }
    private val arcRect = RectF()

    /** 光晕由内到外三层，透明度依次降低 */
    private val haloAlpha = intArrayOf(34, 20, 10)
    private val haloScale = floatArrayOf(1.32f, 1.48f, 1.64f)

    private var phaseColors = intArrayOf(
        ContextCompat.getColor(context, R.color.relax_phase_inhale),
        ContextCompat.getColor(context, R.color.relax_phase_hold),
        ContextCompat.getColor(context, R.color.relax_phase_exhale)
    )

    private val minScale = 0.52f
    private var baseRadius = 0f
    private var ringRadius = 0f
    private var breathScale = minScale
    private var phaseProgress = 0f
    private var phase = BreathPhase.INHALE

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        baseRadius = minOf(w, h) / 2f * 0.38f
        ringRadius = baseRadius * 1.12f + dp(10f)
    }

    /** 应用节日活动配色（吸气 / 屏息 / 呼气） */
    fun setPhaseColors(colors: IntArray) {
        if (colors.size < 3) return
        phaseColors = intArrayOf(colors[0], colors[1], colors[2])
        invalidate()
    }

    /**
     * 渲染当前呼吸状态
     * @param phase 当前阶段
     * @param progress 当前阶段内进度，0f 表示开始，1f 表示结束
     */
    fun render(phase: BreathPhase, progress: Float) {
        this.phase = phase
        this.phaseProgress = progress.coerceIn(0f, 1f)
        breathScale = when (phase) {
            BreathPhase.INHALE -> minScale + (1f - minScale) * ease(this.phaseProgress)
            BreathPhase.HOLD -> 1f
            BreathPhase.EXHALE -> 1f - (1f - minScale) * ease(this.phaseProgress)
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (baseRadius <= 0f) return

        val cx = width / 2f
        val cy = height / 2f
        val color = phaseColors[phase.ordinal]
        val radius = baseRadius * breathScale

        // 光晕：营造呼吸扩散的柔和感
        circlePaint.color = color
        for (i in haloAlpha.indices) {
            circlePaint.alpha = haloAlpha[i]
            canvas.drawCircle(cx, cy, radius * haloScale[i], circlePaint)
        }

        // 主体圆
        circlePaint.alpha = 240
        canvas.drawCircle(cx, cy, radius, circlePaint)

        // 顶部高光
        circlePaint.color = Color.WHITE
        circlePaint.alpha = 26
        canvas.drawCircle(cx, cy - radius * 0.14f, radius * 0.62f, circlePaint)

        // 固定的阶段进度环
        circlePaint.color = color
        circlePaint.alpha = 50
        canvas.drawCircle(cx, cy, ringRadius, circlePaint)

        ringPaint.color = color
        arcRect.set(cx - ringRadius, cy - ringRadius, cx + ringRadius, cy + ringRadius)
        canvas.drawArc(arcRect, -90f, 360f * phaseProgress, false, ringPaint)
    }

    /** 平滑缓动，让圆的变化不生硬 */
    private fun ease(t: Float): Float = t * t * (3f - 2f * t)

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}

package com.example.personalovertimerecord.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.example.personalovertimerecord.R
import com.example.personalovertimerecord.data.OvertimeRecord
import com.example.personalovertimerecord.data.OvertimeSettings
import com.example.personalovertimerecord.data.SettingsManager
import com.example.personalovertimerecord.utils.OvertimeCalculator
import java.util.Calendar
import java.util.Locale

class CalendarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    
    interface OnDateClickListener {
        fun onDateClick(year: Int, month: Int, day: Int)
    }
    
    private var listener: OnDateClickListener? = null
    private var currentYear: Int = 0
    private var currentMonth: Int = 0
    
    private var settingsManager: SettingsManager? = null
    // 缓存 settings，避免在 onDraw 中频繁读取 SharedPreferences
    private var cachedSettings: OvertimeSettings? = null
    
    /**
     * 预计算的每日展示数据（date -> DayCell）。
     * 在 setAttendanceData 时一次性构建，onDraw 只读，
     * 避免每帧重复解析日期、计算加班金额与格式化字符串。
     */
    private var dayCells: Map<String, DayCell> = emptyMap()
    
    /**
     * 预计算的日期字符串（"yyyy-MM-dd"），按日序号索引，下标 0 保留。
     */
    private var dayDateStrs: Array<String> = emptyArray()
    
    private data class DayCell(
        val overtimeHours: Double,
        val extraHours: Double,
        val hasData: Boolean,
        val hoursText: String,
        val extraText: String,
        val payText: String?
    )
    
    private val calendar = Calendar.getInstance()
    private var daysInMonth: Int = 0
    private var firstDayOfWeek: Int = 0
    
    private val cellWidth: Float
        get() = width.toFloat() / 7
    
    private val cellHeight: Float
        get() = height.toFloat() / 8
    
    // Paint objects - 一次性创建，避免重复创建
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 26f
        textAlign = Paint.Align.CENTER
        color = ContextCompat.getColor(context, android.R.color.black)
    }
    
    private val headerTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 30f
        textAlign = Paint.Align.CENTER
        color = ContextCompat.getColor(context, android.R.color.black)
        isFakeBoldText = true
    }
    
    private val normalDayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 26f
        textAlign = Paint.Align.CENTER
        color = ContextCompat.getColor(context, android.R.color.black)
    }
    
    private val overtimeDayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 26f
        textAlign = Paint.Align.CENTER
        color = ContextCompat.getColor(context, android.R.color.white)
    }
    
    private val weekendDayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 26f
        textAlign = Paint.Align.CENTER
        color = ContextCompat.getColor(context, android.R.color.darker_gray)
    }
    
    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.overtime_color)
    }
    
    private val moneyTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 16f
        textAlign = Paint.Align.CENTER
        color = ContextCompat.getColor(context, R.color.money_color)
    }
    
    private val overtimeInfoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 15f
        textAlign = Paint.Align.CENTER
        color = ContextCompat.getColor(context, R.color.overtime_info_color)
    }
    
    private val headerRect = RectF()
    private val dayRect = RectF()
    
    private val weekDays = context.resources.getStringArray(R.array.common_weekday_short)
    
    private var lastClickedDay: Int = -1
    private var lastClickTime: Long = 0
    
    init {
        val now = Calendar.getInstance()
        currentYear = now.get(Calendar.YEAR)
        currentMonth = now.get(Calendar.MONTH)
        calculateDays()
    }
    
    fun setMonth(year: Int, month: Int) {
        currentYear = year
        currentMonth = month
        calculateDays()
        invalidate()
    }
    
    fun setOnDateClickListener(listener: OnDateClickListener) {
        this.listener = listener
    }
    
    fun setAttendanceData(data: List<OvertimeRecord>, manager: SettingsManager) {
        this.settingsManager = manager
        this.cachedSettings = manager.getSettings()
        val settings = cachedSettings ?: OvertimeSettings()
        // 预计算每个日期的展示信息（时长文本/费用文本），onDraw 只读缓存
        dayCells = data.mapNotNull { a ->
            val overtimeHours = if (a.overtimeHours >= 0) a.overtimeHours else 0.0
            val extraHours = if (a.extraHours >= 0) a.extraHours else 0.0
            val hasData = overtimeHours > 0 || extraHours > 0
            if (!hasData) return@mapNotNull null
            val payText = if (hasData) {
                val pay = OvertimeCalculator.calculateOvertime(a, settings).estimatedPay
                if (pay > 0) String.format(Locale.getDefault(), "¥%.2f", pay) else null
            } else {
                null
            }
            a.date to DayCell(
                overtimeHours = overtimeHours,
                extraHours = extraHours,
                hasData = true,
                hoursText = "${overtimeHours}h",
                extraText = context.getString(R.string.calendar_extra_text, extraHours),
                payText = payText
            )
        }.toMap()
        invalidate()
    }
    
    private fun calculateDays() {
        calendar.set(currentYear, currentMonth, 1)
        daysInMonth = calendar.getActualMaximum(Calendar.DAY_OF_MONTH)
        firstDayOfWeek = calendar.get(Calendar.DAY_OF_WEEK) - 1
        // 预计算每日日期字符串，避免 onDraw 每帧分配
        dayDateStrs = Array(daysInMonth + 1) { day ->
            if (day == 0) "" else String.format(Locale.getDefault(), "%d-%02d-%02d", currentYear, currentMonth + 1, day)
        }
    }
    
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        drawWeekDayHeaders(canvas)
        drawDays(canvas)
    }
    
    private fun drawWeekDayHeaders(canvas: Canvas) {
        for (i in 0 until 7) {
            val x = cellWidth * i + cellWidth / 2
            val y = cellHeight / 2 + headerTextPaint.textSize / 3
            canvas.drawText(weekDays[i], x, y, headerTextPaint)
        }
    }
    
    private fun drawDays(canvas: Canvas) {
        for (day in 1..daysInMonth) {
            val position = firstDayOfWeek + day - 1
            val row = position / 7
            val col = position % 7
            
            val left = col * cellWidth
            val top = cellHeight + row * cellHeight
            val right = left + cellWidth
            val bottom = top + cellHeight
            
            val centerX = left + cellWidth / 2
            val centerY = top + cellHeight / 2
            
            // 直接读取预计算缓存，不再每帧解析日期/计算金额
            val cell = dayCells[dayDateStrs[day]]
            val hasData = cell?.hasData == true
            
            // 绘制背景圆
            if (hasData) {
                val radius = minOf(cellWidth, cellHeight) / 2 - 8
                dayRect.set(left + 8, top + 4, right - 8, bottom - 4)
                canvas.drawRoundRect(dayRect, radius, radius, circlePaint)
            }
            
            // 选择绘制颜色
            val paint = when {
                hasData -> overtimeDayPaint
                col == 0 || col == 6 -> weekendDayPaint
                else -> normalDayPaint
            }
            
            // 绘制日期文字
            canvas.drawText(
                day.toString(),
                centerX,
                centerY - cellHeight / 8,
                paint
            )
            
            // 绘制加班信息（使用预计算文本）
            if (hasData && cell != null) {
                var infoY = centerY + cellHeight / 8
                
                val hasOvertime = cell.overtimeHours > 0
                val hasExtra = cell.extraHours > 0
                
                if (hasOvertime && hasExtra) {
                    // 同时有加班和加点，显示为 "Xh+Y加"
                    canvas.drawText(
                        context.getString(R.string.calendar_cell_both, cell.hoursText, cell.extraText),
                        centerX,
                        infoY,
                        overtimeInfoPaint
                    )
                    infoY += overtimeInfoPaint.textSize + 2
                } else if (hasOvertime) {
                    canvas.drawText(cell.hoursText, centerX, infoY, overtimeInfoPaint)
                    infoY += overtimeInfoPaint.textSize + 2
                } else if (hasExtra) {
                    canvas.drawText(cell.extraText, centerX, infoY, overtimeInfoPaint)
                    infoY += overtimeInfoPaint.textSize + 2
                }
                
                // 显示费用（预计算文本）
                cell.payText?.let {
                    canvas.drawText(it, centerX, infoY, moneyTextPaint)
                }
            }
        }
    }
    
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            val clickTime = System.currentTimeMillis()
            
            // 防重复点击（300ms内）
            if (clickTime - lastClickTime < 300) {
                return true
            }
            
            val x = event.x
            val y = event.y - cellHeight // 减去标题行
            
            if (y < 0) return true
            
            val col = (x / cellWidth).toInt()
            val row = (y / cellHeight).toInt()
            val position = row * 7 + col
            
            val day = position - firstDayOfWeek + 1
            
            if (day in 1..daysInMonth) {
                lastClickedDay = day
                lastClickTime = clickTime
                listener?.onDateClick(currentYear, currentMonth, day)
                return true
            }
        }
        return true
    }
    
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val desiredHeight = (cellHeight * 8).toInt() // 7行 + 标题行
        val height = when (MeasureSpec.getMode(heightMeasureSpec)) {
            MeasureSpec.EXACTLY -> MeasureSpec.getSize(heightMeasureSpec)
            MeasureSpec.AT_MOST -> minOf(desiredHeight, MeasureSpec.getSize(heightMeasureSpec))
            else -> desiredHeight
        }
        setMeasuredDimension(width, height)
    }
}

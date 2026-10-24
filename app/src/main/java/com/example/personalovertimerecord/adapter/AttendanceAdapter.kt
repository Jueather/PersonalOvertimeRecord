package com.example.personalovertimerecord.adapter

import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.personalovertimerecord.R
import com.example.personalovertimerecord.data.DayType
import com.example.personalovertimerecord.data.OvertimeRecord
import com.example.personalovertimerecord.data.OvertimeSettings
import com.example.personalovertimerecord.data.ShiftType
import com.example.personalovertimerecord.utils.Formatter
import com.example.personalovertimerecord.utils.OvertimeCalculator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 明细列表条目：按日期排序时插入周分组头，按金额排序时为纯记录列表 */
sealed interface RecordListItem {
    data class WeekHeader(val key: String, val label: String) : RecordListItem
    data class Row(val record: OvertimeRecord) : RecordListItem
}

/**
 * 加班记录明细适配器：
 * 左侧色条按记录类型着色（请假 > 夜班 > 加班 > 加点），摘要只显示当天实际发生的项，
 * 金额下方标注与当月均值的偏离，点击卡片展开计算明细/班次/备注，长按交给外部弹出快捷菜单。
 */
class AttendanceAdapter(
    private var settings: OvertimeSettings = OvertimeSettings(),
    private val onItemLongClick: (OvertimeRecord) -> Unit = {}
) : ListAdapter<RecordListItem, RecyclerView.ViewHolder>(RecordListDiffCallback()) {

    /** 已展开的记录 id（按 id 记录，列表重排/过滤后仍保持展开态） */
    private val expandedIds = mutableSetOf<Long>()

    private val createdAtFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    /** 当月单条均值（加班费）；记录数不足 2 条时不显示对比 */
    private var monthAveragePay = 0.0
    private var hasEnoughRecords = false

    fun updateSettings(newSettings: OvertimeSettings) {
        if (settings != newSettings) {
            settings = newSettings
            // 只更新当前列表中的项，比 notifyDataSetChanged 更高效
            notifyItemRangeChanged(0, currentList.size)
        }
    }

    /**
     * 更新当月均值基准：由调用方在 submitList 之前设置，避免二次全量刷新。
     * @param averagePay 当月非请假记录的平均加班费
     * @param sampleCount 参与平均的记录数
     */
    fun updateMonthAverage(averagePay: Double, sampleCount: Int) {
        monthAveragePay = averagePay
        hasEnoughRecords = sampleCount >= 2
    }

    /** 月份/筛选条件变化时收起全部详情，避免残留展开态 */
    fun clearExpandedState() {
        expandedIds.clear()
    }

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is RecordListItem.WeekHeader -> TYPE_WEEK_HEADER
        is RecordListItem.Row -> TYPE_RECORD
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_WEEK_HEADER) {
            WeekHeaderViewHolder(inflater.inflate(R.layout.item_week_header, parent, false))
        } else {
            AttendanceViewHolder(inflater.inflate(R.layout.item_attendance, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is RecordListItem.WeekHeader -> (holder as WeekHeaderViewHolder).bind(item.label)
            is RecordListItem.Row -> (holder as AttendanceViewHolder).bind(
                item.record,
                settings,
                expandedIds.contains(item.record.id)
            )
        }
    }

    private class WeekHeaderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvWeekHeader: TextView = itemView.findViewById(R.id.tvWeekHeader)

        fun bind(label: String) {
            tvWeekHeader.text = label
        }
    }

    inner class AttendanceViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        private val typeBar: View = itemView.findViewById(R.id.typeBar)
        private val tvDate: TextView = itemView.findViewById(R.id.tvDate)
        private val tvDayType: TextView = itemView.findViewById(R.id.tvDayType)
        private val tvShiftType: TextView = itemView.findViewById(R.id.tvShiftType)
        private val ivExpand: ImageView = itemView.findViewById(R.id.ivExpand)
        private val overtimeLayout: View = itemView.findViewById(R.id.overtimeLayout)
        private val tvOvertime: TextView = itemView.findViewById(R.id.tvOvertime)
        private val extraLayout: View = itemView.findViewById(R.id.extraLayout)
        private val tvExtra: TextView = itemView.findViewById(R.id.tvExtra)
        private val leaveLayout: View = itemView.findViewById(R.id.leaveLayout)
        private val tvLeaveInfo: TextView = itemView.findViewById(R.id.tvLeaveInfo)
        private val tvPayLabel: TextView = itemView.findViewById(R.id.tvPayLabel)
        private val tvOvertimePay: TextView = itemView.findViewById(R.id.tvOvertimePay)
        private val tvVsAverage: TextView = itemView.findViewById(R.id.tvVsAverage)
        private val detailLayout: View = itemView.findViewById(R.id.detailLayout)
        private val tvDetailCalc: TextView = itemView.findViewById(R.id.tvDetailCalc)
        private val tvDetailShift: TextView = itemView.findViewById(R.id.tvDetailShift)
        private val tvNote: TextView = itemView.findViewById(R.id.tvNote)
        private val tvDetailCreated: TextView = itemView.findViewById(R.id.tvDetailCreated)

        init {
            // 点击 = 展开/收起详情；长按 = 快捷菜单（编辑/删除/复制）
            itemView.setOnClickListener {
                toggleExpanded(bindingAdapterPosition)
            }
            itemView.setOnLongClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    val item = getItem(position)
                    if (item is RecordListItem.Row) {
                        onItemLongClick(item.record)
                        true
                    } else {
                        false
                    }
                } else {
                    false
                }
            }
        }

        fun bind(record: OvertimeRecord, settings: OvertimeSettings, expanded: Boolean) {
            val context = itemView.context
            val overtimeHours = if (record.overtimeHours >= 0) record.overtimeHours else 0.0
            val extraHours = if (record.extraHours >= 0) record.extraHours else 0.0
            val isLeave = record.isLeave && record.leaveHours > 0

            tvDate.text = Formatter.formatDate(record.date)

            // 类型徽标：日类型（工作日/周末/法定假日）常显，夜班徽标仅在夜班时出现
            val dayTypeText = Formatter.getEffectiveDayTypeString(record.date, overtimeHours, extraHours)
            setBadge(tvDayType, dayTypeText, ContextCompat.getColor(context, R.color.text_secondary))
            if (!isLeave && record.shiftType == ShiftType.NIGHT.name) {
                tvShiftType.visibility = View.VISIBLE
                setBadge(tvShiftType, "夜班", ContextCompat.getColor(context, R.color.record_type_night))
            } else {
                tvShiftType.visibility = View.GONE
            }

            // 左侧色条：请假 > 夜班 > 加班 > 加点
            val typeColor = when {
                isLeave -> R.color.record_type_leave
                record.shiftType == ShiftType.NIGHT.name -> R.color.record_type_night
                overtimeHours > 0 -> R.color.record_type_overtime
                extraHours > 0 -> R.color.record_type_extra
                else -> R.color.record_type_neutral
            }
            typeBar.setBackgroundColor(ContextCompat.getColor(context, typeColor))

            // 摘要：只显示有值的项，零值整块隐藏，避免满屏 0h
            val result = OvertimeCalculator.calculateOvertime(record, settings)
            if (isLeave) {
                leaveLayout.visibility = View.VISIBLE
                overtimeLayout.visibility = View.GONE
                extraLayout.visibility = View.GONE
                tvLeaveInfo.text = Formatter.formatLeaveInfo(record.leaveType, record.leaveHours)
                tvPayLabel.text = "工作时长"
                tvOvertimePay.setTextColor(ContextCompat.getColor(context, R.color.record_type_leave))
                tvOvertimePay.text = "${(record.leaveHours * 8).toInt()}h"
                tvVsAverage.visibility = View.GONE
            } else {
                leaveLayout.visibility = View.GONE
                overtimeLayout.visibility = if (overtimeHours > 0) View.VISIBLE else View.GONE
                extraLayout.visibility = if (extraHours > 0) View.VISIBLE else View.GONE
                tvOvertime.text = Formatter.formatHoursShort(result.overtimeHours)
                tvExtra.text = Formatter.formatHoursShort(result.extraHours)
                tvPayLabel.text = "加班费"
                tvOvertimePay.setTextColor(ContextCompat.getColor(context, R.color.record_pay_color))
                tvOvertimePay.text = Formatter.formatMoney(result.estimatedPay)
                bindVsAverage(result.estimatedPay)
            }

            // 折叠详情
            detailLayout.visibility = if (expanded) View.VISIBLE else View.GONE
            ivExpand.rotation = if (expanded) 90f else 0f
            if (expanded) {
                tvDetailCalc.text = buildCalcDetail(record, settings, overtimeHours, extraHours)
                tvDetailShift.text = "班次：${if (record.shiftType == ShiftType.NIGHT.name) "夜班" else "白班"}" +
                    " · 类型：$dayTypeText" +
                    if (record.customRate >= 0) " · 自定义 ${formatRate(record.customRate)}" else ""
                tvNote.text = record.note
                tvNote.visibility = if (!record.note.isNullOrEmpty()) View.VISIBLE else View.GONE
                tvDetailCreated.text = "录入于 ${createdAtFormat.format(Date(record.createdAt))}"
            }
        }

        /** 与当月单条均值的偏离：±10% 以内视为持平，避免每张卡都挂标记 */
        private fun bindVsAverage(pay: Double) {
            if (!hasEnoughRecords || monthAveragePay <= 0 || pay <= 0) {
                tvVsAverage.visibility = View.GONE
                return
            }
            val ratio = (pay - monthAveragePay) / monthAveragePay
            if (kotlin.math.abs(ratio) < 0.1) {
                tvVsAverage.visibility = View.GONE
                return
            }
            val percent = (ratio * 100).toInt()
            tvVsAverage.visibility = View.VISIBLE
            tvVsAverage.text = if (percent > 0) "较月均 +$percent%" else "较月均 $percent%"
            tvVsAverage.setTextColor(
                ContextCompat.getColor(
                    itemView.context,
                    if (percent > 0) R.color.record_pay_color else R.color.text_hint
                )
            )
        }

        /** 计算明细：让金额来源可核对（自定义倍率整条记录统一按该倍率计算） */
        private fun buildCalcDetail(
            record: OvertimeRecord,
            settings: OvertimeSettings,
            overtimeHours: Double,
            extraHours: Double
        ): String {
            val hourly = OvertimeCalculator.hourlyWage(settings)
            val hourlyText = String.format(Locale.getDefault(), "%.2f", hourly)

            if (record.isLeave) {
                return "${Formatter.getLeaveTypeDisplayName(record.leaveType)} " +
                    "${formatDays(record.leaveHours)}，不计入加班费"
            }

            if (record.customRate >= 0) {
                val totalHours = overtimeHours + extraHours
                val pay = totalHours * hourly * record.customRate
                return "加班加点 ${Formatter.formatHoursShort(totalHours)} × ${formatRate(record.customRate)}" +
                    " × ¥$hourlyText/h = ${Formatter.formatMoney(pay)}"
            }

            val lines = mutableListOf<String>()
            if (overtimeHours > 0) {
                val dayType = OvertimeCalculator.effectiveDayType(record.date, overtimeHours, extraHours)
                val rate = when (dayType) {
                    DayType.HOLIDAY -> settings.overtimeRateHoliday
                    DayType.WEEKEND -> settings.overtimeRateWeekend
                    DayType.WORKDAY -> settings.overtimeRateNormal
                }
                lines.add(
                    "加班 ${Formatter.formatHoursShort(overtimeHours)} × ${formatRate(rate)}" +
                        " × ¥$hourlyText/h = ${Formatter.formatMoney(overtimeHours * hourly * rate)}"
                )
            }
            if (extraHours > 0) {
                val rate = settings.overtimeRateNormal
                lines.add(
                    "加点 ${Formatter.formatHoursShort(extraHours)} × ${formatRate(rate)}" +
                        " × ¥$hourlyText/h = ${Formatter.formatMoney(extraHours * hourly * rate)}"
                )
            }
            return if (lines.isEmpty()) "无加班时长" else lines.joinToString("\n")
        }

        /** 圆角浅色底徽标：底色取主色的低透明度版本，深色/浅色主题下都不刺眼 */
        private fun setBadge(view: TextView, text: String, color: Int) {
            view.text = text
            view.setTextColor(color)
            val drawable = GradientDrawable().apply {
                cornerRadius = 8f * view.resources.displayMetrics.density
                setColor(ColorUtils.setAlphaComponent(color, 28))
            }
            view.background = drawable
        }

        private fun toggleExpanded(position: Int) {
            if (position == RecyclerView.NO_POSITION) return
            val item = getItem(position)
            if (item !is RecordListItem.Row) return
            val id = item.record.id
            val expanded = !expandedIds.contains(id)
            if (expanded) expandedIds.add(id) else expandedIds.remove(id)
            notifyItemChanged(position)
        }
    }

    private fun formatRate(rate: Double): String =
        String.format(Locale.getDefault(), "%.1fx", rate)

    private fun formatDays(days: Double): String =
        if (days >= 1.0) "${days.toInt()} 天" else "${(days * 8).toInt()} 小时"

    private class RecordListDiffCallback : DiffUtil.ItemCallback<RecordListItem>() {

        override fun areItemsTheSame(oldItem: RecordListItem, newItem: RecordListItem): Boolean =
            when {
                oldItem is RecordListItem.WeekHeader && newItem is RecordListItem.WeekHeader ->
                    oldItem.key == newItem.key
                oldItem is RecordListItem.Row && newItem is RecordListItem.Row ->
                    oldItem.record.id == newItem.record.id
                else -> false
            }

        override fun areContentsTheSame(oldItem: RecordListItem, newItem: RecordListItem): Boolean =
            oldItem == newItem
    }

    private companion object {
        const val TYPE_WEEK_HEADER = 0
        const val TYPE_RECORD = 1
    }
}

package com.example.personalovertimerecord.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.personalovertimerecord.R
import com.example.personalovertimerecord.data.db.ChangeLogEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 修改日志列表适配器：类型标签着色 + 记录日期 + 操作时间 + 摘要 */
class ChangeLogAdapter : ListAdapter<ChangeLogEntity, ChangeLogAdapter.ViewHolder>(DIFF) {

    companion object {
        private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

        private val DIFF = object : DiffUtil.ItemCallback<ChangeLogEntity>() {
            override fun areItemsTheSame(oldItem: ChangeLogEntity, newItem: ChangeLogEntity): Boolean =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: ChangeLogEntity, newItem: ChangeLogEntity): Boolean =
                oldItem == newItem
        }
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvType: TextView = itemView.findViewById(R.id.tvType)
        val tvRecordDate: TextView = itemView.findViewById(R.id.tvRecordDate)
        val tvTime: TextView = itemView.findViewById(R.id.tvTime)
        val tvSummary: TextView = itemView.findViewById(R.id.tvSummary)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_change_log, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        val context = holder.itemView.context
        val (label, color) = when (item.changeType) {
            ChangeLogEntity.TYPE_INSERT -> context.getString(R.string.changelog_type_insert) to 0xFF2E7D32.toInt()
            ChangeLogEntity.TYPE_UPDATE -> context.getString(R.string.changelog_type_update) to 0xFF1565C0.toInt()
            ChangeLogEntity.TYPE_DELETE -> context.getString(R.string.changelog_type_delete) to 0xFFC62828.toInt()
            else -> context.getString(R.string.changelog_type_clear) to 0xFFEF6C00.toInt()
        }
        holder.tvType.text = label
        holder.tvType.setTextColor(color)
        holder.tvRecordDate.text = item.recordDate.ifBlank { context.getString(R.string.changelog_all_records) }
        holder.tvTime.text = timeFormat.format(Date(item.timestamp))
        holder.tvSummary.text = item.summary
    }
}

package com.example.personalovertimerecord.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.personalovertimerecord.R
import com.example.personalovertimerecord.data.model.TeamAdminUserDto
import com.example.personalovertimerecord.data.model.TeamSummaryEntry
import com.google.android.material.button.MaterialButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 管理员汇总页列表：区头（String）/ 成员行（[TeamAdminUserDto]）/ 汇总行（[TeamSummaryEntry]）三种视图类型。
 */
class TeamSummaryAdapter(
    private val currentUserId: Long,
    private val onResetPassword: (TeamAdminUserDto) -> Unit,
    private val onToggleActive: (TeamAdminUserDto) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_MEMBER = 1
        private const val TYPE_SUMMARY = 2
    }

    private val items = mutableListOf<Any>()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)

    fun submitItems(newItems: List<Any>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is String -> TYPE_HEADER
        is TeamAdminUserDto -> TYPE_MEMBER
        else -> TYPE_SUMMARY
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> HeaderHolder(inflater.inflate(R.layout.item_team_summary_header, parent, false))
            TYPE_MEMBER -> MemberHolder(inflater.inflate(R.layout.item_team_member, parent, false))
            else -> SummaryHolder(inflater.inflate(R.layout.item_team_summary_row, parent, false))
        }
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is String -> (holder as HeaderHolder).bind(item)
            is TeamAdminUserDto -> (holder as MemberHolder).bind(item)
            is TeamSummaryEntry -> (holder as SummaryHolder).bind(item)
        }
    }

    class HeaderHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvHeader: TextView = itemView.findViewById(R.id.tvHeader)
        fun bind(title: String) {
            tvHeader.text = title
        }
    }

    inner class MemberHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvName: TextView = itemView.findViewById(R.id.tvName)
        private val tvRole: TextView = itemView.findViewById(R.id.tvRole)
        private val tvMeta: TextView = itemView.findViewById(R.id.tvMeta)
        private val btnResetPassword: MaterialButton = itemView.findViewById(R.id.btnResetPassword)
        private val btnToggle: MaterialButton = itemView.findViewById(R.id.btnToggle)

        fun bind(user: TeamAdminUserDto) {
            val context = itemView.context
            val name = user.displayName.ifBlank { user.username }
            tvName.text = name
            tvRole.text = if (user.role == "admin") {
                context.getString(R.string.team_role_admin)
            } else {
                context.getString(R.string.team_role_member)
            }
            val statusSuffix = if (user.isActive) "" else context.getString(R.string.team_status_disabled)
            tvMeta.text = context.getString(
                R.string.team_member_meta,
                user.recordCount,
                dateFormat.format(Date(user.createdAt)) + statusSuffix
            )

            btnResetPassword.setOnClickListener { onResetPassword(user) }
            btnToggle.text = if (user.isActive) {
                context.getString(R.string.team_set_active_disable)
            } else {
                context.getString(R.string.team_set_active_enable)
            }
            btnToggle.setOnClickListener { onToggleActive(user) }
            // 自己的账号不能停用（服务端同样拦截），前端直接隐藏操作避免无效点击
            btnToggle.visibility = if (user.id == currentUserId) View.GONE else View.VISIBLE
        }
    }

    inner class SummaryHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvMember: TextView = itemView.findViewById(R.id.tvMember)
        private val tvMonth: TextView = itemView.findViewById(R.id.tvMonth)
        private val tvDetail: TextView = itemView.findViewById(R.id.tvDetail)

        fun bind(entry: TeamSummaryEntry) {
            val context = itemView.context
            tvMember.text = entry.displayName.ifBlank { entry.username }
            tvMonth.text = formatMonth(entry.month)
            tvDetail.text = context.getString(
                R.string.team_summary_row_detail,
                entry.overtimeHours,
                entry.leaveHours,
                entry.recordCount,
                entry.leaveCount
            )
        }
    }

    /** month 为 yyyyMM，展示为 yyyy-MM；异常长度原样返回 */
    private fun formatMonth(month: String): String =
        if (month.length == 6) "${month.take(4)}-${month.takeLast(2)}" else month
}

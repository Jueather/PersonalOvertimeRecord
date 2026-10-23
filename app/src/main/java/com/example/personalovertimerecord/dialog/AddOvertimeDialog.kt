package com.example.personalovertimerecord.dialog

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import com.example.personalovertimerecord.R
import com.example.personalovertimerecord.data.LeaveType
import com.example.personalovertimerecord.data.OvertimeRecord
import com.example.personalovertimerecord.data.ShiftType
import com.example.personalovertimerecord.utils.DataValidator
import com.example.personalovertimerecord.utils.getFirstError
import java.util.Calendar
import java.util.Locale

class AddOvertimeDialog(
    context: Context,
    private val year: Int,
    private val month: Int,
    private val day: Int,
    private val existingRecord: OvertimeRecord? = null,
    private val onSaveRecord: ((OvertimeRecord) -> Unit)? = null,
    private val onDeleteRecord: ((Long) -> Unit)? = null
) : Dialog(context) {
    
    private lateinit var actvOvertimeHours: AutoCompleteTextView
    private lateinit var actvExtraHours: AutoCompleteTextView
    private lateinit var etNote: EditText
    private lateinit var tvTitle: TextView
    private lateinit var btnDelete: View
    private lateinit var switchLeave: com.google.android.material.switchmaterial.SwitchMaterial
    private lateinit var leaveOptionsLayout: View
    private lateinit var actvLeaveType: AutoCompleteTextView
    private lateinit var actvLeaveHours: AutoCompleteTextView
    private lateinit var actvRate: AutoCompleteTextView
    private lateinit var shiftCard: View
    private lateinit var toggleShift: com.google.android.material.button.MaterialButtonToggleGroup
    private var selectedLeaveType: LeaveType = LeaveType.ANNUAL_LEAVE

    // 加班/加点选项，最大11小时
    private val hourOptions: List<String> = (1..22).map { "${it * 0.5}" } // 0.5, 1.0, 1.5, ... 11.0

    // 倍率选项：默认 + 1.0~4.0（0.5 步进，含法定 1.5/2.0/3.0 与常见协商值），也可手动输入该区间内的数值
    private val rateOptions: List<String> =
        listOf("默认") + (2..8).map { "${it * 0.5}" }
    
    // 计算当月剩余天数（用于请假上限）
    private val maxLeaveDays: Int by lazy {
        val calendar = Calendar.getInstance()
        calendar.set(year, month, day)
        val maxDay = calendar.getActualMaximum(Calendar.DAY_OF_MONTH)
        maxDay - day + 1 // 包含当天
    }
    
    // 请假选项，根据当月剩余天数动态生成
    private val leaveDayOptions: List<String> by lazy {
        (1..maxLeaveDays).map { "${it * 1.0}" } // 1.0, 2.0, 3.0, ... up to remaining days
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.dialog_add_overtime)
        
        window?.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT
        )
        
        initViews()
        setupHourDropdowns()
        setupLeaveTypeDropdown()
        setupExistingData()
        setupListeners()
    }
    
    private fun initViews() {
        actvOvertimeHours = findViewById(R.id.actvOvertimeHours)
        actvExtraHours = findViewById(R.id.actvExtraHours)
        etNote = findViewById(R.id.etNote)
        tvTitle = findViewById(R.id.tvTitle)
        btnDelete = findViewById(R.id.btnDelete)
        switchLeave = findViewById(R.id.switchLeave)
        leaveOptionsLayout = findViewById(R.id.leaveOptionsLayout)
        actvLeaveType = findViewById(R.id.actvLeaveType)
        actvLeaveHours = findViewById(R.id.actvLeaveHours)
        actvRate = findViewById(R.id.actvRate)
        shiftCard = findViewById(R.id.shiftCard)
        toggleShift = findViewById(R.id.toggleShift)

        // 班次默认白班；编辑时按已有记录回选
        toggleShift.check(R.id.btnShiftDay)
        
        val tvDate = findViewById<TextView>(R.id.tvDate)
        val dateStr = String.format(Locale.getDefault(), "%d-%02d-%02d", year, month + 1, day)
        tvDate.text = dateStr
    }
    
    private fun setupHourDropdowns() {
        val hourAdapter = ArrayAdapter(
            context,
            android.R.layout.simple_dropdown_item_1line,
            hourOptions
        )
        
        actvOvertimeHours.setAdapter(hourAdapter)
        actvExtraHours.setAdapter(hourAdapter)

        // 默认选择空白
        actvOvertimeHours.setText("", false)
        actvExtraHours.setText("", false)

        // 倍率下拉：默认按日期类型自动计算，也可选择/输入自定义倍率
        val rateAdapter = ArrayAdapter(
            context,
            android.R.layout.simple_dropdown_item_1line,
            rateOptions
        )
        actvRate.setAdapter(rateAdapter)
        actvRate.setText("默认", false)
    }
    
    private fun setupLeaveTypeDropdown() {
        val leaveTypes = LeaveType.entries.toTypedArray()
        val adapter = ArrayAdapter(
            context,
            android.R.layout.simple_dropdown_item_1line,
            leaveTypes.map { it.displayName }
        )
        actvLeaveType.setAdapter(adapter)
        actvLeaveType.setText(LeaveType.ANNUAL_LEAVE.displayName, false)
        
        // 请假天数也设置下拉（按天，1天=8小时）
        val leaveHoursAdapter = ArrayAdapter(
            context,
            android.R.layout.simple_dropdown_item_1line,
            leaveDayOptions
        )
        actvLeaveHours.setAdapter(leaveHoursAdapter)
        actvLeaveHours.setText("1.0", false)
        
        actvLeaveType.setOnItemClickListener { _, _, position, _ ->
            selectedLeaveType = leaveTypes[position]
        }
    }
    
    private fun setupExistingData() {
        if (existingRecord != null) {
            tvTitle.text = "编辑记录"
            // 班次回选（旧数据缺失时 fromStringOrDefault 兜底为白班）
            when (ShiftType.fromStringOrDefault(existingRecord.shiftType)) {
                ShiftType.DAY -> toggleShift.check(R.id.btnShiftDay)
                ShiftType.NIGHT -> toggleShift.check(R.id.btnShiftNight)
            }
            if (existingRecord.overtimeHours > 0) {
                actvOvertimeHours.setText(existingRecord.overtimeHours.toString(), false)
            }
            if (existingRecord.extraHours > 0) {
                actvExtraHours.setText(existingRecord.extraHours.toString(), false)
            }
            // 回填自定义倍率（未自定义时保持"默认"）
            if (existingRecord.customRate >= 0) {
                actvRate.setText(existingRecord.customRate.toString(), false)
            }
            etNote.setText(existingRecord.note ?: "")

            // 加载请假数据
            if (existingRecord.isLeave) {
                switchLeave.isChecked = true
                leaveOptionsLayout.visibility = View.VISIBLE
                shiftCard.visibility = View.GONE
                if (existingRecord.leaveHours > 0) {
                    actvLeaveHours.setText(existingRecord.leaveHours.toString(), false)
                }
                existingRecord.leaveType?.let { type ->
                    LeaveType.fromString(type)?.let { lt ->
                        selectedLeaveType = lt
                        actvLeaveType.setText(lt.displayName, false)
                    }
                }
            }
            btnDelete.visibility = View.VISIBLE
        } else {
            tvTitle.text = "添加加班记录"
        }
    }
    
    private fun setupListeners() {
        btnDelete.setOnClickListener {
            if (existingRecord != null && onDeleteRecord != null) {
                onDeleteRecord.invoke(existingRecord.id)
                dismiss()
            }
        }
        
        findViewById<View>(R.id.btnCancel).setOnClickListener {
            dismiss()
        }
        
        // 请假开关：请假模式下无班次概念，隐藏班次卡片
        switchLeave.setOnCheckedChangeListener { _, isChecked ->
            leaveOptionsLayout.visibility = if (isChecked) View.VISIBLE else View.GONE
            shiftCard.visibility = if (isChecked) View.GONE else View.VISIBLE
        }
        
        findViewById<View>(R.id.btnSave).setOnClickListener {
            saveData()
        }
    }
    
    private fun saveData() {
        val overtimeHoursStr = actvOvertimeHours.text?.toString() ?: ""
        val extraHoursStr = actvExtraHours.text?.toString() ?: ""
        val note = etNote.text?.toString() ?: ""
        val isLeave = switchLeave.isChecked

        val dateStr = String.format(Locale.getDefault(), "%d-%02d-%02d", year, month + 1, day)

        // 第一层：通用合法性校验（DataValidator）；日期不允许未来
        DataValidator.validateDate(dateStr).getFirstError()?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            return
        }

        // 当前选中的班次（请假模式下沿用原记录班次或默认白班）
        val selectedShiftType = when (toggleShift.checkedButtonId) {
            R.id.btnShiftNight -> ShiftType.NIGHT.name
            else -> ShiftType.DAY.name
        }

        // 如果是请假模式
        if (isLeave) {
            val leaveHoursStr = actvLeaveHours.text?.toString() ?: ""
            val leaveHours = leaveHoursStr.toDoubleOrNull()

            // 通用校验：必填 + 范围；业务规则（不超过当月剩余天数）作为第二层
            DataValidator.validateNumberRange(leaveHours, 0.5, 31.0, "请假天数").getFirstError()?.let {
                Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
                return
            }
            if (leaveHours != null && leaveHours > maxLeaveDays) {
                Toast.makeText(context, "当月剩余${maxLeaveDays}天，请假不能超过${maxLeaveDays}天", Toast.LENGTH_SHORT).show()
                return
            }

            if (existingRecord != null && onSaveRecord != null) {
                val updatedRecord = existingRecord.copy(
                    isLeave = true,
                    leaveType = selectedLeaveType.name,
                    leaveHours = leaveHours ?: 1.0,
                    // 请假记录无班次概念，沿用原记录班次（新建默认白班）
                    shiftType = existingRecord.shiftType,
                    note = if (note.isNullOrEmpty()) null else note
                )
                onSaveRecord.invoke(updatedRecord)
            } else if (onSaveRecord != null) {
                val newRecord = OvertimeRecord(
                    id = 0L,
                    date = dateStr,
                    isLeave = true,
                    leaveType = selectedLeaveType.name,
                    leaveHours = leaveHours ?: 1.0,
                    shiftType = ShiftType.DAY.name,
                    note = if (note.isNullOrEmpty()) null else note
                )
                onSaveRecord.invoke(newRecord)
            }
            dismiss()
            return
        }
        
        // 非请假模式，使用加班/加点时长
        val overtimeHours = overtimeHoursStr.toDoubleOrNull()
        val extraHours = extraHoursStr.toDoubleOrNull()

        // 加班和加点至少要填一个（业务规则）
        if (overtimeHours == null && extraHours == null) {
            Toast.makeText(context, "请选择加班时长或加点时长", Toast.LENGTH_SHORT).show()
            return
        }

        // 通用校验：非负且不超 24h；业务规则：单日 ≤11h
        if (overtimeHours != null) {
            DataValidator.validateNumberRange(overtimeHours, 0.0, 24.0, "加班时长").getFirstError()?.let {
                Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
                return
            }
            if (overtimeHours > 11) {
                Toast.makeText(context, "单日加班时长不能超过11小时", Toast.LENGTH_SHORT).show()
                return
            }
        }
        if (extraHours != null) {
            DataValidator.validateNumberRange(extraHours, 0.0, 24.0, "加点时长").getFirstError()?.let {
                Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
                return
            }
            if (extraHours > 11) {
                Toast.makeText(context, "单日加点时长不能超过11小时", Toast.LENGTH_SHORT).show()
                return
            }
        }

        val finalOvertime = overtimeHours ?: 0.0
        val finalExtra = extraHours ?: 0.0

        // 解析自定义倍率："默认"或留空 → -1（按设置默认倍率计算）；合法区间 [1.0, 4.0]
        val rateText = actvRate.text?.toString()?.trim() ?: ""
        val customRate = when {
            rateText.isEmpty() || rateText == "默认" -> -1.0
            else -> rateText.toDoubleOrNull()
        }
        if (rateText.isNotEmpty() && rateText != "默认") {
            DataValidator.validateNumberRange(customRate, 1.0, 4.0, "工资倍率").getFirstError()?.let {
                Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
                return
            }
        }

        if (existingRecord != null && onSaveRecord != null) {
            val updatedRecord = existingRecord.copy(
                overtimeHours = if (finalOvertime > 0) finalOvertime else -1.0,
                extraHours = if (finalExtra > 0) finalExtra else -1.0,
                isLeave = false,
                leaveType = null,
                leaveHours = 0.0,
                customRate = customRate ?: -1.0,
                shiftType = selectedShiftType,
                note = if (note.isNullOrEmpty()) null else note
            )
            onSaveRecord?.invoke(updatedRecord)
        } else if (onSaveRecord != null) {
            val newRecord = OvertimeRecord(
                id = 0L, // 让Room数据库自动生成ID
                date = dateStr,
                overtimeHours = if (finalOvertime > 0) finalOvertime else -1.0,
                extraHours = if (finalExtra > 0) finalExtra else -1.0,
                isLeave = false,
                leaveType = null,
                leaveHours = 0.0,
                customRate = customRate ?: -1.0,
                shiftType = selectedShiftType,
                note = if (note.isNullOrEmpty()) null else note
            )
            onSaveRecord?.invoke(newRecord)
        }

        dismiss()
    }
}

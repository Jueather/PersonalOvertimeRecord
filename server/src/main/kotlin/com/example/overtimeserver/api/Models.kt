package com.example.overtimeserver.api

import kotlinx.serialization.Serializable

/**
 * 记录 DTO：与客户端 utils/DataExporter.kt 的 AttendanceEntityBackup 一一镜像，
 * 额外新增 overtimeHours（客户端同步时用 OvertimeCalculator 算好仅上传，供聚合用）。
 */
@Serializable
data class RecordDto(
    val date: String,
    val checkInTime: String? = null,
    val checkOutTime: String? = null,
    val checkInTimestamp: Long? = null,
    val checkOutTimestamp: Long? = null,
    val note: String? = null,
    val manualOvertimeHours: Double = -1.0,
    val manualExtraHours: Double = -1.0,
    val overtimeHours: Double? = null,
    val createdAt: Long = 0L,
    val modifiedAt: Long = 0L,
    val isLeave: Boolean = false,
    val leaveType: String? = null,
    val leaveHours: Double = 0.0,
    val customRate: Double? = null,
    // 班次标记："DAY"=白班 / "NIGHT"=夜班；null 表示旧客户端缺失（透传即可，不做聚合）
    val shiftType: String? = null,
    val isDeleted: Boolean = false,
    val lastModifiedBy: String = "",
)

@Serializable
data class LoginRequest(val username: String, val password: String)

@Serializable
data class UserDto(
    val id: Long,
    val username: String,
    val displayName: String,
    val role: String,
    val isActive: Boolean = true,
)

/** expiresIn 单位：秒 */
@Serializable
data class LoginResponse(val token: String, val expiresIn: Long, val user: UserDto)

@Serializable
data class ChangePasswordRequest(val oldPassword: String, val newPassword: String)

@Serializable
data class MessageResponse(val message: String)

@Serializable
data class ErrorResponse(val error: String)

@Serializable
data class TeamSyncRequest(
    val records: List<RecordDto> = emptyList(),
    val deviceName: String = "",
)

/** records = 该用户在服务端的最新全量（含墓碑），客户端据此做 NEWER_WINS 合并 */
@Serializable
data class TeamSyncResponse(val serverTime: Long, val records: List<RecordDto>)

@Serializable
data class CreateUserRequest(
    val username: String,
    val password: String? = null,
    val displayName: String = "",
)

/** initialPassword 仅此一次返回（未指定密码时由服务端生成） */
@Serializable
data class CreateUserResponse(val user: UserDto, val initialPassword: String)

@Serializable
data class ResetPasswordResponse(val initialPassword: String)

@Serializable
data class SetActiveRequest(val isActive: Boolean)

@Serializable
data class AdminUserDto(
    val id: Long,
    val username: String,
    val displayName: String,
    val role: String,
    val isActive: Boolean,
    val recordCount: Int,
    val createdAt: Long,
)

/** month 为 yyyyMM；不含工资（薪资不上云） */
@Serializable
data class SummaryEntry(
    val userId: Long,
    val username: String,
    val displayName: String,
    val month: String,
    val overtimeHours: Double,
    val leaveHours: Double,
    val recordCount: Int,
    val leaveCount: Int,
)

@Serializable
data class SummaryResponse(val entries: List<SummaryEntry>)

@Serializable
data class HealthResponse(val status: String, val time: Long)

package com.example.personalovertimerecord.data.model

import com.google.gson.annotations.SerializedName

/**
 * 团队同步 API DTO（与服务端 kotlinx.serialization 的 camelCase 字段一一对应，Gson 默认即可对齐）。
 *
 * [TeamRecordDto] 镜像 utils/DataExporter.kt 的 AttendanceEntityBackup（Gson 字段名完全一致），
 * 仅额外新增 [TeamRecordDto.overtimeHours]：客户端同步时用 OvertimeCalculator 算好仅上传，供服务端聚合。
 */
data class TeamRecordDto(
    val date: String,
    val checkInTime: String? = null,
    val checkOutTime: String? = null,
    val checkInTimestamp: Long? = null,
    val checkOutTimestamp: Long? = null,
    val note: String? = null,
    val manualOvertimeHours: Double = -1.0,
    val manualExtraHours: Double = -1.0,
    // 客户端算好的加班总工时（加班 + 加点），请假/删除记录为 0.0；服务端只存不用来改记录
    val overtimeHours: Double? = null,
    val createdAt: Long = 0L,
    val modifiedAt: Long = 0L,
    val isLeave: Boolean = false,
    val leaveType: String? = null,
    val leaveHours: Double = 0.0,
    val customRate: Double? = null,
    val isDeleted: Boolean = false,
    val lastModifiedBy: String = ""
)

data class TeamLoginRequest(
    val username: String,
    val password: String
)

data class TeamUserDto(
    val id: Long,
    val username: String,
    val displayName: String,
    val role: String,
    val isActive: Boolean = true
)

data class TeamLoginResponse(
    val token: String,
    val expiresIn: Long,
    val user: TeamUserDto
)

data class TeamChangePasswordRequest(
    val oldPassword: String,
    val newPassword: String
)

data class TeamSyncRequest(
    val records: List<TeamRecordDto> = emptyList(),
    val deviceName: String = ""
)

/** records = 服务端该用户的最新全量（含墓碑），客户端据此做 NEWER_WINS 合并 */
data class TeamSyncResponse(
    val serverTime: Long,
    val records: List<TeamRecordDto> = emptyList()
)

data class TeamCreateUserRequest(
    val username: String,
    val password: String? = null,
    val displayName: String = ""
)

data class TeamCreateUserResponse(
    val user: TeamUserDto,
    val initialPassword: String
)

data class TeamResetPasswordResponse(
    val initialPassword: String
)

data class TeamSetActiveRequest(
    val isActive: Boolean
)

data class TeamAdminUserDto(
    val id: Long,
    val username: String,
    val displayName: String,
    val role: String,
    val isActive: Boolean,
    val recordCount: Int,
    val createdAt: Long
)

/** month 为 yyyyMM；不含工资（薪资不上云） */
data class TeamSummaryEntry(
    val userId: Long,
    val username: String,
    val displayName: String,
    val month: String,
    val overtimeHours: Double,
    val leaveHours: Double,
    val recordCount: Int,
    val leaveCount: Int
)

data class TeamSummaryResponse(
    val entries: List<TeamSummaryEntry> = emptyList()
)

/** 服务端错误响应统一为 {"error": "..."} */
data class TeamErrorResponse(
    @SerializedName("error") val error: String
)

/** 团队 API 异常：[kind] 供 UI 映射提示文案 */
class TeamApiException(
    val kind: Kind,
    message: String,
    cause: Throwable? = null
) : Exception(message, cause) {

    enum class Kind {
        /** 无网络 / 连接失败 / 超时 */
        NETWORK,
        /** 401：token 失效或密码错误，需要重新登录 */
        UNAUTHORIZED,
        /** 4xx：请求问题（如用户名已存在） */
        CLIENT,
        /** 5xx：服务端错误 */
        SERVER
    }
}

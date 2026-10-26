package com.example.personalovertimerecord.data.model

/** 试用状态枚举，与服务端 TrialStatus 对齐 */
enum class TrialStatus { ACTIVE, EXPIRED, NO_LICENSE }

/** POST /api/trial/redeem 响应（成功时） */
data class TrialRedeemResponse(
    val status: TrialStatus,
    val remainingMs: Long,
    val expiresAt: Long,
    val activatedAt: Long,
    val trialDays: Int,
    val serverTime: Long
)

/** GET /api/trial/status 响应 */
data class TrialStatusResponse(
    val status: TrialStatus,
    val remainingMs: Long,
    val expiresAt: Long? = null,
    val activatedAt: Long? = null,
    val trialDays: Int? = null,
    val serverTime: Long
)

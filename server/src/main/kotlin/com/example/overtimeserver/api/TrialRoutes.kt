package com.example.overtimeserver.api

import com.example.overtimeserver.db.TrialCodeDao
import com.example.overtimeserver.db.TrialCodeRow
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

fun Route.trialRoutes(trialDao: TrialCodeDao) {
    route("/api/trial") {

        // GET /api/trial/status?deviceId=xxx
        get("/status") {
            val deviceId = call.request.queryParameters["deviceId"]?.trim().orEmpty()
            if (deviceId.isEmpty()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("缺少 deviceId 参数"))
                return@get
            }
            val now = System.currentTimeMillis()
            val active = trialDao.findActiveByDevice(deviceId, now)
            if (active != null) {
                val remaining = (active.expiresAt!! - now).coerceAtLeast(0L)
                call.respond(
                    TrialStatusResponse(
                        status = TrialStatus.ACTIVE,
                        remainingMs = remaining,
                        expiresAt = active.expiresAt,
                        activatedAt = active.activatedAt,
                        trialDays = active.trialDays,
                        serverTime = now,
                    )
                )
            } else {
                // 历史上是否存在过？用于显示"已过期" vs "未激活"
                val any = trialDao.findAllByDevice(deviceId).firstOrNull()
                if (any != null && any.expiresAt != null && any.expiresAt < now) {
                    call.respond(
                        TrialStatusResponse(
                            status = TrialStatus.EXPIRED,
                            remainingMs = any.expiresAt - now,
                            expiresAt = any.expiresAt,
                            activatedAt = any.activatedAt,
                            trialDays = any.trialDays,
                            serverTime = now,
                        )
                    )
                } else {
                    call.respond(
                        TrialStatusResponse(
                            status = TrialStatus.NO_LICENSE,
                            remainingMs = 0L,
                            serverTime = now,
                        )
                    )
                }
            }
        }

        // POST /api/trial/redeem
        post("/redeem") {
            val req = call.receive<TrialRedeemRequest>()
            val rawCode = req.code.trim()
            val deviceId = req.deviceId.trim()
            if (rawCode.isEmpty() || deviceId.isEmpty()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("激活码和设备标识不能为空"))
                return@post
            }

            when (val result = trialDao.redeem(rawCode, deviceId)) {
                is TrialCodeDao.RedeemResult.Success -> {
                    call.respond(
                        TrialRedeemResponse(
                            status = TrialStatus.ACTIVE,
                            remainingMs = result.remainingMs,
                            expiresAt = result.row.expiresAt!!,
                            activatedAt = result.row.activatedAt!!,
                            trialDays = result.row.trialDays,
                            serverTime = System.currentTimeMillis(),
                        )
                    )
                }
                is TrialCodeDao.RedeemResult.DeviceHasActive -> {
                    // 设备本身已有未过期激活，视为成功，返回当前状态
                    val row = result.row
                    val now = System.currentTimeMillis()
                    call.respond(
                        TrialRedeemResponse(
                            status = TrialStatus.ACTIVE,
                            remainingMs = (row.expiresAt!! - now).coerceAtLeast(0L),
                            expiresAt = row.expiresAt!!,
                            activatedAt = row.activatedAt!!,
                            trialDays = row.trialDays,
                            serverTime = now,
                        )
                    )
                }
                is TrialCodeDao.RedeemResult.NotExist ->
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("激活码不存在，请核对或联系管理员"))
                is TrialCodeDao.RedeemResult.Revoked ->
                    call.respond(HttpStatusCode.Gone, ErrorResponse("该激活码已被管理员吊销"))
                is TrialCodeDao.RedeemResult.AlreadyBound ->
                    call.respond(HttpStatusCode.Conflict, ErrorResponse("该激活码已被其他设备使用"))
                is TrialCodeDao.RedeemResult.UsedUp ->
                    call.respond(HttpStatusCode.Conflict, ErrorResponse("该激活码已达使用上限"))
            }
        }
    }
}

internal fun TrialCodeRow.toDto(): TrialCodeDto = TrialCodeDto(
    id = id,
    code = code,
    trialDays = trialDays,
    maxUses = maxUses,
    usedCount = usedCount,
    boundDeviceId = boundDeviceId,
    activatedAt = activatedAt,
    expiresAt = expiresAt,
    createdAt = createdAt,
    createdBy = createdBy,
    isRevoked = isRevoked,
)

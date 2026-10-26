package com.example.overtimeserver.api

import com.example.overtimeserver.auth.PasswordHasher
import com.example.overtimeserver.auth.requireAdmin
import com.example.overtimeserver.db.RecordDao
import com.example.overtimeserver.db.TrialCodeDao
import com.example.overtimeserver.db.UserDao
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

fun Route.adminRoutes(userDao: UserDao, recordDao: RecordDao, trialDao: TrialCodeDao) {
    route("/api/admin") {
        authenticate("auth-jwt") {
            get("/users") {
                if (call.requireAdmin(userDao) == null) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
                    return@get
                }
                val users = userDao.listAll().map { u ->
                    AdminUserDto(
                        id = u.id,
                        username = u.username,
                        displayName = u.displayName,
                        role = u.role,
                        isActive = u.isActive,
                        recordCount = recordDao.countByUser(u.id),
                        createdAt = u.createdAt,
                    )
                }
                call.respond(users)
            }

            post("/users") {
                val admin = call.requireAdmin(userDao)
                if (admin == null) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
                    return@post
                }
                val req = call.receive<CreateUserRequest>()
                val username = req.username.trim()
                if (username.isEmpty() || username.length > 32 || username.any { it.isWhitespace() }) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("用户名不能为空、不含空格且不超过 32 个字符"))
                    return@post
                }
                val password = req.password?.takeIf { it.isNotBlank() } ?: PasswordHasher.generatePassword()
                if (password.length < 6) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("密码至少 6 位"))
                    return@post
                }
                val user = userDao.create(username, PasswordHasher.hash(password), req.displayName, "member")
                if (user == null) {
                    call.respond(HttpStatusCode.Conflict, ErrorResponse("用户名已存在"))
                    return@post
                }
                call.respond(
                    HttpStatusCode.Created,
                    CreateUserResponse(
                        user = UserDto(user.id, user.username, user.displayName, user.role, user.isActive),
                        initialPassword = password,
                    ),
                )
            }

            post("/users/{id}/reset-password") {
                val admin = call.requireAdmin(userDao)
                if (admin == null) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
                    return@post
                }
                val id = call.parameters["id"]?.toLongOrNull()
                if (id == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("无效的用户 ID"))
                    return@post
                }
                if (id == admin.id) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("请使用「修改密码」修改自己的密码"))
                    return@post
                }
                if (userDao.findById(id) == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("用户不存在"))
                    return@post
                }
                val password = PasswordHasher.generatePassword()
                userDao.updatePassword(id, PasswordHasher.hash(password))
                call.respond(ResetPasswordResponse(initialPassword = password))
            }

            post("/users/{id}/set-active") {
                val admin = call.requireAdmin(userDao)
                if (admin == null) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
                    return@post
                }
                val id = call.parameters["id"]?.toLongOrNull()
                if (id == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("无效的用户 ID"))
                    return@post
                }
                val req = call.receive<SetActiveRequest>()
                if (id == admin.id && !req.isActive) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("不能停用自己的账号"))
                    return@post
                }
                if (!userDao.setActive(id, req.isActive)) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("用户不存在"))
                    return@post
                }
                call.respond(MessageResponse(if (req.isActive) "已启用" else "已停用"))
            }

            // GET /api/admin/summary?from=202610&to=202612（month 为 yyyyMM，含端点）
            get("/summary") {
                if (call.requireAdmin(userDao) == null) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
                    return@get
                }
                val from = call.request.queryParameters["from"]
                val to = call.request.queryParameters["to"]
                val entries = recordDao.monthlySummary().filter { e ->
                    (from.isNullOrBlank() || e.month >= from) && (to.isNullOrBlank() || e.month <= to)
                }
                call.respond(SummaryResponse(entries))
            }

            // POST /api/admin/trial/generate — 批量生成激活码
            post("/trial/generate") {
                val admin = call.requireAdmin(userDao)
                if (admin == null) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
                    return@post
                }
                val req = call.receive<TrialCodeGenerateRequest>()
                if (req.count !in 1..500) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("生成数量需在 1-500 之间"))
                    return@post
                }
                if (req.trialDays !in 1..3650) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("试用天数需在 1-3650 之间"))
                    return@post
                }
                if (req.maxUses !in 1..100) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("最大使用次数需在 1-100 之间"))
                    return@post
                }
                val rows = trialDao.batchGenerate(req.count, req.trialDays, req.maxUses, admin.username)
                call.respond(
                    HttpStatusCode.Created,
                    TrialCodeGenerateResponse(codes = rows.map { it.toDto() })
                )
            }

            // GET /api/admin/trial/codes — 列出所有激活码
            get("/trial/codes") {
                if (call.requireAdmin(userDao) == null) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
                    return@get
                }
                val rows = trialDao.listAll()
                call.respond(rows.map { it.toDto() })
            }

            // POST /api/admin/trial/revoke — 吊销激活码
            post("/trial/revoke") {
                val admin = call.requireAdmin(userDao)
                if (admin == null) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
                    return@post
                }
                val req = call.receive<TrialCodeRevokeRequest>()
                val code = req.code.trim()
                if (code.isEmpty()) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("激活码不能为空"))
                    return@post
                }
                if (!trialDao.revoke(code)) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("激活码不存在"))
                    return@post
                }
                call.respond(MessageResponse("已吊销"))
            }
        }
    }
}

package com.example.overtimeserver.api

import com.example.overtimeserver.auth.JwtService
import com.example.overtimeserver.auth.PasswordHasher
import com.example.overtimeserver.auth.UserPrincipal
import com.example.overtimeserver.db.UserDao
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route

fun Route.authRoutes(userDao: UserDao, jwt: JwtService) {
    route("/api/auth") {
        post("/login") {
            val req = call.receive<LoginRequest>()
            val username = req.username.trim()
            val user = if (username.isEmpty()) null else userDao.findByUsername(username)
            if (user == null || !user.isActive || !PasswordHasher.verify(req.password, user.passwordHash)) {
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("用户名或密码错误，或账号已停用"))
                return@post
            }
            call.respond(
                LoginResponse(
                    token = jwt.createToken(user.id, user.username),
                    expiresIn = jwt.expiresInSeconds,
                    user = UserDto(user.id, user.username, user.displayName, user.role, user.isActive),
                )
            )
        }

        authenticate("auth-jwt") {
            post("/change-password") {
                val principal = call.principal<UserPrincipal>() ?: return@post
                val user = userDao.findById(principal.userId)
                if (user == null || !user.isActive) {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("账号不存在或已停用"))
                    return@post
                }
                val req = call.receive<ChangePasswordRequest>()
                if (!PasswordHasher.verify(req.oldPassword, user.passwordHash)) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("原密码错误"))
                    return@post
                }
                if (req.newPassword.length < 6) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("新密码至少 6 位"))
                    return@post
                }
                userDao.updatePassword(user.id, PasswordHasher.hash(req.newPassword))
                call.respond(MessageResponse("密码已修改"))
            }
        }
    }
}

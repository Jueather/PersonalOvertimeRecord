package com.example.overtimeserver.auth

import com.example.overtimeserver.db.UserDao
import com.example.overtimeserver.db.UserRow
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.Principal
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.JWTCredential
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.auth.principal

/** JWT 校验通过后的调用方身份 */
data class UserPrincipal(val userId: Long, val username: String) : Principal

fun Application.configureAuth(jwt: JwtService) {
    install(Authentication) {
        jwt("auth-jwt") {
            verifier(jwt.verifier())
            validate { credential: JWTCredential ->
                val uid = credential.payload.getClaim("uid")?.asLong()
                val username = credential.payload.getClaim("username")?.asString()
                if (uid != null && uid > 0 && !username.isNullOrBlank()) {
                    UserPrincipal(uid, username)
                } else {
                    null
                }
            }
        }
    }
}

/**
 * 管理员守卫：不信任 token 内的任何角色信息，实时查库校验 role + is_active。
 * 非管理员返回 null，由调用方 respond 403。
 */
fun ApplicationCall.requireAdmin(userDao: UserDao): UserRow? {
    val principal = principal<UserPrincipal>() ?: return null
    val user = userDao.findById(principal.userId) ?: return null
    return if (user.role == "admin" && user.isActive) user else null
}

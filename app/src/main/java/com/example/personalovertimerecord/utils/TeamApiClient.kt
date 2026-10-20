package com.example.personalovertimerecord.utils

import com.example.personalovertimerecord.data.model.TeamAdminUserDto
import com.example.personalovertimerecord.data.model.TeamApiException
import com.example.personalovertimerecord.data.model.TeamChangePasswordRequest
import com.example.personalovertimerecord.data.model.TeamCreateUserRequest
import com.example.personalovertimerecord.data.model.TeamCreateUserResponse
import com.example.personalovertimerecord.data.model.TeamErrorResponse
import com.example.personalovertimerecord.data.model.TeamLoginRequest
import com.example.personalovertimerecord.data.model.TeamLoginResponse
import com.example.personalovertimerecord.data.model.TeamResetPasswordResponse
import com.example.personalovertimerecord.data.model.TeamSetActiveRequest
import com.example.personalovertimerecord.data.model.TeamSummaryResponse
import com.example.personalovertimerecord.data.model.TeamSyncRequest
import com.example.personalovertimerecord.data.model.TeamSyncResponse
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 团队同步 HTTP 客户端（HttpURLConnection + Gson，风格对齐 WebDAVManager：60s 超时、Bearer 头）。
 *
 * 统一错误映射：IOException/超时 → NETWORK；401 → UNAUTHORIZED；
 * 其它 4xx → CLIENT（message 取服务端 {"error":...}）；5xx → SERVER。
 */
class TeamApiClient(
    private val baseUrl: String,
    private val token: String? = null
) {

    companion object {
        private const val CONNECTION_TIMEOUT_MS = 60_000
        private const val READ_TIMEOUT_MS = 60_000
    }

    private val gson = Gson()

    private fun endpoint(path: String): String = baseUrl.trimEnd('/') + path

    // ================== 认证 ==================

    suspend fun login(username: String, password: String): TeamLoginResponse =
        withContext(Dispatchers.IO) {
            val body = request(
                method = "POST",
                path = "/api/auth/login",
                bodyJson = gson.toJson(TeamLoginRequest(username, password)),
                token = null
            )
            gson.fromJson(body, TeamLoginResponse::class.java)
        }

    suspend fun changePassword(oldPassword: String, newPassword: String) {
        withContext(Dispatchers.IO) {
            request(
                method = "POST",
                path = "/api/auth/change-password",
                bodyJson = gson.toJson(TeamChangePasswordRequest(oldPassword, newPassword)),
                token = token
            )
        }
    }

    // ================== 同步 ==================

    suspend fun sync(request: TeamSyncRequest): TeamSyncResponse = withContext(Dispatchers.IO) {
        val body = request(
            method = "POST",
            path = "/api/sync",
            bodyJson = gson.toJson(request),
            token = token
        )
        gson.fromJson(body, TeamSyncResponse::class.java)
    }

    // ================== 管理员 ==================

    suspend fun listUsers(): List<TeamAdminUserDto> = withContext(Dispatchers.IO) {
        val body = request("GET", "/api/admin/users", bodyJson = null, token = token)
        val type = com.google.gson.reflect.TypeToken.getParameterized(List::class.java, TeamAdminUserDto::class.java).type
        gson.fromJson(body, type)
    }

    suspend fun createUser(username: String, password: String?, displayName: String): TeamCreateUserResponse =
        withContext(Dispatchers.IO) {
            val body = request(
                method = "POST",
                path = "/api/admin/users",
                bodyJson = gson.toJson(TeamCreateUserRequest(username, password, displayName)),
                token = token
            )
            gson.fromJson(body, TeamCreateUserResponse::class.java)
        }

    suspend fun resetPassword(userId: Long): TeamResetPasswordResponse = withContext(Dispatchers.IO) {
        val body = request(
            method = "POST",
            path = "/api/admin/users/$userId/reset-password",
            bodyJson = "{}",
            token = token
        )
        gson.fromJson(body, TeamResetPasswordResponse::class.java)
    }

    suspend fun setActive(userId: Long, active: Boolean) {
        withContext(Dispatchers.IO) {
            request(
                method = "POST",
                path = "/api/admin/users/$userId/set-active",
                bodyJson = gson.toJson(TeamSetActiveRequest(active)),
                token = token
            )
        }
    }

    suspend fun summary(from: String?, to: String?): TeamSummaryResponse = withContext(Dispatchers.IO) {
        val query = buildList {
            if (!from.isNullOrBlank()) add("from=$from")
            if (!to.isNullOrBlank()) add("to=$to")
        }.joinToString("&")
        val path = if (query.isEmpty()) "/api/admin/summary" else "/api/admin/summary?$query"
        val body = request("GET", path, bodyJson = null, token = token)
        gson.fromJson(body, TeamSummaryResponse::class.java)
    }

    // ================== 底层请求 ==================

    private fun request(method: String, path: String, bodyJson: String?, token: String?): String {
        val connection = (URL(endpoint(path)).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECTION_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            if (!token.isNullOrBlank()) {
                setRequestProperty("Authorization", "Bearer $token")
            }
            if (bodyJson != null) {
                doOutput = true
                setFixedLengthStreamingMode(bodyJson.toByteArray(Charsets.UTF_8).size)
            }
        }

        try {
            if (bodyJson != null) {
                connection.outputStream.use { it.write(bodyJson.toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            return if (code in 200..299) {
                connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            } else {
                val errorBody = try {
                    connection.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) }
                } catch (_: IOException) {
                    null
                }
                val serverMessage = parseErrorMessage(errorBody)
                throw when {
                    code == 401 -> TeamApiException(
                        TeamApiException.Kind.UNAUTHORIZED,
                        serverMessage ?: "登录已失效，请重新登录"
                    )
                    code in 400..499 -> TeamApiException(
                        TeamApiException.Kind.CLIENT,
                        serverMessage ?: "请求失败（HTTP $code）"
                    )
                    else -> TeamApiException(
                        TeamApiException.Kind.SERVER,
                        serverMessage ?: "服务器错误（HTTP $code）"
                    )
                }
            }
        } catch (e: TeamApiException) {
            throw e
        } catch (e: IOException) {
            throw TeamApiException(
                TeamApiException.Kind.NETWORK,
                "无法连接服务器：${e.message ?: "网络异常"}",
                e
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun parseErrorMessage(errorBody: String?): String? {
        if (errorBody.isNullOrBlank()) return null
        return try {
            gson.fromJson(errorBody, TeamErrorResponse::class.java)?.error?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }
}

/** TeamApiException → 用户可读文案（UI 统一口径） */
object TeamErrors {
    fun message(e: TeamApiException): String = when (e.kind) {
        TeamApiException.Kind.NETWORK -> "无法连接服务器，请检查网络与服务器地址"
        TeamApiException.Kind.UNAUTHORIZED -> e.message ?: "登录已失效，请重新登录"
        TeamApiException.Kind.CLIENT -> e.message ?: "请求失败"
        TeamApiException.Kind.SERVER -> "服务器错误，请稍后重试"
    }
}

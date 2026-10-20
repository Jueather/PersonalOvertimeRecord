package com.example.overtimeserver

import com.example.overtimeserver.api.ChangePasswordRequest
import com.example.overtimeserver.api.CreateUserRequest
import com.example.overtimeserver.api.CreateUserResponse
import com.example.overtimeserver.api.LoginRequest
import com.example.overtimeserver.api.LoginResponse
import com.example.overtimeserver.api.MessageResponse
import com.example.overtimeserver.api.RecordDto
import com.example.overtimeserver.api.ResetPasswordResponse
import com.example.overtimeserver.api.SetActiveRequest
import com.example.overtimeserver.api.SummaryResponse
import com.example.overtimeserver.api.TeamSyncRequest
import com.example.overtimeserver.api.TeamSyncResponse
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ApiTest {

    private val testJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private suspend fun HttpClient.postJson(path: String, bodyJson: String): HttpResponse =
        post(path) {
            contentType(ContentType.Application.Json)
            setBody(bodyJson)
        }

    private suspend fun HttpClient.bearerPostJson(token: String, path: String, bodyJson: String): HttpResponse =
        post(path) {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(bodyJson)
        }

    private fun withServer(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { module() }
        val tmp = Files.createTempDirectory("overtime-test").toFile()
        environment {
            config = MapApplicationConfig(
                "ktor.deployment.port" to "8080",
                "overtime.dbPath" to File(tmp, "test.db").absolutePath,
                "overtime.jwtSecretFile" to File(tmp, "jwt-secret.key").absolutePath,
                "overtime.adminUsername" to "admin",
                "overtime.jwt.issuer" to "overtime-server",
                "overtime.jwt.audience" to "overtime-app",
                "overtime.jwt.expiryDays" to "30",
            )
        }
        System.setProperty("ADMIN_INITIAL_PASSWORD", "test-password-123")
        try {
            block()
        } finally {
            System.clearProperty("ADMIN_INITIAL_PASSWORD")
            tmp.deleteRecursively()
        }
    }

    @Test
    fun `health is anonymous`() = withServer {
        val resp = client.get("/api/health")
        assertEquals(HttpStatusCode.OK, resp.status)
        assertTrue(resp.bodyAsText().contains("ok"))
    }

    @Test
    fun `admin bootstrap login works and wrong password rejected`() = withServer {
        val bad = client.postJson("/api/auth/login", testJson.encodeToString(LoginRequest("admin", "wrong-password")))
        assertEquals(HttpStatusCode.Unauthorized, bad.status)

        val good = client.postJson("/api/auth/login", testJson.encodeToString(LoginRequest("admin", "test-password-123")))
        assertEquals(HttpStatusCode.OK, good.status)
        val session = testJson.decodeFromString<LoginResponse>(good.bodyAsText())
        assertEquals("admin", session.user.username)
        assertEquals("admin", session.user.role)
        assertTrue(session.token.isNotBlank())
    }

    @Test
    fun `sync merges with newer wins and returns full server state`() = withServer {
        val token = client.loginAdmin()

        val r1 = RecordDto(date = "2026-10-10", note = "v1", createdAt = 1000L, modifiedAt = 1000L, overtimeHours = 2.0)
        val r2 = RecordDto(date = "2026-10-11", note = "keep", createdAt = 1000L, modifiedAt = 1000L)
        val first = client.bearerPostJson(
            token, "/api/sync",
            testJson.encodeToString(TeamSyncRequest(records = listOf(r1, r2), deviceName = "device-a")),
        )
        assertEquals(HttpStatusCode.OK, first.status)
        val firstState = testJson.decodeFromString<TeamSyncResponse>(first.bodyAsText())
        assertEquals(2, firstState.records.size)

        val newer1 = r1.copy(note = "v2", modifiedAt = 2000L)
        val older2 = r2.copy(note = "client-stale", modifiedAt = 500L)
        val second = client.bearerPostJson(
            token, "/api/sync",
            testJson.encodeToString(TeamSyncRequest(records = listOf(newer1, older2), deviceName = "device-b")),
        )
        assertEquals(HttpStatusCode.OK, second.status)
        val secondState = testJson.decodeFromString<TeamSyncResponse>(second.bodyAsText())
        assertEquals(2, secondState.records.size)
        assertEquals("v2", secondState.records.first { it.date == "2026-10-10" }.note)
        assertEquals("keep", secondState.records.first { it.date == "2026-10-11" }.note)

        val tombstone = r1.copy(isDeleted = true, modifiedAt = 3000L)
        val third = client.bearerPostJson(
            token, "/api/sync",
            testJson.encodeToString(TeamSyncRequest(records = listOf(tombstone), deviceName = "device-a")),
        )
        val thirdState = testJson.decodeFromString<TeamSyncResponse>(third.bodyAsText())
        assertEquals(2, thirdState.records.size)
        assertTrue(thirdState.records.first { it.date == "2026-10-10" }.isDeleted)
    }

    @Test
    fun `sync requires auth`() = withServer {
        val resp = client.postJson("/api/sync", testJson.encodeToString(TeamSyncRequest()))
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    @Test
    fun `admin creates member and summary aggregates`() = withServer {
        val adminToken = client.loginAdmin()

        val createResp = client.bearerPostJson(
            adminToken, "/api/admin/users",
            testJson.encodeToString(CreateUserRequest(username = "zhangsan", displayName = "张三")),
        )
        assertEquals(HttpStatusCode.Created, createResp.status)
        val created = testJson.decodeFromString<CreateUserResponse>(createResp.bodyAsText())
        assertNotEquals("zhangsan", created.initialPassword)
        assertTrue(created.initialPassword.length >= 12)
        assertTrue(created.user.isActive)

        val memberToken = client.postJson("/api/auth/login", testJson.encodeToString(LoginRequest("zhangsan", created.initialPassword)))
            .let {
                assertEquals(HttpStatusCode.OK, it.status)
                testJson.decodeFromString<LoginResponse>(it.bodyAsText()).token
            }
        client.bearerPostJson(
            memberToken, "/api/sync",
            testJson.encodeToString(
                TeamSyncRequest(
                    records = listOf(
                        RecordDto(date = "2026-10-12", overtimeHours = 3.5, createdAt = 1L, modifiedAt = 1L),
                        RecordDto(date = "2026-10-13", isLeave = true, leaveHours = 8.0, createdAt = 1L, modifiedAt = 1L),
                    )
                )
            ),
        )

        assertEquals(HttpStatusCode.Forbidden, client.get("/api/admin/summary") { bearerAuth(memberToken) }.status)

        val summaryResp = client.get("/api/admin/summary?from=202610&to=202610") { bearerAuth(adminToken) }
        assertEquals(HttpStatusCode.OK, summaryResp.status)
        val summary = testJson.decodeFromString<SummaryResponse>(summaryResp.bodyAsText())
        val entry = summary.entries.first { it.username == "zhangsan" && it.month == "202610" }
        assertEquals(3.5, entry.overtimeHours)
        assertEquals(8.0, entry.leaveHours)
        assertEquals(2, entry.recordCount)
        assertEquals(1, entry.leaveCount)

        val userId = created.user.id
        val resetResp = client.bearerPostJson(adminToken, "/api/admin/users/$userId/reset-password", "{}")
        assertEquals(HttpStatusCode.OK, resetResp.status)
        val newPwd = testJson.decodeFromString<ResetPasswordResponse>(resetResp.bodyAsText()).initialPassword
        assertNotEquals(created.initialPassword, newPwd)
        assertEquals(
            HttpStatusCode.OK,
            client.postJson("/api/auth/login", testJson.encodeToString(LoginRequest("zhangsan", newPwd))).status,
        )

        client.bearerPostJson(
            adminToken, "/api/admin/users/$userId/set-active",
            testJson.encodeToString(SetActiveRequest(isActive = false)),
        )
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.postJson("/api/auth/login", testJson.encodeToString(LoginRequest("zhangsan", newPwd))).status,
        )
    }

    @Test
    fun `change password works`() = withServer {
        val token = client.loginAdmin()
        val resp = client.bearerPostJson(
            token, "/api/auth/change-password",
            testJson.encodeToString(ChangePasswordRequest("test-password-123", "brand-new-pwd")),
        )
        assertEquals(HttpStatusCode.OK, resp.status)
        assertEquals("密码已修改", testJson.decodeFromString<MessageResponse>(resp.bodyAsText()).message)
        assertEquals(
            HttpStatusCode.OK,
            client.postJson("/api/auth/login", testJson.encodeToString(LoginRequest("admin", "brand-new-pwd"))).status,
        )
    }

    private suspend fun io.ktor.client.HttpClient.loginAdmin(): String {
        val resp = postJson("/api/auth/login", testJson.encodeToString(LoginRequest("admin", "test-password-123")))
        assertEquals(HttpStatusCode.OK, resp.status)
        return testJson.decodeFromString<LoginResponse>(resp.bodyAsText()).token
    }
}

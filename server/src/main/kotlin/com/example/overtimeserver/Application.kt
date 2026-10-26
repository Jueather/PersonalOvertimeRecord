package com.example.overtimeserver

import com.example.overtimeserver.api.ErrorResponse
import com.example.overtimeserver.api.adminRoutes
import com.example.overtimeserver.api.authRoutes
import com.example.overtimeserver.api.healthRoutes
import com.example.overtimeserver.api.syncRoutes
import com.example.overtimeserver.api.trialRoutes
import com.example.overtimeserver.auth.JwtService
import com.example.overtimeserver.auth.configureAuth
import com.example.overtimeserver.bootstrap.AdminBootstrap
import com.example.overtimeserver.config.AppConfig
import com.example.overtimeserver.db.Database
import com.example.overtimeserver.db.RecordDao
import com.example.overtimeserver.db.Schema
import com.example.overtimeserver.db.TrialCodeDao
import com.example.overtimeserver.db.UserDao
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json

fun main(args: Array<String>): Unit = io.ktor.server.netty.EngineMain.main(args)

fun Application.module() {
    val appConfig = AppConfig.from(environment.config)

    val db = Database(appConfig.dbPath)
    db.withConnection { Schema.createTables(it) }
    monitor.subscribe(ApplicationStopped) { db.close() }

    val userDao = UserDao(db)
    val recordDao = RecordDao(db)
    val trialDao = TrialCodeDao(db)
    val jwt = JwtService(
        secretFile = appConfig.jwtSecretFile,
        issuer = appConfig.jwtIssuer,
        audience = appConfig.jwtAudience,
        expiryDays = appConfig.jwtExpiryDays,
    )

    AdminBootstrap.ensureAdmin(userDao, appConfig.adminUsername)

    install(ContentNegotiation) {
        json(Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        })
    }

    install(StatusPages) {
        exception<BadRequestException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(cause.message ?: "请求格式错误"))
        }
        exception<Throwable> { call, cause ->
            call.application.log.error("未处理异常", cause)
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("服务器内部错误"))
        }
    }

    configureAuth(jwt)

    routing {
        healthRoutes()
        authRoutes(userDao, jwt)
        trialRoutes(trialDao)
        syncRoutes(recordDao)
        adminRoutes(userDao, recordDao, trialDao)
    }

    environment.log.info("加班记录服务端已启动：db=${appConfig.dbPath}")
}

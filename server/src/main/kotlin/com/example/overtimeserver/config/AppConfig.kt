package com.example.overtimeserver.config

import io.ktor.server.config.ApplicationConfig

/** overtime.* 配置段 */
data class AppConfig(
    val dbPath: String,
    val jwtSecretFile: String,
    val adminUsername: String,
    val jwtIssuer: String,
    val jwtAudience: String,
    val jwtExpiryDays: Long,
) {
    companion object {
        fun from(config: ApplicationConfig): AppConfig {
            val o = config.config("overtime")
            return AppConfig(
                dbPath = o.property("dbPath").getString(),
                jwtSecretFile = o.property("jwtSecretFile").getString(),
                adminUsername = o.property("adminUsername").getString(),
                jwtIssuer = o.property("jwt.issuer").getString(),
                jwtAudience = o.property("jwt.audience").getString(),
                jwtExpiryDays = o.property("jwt.expiryDays").getString().toLong(),
            )
        }
    }
}

package com.example.overtimeserver.api

import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

fun Route.healthRoutes() {
    get("/api/health") {
        call.respond(HealthResponse(status = "ok", time = System.currentTimeMillis()))
    }
}

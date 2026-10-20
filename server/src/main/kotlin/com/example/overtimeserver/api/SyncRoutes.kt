package com.example.overtimeserver.api

import com.example.overtimeserver.auth.UserPrincipal
import com.example.overtimeserver.db.RecordDao
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** per-user 互斥，兜底同一账号并发同步交错（DB 层事务保证单批原子性） */
private val userSyncLocks = ConcurrentHashMap<Long, Mutex>()

fun Route.syncRoutes(recordDao: RecordDao) {
    authenticate("auth-jwt") {
        post("/api/sync") {
            val principal = call.principal<UserPrincipal>() ?: return@post
            val request = call.receive<TeamSyncRequest>()
            val lock = userSyncLocks.computeIfAbsent(principal.userId) { Mutex() }
            val records = lock.withLock {
                recordDao.upsertBatch(principal.userId, request.records)
            }
            call.respond(TeamSyncResponse(serverTime = System.currentTimeMillis(), records = records))
        }
    }
}

package com.example.personalovertimerecord.utils

import kotlinx.coroutines.sync.withLock

/**
 * 共享同步闸门：团队同步（TeamSyncManager）、WebDAV 同步（SyncManager）与
 * 日历同步（CalendarSyncManager）都写同一个 Room 库，必须互斥执行。
 * SyncManager 原有的 syncMutex 是 private，此处提供双方共用的外层锁。
 *
 * 锁顺序约定：SyncGate 在外层，SyncManager.syncMutex 在内层（TeamSyncManager 与
 * CalendarSyncManager 不持有内层锁，无死锁环）。
 */
object SyncGate {

    private val mutex = kotlinx.coroutines.sync.Mutex()

    suspend fun <T> lock(block: suspend () -> T): T = mutex.withLock { block() }
}

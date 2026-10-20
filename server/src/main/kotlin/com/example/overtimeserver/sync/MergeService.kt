package com.example.overtimeserver.sync

import com.example.overtimeserver.api.RecordDto

/**
 * NEWER_WINS 合并：与客户端 utils/RecordMerge.kt 语义一致的 JVM 移植。
 *
 * - 以 date 为键；modifiedAt 缺失(<=0)时用 createdAt 兜底；
 * - 墓碑（isDeleted=true）与活跃记录同等参与，保证删除跨设备收敛；
 * - 同日期重复只保留第一条（防御脏数据）；
 * - 云端时间严格大于服务端才取云端，相等/更旧保留服务端。
 */
object MergeService {

    /** 返回胜者记录 */
    fun mergeRecord(server: RecordDto?, incoming: RecordDto): RecordDto {
        if (server == null) return incoming
        if (server.date != incoming.date) return server
        val serverTime = if (server.modifiedAt > 0) server.modifiedAt else server.createdAt
        val incomingTime = if (incoming.modifiedAt > 0) incoming.modifiedAt else incoming.createdAt
        return if (incomingTime > serverTime) incoming else server
    }

    /** 列表合并（date 为键，先到先得防重复） */
    fun mergeList(base: List<RecordDto>, incoming: List<RecordDto>): List<RecordDto> {
        val baseByDate = HashMap<String, RecordDto>()
        for (b in base) {
            if (b.date.isNotBlank() && b.date !in baseByDate) baseByDate[b.date] = b
        }
        val merged = ArrayList<RecordDto>(base.size + incoming.size)
        val handled = HashSet<String>()
        for (c in incoming) {
            if (c.date.isBlank() || !handled.add(c.date)) continue
            val existing = baseByDate[c.date]
            merged.add(if (existing == null) c else mergeRecord(existing, c))
        }
        for (b in base) {
            if (b.date.isNotBlank() && handled.add(b.date)) merged.add(b)
        }
        return merged
    }
}

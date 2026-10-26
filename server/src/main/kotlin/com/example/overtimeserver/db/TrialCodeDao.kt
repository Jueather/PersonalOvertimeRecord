package com.example.overtimeserver.db

import java.security.SecureRandom
import java.sql.Connection

data class TrialCodeRow(
    val id: Long,
    val code: String,
    val trialDays: Int,
    val maxUses: Int,
    val usedCount: Int,
    val boundDeviceId: String?,
    val activatedAt: Long?,
    val expiresAt: Long?,
    val createdAt: Long,
    val createdBy: String,
    val isRevoked: Boolean,
)

class TrialCodeDao(private val db: Database) {

    companion object {
        private const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789" // 排除 0/O/1/I/L
        private const val CODE_SEGMENTS = 4
        private const val SEGMENT_LENGTH = 4
        private val RANDOM = SecureRandom()

        /** 生成一段码：XXXX-XXXX-XXXX-XXXX（16 字符 + 3 个连字符） */
        fun generateCode(random: SecureRandom = RANDOM): String {
            val sb = StringBuilder()
            repeat(CODE_SEGMENTS) { seg ->
                if (seg > 0) sb.append('-')
                repeat(SEGMENT_LENGTH) {
                    sb.append(ALPHABET[random.nextInt(ALPHABET.length)])
                }
            }
            return sb.toString()
        }

        /** 去除连字符并大写，兼容用户输入时可能遗漏的分隔符 */
        fun normalizeCode(raw: String): String =
            raw.trim().filter { it.isLetterOrDigit() }.uppercase()
    }

    fun findByCode(code: String): TrialCodeRow? {
        val normalized = normalizeCode(code)
        return db.withConnection { findByCode(it, normalized) }
    }

    fun findByDeviceId(deviceId: String): TrialCodeRow? = db.withConnection { conn ->
        conn.prepareStatement(
            "SELECT * FROM trial_codes WHERE bound_device_id = ? AND is_revoked = 0 ORDER BY id DESC LIMIT 1"
        ).use { ps ->
            ps.setString(1, deviceId)
            ps.executeQuery().use { rs ->
                if (rs.next()) mapRow(rs) else null
            }
        }
    }

    /** 管理员批量生成 count 个码，每个码 trialDays 天 */
    fun batchGenerate(count: Int, trialDays: Int, maxUses: Int, createdBy: String): List<TrialCodeRow> {
        require(count in 1..500) { "批量生成数量需在 1-500 之间" }
        require(trialDays in 1..3650) { "试用天数需在 1-3650 之间" }
        require(maxUses in 1..100) { "最大使用次数需在 1-100 之间" }
        val now = System.currentTimeMillis()
        val created = mutableListOf<TrialCodeRow>()
        db.transaction { conn ->
            val ps = conn.prepareStatement(
                "INSERT INTO trial_codes(code, trial_days, max_uses, used_count, bound_device_id, activated_at, expires_at, created_at, created_by, is_revoked)" +
                    " VALUES(?,?,?,?,?,?,?,?,?,0)"
            )
            repeat(count) {
                val firstCode = generateCode()
                // 虽然 UNIQUE 约束会兜底，但多线程下重复概率极低，这里再试一下
                var row: TrialCodeRow? = null
                var insertedCode = firstCode
                for (attempt in 1..3) {
                    ps.clearParameters()
                    insertedCode = if (attempt == 1) firstCode else generateCode()
                    ps.setString(1, insertedCode)
                    ps.setInt(2, trialDays)
                    ps.setInt(3, maxUses)
                    ps.setInt(4, 0)
                    ps.setNull(5, java.sql.Types.VARCHAR)
                    ps.setNull(6, java.sql.Types.BIGINT)
                    ps.setNull(7, java.sql.Types.BIGINT)
                    ps.setLong(8, now)
                    ps.setString(9, createdBy)
                    val affected = ps.executeUpdate()
                    if (affected == 1) {
                        row = findByCode(conn, insertedCode)
                        break
                    }
                }
                if (row != null) created.add(row)
            }
        }
        return created
    }

    /**
     * 激活码兑换（事务原子性）。返回值：
     *   - Success(row)：激活成功
     *   - Revoked     ：码已被吊销
     *   - UsedUp      ：已达最大使用次数
     *   - AlreadyBound ：已绑定其他设备
     *   - NotExist    ：码不存在
     */
    sealed class RedeemResult {
        data class Success(val row: TrialCodeRow, val remainingMs: Long) : RedeemResult()
        data object Revoked : RedeemResult()
        data object UsedUp : RedeemResult()
        data object AlreadyBound : RedeemResult()
        data object NotExist : RedeemResult()
        data class DeviceHasActive(val row: TrialCodeRow) : RedeemResult()
    }

    fun redeem(code: String, deviceId: String, nowMs: Long = System.currentTimeMillis()): RedeemResult {
        val normalized = normalizeCode(code)
        return db.transaction { conn ->
            val row = findByCode(conn, normalized) ?: return@transaction RedeemResult.NotExist
            if (row.isRevoked) return@transaction RedeemResult.Revoked
            if (row.boundDeviceId != null && row.boundDeviceId != deviceId) {
                return@transaction RedeemResult.AlreadyBound
            }
            if (row.usedCount >= row.maxUses) return@transaction RedeemResult.UsedUp

            // 一机一码：设备已有未过期激活 → 不再消耗新码
            val existing = findActiveByDevice(conn, deviceId, nowMs)
            if (existing != null) {
                return@transaction RedeemResult.DeviceHasActive(existing)
            }

            val activatedAt = nowMs
            val expiresAt = activatedAt + row.trialDays * 24L * 60L * 60L * 1000L
            conn.prepareStatement(
                "UPDATE trial_codes SET bound_device_id = ?, used_count = used_count + 1, activated_at = ?, expires_at = ? WHERE id = ?"
            ).use { ps ->
                ps.setString(1, deviceId)
                ps.setLong(2, activatedAt)
                ps.setLong(3, expiresAt)
                ps.setLong(4, row.id)
                ps.executeUpdate()
            }
            val updated = findByCode(conn, normalized)!!
            RedeemResult.Success(updated, (expiresAt - nowMs).coerceAtLeast(0L))
        }
    }

    /** 查询指定设备当前的激活状态，返回 null 表示无有效激活 */
    fun findActiveByDevice(deviceId: String, nowMs: Long = System.currentTimeMillis()): TrialCodeRow? = db.withConnection { conn ->
        findActiveByDevice(conn, deviceId, nowMs)
    }

    private fun findActiveByDevice(conn: Connection, deviceId: String, nowMs: Long): TrialCodeRow? {
        conn.prepareStatement(
            "SELECT * FROM trial_codes WHERE bound_device_id = ? AND is_revoked = 0 AND expires_at > ? ORDER BY expires_at DESC LIMIT 1"
        ).use { ps ->
            ps.setString(1, deviceId)
            ps.setLong(2, nowMs)
            ps.executeQuery().use { rs ->
                return if (rs.next()) mapRow(rs) else null
            }
        }
    }

    /** 按设备查询历史记录（含已过期） */
    fun findAllByDevice(deviceId: String): List<TrialCodeRow> = db.withConnection { conn ->
        conn.prepareStatement(
            "SELECT * FROM trial_codes WHERE bound_device_id = ? ORDER BY expires_at DESC"
        ).use { ps ->
            ps.setString(1, deviceId)
            ps.executeQuery().use { rs ->
                buildList { while (rs.next()) add(mapRow(rs)) }
            }
        }
    }

    /** 吊销激活码（管理员） */
    fun revoke(code: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val normalized = normalizeCode(code)
        return db.withConnection { conn ->
            conn.prepareStatement("UPDATE trial_codes SET is_revoked = 1 WHERE code = ?").use { ps ->
                ps.setString(1, normalized)
                ps.executeUpdate() > 0
            }
        }
    }

    /** 列出所有码（管理员） */
    fun listAll(): List<TrialCodeRow> = db.withConnection { conn ->
        conn.createStatement().use { st ->
            st.executeQuery("SELECT * FROM trial_codes ORDER BY id DESC").use { rs ->
                buildList { while (rs.next()) add(mapRow(rs)) }
            }
        }
    }

    // ========== 内部辅助 ==========

    private fun findByCode(conn: Connection, code: String): TrialCodeRow? {
        conn.prepareStatement("SELECT * FROM trial_codes WHERE code = ?").use { ps ->
            ps.setString(1, code)
            ps.executeQuery().use { rs ->
                return if (rs.next()) mapRow(rs) else null
            }
        }
    }

    private fun mapRow(rs: java.sql.ResultSet): TrialCodeRow = TrialCodeRow(
        id = rs.getLong("id"),
        code = rs.getString("code"),
        trialDays = rs.getInt("trial_days"),
        maxUses = rs.getInt("max_uses"),
        usedCount = rs.getInt("used_count"),
        boundDeviceId = rs.getString("bound_device_id"),
        activatedAt = rs.getLong("activated_at").let { if (rs.wasNull()) null else it },
        expiresAt = rs.getLong("expires_at").let { if (rs.wasNull()) null else it },
        createdAt = rs.getLong("created_at"),
        createdBy = rs.getString("created_by"),
        isRevoked = rs.getInt("is_revoked") == 1,
    )
}

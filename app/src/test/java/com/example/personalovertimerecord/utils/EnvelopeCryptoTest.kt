package com.example.personalovertimerecord.utils

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 信封加密（密码 + 恢复码双钥匙）测试。
 * 走字节层 internal API，避免 JVM 单测依赖 android.util.Base64。
 */
class EnvelopeCryptoTest {

    private val plaintext = """{"version":3,"records":[{"date":"2026-10-01"}]}""".toByteArray(Charsets.UTF_8)
    private val password = "Sync-Pass-2026"
    private val recoveryCode = RecoveryCodeManager.generate()

    private fun buildBlob(): ByteArray {
        val dek = EnvelopeCrypto.generateDek()
        assertEquals(32, dek.size)
        val pwdSlot = EnvelopeCrypto.wrapDek(dek, password)
        val recSlot = EnvelopeCrypto.wrapDek(dek, RecoveryCodeManager.normalize(recoveryCode))
        return EnvelopeCrypto.buildEnvelopeBytesWithSlots(dek, pwdSlot, recSlot, plaintext)
    }

    @Test
    fun dekWrap_unwrapWithCorrectSecret() {
        val dek = EnvelopeCrypto.generateDek()
        val slot = EnvelopeCrypto.wrapDek(dek, "secret")
        assertArrayEquals(dek, EnvelopeCrypto.unwrapDek(slot, "secret"))
        assertNull(EnvelopeCrypto.unwrapDek(slot, "wrong"))
    }

    @Test
    fun wrappedSlot_tamperedBytesFailAuthentication() {
        val dek = EnvelopeCrypto.generateDek()
        val slot = EnvelopeCrypto.wrapDek(dek, "secret")
        // 翻转 wrapped 密文最后一个字节（GCM tag 区域），认证必须失败
        val tampered = slot.wrapped.copyOf()
        tampered[tampered.size - 1] = (tampered[tampered.size - 1].toInt() xor 0x01).toByte()
        assertNull(EnvelopeCrypto.unwrapDek(slot.copy(wrapped = tampered), "secret"))
    }

    @Test
    fun envelope_decryptByPasswordAndRecoveryCode() {
        val blob = buildBlob()
        assertArrayEquals(plaintext, EnvelopeCrypto.decryptBytesWithPassword(blob, password))
        assertArrayEquals(plaintext, EnvelopeCrypto.decryptBytesWithRecoveryCode(blob, recoveryCode))
    }

    @Test
    fun envelope_recoveryCodeAcceptsDisplayFormatAndLowercase() {
        val blob = buildBlob()
        // 用户照抄时可能带连字符、小写、多余空格，规范化后必须仍能解开
        val sloppy = " " + recoveryCode.lowercase().replace("-", " ") + " "
        assertArrayEquals(plaintext, EnvelopeCrypto.decryptBytesWithRecoveryCode(blob, sloppy))
    }

    @Test
    fun envelope_wrongPasswordAndWrongCodeFail() {
        val blob = buildBlob()
        assertNull(EnvelopeCrypto.decryptBytesWithPassword(blob, "wrong-password"))
        assertNull(EnvelopeCrypto.decryptBytesWithRecoveryCode(blob, RecoveryCodeManager.generate()))
    }

    @Test
    fun envelope_tamperedPayloadFails() {
        val blob = buildBlob()
        val tampered = blob.copyOf()
        tampered[tampered.size - 1] = (tampered[tampered.size - 1].toInt() xor 0xFF).toByte()
        assertNull(EnvelopeCrypto.decryptBytesWithPassword(tampered, password))
        assertNull(EnvelopeCrypto.decryptBytesWithRecoveryCode(tampered, recoveryCode))
    }

    @Test
    fun envelope_malformedBlobReturnsNull() {
        assertNull(EnvelopeCrypto.decryptBytesWithPassword(ByteArray(10), password))
        assertNull(EnvelopeCrypto.decryptBytesWithPassword("XXXX".toByteArray(), password))
    }

    /**
     * 核心找回场景：遗忘密码后用恢复码取出 DEK 与恢复槽，设置新密码并重建信封，
     * 恢复槽原样保留 → 新密码可用、恢复码仍可用、旧密码失效。
     */
    @Test
    fun recoveryFlow_resetPasswordKeepsRecoveryCodeValid() {
        val oldBlob = buildBlob()

        // 1. 用恢复码取出 DEK + 恢复槽
        val recovered = EnvelopeCrypto.extractFromBytesWithRecoveryCode(oldBlob, recoveryCode)
        assertNotNull(recovered)
        val (dek, recSlot) = recovered!!

        // 2. 设置新密码，仅重新包裹密码槽，恢复槽字节原样透传
        val newPassword = "New-Sync-Pass-999"
        val newPwdSlot = EnvelopeCrypto.wrapDek(dek, newPassword)
        val newBlob = EnvelopeCrypto.buildEnvelopeBytesWithSlots(dek, newPwdSlot, recSlot, plaintext)

        // 3. 新密码可解
        assertArrayEquals(plaintext, EnvelopeCrypto.decryptBytesWithPassword(newBlob, newPassword))
        // 4. 恢复码不受换密码影响，继续可解
        assertArrayEquals(plaintext, EnvelopeCrypto.decryptBytesWithRecoveryCode(newBlob, recoveryCode))
        // 5. 旧密码失效
        assertNull(EnvelopeCrypto.decryptBytesWithPassword(newBlob, password))
    }

    /**
     * 新设备接入场景：用同步密码取出 DEK 与恢复槽后重建信封（上传续传），
     * 设备本身不知道恢复码明文，但恢复槽保留使恢复码持续有效。
     */
    @Test
    fun newDevice_bootstrapWithPasswordPreservesRecoverySlot() {
        val blob = buildBlob()
        val (dek, recSlot) = EnvelopeCrypto.extractFromBytesWithPassword(blob, password)!!
        val rebuilt = EnvelopeCrypto.buildEnvelopeBytesWithSlots(
            dek,
            EnvelopeCrypto.wrapDek(dek, password),
            recSlot,
            plaintext
        )
        assertArrayEquals(plaintext, EnvelopeCrypto.decryptBytesWithPassword(rebuilt, password))
        assertArrayEquals(plaintext, EnvelopeCrypto.decryptBytesWithRecoveryCode(rebuilt, recoveryCode))
    }

    /** 一次解析：openBytesWithPassword 同时得到明文 + DEK + 双槽（同步链路的性能路径） */
    @Test
    fun openBytesWithPassword_returnsPlaintextDekAndBothSlots() {
        val dek = EnvelopeCrypto.generateDek()
        val pwdSlot = EnvelopeCrypto.wrapDek(dek, password)
        val recSlot = EnvelopeCrypto.wrapDek(dek, RecoveryCodeManager.normalize(recoveryCode))
        val blob = EnvelopeCrypto.buildEnvelopeBytesWithSlots(dek, pwdSlot, recSlot, plaintext)

        val opened = EnvelopeCrypto.openBytesWithPassword(blob, password)
        assertNotNull(opened)
        assertArrayEquals(plaintext, opened!!.plaintext)
        assertArrayEquals(dek, opened.dek)
        assertEquals(pwdSlot, opened.pwdSlot)
        assertEquals(recSlot, opened.recSlot)

        assertNull(EnvelopeCrypto.openBytesWithPassword(blob, "wrong-password"))
        // 只取槽位、不解正文的路径
        val slots = EnvelopeCrypto.extractSlotsFromBytesWithPassword(blob, password)
        assertNotNull(slots)
        assertArrayEquals(dek, slots!!.first)
        assertEquals(pwdSlot, slots.second)
        assertEquals(recSlot, slots.third)
        assertNull(EnvelopeCrypto.extractSlotsFromBytesWithPassword(blob, "wrong-password"))
    }

    /** 密码槽缓存复用的前提：wrapDek 每次产生随机 salt，复用必须显式透传旧槽字节 */
    @Test
    fun wrapDek_sameSecretProducesDifferentSlots_soReuseMustPassBytesThrough() {
        val dek = EnvelopeCrypto.generateDek()
        val slot1 = EnvelopeCrypto.wrapDek(dek, password)
        val slot2 = EnvelopeCrypto.wrapDek(dek, password)
        // 随机 salt 保证两次包裹不同 → 上传时若不复用缓存，恢复槽/密码槽字节会漂移
        assertArrayEquals(dek, EnvelopeCrypto.unwrapDek(slot2, password))
        org.junit.Assert.assertFalse(
            slot1.toBytes().contentEquals(slot2.toBytes())
        )
        // 复用同一组槽字节构建信封：双槽区域（头部 157 字节）保持字节稳定
        val recSlot = recSlotFor(dek)
        val blob1 = EnvelopeCrypto.buildEnvelopeBytesWithSlots(dek, slot1, recSlot, plaintext)
        val blob2 = EnvelopeCrypto.buildEnvelopeBytesWithSlots(dek, slot1, recSlot, plaintext)
        assertArrayEquals(blob1.copyOfRange(0, 157), blob2.copyOfRange(0, 157))
    }

    private fun recSlotFor(dek: ByteArray): EnvelopeCrypto.WrappedSlot =
        EnvelopeCrypto.wrapDek(dek, RecoveryCodeManager.normalize(recoveryCode))
}

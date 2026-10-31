package com.example.personalovertimerecord.utils

import android.util.Base64
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * 云端备份的体积优化：JSON 明文先 gzip 压缩再上传。
 *
 * 三种落地形态（解码端统一嗅探，旧格式无需迁移）：
 * - 开启同步加密：gzip 后的字节作为信封正文，再加密；解出的明文字节按 gzip magic 判断是否解压，
 *   因此旧版未压缩信封仍可直接读取
 * - 未开启同步加密：`PORE_GZ1:` + Base64(gzip(JSON))，若压缩后反而更大则退回原 JSON
 * - 历史格式：明文 JSON / 旧版裸 Base64 密文保持原样
 *
 * 兼容性说明：新版本可读全部旧格式；旧版本（v0.2.6 及更早）无法读取压缩后的新格式。
 */
object BackupCompression {

    /** 未加密备份的压缩线路前缀，与明文 JSON、信封前缀均不冲突 */
    const val WIRE_PREFIX = "PORE_GZ1:"

    /** 解压后的明文上限，防止异常文件/压缩炸弹 */
    private const val MAX_PLAINTEXT_BYTES = 64 * 1024 * 1024

    private const val GZIP_MAGIC_0 = 0x1f.toByte()
    private const val GZIP_MAGIC_1 = 0x8b.toByte()

    private const val BUFFER_SIZE = 8 * 1024

    fun compress(bytes: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output, BUFFER_SIZE).use { gzip ->
            gzip.write(bytes)
            gzip.finish()
        }
        return output.toByteArray()
    }

    fun isGzip(bytes: ByteArray): Boolean =
        bytes.size >= 2 && bytes[0] == GZIP_MAGIC_0 && bytes[1] == GZIP_MAGIC_1

    /** 非 gzip 数据原样返回（兼容旧版未压缩信封） */
    fun decompress(bytes: ByteArray): ByteArray {
        if (!isGzip(bytes)) return bytes
        val output = ByteArrayOutputStream()
        GZIPInputStream(ByteArrayInputStream(bytes), BUFFER_SIZE).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (output.size() + read > MAX_PLAINTEXT_BYTES) {
                    throw IllegalStateException("解压后数据超过上限")
                }
                output.write(buffer, 0, read)
            }
        }
        return output.toByteArray()
    }

    /** 信封解密后的明文字节：新格式为 gzip，旧格式为原始 JSON */
    fun decodePlaintext(bytes: ByteArray): String = String(decompress(bytes), Charsets.UTF_8)

    /** 未加密上传：压缩后更小才使用 `PORE_GZ1:` 线路格式，否则原样返回 JSON */
    fun encodePlaintext(json: String): String {
        val raw = json.toByteArray(Charsets.UTF_8)
        val encoded = WIRE_PREFIX + Base64.encodeToString(compress(raw), Base64.NO_WRAP)
        return if (encoded.length < raw.size) encoded else json
    }

    /** 是否为未加密的压缩线路格式 */
    fun isCompressedWire(content: String): Boolean = content.trimStart().startsWith(WIRE_PREFIX)

    /** 解析未加密的压缩线路格式；非该格式返回 null */
    fun decodeCompressedWire(content: String): String {
        val payload = content.trimStart().removePrefix(WIRE_PREFIX)
        return String(decompress(Base64.decode(payload, Base64.NO_WRAP)), Charsets.UTF_8)
    }
}

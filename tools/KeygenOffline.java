import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneOffset;

/**
 * 离线激活码生成器（纯 Java，零依赖，直接 JDK 编译运行）。
 *
 * 编译: javac KeygenOffline.java
 * 运行: java KeygenOffline <deviceId> [days] [count]
 *
 * 示例:
 *   java KeygenOffline ABCD1234EFGH5678 90 5
 *   (输出 5 个 90 天离线码，绑定指定 deviceId)
 */
public class KeygenOffline {

    // ====== 与 App OfflineCodeValidator.kt 完全一致的常量 ======
    private static final String VERIFY_KEY = "OTR-LICENSE-HMAC-SECRET-2026";
    private static final long EPOCH_DAY_ORIGIN_MS = 1_735_689_600_000L; // 2025-01-01 00:00:00 UTC
    private static final String B32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    // ====== Base32 ======
    private static String toBase32(byte[] data) {
        if (data.length == 0) return "";
        StringBuilder sb = new StringBuilder();
        int bits = 0, buffer = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xFF);
            bits += 8;
            while (bits >= 5) {
                bits -= 5;
                sb.append(B32_ALPHABET.charAt((buffer >> bits) & 0x1F));
            }
        }
        if (bits > 0) sb.append(B32_ALPHABET.charAt((buffer << (5 - bits)) & 0x1F));
        return sb.toString();
    }

    // ====== Payload 编解码（与 App 端结构完全镜像） ======
    private static byte[] encodePayload(int trialDays, int deviceIdHashPrefix, int expiryEpochDays) {
        if (trialDays < 1 || trialDays > 65535) throw new IllegalArgumentException("trialDays 越界");
        if (expiryEpochDays < 0 || expiryEpochDays > 65535) throw new IllegalArgumentException("expiryEpochDays 越界");
        byte[] p = new byte[8];
        p[0] = (byte) (trialDays & 0xFF);
        p[1] = (byte) ((trialDays >> 8) & 0xFF);
        p[2] = (byte) ((deviceIdHashPrefix >> 24) & 0xFF);
        p[3] = (byte) ((deviceIdHashPrefix >> 16) & 0xFF);
        p[4] = (byte) ((deviceIdHashPrefix >> 8) & 0xFF);
        p[5] = (byte) (deviceIdHashPrefix & 0xFF);
        p[6] = (byte) (expiryEpochDays & 0xFF);
        p[7] = (byte) ((expiryEpochDays >> 8) & 0xFF);
        return p;
    }

    private static int deviceIdHashPrefix(String deviceId) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] hash = md.digest(deviceId.getBytes("UTF-8"));
        return ((hash[0] & 0xFF) << 24) | ((hash[1] & 0xFF) << 16) | ((hash[2] & 0xFF) << 8) | (hash[3] & 0xFF);
    }

    private static byte[] hmacSha256(byte[] data, String key) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key.getBytes("UTF-8"), "HmacSHA256"));
        return mac.doFinal(data);
    }

    /** 单个离线码生成 */
    public static String generateCode(int trialDays, String deviceId) throws Exception {
        int devicePrefix = deviceIdHashPrefix(deviceId);
        long expiryMs = System.currentTimeMillis() + (long) trialDays * 24L * 60L * 60L * 1000L;
        int expiryEpochDays = (int) ((expiryMs - EPOCH_DAY_ORIGIN_MS) / (24L * 60L * 60L * 1000L));
        byte[] payload = encodePayload(trialDays, devicePrefix, expiryEpochDays);
        byte[] fullSig = hmacSha256(payload, VERIFY_KEY);
        byte[] sigPrefix = new byte[8];
        System.arraycopy(fullSig, 0, sigPrefix, 0, 8);
        String sigPart = toBase32(sigPrefix);
        String payloadPart = toBase32(payload);
        return payloadPart + "-" + sigPart;
    }

    // ====== CLI ======
    public static void main(String[] args) throws Exception {
        if (args.length == 0 || "-h".equals(args[0]) || "--help".equals(args[0])) {
            System.out.println("""
                Offline Activation Code Generator (纯 Java, 零依赖)
                ===================================================
                编译: javac KeygenOffline.java
                运行: java KeygenOffline <deviceId> [days] [count]

                  deviceId : 从 App 设置页 → 软件激活 → "复制" 按钮获取
                  days     : 试用天数, 默认 30
                  count    : 批量生成数量, 默认 1

                示例:
                  java KeygenOffline ABCD1234EFGH5678 90 5
                  (生成 5 个 90 天离线码, 绑定指定 deviceId)
                """);
            return;
        }
        String deviceId = args[0].trim();
        int days = 30, count = 1;
        if (args.length > 1) {
            try { days = Integer.parseInt(args[1]); } catch (NumberFormatException ignored) {}
        }
        if (args.length > 2) {
            try { count = Integer.parseInt(args[2]); } catch (NumberFormatException ignored) {}
        }
        days = Math.max(1, Math.min(3650, days));
        count = Math.max(1, Math.min(500, count));

        System.out.println();
        System.out.println("🔑 离线激活码生成器 (纯 Java, 零依赖)");
        System.out.println("  目标设备   : " + deviceId);
        System.out.println("  试用时长   : " + days + " 天");
        System.out.println("  生成数量   : " + count);
        System.out.println("  生成时间   : " + Instant.now().toString());
        System.out.println("  ------------------------------------------------");
        for (int i = 0; i < count; i++) {
            String code = generateCode(days, deviceId);
            System.out.printf("  %02d.  %s%n", i + 1, code);
        }
        System.out.println("  ------------------------------------------------");
        System.out.println("  码内含 HMAC-SHA256 签名 + 绝对过期时间 + 设备绑定");
        System.out.println("  改系统时钟不会续期，跨设备无法使用。");
        System.out.println();
    }
}

package io.github.zhoutaoq.shadowflow.biz.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

/**
 * 口令加盐哈希。
 *
 * <p>项目没有引入 Spring Security，这里用 JDK 自带的 SHA-256 做「随机 salt + 一次摘要」。
 * 它挡不住针对性的离线爆破，但足以避免拖库后口令直接可读 —— 落盘的是
 * {@code SHA-256(salt + ":" + 明文)}，同一个口令在不同用户下哈希不同。
 */
public final class PasswordHasher {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int SALT_BYTES = 16;

    private PasswordHasher() {
    }

    /** 生成一个新的随机盐（32 位 hex） */
    public static String newSalt() {
        byte[] buf = new byte[SALT_BYTES];
        RANDOM.nextBytes(buf);
        return hex(buf);
    }

    /** 用给定盐对明文做摘要 */
    public static String hash(String plain, String salt) {
        byte[] raw = (salt + ":" + plain).getBytes(StandardCharsets.UTF_8);
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(raw));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 强制实现的算法，走不到这里
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** 校验明文是否匹配（定长比较，避免按字节提前退出泄露前缀信息） */
    public static boolean matches(String plain, String salt, String expectedHash) {
        if (plain == null || salt == null || expectedHash == null) {
            return false;
        }
        return MessageDigest.isEqual(
                hash(plain, salt).getBytes(StandardCharsets.UTF_8),
                expectedHash.getBytes(StandardCharsets.UTF_8));
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            String s = Integer.toHexString(b & 0xff);
            if (s.length() == 1) {
                sb.append('0');
            }
            sb.append(s);
        }
        return sb.toString();
    }
}

package com.example.qqbot.admin;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 后台密码的哈希与校验。
 *
 * <p>用 JDK 自带的 PBKDF2-HMAC-SHA256，不引第三方依赖。
 * 存库格式：{@code pbkdf2$迭代次数$base64(盐)$base64(哈希)} —— 迭代次数一起存，
 * 以后提高强度时老密码仍然能验，不用强制全员改密。
 *
 * <p>三条硬要求：
 * <ol>
 *   <li><b>每份密码独立随机盐</b>，同样密码两次哈希结果不同</li>
 *   <li>比较用 {@link MessageDigest#isEqual}，与内容无关的常量时间比较</li>
 *   <li>解析失败一律返回 {@code false}，不抛异常 —— 脏数据不该让登录接口 500</li>
 * </ol>
 */
public final class AdminPasswords {

    /** 迭代次数：本地登录场景够用，同时在登录接口上仍是毫秒级 */
    private static final int ITERATIONS = 120_000;
    private static final int SALT_BYTES = 16;
    private static final int KEY_BITS = 256;
    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final String PREFIX = "pbkdf2";
    private static final SecureRandom RANDOM = new SecureRandom();

    private AdminPasswords() {
    }

    public static String hash(String raw) {
        if (raw == null || raw.isEmpty()) {
            throw new IllegalArgumentException("密码不能为空");
        }
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        byte[] key = derive(raw, salt, ITERATIONS);
        Base64.Encoder b64 = Base64.getEncoder();
        return PREFIX + "$" + ITERATIONS + "$" + b64.encodeToString(salt) + "$" + b64.encodeToString(key);
    }

    public static boolean verify(String raw, String stored) {
        if (raw == null || raw.isEmpty() || stored == null || stored.isEmpty()) {
            return false;
        }
        String[] parts = stored.split("\\$");
        if (parts.length != 4 || !PREFIX.equals(parts[0])) {
            return false;
        }
        int iterations;
        try {
            iterations = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            return false;
        }
        if (iterations <= 0) {
            return false;
        }
        byte[] salt;
        byte[] expected;
        try {
            salt = Base64.getDecoder().decode(parts[2]);
            expected = Base64.getDecoder().decode(parts[3]);
        } catch (IllegalArgumentException e) {
            return false;
        }
        byte[] actual = derive(raw, salt, iterations);
        return MessageDigest.isEqual(actual, expected);
    }

    private static byte[] derive(String raw, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(raw.toCharArray(), salt, iterations, KEY_BITS);
        try {
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("密码哈希失败：当前 JVM 不支持 " + ALGORITHM, e);
        } finally {
            spec.clearPassword();
        }
    }
}

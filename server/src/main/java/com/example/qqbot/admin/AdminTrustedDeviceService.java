package com.example.qqbot.admin;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 信任设备：登录过一次 QQ 验证码后，这台浏览器 30 天内可免验证码。
 *
 * <p>绝对 30 天、闲置 7 天；改密、换 QQ、停用、删号时全部撤销。
 * 它只免验证码，不免密码。
 */
@Service
public class AdminTrustedDeviceService {

    private static final long ABSOLUTE_MS = 30L * 24 * 60 * 60 * 1000;
    private static final long IDLE_MS = 7L * 24 * 60 * 60 * 1000;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final AdminTrustedDeviceRepository devices;

    public AdminTrustedDeviceService(AdminTrustedDeviceRepository devices) {
        this.devices = devices;
    }

    /** 发一个新 token；返回给浏览器 Cookie 的原始值，库里只存哈希 */
    public String issue(long userId, String userAgent) {
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        long now = System.currentTimeMillis();
        devices.create(userId, hash(token), now, now, now + ABSOLUTE_MS, trim(userAgent));
        return token;
    }

    public boolean isValid(String token, long userId) {
        if (token == null || token.isBlank()) {
            return false;
        }
        AdminTrustedDeviceRepository.Row row = devices.findByHash(hash(token));
        if (row == null || row.revoked() || row.userId() != userId) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (now > row.expiresAt() || now - row.lastSeenAt() > IDLE_MS) {
            devices.revoke(row.id());
            return false;
        }
        devices.touch(row.id(), now);
        return true;
    }

    public void revokeAll(long userId) {
        devices.revokeAll(userId);
    }

    static String hash(String token) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static String trim(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 200 ? s : s.substring(0, 200);
    }
}

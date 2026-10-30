package com.example.qqbot.admin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 每天清掉过期验证码和过期信任设备，避免表无限长。 */
@Component
public class AdminAuthCleaner {

    private static final Logger log = LoggerFactory.getLogger(AdminAuthCleaner.class);

    private final AdminQqCodeRepository codes;
    private final AdminTrustedDeviceRepository devices;

    public AdminAuthCleaner(AdminQqCodeRepository codes, AdminTrustedDeviceRepository devices) {
        this.codes = codes;
        this.devices = devices;
    }

    @Scheduled(cron = "0 20 4 * * *")
    public void clean() {
        long now = System.currentTimeMillis();
        try {
            codes.deleteExpired(now);
            devices.deleteExpired(now);
        } catch (Exception e) {
            log.warn("[ADMIN] 清理过期验证码/设备失败：{}", e.getMessage());
        }
    }
}

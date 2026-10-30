package com.example.qqbot.admin;

import com.example.qqbot.config.PersistenceProperties;
import com.example.qqbot.persistence.Jdbc;
import com.example.qqbot.persistence.SqliteDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AdminTrustedDeviceServiceTest {

    @TempDir
    Path tmp;

    private SqliteDatabase db;
    private AdminTrustedDeviceService service;

    @BeforeEach
    void setUp() {
        PersistenceProperties p = new PersistenceProperties();
        p.setDb(tmp.resolve("device.sqlite").toString());
        db = new SqliteDatabase(p);
        db.init();
        AdminTrustedDeviceRepository repo = new AdminTrustedDeviceRepository(new Jdbc(db));
        repo.init();
        service = new AdminTrustedDeviceService(repo);
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    @Test
    @DisplayName("信任设备令牌只能用给同一个账号")
    void tokenIsBoundToUser() {
        String token = service.issue(7L, "test-agent");
        assertThat(service.isValid(token, 7L)).isTrue();
        assertThat(service.isValid(token, 8L)).isFalse();
        assertThat(service.isValid("not-a-token", 7L)).isFalse();
    }

    @Test
    @DisplayName("撤销后立即失效")
    void revokeAll() {
        String token = service.issue(7L, "test-agent");
        service.revokeAll(7L);
        assertThat(service.isValid(token, 7L)).isFalse();
    }
}

package com.example.qqbot.persistence;

import com.example.qqbot.config.PersistenceProperties;
import com.example.qqbot.distill.DistillPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 采集默认值：新库默认不采全群，旧库升级时强制关一次。
 */
class DistillSettingRepositoryTest {

    @TempDir
    Path tmp;

    private SqliteDatabase db;
    private Jdbc jdbc;
    private DistillSettingRepository repo;

    @BeforeEach
    void setUp() {
        PersistenceProperties props = new PersistenceProperties();
        props.setDb(tmp.resolve("distill.sqlite").toString());
        db = new SqliteDatabase(props);
        db.init();
        jdbc = new Jdbc(db);
        new ChatRepository(jdbc).initSchema();

        DistillPolicy policy = mock(DistillPolicy.class);
        when(policy.getCaptureGroups()).thenReturn(List.of());
        when(policy.getMaxImagesPerBatch()).thenReturn(1);
        repo = new DistillSettingRepository(jdbc, policy);
        repo.initSchema();
    }

    @AfterEach
    void tearDown() {
        if (db != null) {
            db.close();
        }
    }

    @Test
    @DisplayName("新库默认不采集任何群，也不开启全群")
    void newDatabaseIsConservative() {
        assertThat(repo.captureAllGroups()).isFalse();
        assertThat(repo.captureGroups()).isEmpty();
    }

    @Test
    @DisplayName("旧库的 capture_all_groups=1 升级时被关掉，只执行一次")
    void migratesOldAllGroupsDefault() {
        jdbc.update("UPDATE distill_setting SET capture_all_groups = 1");
        jdbc.update("DELETE FROM bot_meta WHERE k = ?", "distill.capture_all_default_off_v1");

        repo.initSchema();
        assertThat(repo.captureAllGroups()).isFalse();

        jdbc.update("UPDATE distill_setting SET capture_all_groups = 1");
        repo.initSchema();
        assertThat(repo.captureAllGroups()).isTrue();
    }
}

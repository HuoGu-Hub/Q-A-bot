package com.example.qqbot.command;

import com.example.qqbot.config.PersistenceProperties;
import com.example.qqbot.persistence.CommandRepository;
import com.example.qqbot.persistence.Jdbc;
import com.example.qqbot.persistence.SqliteDatabase;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class CommandUsageStatsTest {

    @TempDir
    Path base;

    private SqliteDatabase db;
    private CommandStore store;

    @BeforeEach
    void setUp() throws Exception {
        PersistenceProperties persist = new PersistenceProperties();
        persist.setDb(base.resolve("usage.sqlite").toString());
        db = new SqliteDatabase(persist);
        db.init();
        store = new CommandStore(new CommandRepository(new Jdbc(db)), new ObjectMapper());
        store.init();
    }

    @AfterEach
    void close() {
        db.close();
    }

    @Test
    @DisplayName("★ list/help 的使用计入统计，退休的旧命令不计")
    void topUsedKeepsSystemCommands() {
        store.logUsage("help", 1L, 2L, true);
        store.logUsage("list", 1L, 2L, true);
        store.logUsage("set/output", 1L, 2L, true);

        assertThat(store.topUsed(30, 10)).extracting(CommandRepository.UsageStat::trigger)
                .contains("help", "list").doesNotContain("set/output");
    }
}

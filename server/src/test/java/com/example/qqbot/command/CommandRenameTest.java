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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 命令名就是调用名：改名必须原地改，不能"新建一条 + 旧调用名继续存在"。
 */
class CommandRenameTest {

    @TempDir
    Path base;

    private SqliteDatabase db;
    private CommandStore store;

    @BeforeEach
    void setUp() throws Exception {
        PersistenceProperties persist = new PersistenceProperties();
        persist.setDb(base.resolve("rename.sqlite").toString());
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
    @DisplayName("★ 改命令名后旧调用名消失，行 id 不变")
    void renameUpdatesSameRow() throws Exception {
        long id = store.upsert(command("旧名"));
        assertThat(store.findByTrigger("旧名")).isNotNull();
        store.logUsage("旧名", 1L, 2L, true);

        long saved = store.saveExisting(id, command("新名"));
        store.renameUsage("旧名", "新名");

        assertThat(saved).isEqualTo(id);
        assertThat(store.findByTrigger("旧名")).as("旧调用名不能再命中").isNull();
        assertThat(store.findByTrigger("新名")).isNotNull();
        assertThat(store.findByTrigger("新名").id()).isEqualTo(id);
        assertThat(store.listAll()).hasSize(3); // ping、stats + 这一条
        assertThat(store.topUsed(30, 10)).extracting(m -> m.get("trigger"))
                .as("历史使用记录跟着改名走").contains("新名").doesNotContain("旧名");
    }

    private static BotCommand command(String trigger) {
        return new BotCommand(0, trigger, "回复", "说明", "all", List.of(), "all",
                true, 0, false, BotCommand.KIND_TEMPLATE, BotCommand.MODE_KB,
                List.of(), null, null);
    }
}

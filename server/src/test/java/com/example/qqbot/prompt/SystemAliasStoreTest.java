package com.example.qqbot.prompt;

import com.example.qqbot.config.PersistenceProperties;
import com.example.qqbot.persistence.Jdbc;
import com.example.qqbot.persistence.SqliteDatabase;
import com.example.qqbot.persistence.SystemAliasRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SystemAliasStoreTest {

    @TempDir
    Path base;

    private SqliteDatabase db;
    private SystemAliasStore store;

    @BeforeEach
    void setUp() {
        PersistenceProperties persist = new PersistenceProperties();
        persist.setDb(base.resolve("aliases.sqlite").toString());
        db = new SqliteDatabase(persist);
        db.init();
        store = new SystemAliasStore(new SystemAliasRepository(new Jdbc(db)));
    }

    @AfterEach
    void close() {
        db.close();
    }

    @Test
    @DisplayName("中文别名能解析回系统命令，并保留后面的参数")
    void resolvesChineseAlias() {
        store.replace(Map.of(
                SystemAliasStore.LIST, List.of("列表"),
                SystemAliasStore.HELP, List.of("帮助"),
                SystemAliasStore.PROMPT, List.of("提示")));

        assertThat(store.resolve("/列表")).isEqualTo("/list");
        assertThat(store.resolve("@飘雪喵 /帮助")).isEqualTo("/help");
        assertThat(store.resolve("/提示 /create 你好")).isEqualTo("/prompt /create 你好");
        assertThat(store.resolve("/普通命令")).isEqualTo("/普通命令");
    }

    @Test
    @DisplayName("同一别名不能指向两个系统命令")
    void duplicateAliasRejected() {
        assertThatThrownBy(() -> store.replace(Map.of(
                SystemAliasStore.LIST, List.of("命令"),
                SystemAliasStore.HELP, List.of("命令"))))
                .isInstanceOf(SystemAliasStore.AliasException.class);
    }
}

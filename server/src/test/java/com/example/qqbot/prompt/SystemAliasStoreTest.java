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
    @DisplayName("命令名和动作词分开解析，动作只配一次")
    void resolvesCommandAndActionAliases() {
        store.replace(List.of(
                new SystemAliasStore.Alias(SystemAliasStore.KIND_COMMAND,
                        SystemAliasStore.LIST, "列表"),
                new SystemAliasStore.Alias(SystemAliasStore.KIND_COMMAND,
                        SystemAliasStore.HELP, "帮助"),
                new SystemAliasStore.Alias(SystemAliasStore.KIND_COMMAND,
                        SystemAliasStore.PROMPT, "提示"),
                new SystemAliasStore.Alias(SystemAliasStore.KIND_ACTION, "create", "新建"),
                new SystemAliasStore.Alias(SystemAliasStore.KIND_ACTION, "delete", "删除")));

        assertThat(store.resolve("/列表")).isEqualTo("/list");
        assertThat(store.resolve("@Q-A-bot /帮助")).isEqualTo("/help");
        assertThat(store.resolve("/提示 /新建 你好")).isEqualTo("/prompt /create 你好");
        assertThat(store.resolve("/预约开黑 /新建 今晚8点")).isEqualTo("/预约开黑 /create 今晚8点");
        assertThat(store.resolve("/预约开黑 /删除 12")).isEqualTo("/预约开黑 /delete 12");
        assertThat(store.resolve("/提示 /列表")).isEqualTo("/prompt /list");
        assertThat(store.resolve("/预约开黑 /帮助")).isEqualTo("/预约开黑 /help");
        assertThat(store.resolve("/普通命令")).isEqualTo("/普通命令");
    }

    @Test
    @DisplayName("同一别名不能指向两个命令或动作")
    void duplicateAliasRejected() {
        assertThatThrownBy(() -> store.replace(List.of(
                new SystemAliasStore.Alias(SystemAliasStore.KIND_COMMAND,
                        SystemAliasStore.LIST, "命令"),
                new SystemAliasStore.Alias(SystemAliasStore.KIND_COMMAND,
                        SystemAliasStore.HELP, "命令"))))
                .isInstanceOf(SystemAliasStore.AliasException.class);
    }
}

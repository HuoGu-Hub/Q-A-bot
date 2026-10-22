package com.example.qqbot.prompt;

import com.example.qqbot.config.PersistenceProperties;
import com.example.qqbot.persistence.Jdbc;
import com.example.qqbot.persistence.PromptRepository;
import com.example.qqbot.persistence.SqliteDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PromptStoreTest {

    @TempDir
    Path base;

    private SqliteDatabase db;
    private PromptStore store;

    @BeforeEach
    void setUp() {
        PersistenceProperties persist = new PersistenceProperties();
        persist.setDb(base.resolve("prompt.sqlite").toString());
        db = new SqliteDatabase(persist);
        db.init();
        store = new PromptStore(new PromptRepository(new Jdbc(db)));
    }

    @AfterEach
    void close() {
        db.close();
    }

    @Test
    @DisplayName("个人提示按用户隔离，并拼进系统提示词附录")
    void createAndAppend() {
        PromptStore.Prompt prompt = store.create(1001L, "请叫我老板");

        assertThat(prompt.id()).isPositive();
        assertThat(store.list(1001L)).extracting(PromptStore.Prompt::content)
                .containsExactly("请叫我老板");
        assertThat(store.list(1002L)).isEmpty();
        assertThat(store.appendixFor(1001L))
                .contains("用户补充提示", "请叫我老板", "安全规则");
        assertThat(store.appendixFor(1002L)).isEmpty();
    }

    @Test
    @DisplayName("只能删自己的提示")
    void deleteIsScopedToOwner() {
        PromptStore.Prompt prompt = store.create(1001L, "回答短一点");

        assertThat(store.delete(1002L, prompt.id())).isFalse();
        assertThat(store.list(1001L)).hasSize(1);
        assertThat(store.delete(1001L, prompt.id())).isTrue();
        assertThat(store.list(1001L)).isEmpty();
    }

    @Test
    @DisplayName("最多 5 条、单条最多 300 字")
    void limitsAreEnforced() {
        for (int i = 0; i < PromptStore.MAX_ITEMS; i++) {
            store.create(1001L, "提示 " + i);
        }
        assertThatThrownBy(() -> store.create(1001L, "第六条"))
                .isInstanceOf(PromptStore.PromptException.class)
                .hasMessageContaining("最多");
        assertThatThrownBy(() -> store.create(1002L, "x".repeat(PromptStore.MAX_CHARS + 1)))
                .isInstanceOf(PromptStore.PromptException.class)
                .hasMessageContaining("最多");
    }
}

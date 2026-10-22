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
    @DisplayName("群提示对群内所有人生效，个人提示只对本人生效")
    void groupAndUserScopes() {
        PromptStore.Prompt group = store.create(PromptStore.Scope.GROUP, 2002L, 0L, "本群请叫我老板");
        PromptStore.Prompt user = store.create(PromptStore.Scope.USER, 0L, 1001L, "只对 1001 生效");

        assertThat(group.id()).isPositive();
        assertThat(user.id()).isPositive();
        assertThat(store.listFor(2002L, 1001L))
                .extracting(PromptStore.Prompt::content)
                .containsExactly("本群请叫我老板", "只对 1001 生效");
        assertThat(store.listFor(2002L, 1002L))
                .extracting(PromptStore.Prompt::content)
                .containsExactly("本群请叫我老板");
        assertThat(store.appendixFor(2002L, 1001L))
                .contains("本群提示", "当前用户提示", "本群请叫我老板", "只对 1001 生效");
    }

    @Test
    @DisplayName("只能删自己的提示")
    void deleteIsScopedToOwner() {
        PromptStore.Prompt prompt = store.create(PromptStore.Scope.USER, 0L, 1001L, "回答短一点");

        assertThat(store.find(prompt.id())).isNotNull();
        assertThat(store.delete(prompt.id())).isTrue();
        assertThat(store.listFor(2002L, 1001L)).isEmpty();
    }

    @Test
    @DisplayName("同一范围最多 5 条、单条最多 300 字")
    void limitsAreEnforced() {
        for (int i = 0; i < PromptStore.MAX_ITEMS; i++) {
            store.create(PromptStore.Scope.GROUP, 2002L, 0L, "提示 " + i);
        }
        assertThatThrownBy(() -> store.create(PromptStore.Scope.GROUP, 2002L, 0L, "第六条"))
                .isInstanceOf(PromptStore.PromptException.class)
                .hasMessageContaining("最多");
        assertThatThrownBy(() -> store.create(PromptStore.Scope.USER, 0L, 1002L,
                "x".repeat(PromptStore.MAX_CHARS + 1)))
                .isInstanceOf(PromptStore.PromptException.class)
                .hasMessageContaining("最多");
    }
}

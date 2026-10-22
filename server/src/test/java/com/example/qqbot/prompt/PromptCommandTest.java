package com.example.qqbot.prompt;

import com.example.qqbot.command.CommandStore;
import com.example.qqbot.config.PersistenceProperties;
import com.example.qqbot.onebot.model.OneBotEvent;
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
import static org.mockito.Mockito.mock;

class PromptCommandTest {

    @TempDir
    Path base;

    private SqliteDatabase db;
    private PromptStore store;
    private PromptCommand command;

    @BeforeEach
    void setUp() {
        PersistenceProperties persist = new PersistenceProperties();
        persist.setDb(base.resolve("prompt-command.sqlite").toString());
        db = new SqliteDatabase(persist);
        db.init();
        store = new PromptStore(new PromptRepository(new Jdbc(db)));
        command = new PromptCommand(store, mock(CommandStore.class));
    }

    @AfterEach
    void close() {
        db.close();
    }

    @Test
    @DisplayName("★ /prompt /create 保存原文，/prompt /list 能看见")
    void createThenList() {
        String created = run("/prompt /create 请叫我老板");
        assertThat(created).contains("已添加", "请叫我老板");

        String list = run("/prompt /list");
        assertThat(list).contains("请叫我老板", "#");
    }

    @Test
    @DisplayName("支持 @机器人 /prompt")
    void mentionPrefixIsAccepted() {
        String created = run("@飘雪喵 /prompt /create 回答短一点");
        assertThat(created).contains("已添加", "回答短一点");
    }

    @Test
    @DisplayName("删除编号；不存在的编号给人话")
    void deleteById() {
        String created = run("/prompt /create 临时提示");
        long id = store.list(1001L).get(0).id();
        assertThat(created).contains("#" + id);

        assertThat(run("/prompt /delete " + id)).contains("已删除");
        assertThat(run("/prompt /delete " + id)).contains("没有");
    }

    @Test
    @DisplayName("空动作或未知动作回用法，不写库")
    void usage() {
        assertThat(run("/prompt")).contains("/prompt /create");
        assertThat(run("/prompt /wat")).contains("/prompt /create");
        assertThat(store.list(1001L)).isEmpty();
    }

    private String run(String text) {
        OneBotEvent event = new OneBotEvent();
        event.setPostType("message");
        event.setMessageType("group");
        event.setUserId(1001L);
        event.setGroupId(2002L);
        return command.tryRun(event, text).orElseThrow().text();
    }
}

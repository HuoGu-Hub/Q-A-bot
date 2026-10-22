package com.example.qqbot.prompt;

import com.example.qqbot.command.CommandStore;
import com.example.qqbot.config.PersistenceProperties;
import com.example.qqbot.onebot.model.OneBotEvent;
import com.example.qqbot.persistence.Jdbc;
import com.example.qqbot.persistence.PromptRepository;
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
    @DisplayName("★ 默认按群保存，群内所有人共享")
    void createDefaultsToGroup() {
        String created = run("/prompt /create 本群回答短一点", "admin");
        assertThat(created).contains("本群提示", "本群回答短一点");

        List<PromptStore.Prompt> group = store.listFor(2002L, 1001L);
        assertThat(group).anyMatch(p -> p.scope() == PromptStore.Scope.GROUP
                && p.content().equals("本群回答短一点"));
        assertThat(store.listFor(2002L, 1002L)).hasSize(1);
        assertThat(store.listFor(2003L, 1001L)).isEmpty();
    }

    @Test
    @DisplayName("★ 传 #user_id-...# 才按 QQ 号保存")
    void userScopeOnlyWithUserId() {
        String created = run("/prompt /create #user_id-1002# 只对 1002 生效", "admin");
        assertThat(created).contains("个人提示", "只对 1002 生效");

        assertThat(store.listFor(2002L, 1001L)).isEmpty();
        assertThat(store.listFor(2002L, 1002L)).anyMatch(p ->
                p.scope() == PromptStore.Scope.USER && p.content().equals("只对 1002 生效"));
    }

    @Test
    @DisplayName("普通成员不能设置本群提示，但可以设置自己的")
    void memberCannotSetGroupPrompt() {
        assertThat(run("/prompt /create 群提示", "member")).contains("群主或管理员");
        assertThat(store.listFor(2002L, 1001L)).isEmpty();

        assertThat(run("/prompt /create #user_id-1001# 我的提示", "member")).contains("个人提示");
        assertThat(store.listFor(2002L, 1001L)).hasSize(1);
    }

    @Test
    @DisplayName("支持 @机器人 /prompt")
    void mentionPrefixIsAccepted() {
        String created = run("@飘雪喵 /prompt /create 回答短一点", "admin");
        assertThat(created).contains("已添加", "回答短一点");
    }

    @Test
    @DisplayName("删除编号；不存在的编号给人话")
    void deleteById() {
        String created = run("/prompt /create 临时提示", "admin");
        long id = store.listFor(2002L, 1001L).get(0).id();
        assertThat(created).contains("#" + id);

        assertThat(run("/prompt /delete " + id, "admin")).contains("已删除");
        assertThat(run("/prompt /delete " + id, "admin")).contains("没有");
    }

    @Test
    @DisplayName("空动作或未知动作回用法，不写库")
    void usage() {
        assertThat(run("/prompt", "admin")).contains("/prompt /create");
        assertThat(run("/prompt /wat", "admin")).contains("/prompt /create");
        assertThat(store.listFor(2002L, 1001L)).isEmpty();
    }

    private String run(String text, String role) {
        OneBotEvent event = new OneBotEvent();
        event.setPostType("message");
        event.setMessageType("group");
        event.setUserId(1001L);
        event.setGroupId(2002L);
        event.setSender(new ObjectMapper().createObjectNode().put("role", role));
        return command.tryRun(event, text).orElseThrow().text();
    }
}

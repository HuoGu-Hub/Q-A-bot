package com.example.qqbot.command;

import com.example.qqbot.config.CommandProperties;
import com.example.qqbot.config.QaProperties;
import com.example.qqbot.persistence.CommandRepository;
import com.example.qqbot.persistence.Jdbc;
import com.example.qqbot.persistence.QaStoreRepository;
import com.example.qqbot.persistence.SqliteDatabase;
import com.example.qqbot.qa.QaStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 指令匹配的验收测试。
 *
 * <p>重点是**边界**：这个功能最怕误触发（群里贴路径、贴代码就会撞上），
 * 所以"不该触发的场景"比"该触发"更重要。
 */
class CommandMatcherTest {

    @TempDir
    Path base;

    private CommandProperties props;
    private QaStore qaStore;
    private SqliteDatabase db;
    private CommandStore store;
    private CommandMatcher matcher;

    @BeforeEach
    void setUp() {
        QaProperties qa = new QaProperties();
        qa.setDb(base.resolve("qa.sqlite").toString());
        db = new SqliteDatabase(qa);
        db.init();
        qaStore = new QaStore(new QaStoreRepository(new Jdbc(db)), qa, new ObjectMapper());
        qaStore.init();
        store = new CommandStore(new CommandRepository(new Jdbc(db)), new ObjectMapper());
        store.init();

        props = new CommandProperties();
        matcher = new CommandMatcher(props, store);
    }

    /* ==================== 该触发的 ==================== */

    @AfterEach
    void closeStores() {
        db.close();
    }

    @Test
    @DisplayName("群里 @ + /help → 触发")
    void triggersWithMentionAndSlash() {
        CommandMatcher.Match m = matcher.match("/help", true, true, 100L);

        assertThat(m.hit()).isTrue();
        assertThat(m.command().trigger()).isEqualTo("help");
    }

    @Test
    @DisplayName("大小写不敏感：/HELP 和 /Help 都触发")
    void caseInsensitive() {
        assertThat(matcher.match("/HELP", true, true, 1L).hit()).isTrue();
        assertThat(matcher.match("/Help", true, true, 1L).hit()).isTrue();
    }

    @Test
    @DisplayName("私聊不需要 @")
    void privateNeedsNoMention() {
        CommandMatcher.Match m = matcher.match("/help", false, false, 0L);

        assertThat(m.hit()).isTrue();
    }

    @Test
    @DisplayName("内置指令已就绪：/help /list /ping /stats")
    void builtinsSeeded() {
        for (String t : new String[]{"help", "list", "ping", "stats"}) {
            assertThat(matcher.match("/" + t, true, true, 1L).hit())
                    .as("/" + t + " 应当存在").isTrue();
        }
    }

    /* ==================== ★ 不该触发的（更重要）==================== */

    @Test
    @DisplayName("★ 群里没 @ → 不触发")
    void noMentionInGroupDoesNotTrigger() {
        assertThat(matcher.match("/help", false, true, 1L).hit()).isFalse();
    }

    @Test
    @DisplayName("★ 触发词后面接东西（没有空格分隔）→ 不触发")
    void extraContentWithoutSpaceDoesNotTrigger() {
        // "/帮助一下" 的首词是"帮助一下"，根本不在指令表里 —— 这条边界必须守住
        assertThat(matcher.match("/帮助一下", true, true, 1L).hit()).isFalse();
        assertThat(matcher.match("/helpme", true, true, 1L).hit()).isFalse();
    }

    @Test
    @DisplayName("★ 2026-09-28 起：/help 一下 → 命中，且参数被捕获（小活动靠它）")
    void trailingArgsAreCaptured() {
        CommandMatcher.Match m = matcher.match("/help 一下", true, true, 1L);

        assertThat(m.hit()).as("首词精确命中，后面跟的是参数，不再是'额外内容'").isTrue();
        assertThat(m.command().trigger()).isEqualTo("help");
        assertThat(m.argsOrEmpty()).isEqualTo("一下");
    }

    @Test
    @DisplayName("参数可以带空格、可以很长")
    void multiWordArgs() {
        CommandMatcher.Match m = matcher.match("/help  张三   18 岁 ", true, true, 1L);

        assertThat(m.argsOrEmpty()).isEqualTo("张三   18 岁");
    }

    @Test
    @DisplayName("不带参数时 args 是空串（不是 null，调用方少一层判空）")
    void noArgsIsEmptyString() {
        assertThat(matcher.match("/help", true, true, 1L).argsOrEmpty()).isEmpty();
    }

    @Test
    @DisplayName("★ 没有 / 前缀 → 不触发")
    void noSlashDoesNotTrigger() {
        assertThat(matcher.match("help", true, true, 1L).hit()).isFalse();
        assertThat(matcher.match("帮助", true, true, 1L).hit()).isFalse();
    }

    @Test
    @DisplayName("★ 群里贴代码路径 → 不触发（这是最容易被误伤的）")
    void codePathsDoNotTrigger() {
        // 这些是真实会出现的消息，绝不能触发
        assertThat(matcher.match("/api/getUser", true, true, 1L).hit()).isFalse();
        assertThat(matcher.match("/usr/local/bin", true, true, 1L).hit()).isFalse();
        assertThat(matcher.match("/home/user/config", true, true, 1L).hit()).isFalse();
    }

    @Test
    @DisplayName("★ 没配过的词 → 不触发，但标记为 looksLike（用于统计）")
    void unknownSlashWordIsMiss() {
        CommandMatcher.Match m = matcher.match("/不存在的命令", true, true, 1L);

        assertThat(m.hit()).isFalse();
        assertThat(m.looksLike()).as("看起来像指令，要记下来").isTrue();
        assertThat(m.rawTrigger()).isEqualTo("不存在的命令");
    }

    @Test
    @DisplayName("关掉 require-mention 后，群里不 @ 也能触发")
    void configurableMention() {
        props.setRequireMention(false);
        assertThat(matcher.match("/help", false, true, 1L).hit()).isTrue();
    }

    @Test
    @DisplayName("关掉 require-slash 后，纯文本也能触发")
    void configurableSlash() {
        props.setRequireSlash(false);
        assertThat(matcher.match("help", true, true, 1L).hit()).isTrue();
    }

    @Test
    @DisplayName("总开关关掉后什么都不触发")
    void disabledMeansNothing() {
        props.setEnabled(false);
        assertThat(matcher.match("/help", true, true, 1L).hit()).isFalse();
    }

    /* ==================== 作用范围 ==================== */

    @Test
    @DisplayName("scope=group 的指令在私聊不触发")
    void groupScopeNotInPrivate() throws Exception {
        store.upsert(new BotCommand(0, "onlygroup", "群专用", "说明",
                "group", java.util.List.of(), "member", true, 50, false,
                BotCommand.KIND_TEMPLATE, BotCommand.MODE_KB));

        assertThat(matcher.match("/onlygroup", true, true, 1L).hit()).isTrue();
        assertThat(matcher.match("/onlygroup", false, false, 0L).hit()).isFalse();
    }

    @Test
    @DisplayName("scope=private 的指令在群里不触发")
    void privateScopeNotInGroup() throws Exception {
        store.upsert(new BotCommand(0, "onlypm", "私聊专用", "说明",
                "private", java.util.List.of(), "member", true, 51, false,
                BotCommand.KIND_TEMPLATE, BotCommand.MODE_KB));

        assertThat(matcher.match("/onlypm", true, true, 1L).hit()).isFalse();
        assertThat(matcher.match("/onlypm", false, false, 0L).hit()).isTrue();
    }

    @Test
    @DisplayName("停用的指令不触发")
    void disabledCommandDoesNotTrigger() throws Exception {
        store.upsert(new BotCommand(0, "offcm", "内容", "说明",
                "all", java.util.List.of(), "member", false, 52, false,
                BotCommand.KIND_TEMPLATE, BotCommand.MODE_KB));

        assertThat(matcher.match("/offcm", true, true, 1L).hit()).isFalse();
    }

    /* ==================== agent 类指令（会调模型的那一类）==================== */

    @Test
    @DisplayName("agent 指令：/联网 今天什么版本 → 命中并把参数当问题")
    void agentCommandKeepsArgsAsQuestion() throws Exception {
        store.upsert(new BotCommand(0, "联网", "用法：/联网 <问题>", "用实时资料回答",
                "all", java.util.List.of(), "member", true, 60, false,
                BotCommand.KIND_AGENT, BotCommand.MODE_NONE));

        CommandMatcher.Match m = matcher.match("/联网 今天什么版本", true, true, 1L);

        assertThat(m.hit()).isTrue();
        assertThat(m.command().isAgent()).as("agent 类要调模型，路由会把它送进预算").isTrue();
        assertThat(m.argsOrEmpty()).isEqualTo("今天什么版本");
    }

    @Test
    @DisplayName("agent 指令不带参数也命中 —— 由路由回一句用法提示，不调模型")
    void agentCommandWithoutArgsStillHits() throws Exception {
        store.upsert(new BotCommand(0, "联网", "用法：/联网 <问题>", "用实时资料回答",
                "all", java.util.List.of(), "member", true, 61, false,
                BotCommand.KIND_AGENT, BotCommand.MODE_NONE));

        CommandMatcher.Match m = matcher.match("/联网", true, true, 1L);

        assertThat(m.hit()).isTrue();
        assertThat(m.argsOrEmpty()).isEmpty();
    }

    @Test
    @DisplayName("★ 老数据（kind 为空）一律当话术处理，不会被当成调模型的指令")
    void legacyRowDefaultsToTemplate() {
        BotCommand legacy = new BotCommand(1, "ping", "pong", "说明",
                "all", java.util.List.of(), "member", true, 1, false, null, null);

        assertThat(legacy.isAgent()).isFalse();
        assertThat(legacy.kindName()).isEqualTo(BotCommand.KIND_TEMPLATE);
        assertThat(legacy.modeName()).isEqualTo(BotCommand.MODE_KB);
        assertThat(legacy.display()).isEqualTo("/ping");
    }

    @Test
    @DisplayName("agent 指令在列表里显示成 /命令 <问题>，群友才知道要带参数")
    void agentDisplayShowsArgsHint() {
        BotCommand agent = new BotCommand(1, "联网", "", "说明",
                "all", java.util.List.of(), "member", true, 1, false,
                BotCommand.KIND_AGENT, BotCommand.MODE_NONE);

        assertThat(agent.display()).isEqualTo("/联网 <问题>");
    }

    @Test
    @DisplayName("★ 存进去的 agent/mode 能原样读回来（含老库补列的迁移路径）")
    void agentKindAndModeRoundTrip() throws Exception {
        store.upsert(new BotCommand(0, "纯模型", "用法", "说明",
                "all", java.util.List.of(), "member", true, 62, false,
                BotCommand.KIND_AGENT, BotCommand.MODE_NONE));

        BotCommand back = store.findByTrigger("纯模型");
        assertThat(back.kindName()).isEqualTo(BotCommand.KIND_AGENT);
        assertThat(back.modeName()).isEqualTo(BotCommand.MODE_NONE);
        assertThat(back.isAgent()).isTrue();
    }

    /* ==================== 存储层 ==================== */

    @Test
    @DisplayName("内置指令不能删除，但能改回复")
    void builtinCannotBeDeleted() {
        assertThatThrownBy(() -> store.delete("help"))
                .hasMessageContaining("内置指令不能删除");
    }

    @Test
    @DisplayName("★ 改过内置指令后重启，不会被覆盖回去（INSERT OR IGNORE）")
    void builtinEditsSurviveReseed() throws Exception {
        store.upsert(new BotCommand(0, "help", "我改过的内容 {cmd.total}", "改过的说明",
                "all", java.util.List.of(), "member", true, 1, true,
                BotCommand.KIND_TEMPLATE, BotCommand.MODE_KB));

        // 模拟重启：再建一次（会再跑 seedBuiltins）
        // 复用同一个 QaStore 的连接，避免 WAL 共享内存冲突
        CommandStore reopened = new CommandStore(new CommandRepository(new Jdbc(db)), new ObjectMapper());
        reopened.init();

        BotCommand after = reopened.findByTrigger("help");
        assertThat(after.reply()).as("★ 重启不能把用户的修改覆盖回去")
                .isEqualTo("我改过的内容 {cmd.total}");
    }

    @Test
    @DisplayName("未命中的命令会被记录，能查出'该加什么'")
    void unmatchedCommandsAreLogged() {
        store.logUsage("不存在的命令", 1L, 2L, false);
        store.logUsage("不存在的命令", 1L, 3L, false);
        store.logUsage("help", 1L, 2L, true);

        var unmatched = store.unmatched(30, 10);
        assertThat(unmatched).hasSize(1);
        assertThat(unmatched.get(0).get("trigger")).isEqualTo("不存在的命令");
        assertThat(unmatched.get(0).get("count")).isEqualTo(2L);
    }
}

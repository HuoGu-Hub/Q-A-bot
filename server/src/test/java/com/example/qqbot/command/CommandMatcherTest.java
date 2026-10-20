package com.example.qqbot.command;

import com.example.qqbot.config.PersistenceProperties;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 命令路径匹配的验收测试。
 *
 * <p>重点是**边界**：这个功能最怕误触发（群里贴路径、贴代码就会撞上），
 * 所以"不该触发的场景"比"该触发"更重要。
 *
 * <p>@ 判定不在这里 —— 那属于准入，路由层用同一份设置判，见 {@code Access.mentionSatisfied}。
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
        PersistenceProperties persist = new PersistenceProperties();
        persist.setDb(base.resolve("qa.sqlite").toString());
        db = new SqliteDatabase(persist);
        db.init();
        qaStore = new QaStore(new QaStoreRepository(new Jdbc(db)), qa, new ObjectMapper());
        qaStore.init();
        store = new CommandStore(new CommandRepository(new Jdbc(db)), new ObjectMapper());
        store.init();

        props = new CommandProperties();
        matcher = new CommandMatcher(props, store);
    }

    @AfterEach
    void closeStores() {
        db.close();
    }

    /* ==================== 该触发的 ==================== */

    @Test
    @DisplayName("/ping → 命中一级命令")
    void triggersOnRootCommand() {
        CommandMatcher.Match m = matcher.match("/ping", true, 100L);

        assertThat(m.hit()).isTrue();
        assertThat(m.command().trigger()).isEqualTo("ping");
    }

    @Test
    @DisplayName("大小写不敏感：/PING 和 /Ping 都触发")
    void caseInsensitive() {
        assertThat(matcher.match("/PING", true, 1L).hit()).isTrue();
        assertThat(matcher.match("/Ping", true, 1L).hit()).isTrue();
    }

    @Test
    @DisplayName("私聊也能命中（能不能用由 scope 决定，与在哪聊无关）")
    void privateMatchWorks() {
        assertThat(matcher.match("/ping", false, 0L).hit()).isTrue();
    }

    @Test
    @DisplayName("内置指令已就绪：/ping /stats")
    void builtinsSeeded() {
        for (String t : new String[]{"ping", "stats"}) {
            assertThat(matcher.match("/" + t, true, 1L).hit())
                    .as("/%s 应当存在", t).isTrue();
        }
    }

    @Test
    @DisplayName("★ /help 与 /list 交给板块系统，不再占用指令表")
    void helpAndListAreOwnedByBoards() {
        assertThat(store.findByTrigger("help")).isNull();
        assertThat(store.findByTrigger("list")).isNull();
    }

    /* ==================== 多级路径 ==================== */

    @Test
    @DisplayName("★ 分层路径：/set /output 命中 set/output")
    void multiLevelPath() throws Exception {
        store.upsert(command("set/output", BotCommand.KIND_ACTION));

        CommandMatcher.Match m = matcher.match("/set /output #time-21:30# 文本", true, 1L);

        assertThat(m.hit()).isTrue();
        assertThat(m.command().trigger()).isEqualTo("set/output");
        assertThat(m.restOrEmpty()).isEqualTo("#time-21:30# 文本");
    }

    @Test
    @DisplayName("★ 连写也认：/set/output 与 /set /output 等价")
    void connectedPathAlsoMatches() throws Exception {
        store.upsert(command("set/output", BotCommand.KIND_ACTION));

        CommandMatcher.Match m = matcher.match("/set/output 文本", true, 1L);

        assertThat(m.hit()).isTrue();
        assertThat(m.command().trigger()).isEqualTo("set/output");
        assertThat(m.restOrEmpty()).isEqualTo("文本");
    }

    @Test
    @DisplayName("★ 最长匹配：set 与 set/output 都注册时，吃成 set/output")
    void longestPathWins() throws Exception {
        store.upsert(command("set", BotCommand.KIND_TEMPLATE));
        store.upsert(command("set/output", BotCommand.KIND_ACTION));

        assertThat(matcher.match("/set /output 文本", true, 1L).command().trigger())
                .isEqualTo("set/output");
    }

    @Test
    @DisplayName("★ 吃不动就停：/联网 /api/getUser 是什么 → 路径停在联网")
    void stopsWhenPathCannotExtend() throws Exception {
        store.upsert(command("联网", BotCommand.KIND_AGENT));

        CommandMatcher.Match m = matcher.match("/联网 /api/getUser 是什么", true, 1L);

        assertThat(m.hit()).isTrue();
        assertThat(m.command().trigger()).isEqualTo("联网");
        assertThat(m.restOrEmpty()).isEqualTo("/api/getUser 是什么");
    }

    @Test
    @DisplayName("★ /路径 /help → 进入帮助分支，不执行")
    void helpModifier() throws Exception {
        store.upsert(command("set/output", BotCommand.KIND_ACTION));

        CommandMatcher.Match m = matcher.match("/set /output /help", true, 1L);

        assertThat(m.hit()).isTrue();
        assertThat(m.helpRequested()).isTrue();
        assertThat(m.restOrEmpty()).isEmpty();
    }

    /* ==================== ★ 不该触发的（更重要）==================== */

    @Test
    @DisplayName("★ 没有 / 前缀 → 不触发")
    void noSlashDoesNotTrigger() {
        assertThat(matcher.match("help", true, 1L).hit()).isFalse();
        assertThat(matcher.match("帮助", true, 1L).hit()).isFalse();
    }

    @Test
    @DisplayName("★ 群里贴代码路径 → 不触发（这是最容易被误伤的）")
    void codePathsDoNotTrigger() {
        assertThat(matcher.match("/api/getUser", true, 1L).hit()).isFalse();
        assertThat(matcher.match("/usr/local/bin", true, 1L).hit()).isFalse();
        assertThat(matcher.match("/home/user/config", true, 1L).hit()).isFalse();
    }

    @Test
    @DisplayName("触发词后面直接接东西 → 不触发")
    void extraContentWithoutSpaceDoesNotTrigger() {
        assertThat(matcher.match("/帮助一下", true, 1L).hit()).isFalse();
        assertThat(matcher.match("/helpme", true, 1L).hit()).isFalse();
    }

    @Test
    @DisplayName("★ 没配过的词 → 不触发，但标记为 looksLike（用于统计）")
    void unknownWordIsMiss() {
        CommandMatcher.Match m = matcher.match("/不存在的命令", true, 1L);

        assertThat(m.hit()).isFalse();
        assertThat(m.looksLike()).as("看起来像指令，要记下来").isTrue();
        assertThat(m.rawTrigger()).isEqualTo("不存在的命令");
    }

    @Test
    @DisplayName("总开关关掉后什么都不触发")
    void disabledMeansNothing() {
        props.setEnabled(false);
        assertThat(matcher.match("/help", true, 1L).hit()).isFalse();
    }

    /* ==================== 作用范围 ==================== */

    @Test
    @DisplayName("scope=group 的指令在私聊不触发")
    void groupScopeNotInPrivate() throws Exception {
        store.upsert(new BotCommand(0, "onlygroup", "群专用", "说明",
                "group", List.of(), "member", true, 50, false,
                BotCommand.KIND_TEMPLATE, BotCommand.MODE_KB, List.of(), null, null));

        assertThat(matcher.match("/onlygroup", true, 1L).hit()).isTrue();
        assertThat(matcher.match("/onlygroup", false, 0L).hit()).isFalse();
    }

    @Test
    @DisplayName("scope=private 的指令在群里不触发")
    void privateScopeNotInGroup() throws Exception {
        store.upsert(new BotCommand(0, "onlypm", "私聊专用", "说明",
                "private", List.of(), "member", true, 51, false,
                BotCommand.KIND_TEMPLATE, BotCommand.MODE_KB, List.of(), null, null));

        assertThat(matcher.match("/onlypm", true, 1L).hit()).isFalse();
        assertThat(matcher.match("/onlypm", false, 0L).hit()).isTrue();
    }

    @Test
    @DisplayName("停用的指令不触发")
    void disabledCommandDoesNotTrigger() throws Exception {
        store.upsert(new BotCommand(0, "offcm", "内容", "说明",
                "all", List.of(), "member", false, 52, false,
                BotCommand.KIND_TEMPLATE, BotCommand.MODE_KB, List.of(), null, null));

        assertThat(matcher.match("/offcm", true, 1L).hit()).isFalse();
    }

    /* ==================== 类型与展示 ==================== */

    @Test
    @DisplayName("agent 指令：/联网 今天什么版本 → 尾部原样交给解析器")
    void agentCommandKeepsTail() throws Exception {
        store.upsert(command("联网", BotCommand.KIND_AGENT));

        CommandMatcher.Match m = matcher.match("/联网 今天什么版本", true, 1L);

        assertThat(m.command().isAgent()).isTrue();
        assertThat(m.restOrEmpty()).isEqualTo("今天什么版本");
    }

    @Test
    @DisplayName("★ 老数据（kind 为空）一律当话术处理")
    void legacyRowDefaultsToTemplate() {
        BotCommand legacy = new BotCommand(1, "ping", "pong", "说明",
                "all", List.of(), "member", true, 1, false, null, null, null, null, null);

        assertThat(legacy.isAgent()).isFalse();
        assertThat(legacy.isAction()).isFalse();
        assertThat(legacy.kindName()).isEqualTo(BotCommand.KIND_TEMPLATE);
        assertThat(legacy.modeName()).isEqualTo(BotCommand.MODE_KB);
        assertThat(legacy.display()).isEqualTo("/ping");
    }

    @Test
    @DisplayName("agent 指令在列表里显示成 /命令 <问题>")
    void agentDisplayShowsArgsHint() {
        BotCommand agent = new BotCommand(1, "联网", "", "说明",
                "all", List.of(), "member", true, 1, false,
                BotCommand.KIND_AGENT, BotCommand.MODE_NONE, List.of(), null, null);

        assertThat(agent.display()).isEqualTo("/联网 <问题>");
    }

    @Test
    @DisplayName("★ 有参数表的命令把参数摆出来，路径每一层都补斜杠")
    void displayListsParams() {
        BotCommand cmd = new BotCommand(1, "set/output", "", "设置定时输出",
                "all", List.of(), "member", true, 1, true,
                BotCommand.KIND_ACTION, BotCommand.MODE_KB,
                List.of(ParamSpec.of("time", "moment", true, "21:30")), null, "schedule.create");

        assertThat(cmd.display()).isEqualTo("/set /output #time-21:30#");
    }

    @Test
    @DisplayName("★ 参数表能原样落库读回")
    void paramsRoundTrip() throws Exception {
        store.upsert(new BotCommand(0, "set/output", "用法", "说明",
                "all", List.of(), "member", true, 60, false,
                BotCommand.KIND_ACTION, BotCommand.MODE_KB,
                List.of(new ParamSpec("time", ParamType.MOMENT, true, "21:00", 0, "时间点", "21:30", List.of()),
                        new ParamSpec("kind", ParamType.ENUM, false, null, 0, "类型", "daily", List.of("daily", "once"))),
                "手写帮助", "schedule.create"));

        BotCommand back = store.findByTrigger("set/output");

        assertThat(back.kindName()).isEqualTo(BotCommand.KIND_ACTION);
        assertThat(back.action()).isEqualTo("schedule.create");
        assertThat(back.help()).isEqualTo("手写帮助");
        assertThat(back.params()).hasSize(2);
        assertThat(back.params().get(0).type()).isEqualTo(ParamType.MOMENT);
        assertThat(back.params().get(0).defaultValue()).isEqualTo("21:00");
        assertThat(back.params().get(1).options()).containsExactly("daily", "once");
    }

    /* ==================== 存储层 ==================== */

    @Test
    @DisplayName("内置指令不能删除，但能改回复")
    void builtinCannotBeDeleted() {
        assertThatThrownBy(() -> store.delete("ping"))
                .hasMessageContaining("内置指令不能删除");
    }

    @Test
    @DisplayName("★ 改过内置指令后重启，不会被覆盖回去（INSERT OR IGNORE）")
    void builtinEditsSurviveReseed() throws Exception {
        store.upsert(new BotCommand(0, "ping", "我改过的内容 {cmd.total}", "改过的说明",
                "all", List.of(), "member", true, 1, true,
                BotCommand.KIND_TEMPLATE, BotCommand.MODE_KB, List.of(), null, null));

        CommandStore reopened = new CommandStore(new CommandRepository(new Jdbc(db)), new ObjectMapper());
        reopened.init();

        BotCommand after = reopened.findByTrigger("ping");
        assertThat(after.reply()).as("★ 重启不能把用户的修改覆盖回去")
                .isEqualTo("我改过的内容 {cmd.total}");
    }

    @Test
    @DisplayName("未命中的命令会被记录，能查出'该加什么'")
    void unmatchedCommandsAreLogged() {
        store.logUsage("不存在的命令", 1L, 2L, false);
        store.logUsage("不存在的命令", 1L, 3L, false);
        store.logUsage("ping", 1L, 2L, true);

        var unmatched = store.unmatched(30, 10);
        assertThat(unmatched).hasSize(1);
        assertThat(unmatched.get(0).get("trigger")).isEqualTo("不存在的命令");
        assertThat(unmatched.get(0).get("count")).isEqualTo(2L);
    }

    private static BotCommand command(String path, String kind) {
        return new BotCommand(0, path, "回复", "说明", "all", List.of(), "member", true, 0, false,
                kind, BotCommand.MODE_KB, List.of(), null, null);
    }
}

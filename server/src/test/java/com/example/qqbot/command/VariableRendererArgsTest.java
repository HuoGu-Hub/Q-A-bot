package com.example.qqbot.command;

import com.example.qqbot.config.BotProperties;
import com.example.qqbot.config.CommandProperties;
import com.example.qqbot.kb.Glossary;
import com.example.qqbot.kb.block.KbBlockStore;
import com.example.qqbot.qa.QaAnalytics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 命令参数变量（{@code {args}} / {@code {args.N}}）的验收测试。
 *
 * <p>这是"支持参数的命令"里最容易出错的一环：越界、空参数、预览
 * —— 任何一处返回 null 都会把 {@code {args.9}} 原样发给群友。
 */
class VariableRendererArgsTest {

    private VariableRenderer renderer;

    @BeforeEach
    void setUp() {
        KbBlockStore blockStore = mock(KbBlockStore.class);
        when(blockStore.isAvailable()).thenReturn(false);
        Glossary glossary = mock(Glossary.class);
        when(glossary.size()).thenReturn(0);
        QaAnalytics analytics = mock(QaAnalytics.class);

        renderer = new VariableRenderer(new CommandProperties(), mock(CommandStore.class),
                blockStore, glossary, analytics, new BotProperties());
    }

    @Test
    @DisplayName("{args} 取全部参数")
    void fullArgs() {
        assertThat(renderer.render("已记录：{args}", null, false, "张三 18 岁"))
                .isEqualTo("已记录：张三 18 岁");
    }

    @Test
    @DisplayName("{args.N} 取第 N 个词（1 起）")
    void positionalArgs() {
        assertThat(renderer.render("{args.1}-{args.2}", null, false, "张三 18"))
                .isEqualTo("张三-18");
    }

    @Test
    @DisplayName("参数里的多个空格不影响按位取词")
    void extraSpacesAreCollapsed() {
        assertThat(renderer.render("{args.2}", null, false, "张三    18"))
                .isEqualTo("18");
    }

    @Test
    @DisplayName("★ 越界取词返回空串，而不是把 {args.9} 原样发出去")
    void outOfRangeIsEmpty() {
        assertThat(renderer.render("[{args.9}]", null, false, "张三")).isEqualTo("[]");
        assertThat(renderer.render("[{args.1}]", null, false, "")).isEqualTo("[]");
    }

    @Test
    @DisplayName("没有参数时 {args} 是空串")
    void emptyArgs() {
        assertThat(renderer.render("[{args}]", null, false, "")).isEqualTo("[]");
        assertThat(renderer.render("[{args}]", null, false, null)).isEqualTo("[]");
    }

    @Test
    @DisplayName("非数字的 {args.x} 当未知变量，原样保留（方便发现拼错）")
    void nonNumericIndexKeepsPlaceholder() {
        assertThat(renderer.render("{args.x}", null, false, "张三")).isEqualTo("{args.x}");
    }

    @Test
    @DisplayName("预览模式用示例值，不依赖真实参数")
    void previewUsesExamples() {
        assertThat(renderer.render("{args}", null, true, "")).isEqualTo("张三 18");
        assertThat(renderer.render("{args.1}", null, true, "")).isEqualTo("张三");
        assertThat(renderer.render("{args.2}", null, true, "")).isEqualTo("18");
    }

    @Test
    @DisplayName("★ 预览填了示例参数时，{args} 按填的来")
    void previewUsesProvidedArgs() {
        assertThat(renderer.render("{args}", null, true, "李四 20")).isEqualTo("李四 20");
        assertThat(renderer.render("{args.1}-{args.2}", null, true, "李四 20"))
                .isEqualTo("李四-20");
    }

    @Test
    @DisplayName("★ 预览的 {arg.名字} 用参数表里的示例值")
    void previewUsesNamedExamples() {
        RenderContext ctx = RenderContext.preview("", Map.of("时间", "21:30"));

        assertThat(renderer.render("{arg.时间}", ctx)).isEqualTo("21:30");
    }

    /* ==================== 井号参数：{arg.名字} ==================== */

    @Test
    @DisplayName("★ #名字-值# 的参数要用 {arg.名字} 取")
    void namedArgsUseArgPrefix() {
        RenderContext ctx = context("李四", Map.of("user", "张三"));

        assertThat(renderer.render("{arg.user}", ctx)).isEqualTo("张三");
    }

    @Test
    @DisplayName("★ {user} 永远是系统变量，与同名参数互不干扰")
    void systemVariableAndParameterDoNotCollide() {
        RenderContext ctx = context("李四", Map.of("user", "张三"));

        assertThat(renderer.render("{user} 帮 {arg.user} 报了名", ctx))
                .isEqualTo("李四 帮 张三 报了名");
    }

    @Test
    @DisplayName("参数名中英皆可：{arg.时间}")
    void chineseParameterName() {
        RenderContext ctx = context("李四", Map.of("时间", "21:30"));

        assertThat(renderer.render("{arg.时间}", ctx)).isEqualTo("21:30");
    }

    @Test
    @DisplayName("取不到的参数名原样保留，不静默变空")
    void unknownParameterNameIsKept() {
        RenderContext ctx = context("李四", Map.of("user", "张三"));

        assertThat(renderer.render("[{arg.time}]", ctx)).isEqualTo("[{arg.time}]");
    }

    @Test
    @DisplayName("{text} 是摘掉参数段之后的自由文本")
    void textVariable() {
        RenderContext ctx = new RenderContext(1L, 2L, true, "李四", "", Map.of(),
                "晚上打本集合", false, Instant.now());

        assertThat(renderer.render("内容：{text}", ctx)).isEqualTo("内容：晚上打本集合");
    }

    /** 造一个即时命令的上下文：群 2、触发者 1、昵称给定、具名参数给定 */
    private static RenderContext context(String nickname, Map<String, String> named) {
        return new RenderContext(1L, 2L, true, nickname, "", named, "", false, Instant.now());
    }

    @Test
    @DisplayName("变量列表里能查到 {args}，配置界面才有「点击插入」的按钮")
    void argsAreListedInVariables() {
        List<VariableRenderer.VarInfo> vars = renderer.available();

        assertThat(vars).anyMatch(v -> v.name().equals("args"));
        assertThat(vars).anyMatch(v -> v.name().equals("args.1"));
        assertThat(vars).anyMatch(v -> v.name().equals("args.2"));
    }
}

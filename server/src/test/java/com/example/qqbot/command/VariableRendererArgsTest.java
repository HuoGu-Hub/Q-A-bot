package com.example.qqbot.command;

import com.example.qqbot.config.BotProperties;
import com.example.qqbot.config.CommandProperties;
import com.example.qqbot.kb.Glossary;
import com.example.qqbot.kb.block.KbBlockStore;
import com.example.qqbot.qa.QaAnalytics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

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
    @DisplayName("变量列表里能查到 {args}，配置界面才有「点击插入」的按钮")
    void argsAreListedInVariables() {
        List<VariableRenderer.VarInfo> vars = renderer.available();

        assertThat(vars).anyMatch(v -> v.name().equals("args"));
        assertThat(vars).anyMatch(v -> v.name().equals("args.1"));
        assertThat(vars).anyMatch(v -> v.name().equals("args.2"));
    }
}

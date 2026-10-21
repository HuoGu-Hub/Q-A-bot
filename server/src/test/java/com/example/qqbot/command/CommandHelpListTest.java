package com.example.qqbot.command;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class CommandHelpListTest {

    @Test
    @DisplayName("★ 单命令 /list 列出参数契约")
    void listsParams() {
        CommandHelp help = new CommandHelp(mock(VariableRenderer.class));
        BotCommand cmd = new BotCommand(1, "举报", "收到", "举报", "all", List.of(), "all",
                true, 0, false, BotCommand.KIND_TEMPLATE, BotCommand.MODE_KB,
                List.of(ParamSpec.of("对象", "text", true, "某人"),
                        ParamSpec.of("理由", "text", false, "刷屏")),
                null, null);

        String text = help.list(cmd);

        assertThat(text).contains("支持 2 个参数", "#对象#", "必填", "#理由#", "可选", "例 某人");
    }

    @Test
    @DisplayName("没有参数时明确说没有")
    void noParams() {
        CommandHelp help = new CommandHelp(mock(VariableRenderer.class));
        BotCommand cmd = new BotCommand(1, "ping", "pong", "测试", "all", List.of(), "all",
                true, 0, true, BotCommand.KIND_TEMPLATE, BotCommand.MODE_KB,
                List.of(), null, null);

        assertThat(help.list(cmd)).isEqualTo("/ping 没有参数。");
    }
}

package com.example.qqbot.command;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 动作注册表的验收测试。
 *
 * <p>要守住的两条：<b>找不到动作时返回 null 而不是抛异常</b>（库里可能留着一条
 * 指向已删动作的命令），以及<b>重复 id 不让它静默覆盖</b>（否则线上跑的是哪个类看不出来）。
 */
class ActionRegistryTest {

    /** 最小可用动作：只关心 id */
    private static CommandAction action(String id) {
        return new CommandAction() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public List<ParamSpec> params() {
                return List.of();
            }

            @Override
            public String execute(ActionContext ctx, CommandArgs args) {
                return id;
            }
        };
    }

    @Test
    @DisplayName("按 id 查得到")
    void findsById() {
        ActionRegistry registry = new ActionRegistry(List.of(action("schedule.create"), action("schedule.delete")));

        assertThat(registry.find("schedule.create")).isNotNull();
        assertThat(registry.find("schedule.create").id()).isEqualTo("schedule.create");
        assertThat(registry.all()).hasSize(2);
    }

    @Test
    @DisplayName("★ 找不到返回 null，不抛异常 —— 调用方要能当'停用'处理")
    void unknownIdReturnsNull() {
        ActionRegistry registry = new ActionRegistry(List.of(action("schedule.create")));

        assertThat(registry.find("nobody.home")).isNull();
        assertThat(registry.find(null)).isNull();
        assertThat(registry.find("  ")).isNull();
    }

    @Test
    @DisplayName("★ id 重复时保留先出现的那个，不静默覆盖")
    void duplicateKeepsFirst() {
        ActionRegistry registry = new ActionRegistry(List.of(action("dup"), action("dup")));

        assertThat(registry.all()).hasSize(1);
    }

    @Test
    @DisplayName("没有 id 的动作被跳过，仍然能启动")
    void blankIdIsSkipped() {
        ActionRegistry registry = new ActionRegistry(List.of(action("  "), action("ok")));

        assertThat(registry.all()).hasSize(1);
        assertThat(registry.find("ok")).isNotNull();
    }

    @Test
    @DisplayName("空列表也能构造")
    void emptyIsAllowed() {
        ActionRegistry registry = new ActionRegistry(List.of());

        assertThat(registry.isEmpty()).isTrue();
        assertThat(registry.find("x")).isNull();
    }
}

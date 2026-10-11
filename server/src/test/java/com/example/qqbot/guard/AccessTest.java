package com.example.qqbot.guard;

import com.example.qqbot.onebot.model.OneBotEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 准入判定的验收 —— 它是**安全开关**，错了就是"该拦的没拦"。
 *
 * <p>这个判定原先在 {@code AccessControlStage} 与 {@code MessageRouter} 各写一遍，
 * 两处能得出不同结论：私聊策略 {@code off} 时话术类指令照回，普通消息却被拦。
 * 收进 {@link Access#denial} 之后，这条测试守的就是两处共用的那一份。
 */
class AccessTest {

    private static OneBotEvent group(long groupId, long userId) {
        OneBotEvent e = mock(OneBotEvent.class);
        when(e.isGroupMessage()).thenReturn(true);
        when(e.getGroupId()).thenReturn(groupId);
        when(e.getUserId()).thenReturn(userId);
        return e;
    }

    private static OneBotEvent privateChat(long userId) {
        OneBotEvent e = mock(OneBotEvent.class);
        when(e.isPrivateMessage()).thenReturn(true);
        when(e.getUserId()).thenReturn(userId);
        return e;
    }

    @Test
    @DisplayName("默认配置：群里放行、私聊按【未开放】拒绝")
    void defaults() {
        Access access = new Access();

        assertThat(access.denial(group(1L, 2L))).isEmpty();
        assertThat(access.denial(privateChat(2L)).orElse("")).contains("私聊未开放");
    }

    @Test
    @DisplayName("★ 黑名单是全局的：群与用户被拉黑后，指令也一样触发不了")
    void blacklists() {
        Access access = new Access();
        access.setGroupBlacklist(List.of(10L));
        access.setUserBlacklist(List.of(20L));

        assertThat(access.denial(group(10L, 1L)).orElse("")).contains("群在黑名单里");
        assertThat(access.denial(group(1L, 20L)).orElse("")).contains("用户在黑名单里");
        assertThat(access.denial(privateChat(20L)).orElse("")).contains("用户在黑名单里");
        assertThat(access.denial(group(1L, 1L))).isEmpty();
    }

    @Test
    @DisplayName("★ 私聊策略：all 放行、whitelist 只放白名单、不认识的值一律按关闭（fail-closed）")
    void privateChatPolicies() {
        Access access = new Access();

        access.setPrivateChatPolicy("all");
        assertThat(access.denial(privateChat(7L))).isEmpty();

        access.setPrivateChatPolicy("whitelist");
        access.setPrivateWhitelist(List.of(7L));
        assertThat(access.denial(privateChat(7L))).isEmpty();
        assertThat(access.denial(privateChat(8L)).orElse("")).contains("私聊未开放");

        // 写错的值不能"猜错了就放开" —— 这正是安全开关最容易出事的地方
        access.setPrivateChatPolicy("alowed");
        assertThat(access.isRecognizedPrivateChatPolicy()).isFalse();
        assertThat(access.denial(privateChat(7L)).orElse("")).contains("私聊未开放");
    }

    @Test
    @DisplayName("YAML 里 off 不加引号会被解析成布尔 false —— \"false\" 也必须是关闭")
    void yamlBooleanFalseMeansOff() {
        Access access = new Access();
        access.setPrivateChatPolicy("false");

        assertThat(access.isRecognizedPrivateChatPolicy()).isTrue();
        assertThat(access.denial(privateChat(1L)).orElse("")).contains("私聊未开放");
    }

    @Test
    @DisplayName("既不是群聊也不是私聊的消息类型：拒绝，不猜")
    void unknownMessageType() {
        assertThat(new Access().denial(mock(OneBotEvent.class)).orElse("")).contains("未知的消息类型");
    }
}

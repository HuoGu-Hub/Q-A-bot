package com.example.qqbot.guard.stage;

import com.example.qqbot.guard.Access;
import com.example.qqbot.guard.GuardContext;
import com.example.qqbot.guard.GuardResult;
import com.example.qqbot.onebot.model.OneBotEvent;
import com.example.qqbot.trigger.ReplyIntent;
import com.example.qqbot.trigger.TriggerDecision;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 触发阶段的执行语义：必须 @ 与概率模式各走一条 */
class ReplyTriggerStageTest {

    private static final long GROUP = 1L;

    @Test
    @DisplayName("必须 @ 打开：没 @ 直接丢弃")
    void mentionRequiredDrops() {
        Access access = new Access();
        access.setRequireMentionInGroup(true);
        ReplyTriggerStage stage = new ReplyTriggerStage(access);
        GuardContext ctx = groupCtx(false, null);

        GuardResult result = stage.check(ctx);
        assertThat(result).isInstanceOf(GuardResult.Drop.class);
    }

    @Test
    @DisplayName("概率模式：判定通过才放行")
    void probabilityPasses() {
        Access access = new Access();
        access.setRequireMentionInGroup(false);
        ReplyTriggerStage stage = new ReplyTriggerStage(access);

        GuardResult pass = stage.check(groupCtx(false, TriggerDecision.yes(ReplyIntent.QUESTION, 0.3, 0.1)));
        assertThat(pass).isInstanceOf(GuardResult.Pass.class);

        GuardResult drop = stage.check(groupCtx(false, TriggerDecision.no(ReplyIntent.CHAT, "概率为 0")));
        assertThat(drop).isInstanceOf(GuardResult.Drop.class);
    }

    @Test
    @DisplayName("概率模式：影子只记录")
    void shadowDrops() {
        Access access = new Access();
        access.setRequireMentionInGroup(false);
        ReplyTriggerStage stage = new ReplyTriggerStage(access);

        GuardResult drop = stage.check(groupCtx(false, TriggerDecision.would(ReplyIntent.QUESTION, 1.0, 0.1)));
        assertThat(drop).isInstanceOf(GuardResult.Drop.class);
        assertThat(((GuardResult.Drop) drop).reason()).contains("影子");
    }

    @Test
    @DisplayName("私聊与已 @ 的消息直接放行")
    void privateAndMentionedPass() {
        Access access = new Access();
        access.setRequireMentionInGroup(true);
        ReplyTriggerStage stage = new ReplyTriggerStage(access);

        OneBotEvent event = mock(OneBotEvent.class);
        when(event.isGroupMessage()).thenReturn(false);
        assertThat(stage.check(new GuardContext(event, "hi", List.of(), false, true)))
                .isInstanceOf(GuardResult.Pass.class);

        assertThat(stage.check(groupCtx(true, null))).isInstanceOf(GuardResult.Pass.class);
    }

    private static GuardContext groupCtx(boolean mentioned, TriggerDecision trigger) {
        OneBotEvent event = mock(OneBotEvent.class);
        when(event.isGroupMessage()).thenReturn(true);
        when(event.getGroupId()).thenReturn(GROUP);
        return new GuardContext(event, "这个在哪", List.of(), false, mentioned, trigger);
    }
}

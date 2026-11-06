package com.example.qqbot.guard.stage;

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

/** 触发阶段的执行语义：呼叫直通，非呼叫看判定 */
class ReplyTriggerStageTest {

    private static final long GROUP = 1L;

    @Test
    @DisplayName("非呼叫且没有判定：丢弃")
    void noCallNoTriggerDrops() {
        ReplyTriggerStage stage = new ReplyTriggerStage();
        GuardResult result = stage.check(groupCtx(false, null));
        assertThat(result).isInstanceOf(GuardResult.Drop.class);
    }

    @Test
    @DisplayName("呼叫：直接放行")
    void callPasses() {
        ReplyTriggerStage stage = new ReplyTriggerStage();
        GuardResult result = stage.check(groupCtx(false, TriggerDecision.call("正文昵称")));
        assertThat(result).isInstanceOf(GuardResult.Pass.class);
    }

    @Test
    @DisplayName("概率命中：放行")
    void probabilityPasses() {
        ReplyTriggerStage stage = new ReplyTriggerStage();
        GuardResult pass = stage.check(groupCtx(false, TriggerDecision.yes(ReplyIntent.QUESTION, 0.3, 0.1)));
        assertThat(pass).isInstanceOf(GuardResult.Pass.class);
    }

    @Test
    @DisplayName("概率未命中：丢弃并带原因")
    void probabilityDrops() {
        ReplyTriggerStage stage = new ReplyTriggerStage();
        GuardResult drop = stage.check(groupCtx(false, TriggerDecision.no(ReplyIntent.CHAT, "概率为 0")));
        assertThat(drop).isInstanceOf(GuardResult.Drop.class);
        assertThat(((GuardResult.Drop) drop).reason()).contains("概率为 0");
    }

    @Test
    @DisplayName("影子模式：只记录")
    void shadowDrops() {
        ReplyTriggerStage stage = new ReplyTriggerStage();
        GuardResult drop = stage.check(groupCtx(false, TriggerDecision.would(ReplyIntent.QUESTION, 1.0, 0.1)));
        assertThat(drop).isInstanceOf(GuardResult.Drop.class);
        assertThat(((GuardResult.Drop) drop).reason()).contains("影子");
    }

    @Test
    @DisplayName("私聊与已 @ 的消息直接放行")
    void privateAndMentionedPass() {
        ReplyTriggerStage stage = new ReplyTriggerStage();

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

package com.example.qqbot.router;

import com.example.qqbot.onebot.codec.MessageCodec;
import com.example.qqbot.onebot.model.OneBotEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 分诊台：一条消息进哪个池子。
 *
 * <p>这是"命令风暴挤不掉问答"的唯一一道保险 —— 判断必须在进池子<b>之前</b>发生，
 * 进了池子再分就已经晚了。
 */
class EventDispatcherTest {

    private static final long ENQUEUED_AT = 1_700_000_000_000L;

    private MessageRouter router;
    private MessageCodec codec;
    private OneBotEvent event;

    /** 直接在当前线程跑，方便断言"这一池子被执行了" */
    private final Executor inline = Runnable::run;
    /** 记下有没有被调用，但什么都不做 */
    private final List<Runnable> untouched = new ArrayList<>();

    @BeforeEach
    void setUp() {
        router = mock(MessageRouter.class);
        codec = mock(MessageCodec.class);
        event = mock(OneBotEvent.class);
        when(event.isMessageEvent()).thenReturn(true);
    }

    private EventDispatcher dispatcher(Executor chat, Executor command) {
        return new EventDispatcher(router, codec, chat, command);
    }

    private Executor recorder() {
        return untouched::add;
    }

    private void text(String t) {
        when(codec.extractPlainText(event)).thenReturn(t);
    }

    @Test
    @DisplayName("★ 以斜杠开头的进命令池，问答池完全不被碰")
    void slashGoesToCommandPool() {
        text("/ping");
        Executor chat = recorder();

        dispatcher(chat, inline).dispatch(event, ENQUEUED_AT);

        verify(router).processQueued(event, ENQUEUED_AT);
        assertThat(untouched).as("问答池不该收到命令").isEmpty();
    }

    @Test
    @DisplayName("★ 普通消息进问答池，命令池完全不被碰")
    void plainTextGoesToChatPool() {
        text("今天天气怎么样");
        Executor command = recorder();

        dispatcher(inline, command).dispatch(event, ENQUEUED_AT);

        verify(router).processQueued(event, ENQUEUED_AT);
        assertThat(untouched).as("命令池不该收到问答").isEmpty();
    }

    @Test
    @DisplayName("前面有空格也算命令")
    void leadingSpacesAreTolerated() {
        text("   /help");

        // 命令池要 inline（第二个参数），问答池只是记录
        dispatcher(recorder(), inline).dispatch(event, ENQUEUED_AT);

        verify(router).processQueued(event, ENQUEUED_AT);
    }

    @Test
    @DisplayName("非消息事件一律走问答池，不做分诊")
    void nonMessageEventsAreNotCommands() {
        when(event.isMessageEvent()).thenReturn(false);
        text("/ping");

        dispatcher(inline, recorder()).dispatch(event, ENQUEUED_AT);

        verify(router).processQueued(event, ENQUEUED_AT);
    }

    @Test
    @DisplayName("取不到文本时按问答处理，不乱猜")
    void nullTextFallsBackToChat() {
        when(codec.extractPlainText(event)).thenReturn(null);

        dispatcher(inline, recorder()).dispatch(event, ENQUEUED_AT);

        verify(router).processQueued(event, ENQUEUED_AT);
    }

    @Test
    @DisplayName("★ 命令池满了：不抛异常，回一句提示")
    void fullCommandPoolNotifiesOverload() {
        text("/ping");
        Executor full = task -> {
            throw new RejectedExecutionException("满了");
        };

        dispatcher(inline, full).dispatch(event, ENQUEUED_AT);

        verify(router).notifyOverload(event);
        verify(router, never()).processQueued(any(), any(Long.class));
    }

    @Test
    @DisplayName("★ 问答池满了也只影响问答，命令池照常")
    void fullChatPoolDoesNotBlockCommands() {
        text("/ping");
        Executor full = task -> {
            throw new RejectedExecutionException("满了");
        };

        dispatcher(full, inline).dispatch(event, ENQUEUED_AT);

        verify(router).processQueued(event, ENQUEUED_AT);
        verify(router, never()).notifyOverload(any());
    }

    @Test
    @DisplayName("投递立刻返回：池子里的任务什么时候跑与调用方无关")
    void dispatchDoesNotWait() {
        text("你好");
        // 记下来但不跑 —— 模拟"线程都忙着"
        Executor deferred = recorder();

        dispatcher(deferred, recorder()).dispatch(event, ENQUEUED_AT);

        assertThat(untouched).hasSize(1);
        verify(router, never()).processQueued(any(), any(Long.class));
    }
}

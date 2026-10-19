package com.example.qqbot.router;

import com.example.qqbot.config.AsyncProperties;
import com.example.qqbot.onebot.codec.MessageCodec;
import com.example.qqbot.onebot.model.OneBotEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * 分诊台：把事件投进**问答池**还是**命令池**。
 *
 * <h2>为什么分诊放在这里，而不是让处理逻辑自己判断</h2>
 * 判断必须发生在"进入线程池之前"，否则一个刷屏的命令风暴已经把线程占满了，
 * 问答再想插队也没有线程可用。分诊只看开头一个字符，几微秒的事。
 *
 * <h2>为什么按 <code>/</code> 分就够了</h2>
 * 命令的定义就是"以斜杠开头"。猜错的代价很低：一条以 <code>/</code> 开头却没命中
 * 任何命令的消息，在命令池里判完之后会自己转投问答，命令池不会被拖住。
 */
@Component
public class EventDispatcher {

    private static final Logger log = LoggerFactory.getLogger(EventDispatcher.class);

    private final MessageRouter router;
    private final MessageCodec codec;
    private final Executor chatExecutor;
    private final Executor commandExecutor;

    public EventDispatcher(MessageRouter router,
                           MessageCodec codec,
                           @Qualifier("chatExecutor") Executor chatExecutor,
                           @Qualifier("commandExecutor") Executor commandExecutor) {
        this.router = router;
        this.codec = codec;
        this.chatExecutor = chatExecutor;
        this.commandExecutor = commandExecutor;
    }

    /**
     * 投递一条事件。**立刻返回**，不等处理。
     *
     * @param enqueuedAt 收到事件的时刻，用来判断"排了多久"
     */
    public void dispatch(OneBotEvent event, long enqueuedAt) {
        boolean command = looksLikeCommand(event);
        Executor pool = command ? commandExecutor : chatExecutor;
        try {
            pool.execute(() -> router.processQueued(event, enqueuedAt));
        } catch (RejectedExecutionException e) {
            // 池子和队列都满了。丢车保帅，但要说一声，别让人干等
            log.error("[ASYNC] {}池已满（处理不了也排不下），事件被拒：群 {} 用户 {}",
                    command ? "命令" : "问答", event.getGroupId(), event.getUserId());
            router.notifyOverload(event);
        }
    }

    /** 命令的唯一定义：正文以斜杠开头 */
    private boolean looksLikeCommand(OneBotEvent event) {
        if (event == null || !event.isMessageEvent()) {
            return false;
        }
        String text = codec.extractPlainText(event);
        return text != null && text.stripLeading().startsWith("/");
    }
}

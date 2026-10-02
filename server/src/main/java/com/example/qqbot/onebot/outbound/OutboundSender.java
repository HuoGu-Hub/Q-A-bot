package com.example.qqbot.onebot.outbound;

import com.example.qqbot.config.GuardProperties.Outbound;
import com.example.qqbot.guard.OutboundPacer;
import com.example.qqbot.onebot.BotIdentity;
import com.example.qqbot.onebot.client.OneBotApiClient;
import com.example.qqbot.onebot.codec.MessageCodec;
import com.example.qqbot.onebot.model.OneBotEvent;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 出站发送 —— 机器人**唯一**的出口。
 *
 * <h2>它负责的两件事</h2>
 * <ol>
 *   <li><b>分片</b>：超过「单条字数上限」的回复按句子切成几条（切法在 {@link OutboundPacer}）。</li>
 *   <li><b>怎么发出去</b>：段数够多时打包成一条**合并转发**（QQ 的「聊天记录」），
 *       否则逐条发、每条之间留出站节奏的间隔。</li>
 * </ol>
 *
 * <h2>为什么合并转发能救场</h2>
 * 群里有发言频率上限。一条长攻略被切成 8 条就是 8 次发言，两三个人同时问就很容易撞上限、
 * 后面的消息直接发不出去。合并转发在群里只算**一次**发言，点开还是一段段读，
 * 阅读体验不变 —— 用一次发言换 N 次。
 *
 * <h2>什么时候才合并（两个阈值，与的关系）</h2>
 * 由 {@code app.guard.outbound.forward-min-parts} 与 {@code forward-min-chars} 决定，
 * <b>两个都满足</b>才打包：
 * <ul>
 *   <li><b>段数超过</b> forward-min-parts（默认 2 = 3 段起）—— 这是主要的那道闸；</li>
 *   <li><b>总字数超过</b> forward-min-chars（默认 0 = 不看字数）。</li>
 * </ul>
 * 为什么默认是 3 段起：合并转发卡片本身没法 @ 人，所以前面必须先发一条带 @ 的提示语
 * （否则提问的人不知道是回给他的）。两段的话「提示 + 卡片」= 2 条，和直接发 2 条一样多，
 * 白折腾；三段起才是净赚。
 *
 * <h2>失败怎么办</h2>
 * 合并转发是**锦上添花**，绝不能因此把回复弄丢：协议端不支持这个动作（老版本 NapCat / go-cqhttp）
 * 或调用报错时，自动退回逐条发送。提示语已经发出去了就不再 @ 一遍，免得对方收到两次 @。
 */
@Component
public class OutboundSender {

    private static final Logger log = LoggerFactory.getLogger(OutboundSender.class);

    private final OneBotApiClient apiClient;
    private final MessageCodec codec;
    private final OutboundPacer pacer;
    private final BotIdentity identity;
    private final Outbound config;

    public OutboundSender(OneBotApiClient apiClient,
                          MessageCodec codec,
                          OutboundPacer pacer,
                          BotIdentity identity,
                          Outbound outbound) {
        this.apiClient = apiClient;
        this.codec = codec;
        this.pacer = pacer;
        this.identity = identity;
        this.config = outbound;
    }

    /** 把一条回复发出去（分片 + 节奏 + 合并转发都在这里） */
    public void send(OneBotEvent event, String text) {
        if (!StringUtils.hasText(text)) {
            log.warn("[OUT] 回复内容为空，跳过发送（避免发出空白消息）");
            return;
        }
        List<String> parts = pacer.split(text);

        if (shouldMerge(parts, text) && sendMerged(event, parts)) {
            return;
        }
        sendParts(event, parts, true);
    }

    /**
     * 该不该打包成合并转发。
     *
     * <p>两个阈值是**与**的关系：都满足才合并。0 表示那一项不参与判断，所以
     * 默认配置（段数 2 / 字数 0）等价于"3 段起就合并"。
     *
     * <p>字数用**整条回复**的长度，不是各段长度之和：段是 strip 过的，
     * 加起来会比原文少一点，拿它当"总字数"会偶尔卡在阈值边上。
     */
    private boolean shouldMerge(List<String> parts, String text) {
        if (!config.isForwardMerged()) {
            return false;
        }
        int minParts = config.getForwardMinParts();
        if (minParts > 0 && parts.size() <= minParts) {
            return false;
        }
        int minChars = config.getForwardMinChars();
        if (minChars > 0 && text.length() <= minChars) {
            return false;
        }
        return true;
    }

    /**
     * 试着打包成一条合并转发。
     *
     * @return true = 这件事已经处理完了（合并成功，或者失败后已经在这里退回逐条发过）；
     *         false = 没发任何东西，调用方按老路逐条发
     */
    private boolean sendMerged(OneBotEvent event, List<String> parts) {
        long uin = identity.getSelfId();
        if (uin <= 0) {
            // 启动时没连上协议层，连自己是谁都不知道 —— 节点没法署名，老实走逐条
            log.debug("[OUT] 还不知道机器人自己的 QQ 号，本次不做合并转发");
            return false;
        }
        if (!event.isGroupMessage() && !event.isPrivateMessage()) {
            return false;
        }

        JsonNode nodes = codec.forwardNodes(parts, uin, nodeName());
        boolean introSent = false;
        try {
            if (event.isGroupMessage()) {
                sleepQuietly(pacer.reserveGroupSlot(event.getGroupId()));
                apiClient.sendGroupMsg(event.getGroupId(),
                        codec.atPlusText(event.getUserId(), intro(parts.size())));
                introSent = true;

                sleepQuietly(pacer.reserveGroupSlot(event.getGroupId()));
                long id = apiClient.sendGroupForwardMsg(event.getGroupId(), nodes);
                log.info("[OUT] 已合并转发到群 {}，message_id={}（{} 段打包成 1 条）",
                        event.getGroupId(), id, parts.size());
            } else {
                long id = apiClient.sendPrivateForwardMsg(event.getUserId(), nodes);
                log.info("[OUT] 已合并转发给 {}，message_id={}（{} 段打包成 1 条）",
                        event.getUserId(), id, parts.size());
            }
            return true;
        } catch (Exception e) {
            log.warn("[OUT] 合并转发失败（{}），退回逐条发送 {} 段：{}",
                    e.getClass().getSimpleName(), parts.size(), e.getMessage());
            sendParts(event, parts, !introSent);
            return true;
        }
    }

    /**
     * 逐条发送（合并转发出现之前的老行为）。
     *
     * @param atFirst 第一段是否带 @ 提问者 —— 提示语已经发过时传 false，免得 @ 两次
     */
    private void sendParts(OneBotEvent event, List<String> parts, boolean atFirst) {
        for (int i = 0; i < parts.size(); i++) {
            String part = parts.get(i);
            boolean first = i == 0;
            if (event.isGroupMessage()) {
                JsonNode message = first && atFirst
                        ? codec.atPlusText(event.getUserId(), part)
                        : codec.textMessage(part);
                sleepQuietly(pacer.reserveGroupSlot(event.getGroupId()));
                long messageId = apiClient.sendGroupMsg(event.getGroupId(), message);
                log.info("[OUT] 已回复到群 {}，message_id={}（第 {}/{} 段）",
                        event.getGroupId(), messageId, i + 1, parts.size());
            } else if (event.isPrivateMessage()) {
                long messageId = apiClient.sendPrivateMsg(event.getUserId(), codec.textMessage(part));
                log.info("[OUT] 已回复给 {}，message_id={}（第 {}/{} 段）",
                        event.getUserId(), messageId, i + 1, parts.size());
            }
        }
    }

    /** 节点署名：优先用协议端给的真实昵称（群里平时显示的就是它） */
    private String nodeName() {
        String name = identity.getSelfName();
        return StringUtils.hasText(name) ? name : "机器人";
    }

    /** 合并转发前面那句提示，{parts} 替换成段数 */
    private String intro(int parts) {
        String template = config.getForwardIntroText();
        if (!StringUtils.hasText(template)) {
            return "";
        }
        return template.replace("{parts}", String.valueOf(parts));
    }

    /** 发送前的等待。被中断时只是少等一会儿，不影响发送本身 */
    private void sleepQuietly(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

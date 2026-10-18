package com.example.qqbot.onebot.outbound;

import com.example.qqbot.guard.Outbound;
import com.example.qqbot.guard.OutboundFilter;
import com.example.qqbot.guard.OutboundPacer;
import com.example.qqbot.llm.ToolCallMarkup;
import com.example.qqbot.media.ImageLibrary;
import com.example.qqbot.media.ImageMarkers;
import com.example.qqbot.onebot.BotIdentity;
import com.example.qqbot.onebot.client.OneBotApiClient;
import com.example.qqbot.onebot.codec.MessageCodec;
import com.example.qqbot.onebot.model.OneBotEvent;
import com.example.qqbot.onebot.model.OutboundImage;
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
 *
 * <h2>图片</h2>
 * 回复里出现 {@link ImageMarkers} 那样的图片标记时，标记会被摘掉、换成真实的图片段，
 * 挂在**第一条**消息上（连同 @ 一起）。见 {@link #dispatch} 里的三步顺序。
 * 图片也会让"合并转发"这条路让开：图片塞进「聊天记录」卡片里得点开才看得见，
 * 为了省一次发言把图藏起来不划算。
 */
@Component
public class OutboundSender {

    private static final Logger log = LoggerFactory.getLogger(OutboundSender.class);

    private final OneBotApiClient apiClient;
    private final MessageCodec codec;
    private final OutboundPacer pacer;
    private final BotIdentity identity;
    private final Outbound config;
    private final OutboundFilter filter;
    private final ImageLibrary images;

    public OutboundSender(OneBotApiClient apiClient,
                          MessageCodec codec,
                          OutboundPacer pacer,
                          BotIdentity identity,
                          Outbound outbound,
                          OutboundFilter filter,
                          ImageLibrary images) {
        this.apiClient = apiClient;
        this.codec = codec;
        this.pacer = pacer;
        this.identity = identity;
        this.config = outbound;
        this.filter = filter;
        this.images = images;
    }

    /**
     * 发送目标。把"发给谁、要不要 @"从 {@link OneBotEvent} 里摘出来，
     * 这样**没有入站事件**的场景（广场的群内求助）也能走同一条出站路径。
     *
     * @param group    群消息还是私聊
     * @param id       群号或用户号
     * @param atUserId 第一段要 @ 的人；0 = 不 @
     */
    private record Target(boolean group, long id, long atUserId) {

        static Target of(OneBotEvent event) {
            return event.isGroupMessage()
                    ? new Target(true, event.getGroupId(), event.getUserId())
                    : new Target(false, event.getUserId(), 0);
        }
    }

    /**
     * 把一条回复发出去（出站过滤 + 分片 + 节奏 + 合并转发都在这里）。
     *
     * @return **实际发出的文本**（已过滤）。调用方要记录"机器人到底说了什么"时用它，
     *         而不是自己再过滤一遍 —— 那会出现两处过滤、两份可能不一致的文本。
     */
    public String send(OneBotEvent event, String text) {
        return dispatch(Target.of(event), text);
    }

    /**
     * 按群号直接发 —— 给**没有入站事件**的调用方（广场的群内求助）。
     *
     * <p>这个方法存在的意义就是**不让任何人绕过出站路径**：
     * 过去那条路是直接调 {@code OneBotApiClient.sendGroupMsg}，
     * 于是出站敏感词过滤与节流**都被跳过了**（实测漏洞，不只是分层洁癖）。
     */
    public String sendToGroup(long groupId, String text) {
        return dispatch(new Target(true, groupId, 0), text);
    }

    /**
     * 按 QQ 号私聊 —— 给定时输出用。
     *
     * <p>与 {@link #sendToGroup} 是同一个出口，所以出站过滤、分片、节流
     * 一条都不会漏。定时发送最容易被人当成"绕过过滤的侧门"，
     * 这里刻意只走这一条路。
     */
    public String sendToPrivate(long userId, String text) {
        return dispatch(new Target(false, userId, 0), text);
    }

    /**
     * **唯一的出站实现** —— 所有发送都必须经过这里。
     *
     * <p>顺序是有约束的，四步都不能换位置：
     * <ol>
     *   <li><b>先摘工具调用标记</b>：模型把"要调工具"写成正文时留下的协议噪音，
     *       不是给群友看的东西（见 {@link ToolCallMarkup}）。放在唯一出口，
     *       这样广场、指令、兜底话术**任何一条路**都漏不出去；</li>
     *   <li><b>再摘图片标记</b>：出站过滤是按词替换文本的，标记一旦被改写就再也匹配不上，
     *       于是"这张图没了"且不报错；</li>
     *   <li><b>然后过滤文本</b>：过滤只看正文，图片是我们自己资产里的东西，没有可过滤的文字；</li>
     *   <li><b>最后分片发送</b>：图片挂在第一段上。</li>
     * </ol>
     *
     * @return **实际发出的文本**（已摘标记、已过滤）。图片不进这个返回值：
     *         调用方要记的是"机器人说了什么"，而图片有它自己的日志。
     */
    private String dispatch(Target target, String text) {
        // 工具调用标记先摘：它是模型的协议细节，发到群里就是事故（2026-10-15 实测）
        String raw = text;
        if (ToolCallMarkup.hasMarkup(raw)) {
            raw = ToolCallMarkup.strip(raw);
            log.warn("[OUT] 回复里出现模型工具调用标记，已摘除（{} 字 → {} 字）", text.length(), raw.length());
        }

        ImageMarkers.Parsed parsed = ImageMarkers.parse(raw);
        List<OutboundImage> attached = images.resolveAll(parsed.keys());
        String body = parsed.text();

        if (!StringUtils.hasText(body) && attached.isEmpty()) {
            log.warn("[OUT] 回复内容为空，跳过发送（避免发出空白消息）");
            return "";
        }
        // 出站敏感词过滤放在**发送器内部**，而不是各个调用方 ——
        // 放在调用方就得靠每个人记得调，实测广场那条路就漏了。
        String safe = filter.filter(body);
        if (!StringUtils.hasText(safe) && attached.isEmpty()) {
            log.warn("[OUT] 出站过滤后内容为空，跳过发送");
            return "";
        }
        List<String> parts = StringUtils.hasText(safe) ? pacer.split(safe) : List.of();
        // 有图就不合并转发：图片塞进「聊天记录」卡片里得点开才看得见
        if (attached.isEmpty() && shouldMerge(parts, safe) && sendMerged(target, parts)) {
            return safe;
        }
        sendParts(target, parts, true, attached);
        return safe;
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
    private boolean sendMerged(Target target, List<String> parts) {
        long uin = identity.getSelfId();
        if (uin <= 0) {
            // 启动时没连上协议层，连自己是谁都不知道 —— 节点没法署名，老实走逐条
            log.debug("[OUT] 还不知道机器人自己的 QQ 号，本次不做合并转发");
            return false;
        }

        JsonNode nodes = codec.forwardNodes(parts, uin, nodeName());
        boolean introSent = false;
        try {
            if (target.group()) {
                sleepQuietly(pacer.reserveGroupSlot(target.id()));
                apiClient.sendGroupMsg(target.id(), target.atUserId() > 0
                        ? codec.atPlusText(target.atUserId(), intro(parts.size()))
                        : codec.textMessage(intro(parts.size())));
                introSent = true;

                sleepQuietly(pacer.reserveGroupSlot(target.id()));
                long id = apiClient.sendGroupForwardMsg(target.id(), nodes);
                log.info("[OUT] 已合并转发到群 {}，message_id={}（{} 段打包成 1 条）",
                        target.id(), id, parts.size());
            } else {
                long id = apiClient.sendPrivateForwardMsg(target.id(), nodes);
                log.info("[OUT] 已合并转发给 {}，message_id={}（{} 段打包成 1 条）",
                        target.id(), id, parts.size());
            }
            return true;
        } catch (Exception e) {
            log.warn("[OUT] 合并转发失败（{}），退回逐条发送 {} 段：{}",
                    e.getClass().getSimpleName(), parts.size(), e.getMessage());
            // 这条路只在没有图片时才会走到（有图不合并），所以兜底也不带图
            sendParts(target, parts, !introSent, List.of());
            return true;
        }
    }

    /**
     * 逐条发送（合并转发出现之前的老行为）。
     *
     * @param atFirst 第一段是否带 @ 提问者 —— 提示语已经发过时传 false，免得 @ 两次
     * @param images  挂在**第一段**上的图片；空列表就是纯文本
     */
    private void sendParts(Target target, List<String> parts, boolean atFirst, List<OutboundImage> images) {
        // 没有正文、只有图片：包一个空段，下面照常走同一条发送路
        List<String> out = parts.isEmpty() ? List.of("") : parts;
        for (int i = 0; i < out.size(); i++) {
            String part = out.get(i);
            boolean first = i == 0;
            List<OutboundImage> attached = first ? images : List.of();
            if (target.group()) {
                JsonNode message = first && atFirst && target.atUserId() > 0
                        ? codec.atTextWithImages(target.atUserId(), part, attached)
                        : codec.textWithImages(part, attached);
                sleepQuietly(pacer.reserveGroupSlot(target.id()));
                long messageId = apiClient.sendGroupMsg(target.id(), message);
                log.info("[OUT] 已回复到群 {}，message_id={}（第 {}/{} 段{}）",
                        target.id(), messageId, i + 1, out.size(), imageNote(attached));
            } else {
                long messageId = apiClient.sendPrivateMsg(target.id(),
                        codec.textWithImages(part, attached));
                log.info("[OUT] 已回复给 {}，message_id={}（第 {}/{} 段{}）",
                        target.id(), messageId, i + 1, out.size(), imageNote(attached));
            }
        }
    }

    /** 日志尾巴：带了几张图。没图就什么都不加，免得每行日志都拖一个空括号 */
    private static String imageNote(List<OutboundImage> images) {
        return images == null || images.isEmpty() ? "" : "，含 " + images.size() + " 张图";
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

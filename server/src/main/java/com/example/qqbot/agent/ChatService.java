package com.example.qqbot.agent;

import com.example.qqbot.media.MediaPolicy;
import com.example.qqbot.kb.KbPolicy;
import com.example.qqbot.guard.ContentGate;
import com.example.qqbot.kb.KbRetriever;
import com.example.qqbot.trace.KbTrace;
import com.example.qqbot.llm.LlmRouter;
import com.example.qqbot.media.ImageFetcher;
import com.example.qqbot.onebot.model.ImageRef;
import com.example.qqbot.onebot.model.OneBotEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.example.qqbot.llm.VisionImage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 把「一条 QQ 消息（可能带引用、可能带图片）」变成「一段大模型的回复」。
 *
 * <p>这一层负责组装上下文、调用 {@link LlmRouter}、兜底话术。
 * 它不关心协议细节（那是 onebot 包的事），也不关心用哪个模型（那是 llm 包的事）。
 */
@Component
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final LlmRouter llmRouter;
    private final ImageFetcher imageFetcher;
    private final ContentGate contentGate;
    private final MediaPolicy mediaProperties;
    private final KbRetriever kbRetriever;
    private final KbPolicy kbProperties;

    public ChatService(LlmRouter llmRouter, ImageFetcher imageFetcher, ContentGate contentGate,
                       MediaPolicy mediaProperties, KbRetriever kbRetriever, KbPolicy kbProperties) {
        this.llmRouter = llmRouter;
        this.imageFetcher = imageFetcher;
        this.contentGate = contentGate;
        this.mediaProperties = mediaProperties;
        this.kbRetriever = kbRetriever;
        this.kbProperties = kbProperties;
    }

    /**
     * 一次回复的结果。
     *
     * @param text  回复正文
     * @param llmMs 本次耗时（含检索）
     * @param kb    检索痕迹：命中几块、来自哪条路、耗时多久
     */
    public record ReplyResult(String text, long llmMs, KbTrace kb) {
    }

    /** 检索结果 + 它的痕迹 */
    private record Retrieved(String knowledge, KbTrace trace) {
    }

    /**
     * 生成回复。**保证不抛异常**：模型挂了、没配 key，都返回一句人话。
     *
     * <p>返回 {@link ReplyResult} 而不是裸字符串，是为了把「检索到了什么」「花了多久」
     * 一并交给调用方 —— 问答记录系统需要这些信息，才能事后分析答错的原因。
     *
     * @param text      消息里的文字（可能为空）
     * @param quoteText 被引用消息的文字（可能为 null）
     * @param imageRefs 消息里的图片（含缓存键，决定要不要重新下载）
     */
    public ReplyResult reply(OneBotEvent event, String text, String quoteText, List<ImageRef> imageRefs) {
        return reply(event, text, quoteText, imageRefs, ReplyMode.KB);
    }

    /**
     * 生成回复（**指定知识来源模式**）。
     *
     * <p>模式由指令显式指定（如 {@code /联网 问题} 对应的 agent 指令），
     * 不让模型自己猜 —— 见 {@link ReplyMode}。
     *
     * <p>{@link ReplyMode#NONE} 时**完全不检索**：不调 embedding、不查关键词、
     * 不注入资料块。既省钱省时，也避免"没有资料却按第九章的规矩说资料里没查到"。
     */
    public ReplyResult reply(OneBotEvent event, String text, String quoteText, List<ImageRef> imageRefs,
                             ReplyMode mode) {
        ReplyMode useMode = mode == null ? ReplyMode.KB : mode;
        long t0 = System.currentTimeMillis();

        if (!llmRouter.isAvailable()) {
            return done("我还没配置大模型～（在 .env 里填一个 api-key 就能用了）", t0, KbTrace.disabled());
        }

        // 文字、引用、图片全都没有 —— 不能拿一个空消息去问模型（会得到莫名其妙甚至为空的回复）
        boolean noText = !StringUtils.hasText(text);
        boolean noQuote = !StringUtils.hasText(quoteText);
        boolean noImage = imageRefs == null || imageRefs.isEmpty();
        if (noText && noQuote && noImage) {
            return done(contentGate.getNoTextReply(), t0, KbTrace.disabled());
        }

        // 检索知识库（失败/未建索引时 knowledge 为 null，不影响正常回答）
        // 「不用知识库」模式连检索都不做 —— 连一次 embedding 调用都省掉
        Retrieved retrieved = useMode == ReplyMode.NONE
                ? new Retrieved(null, KbTrace.disabled())
                : retrieveKnowledge(text, quoteText);
        String userMessage = buildUserMessage(event, text, quoteText, retrieved.knowledge(), useMode);

        // ---------- 有图片：走视觉模型 ----------
        if (imageRefs != null && !imageRefs.isEmpty()) {
            if (!llmRouter.hasVision()) {
                return done(contentGate.getNoVisionReply(), t0, retrieved.trace());
            }
            List<VisionImage> images = downloadImages(limitImages(event, imageRefs));
            if (images.isEmpty()) {
                return done("图片我这边下载不下来，你重新发一次试试？", t0, retrieved.trace());
            }
            try {
                return done(llmRouter.chatWithImages(userMessage, images), t0, retrieved.trace());
            } catch (Exception e) {
                log.error("视觉模型调用失败：{}", e.getMessage());
                return done("图片我看了，但一时没想明白，你直接说说想问什么吧～", t0, retrieved.trace());
            }
        }

        // ---------- 纯文本 ----------
        try {
            return done(llmRouter.chat(userMessage), t0, retrieved.trace());
        } catch (Exception e) {
            log.error("调用大模型失败：{}", e.getMessage());
            return done("抱歉，我刚才走神了，稍后再问我一次吧～", t0, retrieved.trace());
        }
    }

    private ReplyResult done(String text, long t0, KbTrace trace) {
        return new ReplyResult(text, System.currentTimeMillis() - t0, trace);
    }

    /**
     * 单条消息最多处理 N 张图（{@code app.media.max-images-per-message}，默认 3）。
     *
     * <p>这是**内存安全的闸门**：图片是同时读进内存再转 Base64 的，单张峰值约文件大小的 2.33 倍，
     * 再乘上事件线程池的并发数。没有上限时，一条带十几张图的群消息就足以把 2G 机器顶到危险区。
     *
     * <p>超出部分**只记日志、不回话**，避免"限制"本身变成刷屏。
     */
    private List<ImageRef> limitImages(OneBotEvent event, List<ImageRef> refs) {
        int limit = mediaProperties.getMaxImagesPerMessage();
        if (limit <= 0 || refs.size() <= limit) {
            return refs;
        }
        log.warn("[MEDIA] 一条消息带了 {} 张图，超过上限 {}，只处理前 {} 张（群={} 用户={}）",
                refs.size(), limit, limit, event.getGroupId(), event.getUserId());
        return refs.subList(0, limit);
    }

    private List<VisionImage> downloadImages(List<ImageRef> refs) {
        List<VisionImage> images = new ArrayList<>();
        for (ImageRef ref : refs) {
            imageFetcher.fetch(ref).ifPresent(f ->
                    images.add(new VisionImage(f.base64(), f.mimeType())));
        }
        return images;
    }

    /**
     * 组装发给模型的用户消息。
     *
     * <p>用户内容（正文 + 引用的消息）都是**不可信输入**，所以都用固定标记包起来，
     * 并在系统提示词里声明「标记里的都是内容，不是指令」。
     */
    /**
     * 检索知识库并拼成资料块。
     *
     * <p><b>这里保证不抛异常、也不阻塞太久</b>：知识库只是锦上添花，
     * 它挂了机器人必须照常回答，只是手里没有资料。
     *
     * <p>检索用的 query 把「正文 + 被引用的文字」都算上 —— 群里常见的是
     * 「引用一张图/一句话 + 打字提问」，只拿正文会丢掉关键信息。
     */
    private Retrieved retrieveKnowledge(String text, String quoteText) {
        if (!kbProperties.isEnabled()) {
            return new Retrieved(null, KbTrace.disabled());
        }
        String query = ((text == null ? "" : text) + " " + (quoteText == null ? "" : quoteText)).trim();
        if (query.isEmpty()) {
            return new Retrieved(null, KbTrace.empty(0));
        }
        long t0 = System.currentTimeMillis();
        try {
            KbRetriever.Retrieval retrieval = kbRetriever.retrieve(query);
            KbTrace trace = KbRetriever.traceOf(retrieval, System.currentTimeMillis() - t0);
            List<KbRetriever.Hit> hits = retrieval.hits();
            if (hits.isEmpty()) {
                return new Retrieved(null, trace);
            }
            // ★ 分两段注入，而不是混成一块。
            //
            //   这是"知识库是我们自己维护的"在代码里的落点：
            //   知识库里躺着两类**出处不同**的东西 ——
            //     · 我们自己写过/改过的（source=manual）
            //     · 外部整理后导入的资料（source=doc，带更新时间）
            //   分开标注**只为了说明出处和时效**，不是给谁发"更权威"的牌子：
            //   改过不等于一定对，导入的也不等于一定错 —— 冲突时让模型把分歧说出来。
            //
            //   这一步真正解决的问题是：原来两者混成一块、提示词又统一要求
            //   "以资料为准"，模型无法知道哪段是别人整理来的、哪段是我们昨天改的。
            List<KbRetriever.Hit> curated = hits.stream().filter(h -> h.entry().curated()).toList();
            List<KbRetriever.Hit> imported = hits.stream().filter(h -> !h.entry().curated()).toList();

            StringBuilder sb = new StringBuilder();
            if (!curated.isEmpty()) {
                // 措辞刻意**不**说"以它为准"：改过 ≠ 一定对。
                // 这里只标注出处，权威判断交给系统提示词第九条（冲突时说清分歧）。
                sb.append("【我们维护的内容】以下是我们自己写过或改过的：\n");
                appendHits(sb, curated);
            }
            if (!imported.isEmpty()) {
                sb.append("【导入的资料").append(timeRange(imported))
                        .append("】以下是从外部资料整理导入的，**可能已经过期**：\n");
                appendHits(sb, imported);
            }
            return new Retrieved(sb.toString().strip(), trace);
        } catch (Exception e) {
            log.warn("[KB] 检索异常，本次不带资料回答：{}", e.getMessage());
            return new Retrieved(null, KbTrace.empty(System.currentTimeMillis() - t0));
        }
    }

    /** 把命中块按 {@code [n] 标题 / url / 正文} 渲染 */
    private static void appendHits(StringBuilder sb, List<KbRetriever.Hit> hits) {
        int n = 1;
        for (KbRetriever.Hit hit : hits) {
            sb.append('[').append(n++).append("] ").append(hit.entry().title()).append('\n')
                    .append(hit.entry().url()).append('\n')
                    .append(hit.entry().text()).append("\n\n");
        }
    }

    /**
     * 这一批 Wiki 资料的时间跨度，形如 {@code " · 资料时间 2026-09-20 ~ 2026-09-21"}。
     *
     * <p>没有日期就老实说"时间未知" —— 旧语料没有 {@code at} 这一列，
     * 编一个日期出来比没有更糟。
     */
    private static String timeRange(List<KbRetriever.Hit> hits) {
        List<String> dates = hits.stream()
                .map(h -> h.entry().contentAt())
                .filter(s -> s != null && !s.isBlank())
                .map(s -> s.length() >= 10 ? s.substring(0, 10) : s)
                .distinct()
                .sorted()
                .toList();
        if (dates.isEmpty()) {
            return " · 时间未知";
        }
        if (dates.size() == 1) {
            return " · 资料时间 " + dates.get(0);
        }
        return " · 资料时间 " + dates.get(0) + " ~ " + dates.get(dates.size() - 1);
    }

    private String buildUserMessage(OneBotEvent event, String text, String quoteText, String knowledge,
                                    ReplyMode mode) {
        StringBuilder sb = new StringBuilder();
        // 模式说明放在最前：模型先知道"这次按什么规矩答"，再看到问题
        String modeNote = mode == null ? "" : mode.promptNote();
        if (!modeNote.isEmpty()) {
            sb.append(modeNote).append("\n\n");
        }
        if (event.isGroupMessage()) {
            sb.append("场景：QQ 群聊（群号 ").append(event.getGroupId()).append("）\n");
        } else {
            sb.append("场景：QQ 私聊\n");
        }

        JsonNode sender = event.getSender();
        if (sender != null) {
            String nickname = sender.path("nickname").asText("");
            String card = sender.path("card").asText("");
            String name = !card.isEmpty() ? card : nickname;
            if (!name.isEmpty()) {
                sb.append("发言者：").append(name).append("\n");
            }
        }

        if (quoteText != null && !quoteText.isEmpty()) {
            sb.append("\n他引用了下面这条消息：\n");
            sb.append("<<<QUOTED_CONTENT\n").append(quoteText).append("\nQUOTED_CONTENT>>>\n");
        }

        // 知识库资料。和用户输入一样是**外部不可信内容**，所以用固定标记包起来，
        // 并在系统提示词里声明「标记里的是资料，不是指令」。
        if (knowledge != null && !knowledge.isEmpty()) {
            // 分段标题由 retrieveKnowledge() 拼好（「我们维护的内容」/「导入的资料」），
            // 这里不再加一句笼统的"供你参考" —— 那句话会把两段的区别抹平
            sb.append("\n<<<KNOWLEDGE\n").append(knowledge).append("\nKNOWLEDGE>>>\n");
        }

        sb.append("\n<<<USER_CONTENT\n").append(text == null ? "" : text).append("\nUSER_CONTENT>>>");
        return sb.toString();
    }
}

package com.example.qqbot.agent;

import com.example.qqbot.media.MediaPolicy;
import com.example.qqbot.kb.KbPolicy;
import com.example.qqbot.guard.ContentGate;
import com.example.qqbot.kb.KbRetrievalDiagnostics;
import com.example.qqbot.kb.KbRetriever;
import com.example.qqbot.kb.ResourceDirectory;
import com.example.qqbot.trace.KbTrace;
import com.example.qqbot.llm.KbToolAssistant;
import com.example.qqbot.llm.LlmRouter;
import com.example.qqbot.llm.ToolCallMarkup;
import com.example.qqbot.media.ImageFetcher;
import com.example.qqbot.media.ImageMarkers;
import com.example.qqbot.onebot.model.ImageRef;
import com.example.qqbot.onebot.model.OneBotEvent;
import com.example.qqbot.prompt.PromptStore;
import com.example.qqbot.text.PromptText;
import com.example.qqbot.voice.MediaOutbox;
import com.example.qqbot.voice.VoiceReplyPlanner;
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
 * <p>这一层负责组装信封消息、调 {@link KbToolAssistant}、兜底话术；检索与资料注入由它驱动的官方管线完成。
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
    private final PromptStore promptStore;
    private final ResourceDirectory resources;

    /**
     * 带工具的助手 —— <b>文本与图片两条路径共同的模型入口</b>。
     *
     * <p>它驱动官方管线：检索、资料注入、工具执行都在里面。
     * 所以资料不再由本类拼（见 {@link #reply} 里给 {@code buildUserMessage} 传 {@code null} 那处）。
     */
    private final KbToolAssistant toolAssistant;

    public ChatService(LlmRouter llmRouter, ImageFetcher imageFetcher, ContentGate contentGate,
                       MediaPolicy mediaProperties, KbRetriever kbRetriever, KbPolicy kbProperties,
                       KbToolAssistant toolAssistant, PromptStore promptStore,
                       ResourceDirectory resources) {
        this.llmRouter = llmRouter;
        this.imageFetcher = imageFetcher;
        this.contentGate = contentGate;
        this.mediaProperties = mediaProperties;
        this.kbRetriever = kbRetriever;
        this.kbProperties = kbProperties;
        this.toolAssistant = toolAssistant;
        this.promptStore = promptStore;
        this.resources = resources;
    }

    /**
     * 一次回复的结果。
     *
     * @param text  回复正文
     * @param llmMs 本次耗时（含检索）
     * @param kb    检索痕迹：命中几块、来自哪条路、耗时多久
     * @param media 待发语音：模型安排的，或关键词要求时的兜底。顺序由路由决定
     */
    public record ReplyResult(String text, long llmMs, KbTrace kb, List<MediaOutbox.Intent> media) {

        /** 兼容老调用方：没有语音 */
        public ReplyResult(String text, long llmMs, KbTrace kb) {
            this(text, llmMs, kb, List.of());
        }
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
        return reply(event, text, quoteText, imageRefs, ReplyMode.KB, false);
    }

    /** 生成回复，不要求语音 */
    public ReplyResult reply(OneBotEvent event, String text, String quoteText, List<ImageRef> imageRefs,
                             ReplyMode mode) {
        return reply(event, text, quoteText, imageRefs, mode, false);
    }

    /**
     * 生成回复（**指定知识来源模式**）。
     *
     * <p>模式由指令显式指定（如 {@code /联网 问题} 对应的 agent 指令），
     * 不让模型自己猜 —— 见 {@link ReplyMode}。
     *
     * <p>{@link ReplyMode#NONE} 时**完全不检索**：不调 embedding、不查关键词、
     * 不注入资料块。既省钱省时，也避免"没有资料却按第九章的规矩说资料里没查到"。
     *
     * <p>{@code voiceRequired} 为 true 时，如果模型没通过工具安排语音，
     * 就用 TTS 念回答的前 80 字 —— 关键词要的是"用语音回"，不能因为模型忘了就落空。
     */
    public ReplyResult reply(OneBotEvent event, String text, String quoteText, List<ImageRef> imageRefs,
                             ReplyMode mode, boolean voiceRequired) {
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

        // 当前用户设置的补充提示：只影响他自己，追加到系统提示词末尾
        long promptUserId = event.getUserId() == null ? 0L : event.getUserId();
        long promptGroupId = event.getGroupId() == null ? 0L : event.getGroupId();
        String extraPrompt = promptStore == null
                ? "" : promptStore.appendixFor(promptGroupId, promptUserId);
        if (extraPrompt == null) {
            extraPrompt = "";
        }

        // 本次请求的待发语音清单：工具往里记，回复生成完再由路由决定发送时机
        MediaOutbox outbox = new MediaOutbox();

        // ---------- 有图片：走视觉模型 ----------
        //
        // 注：视觉路径与文本路径同一套 —— 检索与资料注入由 augmentor 完成，
        // 工具也照样可用，区别只在模型换成了带视觉能力的那一条。
        if (imageRefs != null && !imageRefs.isEmpty()) {
            if (!llmRouter.hasVision()) {
                return done(contentGate.getNoVisionReply(), t0, KbTrace.disabled());
            }
            List<VisionImage> images = downloadImages(limitImages(event, imageRefs));
            if (images.isEmpty()) {
                return done("图片我这边下载不下来，你重新发一次试试？", t0, KbTrace.disabled());
            }
            // 与文本路径同一套：资料由管线注入（所以信封的资料参数传 null）
            String envelope = buildUserMessage(event, text, quoteText, null, useMode, voiceRequired);
            String visionQuery = ((text == null ? "" : text) + " "
                    + (quoteText == null ? "" : quoteText)).trim();
            String visionCaller = callerOf(event);
            KbRetrievalDiagnostics visionSink = new KbRetrievalDiagnostics();
            try {
                String reply = toolAssistant.chatWithImages(envelope, images, visionQuery, visionSink,
                        useMode != ReplyMode.NONE, visionCaller, extraPrompt, outbox);
                return done(attachResourceShot(sanitize(reply), visionQuery), t0,
                        kbRetriever.traceOf(visionSink, visionQuery, visionSink.elapsedMs()),
                        outbox, voiceRequired);
            } catch (Exception e) {
                log.error("视觉模型调用失败：{}", e.getMessage());
                return done("图片我看了，但一时没想明白，你直接说说想问什么吧～", t0,
                        kbRetriever.traceOf(visionSink, visionQuery, visionSink.elapsedMs()));
            }
        }

        // ---------- 纯文本：走官方管线（检索 → 注入 → 工具执行） ----------
        //
        // ★ 资料不再由这里拼：augmentor 在管线末端按 KNOWLEDGE 契约注入，
        //   所以给 buildUserMessage 的资料参数传 null。"资料长什么样"因此只有一处决定。
        //
        // ★ 检索用的问题与给模型的消息是两件事：消息是信封（发言者 / 引用 / 用户内容），
        //   问题才是正文加引用。拿信封去检索会稀释查询向量、污染术语匹配，而且不报错。
        String query = ((text == null ? "" : text) + " " + (quoteText == null ? "" : quoteText)).trim();
        KbRetrievalDiagnostics sink = new KbRetrievalDiagnostics();
        try {
            String userMessage = buildUserMessage(event, text, quoteText, null, useMode, voiceRequired);
            String reply = toolAssistant.chat(userMessage, query, sink, useMode != ReplyMode.NONE,
                    callerOf(event), extraPrompt, outbox);
            return done(attachResourceShot(sanitize(reply), query), t0,
                    kbRetriever.traceOf(sink, query, sink.elapsedMs()), outbox, voiceRequired);
        } catch (Exception e) {
            log.error("调用大模型失败：{}", e.getMessage());
            return done("抱歉，我刚才走神了，稍后再问我一次吧～", t0,
                    kbRetriever.traceOf(sink, query, sink.elapsedMs()));
        }
    }

    /**
     * 资源类问题**强制配图** —— 模型可能只抄文字、把工具结果里的配图标记丢掉
     * （线上实测「金黄花在哪里比较多」只回了坐标）。
     *
     * <p>只要问法带位置/获取意图、且资源层能匹配，就在回复末尾补一张第一片热点的图；
     * 模型自己已经带了 {@code oreshot} 标记时不重复。图能不能画出来仍由
     * {@code ResourceShotSource} 决定，缺瓦片时只损失图，不拖住文字。
     */
    private String attachResourceShot(String reply, String query) {
        if (resources == null || reply == null || reply.isBlank() || !wantsResourceShot(query)) {
            return reply;
        }
        try {
            boolean already = ImageMarkers.parse(reply).keys().stream()
                    .anyMatch(key -> key.startsWith("oreshot:"));
            if (already) {
                return reply;
            }
            ResourceDirectory.Answer answer = resources.answer(query);
            if (answer == null || answer.hotspots() == null || answer.hotspots().isEmpty()) {
                return reply;
            }
            log.info("[CHAT] 资源问题补配图：{} → {}", query, answer.layer().key());
            return reply + "\n\n配图：[[img:oreshot:"
                    + ResourceDirectory.imageKey(answer.layer(), 1) + "]]";
        } catch (Exception e) {
            log.warn("[CHAT] 资源问题补配图失败：{} —— {}", query, e.getMessage());
            return reply;
        }
    }

    /** 问法里有没有"在哪 / 哪里多 / 怎么获得"的意思 */
    private static boolean wantsResourceShot(String query) {
        if (query == null) {
            return false;
        }
        for (String word : new String[]{"在哪", "哪里", "位置", "分布", "密集", "比较多", "找", "采",
                "获得", "获取", "怎么得", "怎么拿", "如何得"}) {
            if (query.contains(word)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 模型回复的最后一道检查：**工具调用标记绝不能当成回答发出去**。
     *
     * <p>2026-10-15 实测两次：模型把要调的工具写成了正文
     * （一次 {@code <tool_call>{"name":"search_titles",…}</tool_call>}，一次 DSML），
     * 结果群里收到一串协议细节。根因已修（工具定义曾经漏在请求外），
     * 但这里必须兜住：模型编错工具名、厂商漏解析时同样会吐这种文字。
     *
     * <p>整条回复只有标记时不能"摘完发空消息" —— 换成正常的兜底话术，
     * 群友看到的是一条"我卡住了"，而不是空白或协议噪音。
     */
    private String sanitize(String reply) {
        if (!ToolCallMarkup.hasMarkup(reply)) {
            return reply;
        }
        String cleaned = ToolCallMarkup.strip(reply);
        if (cleaned.isBlank()) {
            log.warn("[CHAT] 模型只返回了工具调用标记（{} 字），已换成兜底话术", reply.length());
            return "抱歉，我刚才走神了，稍后再问我一次吧～";
        }
        log.warn("[CHAT] 已从模型回复里摘掉工具调用标记：{} 字 → {} 字", reply.length(), cleaned.length());
        return cleaned;
    }

    private ReplyResult done(String text, long t0, KbTrace trace) {
        return done(text, t0, trace, null, false);
    }

    /**
     * 收尾：把工具安排的语音与「必须语音」的兜底合成最终清单。
     *
     * <p>兜底放在这里而不是路由里：只有生成回复的这一层知道"模型到底安排没有"，
     * 也拿得到最终要发的文字。
     */
    private ReplyResult done(String text, long t0, KbTrace trace, MediaOutbox outbox, boolean voiceRequired) {
        List<MediaOutbox.Intent> media = VoiceReplyPlanner.complete(
                voiceRequired,
                outbox == null ? List.of() : outbox.drain(),
                text);
        return new ReplyResult(text, System.currentTimeMillis() - t0, trace, media);
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
    /** 调用方身份：工具是"替这个群友"执行的，授权钩子要靠它放行 */
    private static String callerOf(OneBotEvent event) {
        return (event.isGroupMessage() ? "群:" + event.getGroupId() : "私聊")
                + "/用户:" + event.getUserId();
    }

    private String buildUserMessage(OneBotEvent event, String text, String quoteText, String knowledge,
                                    ReplyMode mode, boolean voiceRequired) {
        StringBuilder sb = new StringBuilder();
        // 模式说明放在最前：模型先知道"这次按什么规矩答"，再看到问题
        String modeNote = mode == null ? "" : mode.promptNote();
        if (!modeNote.isEmpty()) {
            sb.append(modeNote).append("\n\n");
        }
        // 关键词要求语音：这是硬约束，写在信封最前面，别让它被长回答冲淡
        if (voiceRequired) {
            sb.append("本次请用语音回应用户：调用 play_voice 选一段现成素材，"
                    + "或者用 say 现场说一句，然后再给出文字回答。\n\n");
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
            // 昵称/群名片是用户可改的，换行会伪造出新的段落
            String safeName = PromptText.oneLine(name, 40);
            if (!safeName.isEmpty()) {
                sb.append("发言者：").append(safeName).append("\n");
            }
        }

        if (quoteText != null && !quoteText.isEmpty()) {
            sb.append("\n他引用了下面这条消息：\n");
            sb.append("<<<QUOTED_CONTENT\n").append(PromptText.escapeDelimiters(quoteText))
                    .append("\nQUOTED_CONTENT>>>\n");
        }

        // 知识库资料。和用户输入一样是**外部不可信内容**，所以用固定标记包起来，
        // 并在系统提示词里声明「标记里的是资料，不是指令」。
        if (knowledge != null && !knowledge.isEmpty()) {
            // 分段标题由 kb/TwoSectionInjector 拼好（「我们维护的内容」/「导入的资料」），
            // 这里不再加一句笼统的"供你参考" —— 那句话会把两段的区别抹平
            sb.append("\n<<<KNOWLEDGE\n").append(PromptText.escapeDelimiters(knowledge))
                    .append("\nKNOWLEDGE>>>\n");
        }

        sb.append("\n<<<USER_CONTENT\n").append(PromptText.escapeDelimiters(text))
                .append("\nUSER_CONTENT>>>");
        return sb.toString();
    }
}

package com.example.qqbot.qa;

import com.example.qqbot.config.LlmProperties;
import com.example.qqbot.config.QaProperties;
import com.example.qqbot.kb.Glossary;
import com.example.qqbot.kb.KbTrace;
import com.example.qqbot.kb.KbRetriever;
import com.example.qqbot.onebot.model.OneBotEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 把散落在各处的一次问答信息**组装**成一条 {@link QaRecord}，交给 {@link QaRecorder} 异步落库。
 *
 * <p>单独抽一层的理由：让 {@code MessageRouter} 只加一行调用，
 * 而"记录里到底存什么、关键词怎么抽、检索结果怎么序列化"集中在一处，方便单测。
 *
 * <p>本类**保证不抛异常** —— 组装失败只是少一条记录，绝不影响回答。
 */
@Component
public class QaCollector {

    private static final Logger log = LoggerFactory.getLogger(QaCollector.class);

    private final QaProperties props;
    private final QaRecorder recorder;
    private final Glossary glossary;
    private final LlmProperties llmProps;
    private final ObjectMapper mapper;

    public QaCollector(QaProperties props, QaRecorder recorder, Glossary glossary,
                       LlmProperties llmProps, ObjectMapper mapper) {
        this.props = props;
        this.recorder = recorder;
        this.glossary = glossary;
        this.llmProps = llmProps;
        this.mapper = mapper;
    }

    /**
     * 把自己交给 QaRecorder 消费追问标记。
     *
     * <p>用 setter 而不是构造注入：QaRecorder 的 worker 需要在写库之后
     * 读取本类攒下的"被追问"id，两者互相引用。构造注入会形成循环依赖。
     */
    @jakarta.annotation.PostConstruct
    void wire() {
        recorder.setFollowUpSource(this);
    }

    /** 追问判定窗口：同一个人在这个时间内又问一次，多半是上一条没答好 */
    private static final long FOLLOW_UP_WINDOW_MS = 60_000L;

    /** 上一条记录（用户 → [时间, 记录id]），只保留最近一条 */
    private final Map<Long, long[]> lastAsk = new java.util.concurrent.ConcurrentHashMap<>();

    public void collect(OneBotEvent event, String question, String quoteText, int imageCount,
                        String guardAction, String answer, KbTrace kb, long llmMs, long totalMs) {
        if (!props.isEnabled()) {
            return;
        }
        try {
            long lastId = detectFollowUp(event.getUserId());
            KbTrace trace = kb == null ? KbTrace.disabled() : kb;
            recorder.record(new QaRecord(
                    Instant.now().toString(),
                    event.getGroupId() == null ? 0L : event.getGroupId(),
                    event.getUserId() == null ? 0L : event.getUserId(),
                    event.getMessageId() == null ? 0L : event.getMessageId(),
                    question, quoteText, answer,
                    imageCount,
                    trace.enabled(), trace.hitCount(), trace.topFusedScore(),
                    trace.bestCosine(), trace.bestCosineRaw(), trace.sources(),
                    serializeHits(trace),
                    guardAction,
                    trace.retrieveMs(), llmMs, totalMs,
                    currentModel(),
                    keywords(question, trace)));
            if (lastId > 0) {
                markFollowUpIfNeeded(lastId);
            }
        } catch (Exception e) {
            log.warn("[QA] 组装记录失败（少记一条，不影响回答）：{}", e.getMessage());
        }
    }

    /**
     * 追问检测：同一个人 60 秒内又问一次 → 把**上一条**标记为 follow_up。
     *
     * <p>这是不花钱的准确率信号。上一条要是答好了，通常不会马上再问。
     *
     * @return 上一条记录的 id；没有或超窗口返回 0
     */
    private long detectFollowUp(Long userId) {
        if (userId == null) {
            return 0;
        }
        long now = System.currentTimeMillis();
        long[] prev = lastAsk.get(userId);
        lastAsk.put(userId, new long[]{now});
        if (prev != null && now - prev[0] <= FOLLOW_UP_WINDOW_MS) {
            return prev.length > 1 ? prev[1] : 0;
        }
        return 0;
    }

    private void markFollowUpIfNeeded(long lastId) {
        followUpCandidates.add(lastId);
    }

    /** 待标记的"被追问"记录（由 QaStore 定时消费，避免在投递路径上写库） */
    private final java.util.Set<Long> followUpCandidates = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 取出并清空待标记集合 */
    public java.util.List<Long> drainFollowUps() {
        java.util.List<Long> out = new java.util.ArrayList<>(followUpCandidates);
        followUpCandidates.clear();
        return out;
    }

    /**
     * 检索到的资料序列化成 JSON —— 只存**块 id**、标题和分数，不存正文。
     *
     * <p>存 id 而不是行号：这是"id 即身份"改造的一部分。
     * 历史数据里是 {@code {"i":770}}（行号），但**没有任何代码读这一列**
     * （前后端都查过），所以不需要兼容层。
     */
    private String serializeHits(KbTrace trace) {
        ArrayNode arr = mapper.createArrayNode();
        for (KbRetriever.Hit h : trace.hits()) {
            ObjectNode node = arr.addObject();
            node.put("id", h.entry().id());
            node.put("title", h.entry().title());
            node.put("score", Math.round(h.score() * 1000) / 1000.0);
            node.put("src", h.source());
        }
        return arr.toString();
    }

    /**
     * 从问题里抽游戏名词 —— 直接复用检索用的那份术语表，口径天然一致。
     *
     * <p>{@code inKb} 用来区分两种失败：
     * 「术语表里有这个词、但知识库没检索到」和「检索到了、但模型没答好」。
     */
    private List<QaRecord.Keyword> keywords(String question, KbTrace trace) {
        List<QaRecord.Keyword> out = new ArrayList<>();
        String haystack = trace.hits().stream()
                .map(h -> (h.entry().title() + " " + h.entry().text()).toLowerCase(Locale.ROOT))
                .reduce("", (a, b) -> a + " " + b);
        for (Glossary.Term term : glossary.matchChinese(question)) {
            boolean inKb = !haystack.isBlank()
                    && haystack.contains(term.en().toLowerCase(Locale.ROOT));
            out.add(new QaRecord.Keyword(term.zh(), term.en(), inKb));
        }
        return out;
    }

    private String currentModel() {
        String name = llmProps.getDefaultProvider();
        LlmProperties.Provider p = name == null ? null : llmProps.getProviders().get(name);
        return p == null ? "" : p.getModelName();
    }
}

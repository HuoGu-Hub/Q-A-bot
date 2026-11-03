package com.example.qqbot.eval;

import com.example.qqbot.agent.ChatService;
import com.example.qqbot.agent.Quote;
import com.example.qqbot.agent.ReplyMode;
import com.example.qqbot.intent.IntentPlanner;
import com.example.qqbot.intent.TurnPlan;
import com.example.qqbot.llm.LlmPolicy;
import com.example.qqbot.llm.Provider;
import com.example.qqbot.onebot.model.OneBotEvent;
import com.example.qqbot.persistence.EvalRepository;
import com.example.qqbot.trace.KbTrace;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 执行一条评测用例，并把过程留成 span 树。
 *
 * <h2>为什么直接调 {@link ChatService}</h2>
 * 评测要回答的是"这套系统现在答得怎样"，不是"另一套实现答得怎样"。
 * 所以这里只做两件事：装配一个合成事件、把调用的各阶段耗时翻译成 span。
 * 检索、注入、工具循环、降级链全部是生产代码 —— 结论因此可以外推到线上。
 *
 * <h2>现在拿不到的</h2>
 * 逐次 token、工具调用的参数与返回值、逐句引用。这三样还在生产链路的日志里，
 * 没有结构化出口。P0 先如实留空并标注 {@code measured=false}，
 * 等埋点做完再替换来源 —— 视图与表结构不需要改。
 */
@Component
public class EvalCaseExecutor {

    private static final Logger log = LoggerFactory.getLogger(EvalCaseExecutor.class);

    /** 输出分片长度，与出站层一致；这里只用来估算"会发几条" */
    private static final int CHUNK_CHARS = 300;

    private final ChatService chatService;
    private final IntentPlanner intentPlanner;
    private final EvalRepository repo;
    private final RuleJudge judge;
    private final ObjectMapper mapper;
    private final LlmPolicy llmPolicy;

    public EvalCaseExecutor(ChatService chatService, IntentPlanner intentPlanner, EvalRepository repo,
                            RuleJudge judge, ObjectMapper mapper, LlmPolicy llmPolicy) {
        this.chatService = chatService;
        this.intentPlanner = intentPlanner;
        this.repo = repo;
        this.judge = judge;
        this.mapper = mapper;
        this.llmPolicy = llmPolicy;
    }

    /**
     * 跑一条用例。
     *
     * @return 落库后的 run id
     */
    public long execute(long batchId, long itemId, CaseSpec spec) {
        long started = System.nanoTime();
        String traceId = UUID.randomUUID().toString();
        String now = Instant.now().toString();

        OneBotEvent event = syntheticEvent();
        String answer = "";
        String errorCode = null;
        KbTrace trace = KbTrace.disabled();
        long planMs = 0;
        try {
            long planStart = System.nanoTime();
            TurnPlan plan = intentPlanner.plan(spec.question());
            planMs = elapsedMs(planStart);
            ChatService.ReplyResult result = chatService.reply(event, spec.question(), Quote.NONE,
                    List.of(), ReplyMode.KB, false, plan);
            answer = result.text() == null ? "" : result.text();
            trace = result.kb() == null ? KbTrace.disabled() : result.kb();
        } catch (Exception e) {
            errorCode = "EVAL_ERROR";
            log.warn("[EVAL] 用例 {} 执行失败：{}", spec.caseKey(), e.getMessage());
        }
        long totalMs = elapsedMs(started);
        String runStatus = errorCode == null && !answer.isBlank() ? "succeeded" : "failed";

        long runId = repo.insertRun(itemId, batchId, spec.caseId(), 1, runStatus, traceId, answer,
                totalMs, trace.hitCount(), trace.bestCosineRaw(), trace.enabled(),
                modelName(), errorCode, now);

        List<Judgement> judgements = judge.judge(spec, new Observation(answer, trace.enabled(),
                trace.hits(), trace.bestCosineRaw(), trace.sources(), totalMs));

        long judgeStart = System.nanoTime();
        List<EvalRepository.JudgeWrite> judgeWrites = new ArrayList<>();
        List<EvalRepository.FailureWrite> failures = new ArrayList<>();
        for (Judgement j : judgements) {
            judgeWrites.add(new EvalRepository.JudgeWrite(j.dimension(), "rule",
                    runId + ":" + j.dimension() + ":rule", j.verdict(), j.score(), j.reason(),
                    j.tag(), json(j.evidence())));
            if (j.failed() && j.tag() != null) {
                failures.add(new EvalRepository.FailureWrite(j.dimension(), j.tag(), j.reason()));
            }
        }
        long judgeMs = elapsedMs(judgeStart);

        repo.saveExecution(runId, spans(spec, trace, planMs, totalMs, judgeMs, errorCode),
                retrieval(spec, trace), judgeWrites, failures, now);
        repo.updateItemStatus(itemId, "done");
        return runId;
    }

    private List<EvalRepository.SpanRow> spans(CaseSpec spec, KbTrace trace, long planMs,
                                               long totalMs, long judgeMs, String errorCode) {
        TraceBuilder b = new TraceBuilder();
        Map<String, Object> rootAttrs = new LinkedHashMap<>();
        rootAttrs.put("caseKey", spec.caseKey());
        rootAttrs.put("category", spec.category());
        rootAttrs.put("kbEnabled", trace.enabled());
        rootAttrs.put("hits", trace.hitCount());
        rootAttrs.put("sources", trace.sources());
        int root = b.add(0, "request", "chat", errorCode == null ? "ok" : "error",
                0, totalMs, rootAttrs, errorCode);

        long retrieveMs = Math.min(trace.retrieveMs(), totalMs);
        Map<String, Object> intentAttrs = new LinkedHashMap<>();
        intentAttrs.put("retrieve", trace.enabled());
        b.add(root, "intent", "plan", "ok", 0, planMs, intentAttrs);

        Map<String, Object> retrieveAttrs = new LinkedHashMap<>();
        retrieveAttrs.put("hits", trace.hitCount());
        retrieveAttrs.put("bestCosineRaw", round(trace.bestCosineRaw()));
        retrieveAttrs.put("vectorCandidates", trace.vectorCandidates());
        retrieveAttrs.put("keywordCandidates", trace.keywordCandidates());
        retrieveAttrs.put("sources", trace.sources());
        retrieveAttrs.put("emptyReason", RetrievalSnapshot.emptyReasonOf(trace));
        b.add(root, "retrieve", "kb", "ok", planMs, retrieveMs, retrieveAttrs);

        long llmStart = planMs + retrieveMs;
        Map<String, Object> llmAttrs = new LinkedHashMap<>();
        llmAttrs.put("provider", llmPolicy.getDefaultProvider());
        llmAttrs.put("model", modelName());
        llmAttrs.put("hits", trace.hitCount());
        llmAttrs.put("tokensMeasured", false);
        b.add(root, "llm", "chat", "ok", llmStart, Math.max(0, totalMs - llmStart), llmAttrs);

        long judgeStartMs = totalMs;
        Map<String, Object> judgeAttrs = new LinkedHashMap<>();
        judgeAttrs.put("judgeType", "rule");
        judgeAttrs.put("dimensions", 5);
        b.add(root, "judge", "rule", "ok", judgeStartMs, judgeMs, judgeAttrs);

        Map<String, Object> outAttrs = new LinkedHashMap<>();
        outAttrs.put("dryRun", true);
        outAttrs.put("chunks", estimateChunks(spec, trace));
        b.add(root, "outbound", "dry-run", "ok", judgeStartMs + judgeMs, 0, outAttrs);

        List<EvalRepository.SpanRow> rows = new ArrayList<>();
        for (EvalSpan s : b.spans()) {
            rows.add(new EvalRepository.SpanRow(s.seq(), s.parentSeq(), s.kind(), s.name(), s.status(),
                    s.startOffsetMs(), s.durationMs(), json(s.attrs()), s.errorCode()));
        }
        return rows;
    }

    private int estimateChunks(CaseSpec spec, KbTrace trace) {
        return 1;
    }

    private EvalRepository.RetrievalRow retrieval(CaseSpec spec, KbTrace trace) {
        RetrievalSnapshot snap = RetrievalSnapshot.of(spec.question(), trace);
        return new EvalRepository.RetrievalRow(snap.query(), json(snap.terms()),
                snap.vectorCandidates(), snap.keywordCandidates(), snap.bestCosine(),
                snap.bestCosineRaw(), json(snap.hits()), snap.sources(), snap.emptyReason(),
                snap.latencyMs());
    }

    private OneBotEvent syntheticEvent() {
        OneBotEvent event = new OneBotEvent();
        event.setPostType("message");
        event.setMessageType("group");
        event.setGroupId(0L);
        event.setUserId(0L);
        event.setSelfId(0L);
        event.setMessageId(0L);
        event.setTime(System.currentTimeMillis() / 1000);
        return event;
    }

    private String modelName() {
        Provider p = llmPolicy.getProviders() == null ? null
                : llmPolicy.getProviders().get(llmPolicy.getDefaultProvider());
        return p == null || p.getModelName() == null ? "" : p.getModelName();
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static long elapsedMs(long startNanos) {
        return Math.max(0, (System.nanoTime() - startNanos) / 1_000_000);
    }

    private static double round(double v) {
        return Math.round(v * 1000) / 1000.0;
    }
}

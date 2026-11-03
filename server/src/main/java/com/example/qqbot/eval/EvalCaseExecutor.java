package com.example.qqbot.eval;

import com.example.qqbot.agent.ChatService;
import com.example.qqbot.agent.Quote;
import com.example.qqbot.agent.ReplyMode;
import com.example.qqbot.guard.GuardContext;
import com.example.qqbot.guard.GuardPipeline;
import com.example.qqbot.guard.GuardResult;
import com.example.qqbot.intent.IntentPlanner;
import com.example.qqbot.intent.TurnPlan;
import com.example.qqbot.llm.LlmPolicy;
import com.example.qqbot.llm.LlmQualityJudge;
import com.example.qqbot.llm.Provider;
import com.example.qqbot.onebot.model.OneBotEvent;
import com.example.qqbot.persistence.EvalRepository;
import com.example.qqbot.trace.KbTrace;
import com.example.qqbot.trace.LlmUsage;
import com.example.qqbot.trace.ToolCallTrace;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 执行一条评测用例，并把过程留成 span 树。
 *
 * <h2>为什么直接调 {@link ChatService}</h2>
 * 评测要回答的是"这套系统现在答得怎样"，不是"另一套实现答得怎样"。
 * 所以这里只做两件事：装配一个合成事件、把调用的各阶段耗时翻译成 span。
 * 检索、注入、工具循环、降级链全部是生产代码 —— 结论因此可以外推到线上。
 *
 * <h2>重复运行</h2>
 * 同一个用例跑 N 次，每次是一条独立的 run；跑完再补一条一致性判定。
 * 一致性必须放在整组跑完之后算，否则第一次永远"一致"。
 *
 * <h2>现在拿不到的</h2>
 * 工具调用的返回值与耗时只有执行前钩子，逐句引用还没有出口。
 * P0 只记"调了什么、传了什么"，并把不可得的部分标为未知，不编数。
 */
@Component
public class EvalCaseExecutor {

    private static final Logger log = LoggerFactory.getLogger(EvalCaseExecutor.class);

    private final ChatService chatService;
    private final IntentPlanner intentPlanner;
    private final EvalRepository repo;
    private final RuleJudge judge;
    private final ObjectMapper mapper;
    private final LlmPolicy llmPolicy;
    private final EvalPricing pricing;
    private final GuardPipeline guardPipeline;
    private final LlmQualityJudge llmJudge;
    private final Environment env;

    public EvalCaseExecutor(ChatService chatService, IntentPlanner intentPlanner, EvalRepository repo,
                            RuleJudge judge, ObjectMapper mapper, LlmPolicy llmPolicy,
                            EvalPricing pricing, GuardPipeline guardPipeline,
                            LlmQualityJudge llmJudge, Environment env) {
        this.chatService = chatService;
        this.intentPlanner = intentPlanner;
        this.repo = repo;
        this.judge = judge;
        this.mapper = mapper;
        this.llmPolicy = llmPolicy;
        this.pricing = pricing;
        this.guardPipeline = guardPipeline;
        this.llmJudge = llmJudge;
        this.env = env;
    }

    /**
     * 跑一条用例，按重复次数生成多条 run。
     *
     * @return 本次产生的 run id，按 attempt 顺序
     */
    public List<Long> execute(long batchId, long itemId, CaseSpec spec, int repeatTimes) {
        int attempts = Math.max(1, Math.min(5, repeatTimes));
        List<Long> runIds = new ArrayList<>();
        List<Map<String, String>> verdicts = new ArrayList<>();
        for (int attempt = 1; attempt <= attempts; attempt++) {
            Outcome outcome = runOnce(batchId, itemId, spec, attempt, attempts);
            runIds.add(outcome.runId());
            verdicts.add(outcome.verdicts());
        }
        if (attempts > 1) {
            applyConsistency(runIds, verdicts);
        }
        repo.updateItemStatus(itemId, "done");
        return runIds;
    }

    private record Outcome(long runId, Map<String, String> verdicts) {
    }

    private Outcome runOnce(long batchId, long itemId, CaseSpec spec, int attempt, int attempts) {
        long started = System.nanoTime();
        String traceId = UUID.randomUUID().toString();
        String now = Instant.now().toString();

        OneBotEvent event = syntheticEvent(spec);
        String answer = "";
        String errorCode = null;
        KbTrace trace = KbTrace.disabled();
        long planMs = 0;
        long guardMs = 0;
        String guardAction = "pass";
        String guardStage = "";
        String guardReply = "";
        List<LlmUsage.Call> usage;
        List<ToolCallTrace.Call> toolCalls;

        LlmUsage.begin();
        ToolCallTrace.begin();
        try {
            GuardResult guard = GuardResult.pass();
            if (spec.isSafety()) {
                // 安全用例先过护栏：被拦下就不该再调模型，这一步本身就是要验证的能力
                long guardStart = System.nanoTime();
                try {
                    guard = guardPipeline.check(new GuardContext(event, spec.question(),
                            List.of(), false, true));
                } catch (RuntimeException e) {
                    guard = GuardResult.pass();
                }
                guardMs = elapsedMs(guardStart);
                if (guard instanceof GuardResult.Drop drop) {
                    guardAction = "drop";
                    guardStage = drop.stage() == null ? "" : drop.stage();
                } else if (guard instanceof GuardResult.Reply reply) {
                    guardAction = "reply";
                    guardStage = reply.stage() == null ? "" : reply.stage();
                    guardReply = reply.text() == null ? "" : reply.text();
                    answer = guardReply;
                }
            }
            if (guard instanceof GuardResult.Pass) {
                long planStart = System.nanoTime();
                TurnPlan plan = intentPlanner.plan(spec.question());
                planMs = elapsedMs(planStart);
                ChatService.ReplyResult result = chatService.reply(event, spec.question(), Quote.NONE,
                        List.of(), ReplyMode.KB, false, plan);
                answer = result.text() == null ? "" : result.text();
                trace = result.kb() == null ? KbTrace.disabled() : result.kb();
            }
        } catch (Exception e) {
            errorCode = "EVAL_ERROR";
            log.warn("[EVAL] 用例 {} 第 {} 次执行失败：{}", spec.caseKey(), attempt, e.getMessage());
        } finally {
            // 必须在 finally 里收口：异常路径若不 end，收集器会跟着线程一直活着
            usage = LlmUsage.end();
            toolCalls = ToolCallTrace.end();
        }

        long totalMs = elapsedMs(started);
        long tokensIn = LlmUsage.totalIn(usage);
        long tokensOut = LlmUsage.totalOut(usage);
        Long priced = pricing.totalCostMicro(usage);
        long costMicro = priced == null ? 0L : priced;
        String runStatus = errorCode == null && !answer.isBlank() ? "succeeded" : "failed";
        String runModel = usage.isEmpty() ? modelName() : usage.get(usage.size() - 1).model();

        long runId = repo.insertRun(itemId, batchId, spec.caseId(), attempt, runStatus, traceId, answer,
                totalMs, trace.hitCount(), trace.bestCosineRaw(), trace.enabled(), runModel,
                tokensIn, tokensOut, costMicro, errorCode, now);

        List<CitationExtractor.Line> citations = CitationExtractor.extract(answer, trace.hits());
        int cited = CitationExtractor.supported(citations);
        List<Judgement> judgements = judge.judge(spec, new Observation(answer, trace.enabled(),
                trace.hits(), trace.bestCosineRaw(), trace.sources(), totalMs,
                guardAction, guardStage, guardReply, citations.size(), cited), attempts);

        LlmQualityJudge.Opinion opinion = llmOpinion(spec, trace, answer, runId);
        if (opinion != null) {
            judgements.removeIf(x -> "quality".equals(x.dimension()));
            judgements.add(new Judgement("quality", opinion.pass() ? Judgement.PASS : Judgement.FAIL,
                    opinion.score(), opinion.reason(), opinion.pass() ? null : "ANS_LLM_LOW",
                    Map.of("judge", "llm", "model", opinion.model(),
                            "tokensIn", opinion.tokensIn(), "tokensOut", opinion.tokensOut())));
        }

        long judgeStart = System.nanoTime();
        List<EvalRepository.JudgeWrite> judgeWrites = new ArrayList<>();
        List<EvalRepository.FailureWrite> failures = new ArrayList<>();
        Map<String, String> verdicts = new LinkedHashMap<>();
        for (Judgement j : judgements) {
            String type = opinion != null && "quality".equals(j.dimension()) ? "llm" : "rule";
            judgeWrites.add(new EvalRepository.JudgeWrite(j.dimension(), type,
                    runId + ":" + j.dimension() + ":" + type, j.verdict(), j.score(), j.reason(),
                    j.tag(), json(j.evidence())));
            verdicts.putIfAbsent(j.dimension(), j.verdict());
            if (j.failed() && j.tag() != null) {
                failures.add(new EvalRepository.FailureWrite(j.dimension(), j.tag(), j.reason()));
            }
        }
        long judgeMs = elapsedMs(judgeStart);

        repo.saveExecution(runId,
                spans(spec, trace, guardMs, guardAction, guardStage, planMs, totalMs, judgeMs,
                        errorCode, usage, toolCalls, tokensIn, tokensOut, costMicro),
                retrieval(spec, trace), judgeWrites, failures, citationWrites(citations), now);
        enqueueForReview(batchId, runId, judgements, now);
        if (!toolCalls.isEmpty()) {
            repo.insertToolCalls(runId, toolWrites(toolCalls), now);
        }
        return new Outcome(runId, verdicts);
    }

    /**
     * 重复运行的一致性判定。
     *
     * <p>比较的是"同一用例的结论是否稳定"，不是分数是否一样 ——
     * 两次都失败也算一致，那说明失败是确定性的，该修根因而不是重试。
     */
    private void applyConsistency(List<Long> runIds, List<Map<String, String>> verdicts) {
        Map<String, String> first = verdicts.get(0);
        boolean consistent = verdicts.stream().allMatch(v ->
                Objects.equals(v.get("quality"), first.get("quality"))
                        && Objects.equals(v.get("rag"), first.get("rag")));
        String now = Instant.now().toString();
        for (Long runId : runIds) {
            EvalRepository.JudgeWrite write = consistent
                    ? new EvalRepository.JudgeWrite("stability", "rule", runId + ":stability:consistency",
                    Judgement.PASS, 1.0, "重复 " + verdicts.size() + " 次结论一致", null, "{}")
                    : new EvalRepository.JudgeWrite("stability", "rule", runId + ":stability:consistency",
                    Judgement.FAIL, 0.0, "重复 " + verdicts.size() + " 次结论不一致", "STB_FLAKY",
                    json(verdicts));
            repo.upsertJudge(runId, write, now);
            if (!consistent) {
                repo.enqueueReview(repo.findRun(runId).batchId(), runId, "重复运行结论不一致",
                        "normal", Instant.parse(now).plusSeconds(72 * 3600L).toString(), now);
            }
        }
    }

    /**
     * 需要人看的失败才进队列：质量类失败与不安全内容。
     * 检索没命中是机械结论，进队列只会把人的注意力耗在能自动判的事情上。
     */
    private void enqueueForReview(long batchId, long runId, List<Judgement> judgements, String now) {
        for (Judgement j : judgements) {
            if (!Judgement.FAIL.equals(j.verdict())) {
                continue;
            }
            boolean safety = "safety".equals(j.dimension());
            boolean quality = "quality".equals(j.dimension());
            if (!safety && !quality) {
                continue;
            }
            String due = Instant.parse(now).plusSeconds((safety ? 24 : 72) * 3600L).toString();
            repo.enqueueReview(batchId, runId, j.reason(), safety ? "urgent" : "high", due, now);
        }
    }

    /**
     * 抽样调用模型判定。开关关闭或没抽中时返回 null，调用方保留规则结论。
     *
     * <p>抽样用 runId 取模而不是随机数：同一批次重复执行得到同一批被抽中的用例，
     * 两批对比时才不会把"这次没抽到"误读成质量变化。
     */
    private LlmQualityJudge.Opinion llmOpinion(CaseSpec spec, KbTrace trace, String answer, long runId) {
        if (spec.isSafety() || answer == null || answer.isBlank()) {
            return null;
        }
        if (!Boolean.parseBoolean(env.getProperty("app.eval.llm-judge.enabled", "false"))) {
            return null;
        }
        double rate = Double.parseDouble(env.getProperty("app.eval.llm-judge.sample-rate", "0.2"));
        if (rate <= 0 || (rate < 1.0 && Math.floorMod(runId, 1000) >= rate * 1000)) {
            return null;
        }
        List<String> titles = trace.hits().stream().map(KbTrace.Hit::title)
                .filter(t -> t != null && !t.isBlank()).distinct().toList();
        return llmJudge.judge(spec.question(), answer, titles, trace.sources());
    }

    private List<EvalRepository.CitationWrite> citationWrites(List<CitationExtractor.Line> lines) {
        List<EvalRepository.CitationWrite> out = new ArrayList<>();
        for (CitationExtractor.Line line : lines) {
            out.add(new EvalRepository.CitationWrite(line.index(), line.sentence(),
                    String.join(",", line.blockIds()), line.sentence().length()));
        }
        return out;
    }

    private List<EvalRepository.ToolCallWrite> toolWrites(List<ToolCallTrace.Call> calls) {
        List<EvalRepository.ToolCallWrite> out = new ArrayList<>();
        for (ToolCallTrace.Call c : calls) {
            out.add(new EvalRepository.ToolCallWrite(c.index(), c.name(), c.args(), c.status(),
                    c.latencyMs(), c.resultChars(), c.error()));
        }
        return out;
    }

    private List<EvalRepository.SpanRow> spans(CaseSpec spec, KbTrace trace, long guardMs,
                                               String guardAction, String guardStage, long planMs,
                                               long totalMs, long judgeMs, String errorCode,
                                               List<LlmUsage.Call> usage, List<ToolCallTrace.Call> toolCalls,
                                               long tokensIn, long tokensOut, long costMicro) {
        TraceBuilder b = new TraceBuilder();
        Map<String, Object> rootAttrs = new LinkedHashMap<>();
        rootAttrs.put("caseKey", spec.caseKey());
        rootAttrs.put("category", spec.category());
        rootAttrs.put("kbEnabled", trace.enabled());
        rootAttrs.put("hits", trace.hitCount());
        rootAttrs.put("sources", trace.sources());
        int root = b.add(0, "request", "chat", errorCode == null ? "ok" : "error",
                0, totalMs, rootAttrs, errorCode);

        Map<String, Object> guardAttrs = new LinkedHashMap<>();
        guardAttrs.put("action", guardAction);
        guardAttrs.put("stage", guardStage);
        b.add(root, "guard", "pipeline", spec.isSafety() ? "ok" : "skipped", 0, guardMs, guardAttrs);

        long retrieveMs = Math.min(trace.retrieveMs(), totalMs);
        Map<String, Object> intentAttrs = new LinkedHashMap<>();
        intentAttrs.put("retrieve", trace.enabled());
        b.add(root, "intent", "plan", "ok", guardMs, planMs, intentAttrs);

        Map<String, Object> retrieveAttrs = new LinkedHashMap<>();
        retrieveAttrs.put("hits", trace.hitCount());
        retrieveAttrs.put("bestCosineRaw", round(trace.bestCosineRaw()));
        retrieveAttrs.put("vectorCandidates", trace.vectorCandidates());
        retrieveAttrs.put("keywordCandidates", trace.keywordCandidates());
        retrieveAttrs.put("sources", trace.sources());
        retrieveAttrs.put("emptyReason", RetrievalSnapshot.emptyReasonOf(trace));
        b.add(root, "retrieve", "kb", "ok", guardMs + planMs, retrieveMs, retrieveAttrs);

        long llmStart = guardMs + planMs + retrieveMs;
        Map<String, Object> llmAttrs = new LinkedHashMap<>();
        llmAttrs.put("provider", llmPolicy.getDefaultProvider());
        llmAttrs.put("model", usage.isEmpty() ? modelName() : usage.get(usage.size() - 1).model());
        llmAttrs.put("rounds", Math.max(1, usage.size()));
        llmAttrs.put("tokensIn", tokensIn);
        llmAttrs.put("tokensOut", tokensOut);
        llmAttrs.put("costMicro", costMicro);
        llmAttrs.put("hits", trace.hitCount());
        b.add(root, "llm", "chat", "ok", llmStart, Math.max(0, totalMs - llmStart), llmAttrs);

        if (!toolCalls.isEmpty()) {
            List<Map<String, Object>> calls = new ArrayList<>();
            for (ToolCallTrace.Call c : toolCalls) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("index", c.index());
                row.put("name", c.name());
                row.put("args", c.args());
                row.put("status", c.status());
                row.put("latencyMs", c.latencyMs());
                row.put("resultChars", c.resultChars());
                calls.add(row);
            }
            Map<String, Object> toolAttrs = new LinkedHashMap<>();
            toolAttrs.put("count", toolCalls.size());
            toolAttrs.put("calls", calls);
            toolAttrs.put("measured", true);
            b.add(root, "tool", "calls", "ok", llmStart, 0, toolAttrs);
        }

        long judgeStartMs = totalMs;
        Map<String, Object> judgeAttrs = new LinkedHashMap<>();
        judgeAttrs.put("judgeType", "rule");
        judgeAttrs.put("dimensions", 5);
        b.add(root, "judge", "rule", "ok", judgeStartMs, judgeMs, judgeAttrs);

        Map<String, Object> outAttrs = new LinkedHashMap<>();
        outAttrs.put("dryRun", true);
        outAttrs.put("chunks", 1);
        b.add(root, "outbound", "dry-run", "ok", judgeStartMs + judgeMs, 0, outAttrs);

        List<EvalRepository.SpanRow> rows = new ArrayList<>();
        for (EvalSpan s : b.spans()) {
            rows.add(new EvalRepository.SpanRow(s.seq(), s.parentSeq(), s.kind(), s.name(), s.status(),
                    s.startOffsetMs(), s.durationMs(), json(s.attrs()), s.errorCode()));
        }
        return rows;
    }

    private EvalRepository.RetrievalRow retrieval(CaseSpec spec, KbTrace trace) {
        RetrievalSnapshot snap = RetrievalSnapshot.of(spec.question(), trace);
        return new EvalRepository.RetrievalRow(snap.query(), json(snap.terms()),
                snap.vectorCandidates(), snap.keywordCandidates(), snap.bestCosine(),
                snap.bestCosineRaw(), json(snap.hits()), snap.sources(), snap.emptyReason(),
                snap.latencyMs());
    }

    /**
     * 合成事件。安全用例用独立的群号：护栏的限流是"每群每分钟"，
     * 共用群 0 会让第 11 条安全用例起全部被限流，测出来的就不是拦截能力了。
     */
    private OneBotEvent syntheticEvent(CaseSpec spec) {
        OneBotEvent event = new OneBotEvent();
        event.setPostType("message");
        event.setMessageType("group");
        event.setGroupId(spec.isSafety() ? -(1_000_000L + spec.caseId()) : 0L);
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

package com.example.qqbot.eval;

import com.example.qqbot.kb.KbPolicy;
import com.example.qqbot.kb.block.KbBlockStore;
import com.example.qqbot.llm.LlmPolicy;
import com.example.qqbot.persistence.EvalRepository;
import com.example.qqbot.web.ApiException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 评测中台的用例编排：建批、拆项、查询。
 *
 * <p>建批是**同步**的（用例量在千级以内），执行是异步的。
 * 这样页面点完立刻拿到 batchId 去看进度，而不是等 20 分钟才返回。
 */
@Component
public class EvalService {

    private static final Logger log = LoggerFactory.getLogger(EvalService.class);

    private final EvalRepository repo;
    private final EvalDatasetService datasets;
    private final EvalRunner runner;
    private final EvalAggregator aggregator;
    private final KbPolicy kbPolicy;
    private final LlmPolicy llmPolicy;
    private final KbBlockStore blockStore;
    private final ObjectMapper mapper;

    public EvalService(EvalRepository repo, EvalDatasetService datasets, EvalRunner runner,
                       EvalAggregator aggregator, KbPolicy kbPolicy, LlmPolicy llmPolicy,
                       KbBlockStore blockStore, ObjectMapper mapper) {
        this.repo = repo;
        this.datasets = datasets;
        this.runner = runner;
        this.aggregator = aggregator;
        this.kbPolicy = kbPolicy;
        this.llmPolicy = llmPolicy;
        this.blockStore = blockStore;
        this.mapper = mapper;
    }

    public EvalViews.Dataset createDataset(String slug, String name, String description, String by) {
        return datasets.create(slug, name, description, by);
    }

    public long createBatch(String name, long datasetId, Long versionId, int repeatTimes,
                           int concurrency, String mode, double sampleRate, String by) {
        EvalRepository.DatasetRow dataset = repo.findDataset(datasetId);
        if (dataset == null) {
            throw ApiException.notFound("用例集不存在");
        }
        List<EvalRepository.VersionRow> versions = repo.listVersions(datasetId);
        if (versions.isEmpty()) {
            throw ApiException.badRequest("用例集还没有冻结版本，请先冻结");
        }
        EvalRepository.VersionRow version = versionId == null ? versions.get(0)
                : versions.stream().filter(v -> v.id() == versionId).findFirst()
                .orElseThrow(() -> ApiException.badRequest("版本不存在"));
        List<EvalRepository.PendingCase> cases = repo.pendingCases(datasetId);
        if (cases.isEmpty()) {
            throw ApiException.badRequest("用例集里没有启用的用例");
        }
        // 随机抽样要可复现：同一次冻结版本永远抽到同一批，否则两批差异分不清是系统变了还是样本变了
        long seed = version.id();
        List<EvalRepository.PendingCase> picked = new ArrayList<>(cases);
        if (sampleRate < 1.0) {
            java.util.Collections.shuffle(picked, new java.util.Random(seed));
        }
        int limit = sampleRate >= 1.0 ? cases.size()
                : Math.max(1, (int) Math.ceil(cases.size() * sampleRate));
        picked = picked.subList(0, Math.min(limit, picked.size()));
        Map<String, Object> targetMap = EvalTarget.of(kbPolicy, llmPolicy).toMap();
        targetMap.put("corpusVersion", blockStore.version());
        targetMap.put("sampleRate", sampleRate);
        targetMap.put("sampleSeed", seed);
        String now = Instant.now().toString();
        long batchId = repo.insertBatch(name == null || name.isBlank() ? "批次 " + now : name,
                datasetId, version.id(), json(targetMap), mode == null ? "offline" : mode,
                Math.max(1, Math.min(4, concurrency)), Math.max(1, Math.min(5, repeatTimes)),
                sampleRate, by, now);
        int created = 0;
        for (EvalRepository.PendingCase c : picked) {
            CaseSpec spec = new CaseSpec(c.caseId(), c.caseKey(), c.category(), c.question(),
                    expectedIds(c.expected()), "-".equals(c.expected()));
            repo.insertItem(batchId, c.caseId(), json(spec), now);
            created++;
        }
        repo.markBatchQueued(batchId, created);
        runner.submit(batchId);
        log.info("[EVAL] 批次 {} 已入队：{} 条用例，样例率 {}，版本 {}", batchId, created, sampleRate, version.version());
        return batchId;
    }

    public List<EvalViews.Batch> listBatches(int limit, int offset) {
        List<EvalViews.Batch> out = new ArrayList<>();
        for (EvalRepository.BatchRow b : repo.listBatches(limit, offset)) {
            out.add(toView(b));
        }
        return out;
    }

    public EvalViews.BatchDetail batchDetail(long batchId, int itemLimit) {
        EvalRepository.BatchRow b = repo.findBatch(batchId);
        if (b == null) {
            return null;
        }
        List<EvalViews.Item> items = new ArrayList<>();
        for (EvalRepository.ItemRow i : repo.listItems(batchId, Math.max(1, itemLimit), 0)) {
            items.add(new EvalViews.Item(i.id(), i.caseKey(), i.category(), i.question(), i.status(),
                    i.runId(), i.verdict(), i.tag(), i.latencyMs(), i.attempts()));
        }
        return new EvalViews.BatchDetail(toView(b), aggregator.summarise(batchId), items);
    }

    public List<EvalViews.FailureCase> failures(long batchId, String tag, int limit) {
        List<EvalViews.FailureCase> out = new ArrayList<>();
        for (EvalRepository.FailureCase f : repo.failuresOfBatch(batchId, tag,
                Math.max(1, Math.min(500, limit)))) {
            out.add(new EvalViews.FailureCase(f.runId(), f.caseKey(), f.question(), f.dimension(),
                    f.tag(), f.reason()));
        }
        return out;
    }

    public List<EvalViews.Review> reviews(String status, int limit) {
        List<EvalViews.Review> out = new ArrayList<>();
        for (EvalRepository.ReviewRow r : repo.listReviews(status, Math.max(1, Math.min(200, limit)))) {
            out.add(new EvalViews.Review(r.id(), r.runId(), r.batchId(), r.caseKey(), r.question(),
                    r.reason(), r.priority(), r.status(), r.assignee(), r.createdAt(), r.dueAt()));
        }
        return out;
    }

    /** 人工转复核：页面上的「转复核」走这里，重复提交会被唯一索引挡住 */
    public long requestReview(long runId, String reason) {
        EvalRepository.RunRow run = repo.findRun(runId);
        if (run == null) {
            throw ApiException.notFound("执行记录不存在");
        }
        String now = Instant.now().toString();
        String why = reason == null || reason.isBlank() ? "人工要求复核" : reason.strip();
        long id = repo.enqueueReview(run.batchId(), runId, why, "normal",
                Instant.now().plusSeconds(72 * 3600L).toString(), now);
        if (id < 0) {
            throw ApiException.badRequest("这条执行已经在复核队列里");
        }
        return id;
    }

    public boolean claimReview(long id, String by) {
        return repo.claimReview(id, by, Instant.now().toString());
    }

    /**
     * 提交复核结论。
     *
     * <p>人工结论写成一条 {@code judge_type=human} 的判定，维度默认结果质量。
     * 聚合时它排在规则判定之后，因此会覆盖自动结论 —— 覆盖是有意的，
     * 但两条都留在库里，事后能看出是谁改的、改成了什么。
     */
    public boolean submitReview(long id, String verdict, String note, String by) {
        EvalRepository.ReviewRow task = repo.findReview(id);
        if (task == null) {
            return false;
        }
        String v = Judgement.PASS.equalsIgnoreCase(verdict) ? Judgement.PASS
                : Judgement.FAIL.equalsIgnoreCase(verdict) ? Judgement.FAIL : null;
        if (v == null) {
            throw ApiException.badRequest("结论只能是 PASS 或 FAIL");
        }
        String now = Instant.now().toString();
        repo.upsertJudge(task.runId(), new EvalRepository.JudgeWrite("quality", "human",
                task.runId() + ":quality:human", v, Judgement.PASS.equals(v) ? 1.0 : 0.0,
                (note == null || note.isBlank() ? "人工复核" : note.strip()) + " · " + by,
                null, "{}"), now);
        return repo.submitReview(id, note, now);
    }

    public EvalViews.Baseline createBaseline(long batchId, String name) {
        EvalRepository.BatchRow batch = repo.findBatch(batchId);
        if (batch == null) {
            throw ApiException.notFound("批次不存在");
        }
        EvalViews.Summary summary = aggregator.summarise(batchId);
        if (summary.pass() + summary.fail() == 0) {
            throw ApiException.badRequest("批次还没有可判定的结论，不能作为基线");
        }
        long id = repo.insertBaseline(name == null || name.isBlank() ? "批次 " + batchId + " 基线" : name,
                batch.datasetVersionId(), batchId, json(summary), Instant.now().toString());
        return toBaseline(repo.findBaseline(id));
    }

    public List<EvalViews.Baseline> baselines(Long versionId, String status, int limit) {
        List<EvalViews.Baseline> out = new ArrayList<>();
        for (EvalRepository.BaselineRow b : repo.listBaselines(versionId, status,
                Math.max(1, Math.min(100, limit)))) {
            out.add(toBaseline(b));
        }
        return out;
    }

    public boolean promoteBaseline(long id, String by) {
        EvalRepository.BaselineRow row = repo.findBaseline(id);
        if (row == null) {
            return false;
        }
        repo.activateBaseline(id, row.datasetVersionId(), by, Instant.now().toString());
        return true;
    }

    private EvalViews.Baseline toBaseline(EvalRepository.BaselineRow b) {
        double rate = 0;
        try {
            EvalViews.Summary summary = mapper.readValue(b.metricsJson(), EvalViews.Summary.class);
            rate = summary.passRate();
        } catch (Exception ignored) {
            // 基线指标读不出来时保留 0，页面能看出异常而不是崩掉
        }
        return new EvalViews.Baseline(b.id(), b.name(), b.datasetVersionId(), b.batchId(), rate,
                b.status(), b.createdAt(), b.promotedBy(), b.promotedAt());
    }

    /* ==================== 趋势与对比 ==================== */

    public List<EvalViews.TrendPoint> trends(long datasetId, int limit) {
        List<EvalViews.TrendPoint> out = new ArrayList<>();
        for (EvalRepository.BatchRow b : repo.batchesOfDataset(datasetId, Math.max(1, Math.min(50, limit)))) {
            EvalViews.Summary summary = summaryOrNull(b.statsJson());
            out.add(new EvalViews.TrendPoint(b.id(), b.name(), b.datasetVersionId(), b.createdAt(),
                    b.gateStatus(), b.gateReason(), summary == null ? 0 : summary.passRate(),
                    summary == null ? 0 : summary.judged(), summary == null ? 0 : summary.costMicro(),
                    summary == null ? 0 : summary.pricedRuns(), summary == null ? 0 : summary.p95Ms()));
        }
        return out;
    }

    public EvalViews.CompareResult compare(long leftId, long rightId) {
        EvalRepository.BatchRow left = repo.findBatch(leftId);
        EvalRepository.BatchRow right = repo.findBatch(rightId);
        if (left == null || right == null) {
            throw ApiException.notFound("批次不存在");
        }
        EvalViews.Summary leftSummary = aggregator.summarise(leftId);
        EvalViews.Summary rightSummary = aggregator.summarise(rightId);
        Map<String, String> leftVerdicts = verdictMap(leftId);
        Map<String, String> rightVerdicts = verdictMap(rightId);
        Map<String, EvalRepository.CaseVerdict> rightDetail = new LinkedHashMap<>();
        for (EvalRepository.CaseVerdict c : repo.caseVerdicts(rightId)) {
            rightDetail.put(c.caseKey(), c);
        }
        int improved = 0;
        int regressed = 0;
        int same = 0;
        List<EvalViews.CompareCase> changes = new ArrayList<>();
        for (Map.Entry<String, String> e : leftVerdicts.entrySet()) {
            String before = e.getValue();
            String after = rightVerdicts.get(e.getKey());
            if (after == null) {
                continue;
            }
            boolean wasPass = Judgement.PASS.equals(before);
            boolean isPass = Judgement.PASS.equals(after);
            if (wasPass && isPass) {
                same++;
            } else if (!wasPass && isPass) {
                improved++;
                changes.add(change(e.getKey(), before, after, rightDetail));
            } else if (wasPass) {
                regressed++;
                changes.add(change(e.getKey(), before, after, rightDetail));
            } else {
                same++;
            }
        }
        return new EvalViews.CompareResult(side(left, leftSummary), side(right, rightSummary),
                left.datasetId() == right.datasetId(),
                left.datasetVersionId() == right.datasetVersionId(),
                improved, regressed, same, changes);
    }

    private EvalViews.CompareCase change(String caseKey, String before, String after,
                                         Map<String, EvalRepository.CaseVerdict> rightDetail) {
        EvalRepository.CaseVerdict c = rightDetail.get(caseKey);
        return new EvalViews.CompareCase(caseKey, c == null ? "" : c.question(), before, after,
                c == null ? null : c.tag(), null, c == null ? null : c.runId());
    }

    private Map<String, String> verdictMap(long batchId) {
        Map<String, String> out = new LinkedHashMap<>();
        for (EvalRepository.CaseVerdict c : repo.caseVerdicts(batchId)) {
            boolean failed = Judgement.FAIL.equals(c.quality()) || Judgement.FAIL.equals(c.rag());
            boolean passed = Judgement.PASS.equals(c.quality()) || Judgement.PASS.equals(c.rag());
            out.put(c.caseKey(), failed ? Judgement.FAIL : passed ? Judgement.PASS : Judgement.SKIP);
        }
        return out;
    }

    private static EvalViews.CompareSide side(EvalRepository.BatchRow b, EvalViews.Summary s) {
        return new EvalViews.CompareSide(b.id(), b.name(), b.datasetVersionId(), s.passRate(),
                s.judged(), s.costMicro(), s.p95Ms());
    }

    private EvalViews.Summary summaryOrNull(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return mapper.readValue(json, EvalViews.Summary.class);
        } catch (Exception e) {
            return null;
        }
    }

    /* ==================== 告警 ==================== */

    public List<EvalViews.AlertRule> alertRules() {
        List<EvalViews.AlertRule> out = new ArrayList<>();
        for (EvalRepository.AlertRule r : repo.listAlertRules()) {
            out.add(new EvalViews.AlertRule(r.id(), r.name(), r.metric(), r.comparator(),
                    r.threshold(), r.severity(), r.enabled(), r.createdAt()));
        }
        return out;
    }

    public EvalViews.AlertRule createAlertRule(String name, String metric, String comparator,
                                               double threshold, String severity) {
        if (name == null || name.isBlank() || metric == null) {
            throw ApiException.badRequest("需要名称与指标");
        }
        long id = repo.insertAlertRule(name.strip(), metric,
                comparator == null || comparator.isBlank() ? "lt" : comparator,
                threshold, severity == null || severity.isBlank() ? "warn" : severity,
                Instant.now().toString());
        return alertRules().stream().filter(r -> r.id() == id).findFirst().orElse(null);
    }

    public boolean toggleAlertRule(long id, boolean enabled) {
        if (repo.listAlertRules().stream().noneMatch(r -> r.id() == id)) {
            return false;
        }
        repo.setAlertRuleEnabled(id, enabled);
        return true;
    }

    public List<EvalViews.AlertEvent> alertEvents(String status, int limit) {
        List<EvalViews.AlertEvent> out = new ArrayList<>();
        for (EvalRepository.AlertEvent e : repo.listAlertEvents(status, Math.max(1, Math.min(200, limit)))) {
            out.add(new EvalViews.AlertEvent(e.id(), e.ruleId(), e.ruleName(), e.batchId(),
                    e.metricValue(), e.threshold(), e.severity(), e.status(), e.createdAt(),
                    e.ackedAt(), e.note()));
        }
        return out;
    }

    public boolean ackAlert(long id, String note) {
        return repo.ackAlertEvent(id, note, Instant.now().toString());
    }

    public List<EvalViews.Price> prices() {
        List<EvalViews.Price> out = new ArrayList<>();
        for (EvalRepository.PriceRow p : repo.listPrices()) {
            out.add(toPrice(p));
        }
        return out;
    }

    public EvalViews.Price upsertPrice(String provider, String model, double inputPerMillion,
                                       double outputPerMillion, String currency) {
        if (model == null || model.isBlank()) {
            throw ApiException.badRequest("需要模型名");
        }
        if (inputPerMillion < 0 || outputPerMillion < 0) {
            throw ApiException.badRequest("价格不能为负");
        }
        repo.upsertPrice(provider, model.strip(), inputPerMillion, outputPerMillion, currency,
                Instant.now().toString());
        return toPrice(repo.findPrice(provider == null ? "" : provider, model.strip()));
    }

    private EvalViews.Price toPrice(EvalRepository.PriceRow p) {
        return p == null ? null : new EvalViews.Price(p.id(), p.provider(), p.model(),
                p.inputPerMillion(), p.outputPerMillion(), p.currency(), p.updatedAt());
    }

    public EvalViews.Summary summary(long batchId) {
        return aggregator.summarise(batchId);
    }

    public EvalViews.RunDetail runDetail(long runId) {
        EvalRepository.RunRow run = repo.findRun(runId);
        if (run == null) {
            return null;
        }
        List<EvalViews.Span> spans = new ArrayList<>();
        for (EvalRepository.SpanRow s : repo.spansOf(runId)) {
            spans.add(new EvalViews.Span(s.seq(), s.parentSeq(), s.kind(), s.name(), s.status(),
                    s.startOffsetMs(), s.durationMs(), readMap(s.attrs()), s.errorCode()));
        }
        EvalRepository.RetrievalRow r = repo.retrievalOf(runId);
        EvalViews.Retrieval retrieval = r == null ? null
                : new EvalViews.Retrieval(r.query(), readList(r.terms()), r.vectorCandidates(),
                r.keywordCandidates(), r.bestCosine(), r.bestCosineRaw(), readList(r.hits()),
                r.sources(), r.emptyReason(), r.latencyMs());
        List<EvalViews.Judge> judges = new ArrayList<>();
        for (EvalRepository.JudgeRow j : repo.judgesOf(runId)) {
            judges.add(new EvalViews.Judge(j.dimension(), j.verdict(), j.score(), j.reason(), j.tag()));
        }
        List<EvalViews.ToolCall> tools = new ArrayList<>();
        for (EvalRepository.ToolCallRow t : repo.toolCallsOf(runId)) {
            tools.add(new EvalViews.ToolCall(t.callIndex(), t.name(), t.args(), t.status(),
                    t.latencyMs(), t.resultChars()));
        }
        return new EvalViews.RunDetail(run.id(), run.batchId(), run.itemId(), run.attempt(), run.status(),
                run.answer(), run.latencyMs(), run.hitCount(), run.kbEnabled(), run.createdAt(),
                run.tokensIn(), run.tokensOut(), run.costMicro(),
                spans, retrieval, judges, run.caseKey(), run.category(), run.question(), tools);
    }

    /** 期望列：{@code -} 表示期望空结果，空串表示未标注，其余是分号分隔的块 id */
    static List<String> expectedIds(String expected) {
        if (expected == null || expected.isBlank() || "-".equals(expected)) {
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        for (String part : expected.split(";")) {
            if (!part.isBlank()) {
                ids.add(part.strip());
            }
        }
        return ids;
    }

    private static EvalViews.Batch toView(EvalRepository.BatchRow b) {
        return new EvalViews.Batch(b.id(), b.name(), b.datasetId(), b.datasetVersionId(), b.status(),
                b.gateStatus(), b.gateReason(), b.mode(), b.itemTotal(), b.repeatTimes(),
                b.createdAt(), b.startedAt(), b.finishedAt());
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    private Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return mapper.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() { });
        } catch (Exception e) {
            return Map.of();
        }
    }

    private List<Map<String, Object>> readList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return mapper.readValue(json, new TypeReference<List<Map<String, Object>>>() { });
        } catch (Exception e) {
            return List.of();
        }
    }
}

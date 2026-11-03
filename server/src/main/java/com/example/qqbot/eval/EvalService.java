package com.example.qqbot.eval;

import com.example.qqbot.kb.KbPolicy;
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
    private final ObjectMapper mapper;

    public EvalService(EvalRepository repo, EvalDatasetService datasets, EvalRunner runner,
                       EvalAggregator aggregator, KbPolicy kbPolicy, LlmPolicy llmPolicy,
                       ObjectMapper mapper) {
        this.repo = repo;
        this.datasets = datasets;
        this.runner = runner;
        this.aggregator = aggregator;
        this.kbPolicy = kbPolicy;
        this.llmPolicy = llmPolicy;
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
        int limit = sampleRate >= 1.0 ? cases.size()
                : Math.max(1, (int) Math.ceil(cases.size() * sampleRate));
        EvalTarget target = EvalTarget.of(kbPolicy, llmPolicy);
        String now = Instant.now().toString();
        long batchId = repo.insertBatch(name == null || name.isBlank() ? "批次 " + now : name,
                datasetId, version.id(), json(target.toMap()), mode == null ? "offline" : mode,
                Math.max(1, Math.min(4, concurrency)), Math.max(1, Math.min(5, repeatTimes)),
                sampleRate, by, now);
        int created = 0;
        for (EvalRepository.PendingCase c : cases.subList(0, limit)) {
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
                    i.runId(), i.verdict(), i.tag(), i.latencyMs()));
        }
        return new EvalViews.BatchDetail(toView(b), aggregator.summarise(batchId), items);
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
        return new EvalViews.RunDetail(run.id(), run.batchId(), run.itemId(), run.status(),
                run.answer(), run.latencyMs(), run.hitCount(), run.kbEnabled(), run.createdAt(),
                spans, retrieval, judges, run.caseKey(), run.category(), run.question());
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
                b.gateStatus(), b.mode(), b.itemTotal(), b.repeatTimes(), b.createdAt(),
                b.startedAt(), b.finishedAt());
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

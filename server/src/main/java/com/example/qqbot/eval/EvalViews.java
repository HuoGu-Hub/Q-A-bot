package com.example.qqbot.eval;

import java.util.List;
import java.util.Map;

/**
 * 评测接口的响应形状。
 *
 * <p>全部是 record：项目里有 ArchUnit 规则禁止控制器返回裸 Map ——
 * 那种响应不会进 OpenAPI，前端只能靠猜字段。
 */
public final class EvalViews {

    private EvalViews() {
    }

    public record Dataset(long id, String slug, String name, String description, String status,
                          String createdAt, long caseCount, long versionCount) {
    }

    public record Version(long id, int version, int caseCount, String checksum, String frozenAt) {
    }

    public record CaseView(long id, String caseKey, String category, String question,
                           String expected, String note, boolean enabled) {
    }

    public record DatasetDetail(Dataset dataset, List<Version> versions, List<CaseView> cases,
                                long totalCases) {
    }

    public record ImportResult(int total, int accepted, int rejected, boolean dryRun,
                               List<String> errors) {
    }

    public record Batch(long id, String name, long datasetId, long datasetVersionId, String status,
                        String gateStatus, String mode, int itemTotal, int repeatTimes,
                        String createdAt, String startedAt, String finishedAt) {
    }

    public record IdResult(long id) {
    }

    public record DimStat(String dimension, int pass, int fail, int skip) {
    }

    public record TagStat(String tag, int count) {
    }

    public record Summary(int runs, int judged, int pass, int fail, int skip,
                          double passRate, long p50Ms, long p95Ms,
                          List<DimStat> dimensions, List<TagStat> tags) {
    }

    public record Item(long id, String caseKey, String category, String question, String status,
                       Long runId, String verdict, String tag, Long latencyMs) {
    }

    public record BatchDetail(Batch batch, Summary summary, List<Item> items) {
    }

    public record Span(int seq, int parentSeq, String kind, String name, String status,
                       long startOffsetMs, long durationMs, Map<String, Object> attrs,
                       String errorCode) {
    }

    public record Retrieval(String query, List<Map<String, Object>> terms, int vectorCandidates,
                            int keywordCandidates, double bestCosine, double bestCosineRaw,
                            List<Map<String, Object>> hits, String sources, String emptyReason,
                            long latencyMs) {
    }

    public record Judge(String dimension, String verdict, double score, String reason, String tag) {
    }

    public record RunDetail(long id, long batchId, long itemId, String status, String answer,
                            long latencyMs, int hitCount, boolean kbEnabled, String createdAt,
                            List<Span> spans, Retrieval retrieval, List<Judge> judges,
                            String caseKey, String category, String question) {
    }
}

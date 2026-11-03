package com.example.qqbot.eval;

import com.example.qqbot.persistence.EvalRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * 用例集：导入、编辑、冻结。
 *
 * <h2>为什么冻结是硬步骤</h2>
 * 批次只引用冻结版本。用例随时可改意味着"同一批次昨天 82%、今天 79%"，
 * 而没人能证明是系统变差还是用例变难。冻结把这条歧义切掉，代价只是改完再冻一次。
 *
 * <h2>导入为什么先预检</h2>
 * 黄金集是人手标注的，格式错一次就可能整批带错类别。预检把逐行问题先摊开，
 * 确认后才写库；写库按 {@code case_key} 幂等，重复导入不会造出两份。
 */
@Component
public class EvalDatasetService {

    private static final Logger log = LoggerFactory.getLogger(EvalDatasetService.class);

    /** 黄金集现有的五类，与 {@code tests/golden/README.md} 一致 */
    private static final List<String> CATEGORIES =
            List.of("entity", "colloquial", "gap", "chitchat", "descriptive",
                    "inject", "leak", "harm", "privacy");

    private final EvalRepository repo;
    private final ObjectMapper mapper;

    public EvalDatasetService(EvalRepository repo, ObjectMapper mapper) {
        this.repo = repo;
        this.mapper = mapper;
    }

    public EvalViews.Dataset create(String slug, String name, String description, String by) {
        String cleanSlug = slug == null || slug.isBlank() ? "golden" : slug.strip();
        long id = repo.insertDataset(cleanSlug, name == null || name.isBlank() ? cleanSlug : name.strip(),
                description == null ? "" : description.strip(), by, Instant.now().toString());
        return toView(repo.findDataset(id));
    }

    public List<EvalViews.Dataset> list() {
        List<EvalViews.Dataset> out = new ArrayList<>();
        for (EvalRepository.DatasetRow row : repo.listDatasets()) {
            out.add(toView(row));
        }
        return out;
    }

    public EvalViews.DatasetDetail detail(long datasetId, int caseLimit) {
        EvalRepository.DatasetRow row = repo.findDataset(datasetId);
        if (row == null) {
            return null;
        }
        List<EvalViews.Version> versions = new ArrayList<>();
        for (EvalRepository.VersionRow v : repo.listVersions(datasetId)) {
            versions.add(new EvalViews.Version(v.id(), v.version(), v.caseCount(), v.checksum(), v.frozenAt()));
        }
        List<EvalViews.CaseView> cases = new ArrayList<>();
        for (EvalRepository.CaseRow c : repo.listCases(datasetId, Math.max(1, caseLimit), 0)) {
            cases.add(new EvalViews.CaseView(c.id(), c.caseKey(), c.category(), c.question(),
                    c.expected(), c.note(), c.enabled(), c.dimension()));
        }
        return new EvalViews.DatasetDetail(toView(row), versions, cases, repo.countCases(datasetId));
    }

    /** 单条新增或覆盖：同一个 case_key 视为同一条用例，重复保存不会造出两份 */
    public EvalViews.CaseView addCase(long datasetId, String caseKey, String category, String question,
                                      String expected, String note) {
        if (question == null || question.isBlank()) {
            return null;
        }
        String cat = CATEGORIES.contains(category) ? category : "entity";
        String key = caseKey == null || caseKey.isBlank() ? caseKey(cat, question) : caseKey.strip();
        String now = Instant.now().toString();
        repo.upsertCase(datasetId, key, cat, question.strip(), normaliseExpected(expected), note,
                dimensionOf(cat), now);
        EvalRepository.CaseRow row = repo.findCase(datasetId, key);
        return row == null ? null : new EvalViews.CaseView(row.id(), row.caseKey(), row.category(),
                row.question(), row.expected(), row.note(), row.enabled(), row.dimension());
    }

    /**
     * 导入 TSV。格式与 {@code retrieval-golden.tsv} 一致：类别、问题、期望块、备注。
     *
     * <p>期望块为 {@code -} 表示"期望一条都不注入"，{@code ?} 表示"尚未人工标注"。
     * 这两种语义不同：前者能自动判噪声，后者只能进人工 —— 混为一谈会让干净率虚高。
     */
    public EvalViews.ImportResult importTsv(long datasetId, String content, boolean dryRun) {
        if (repo.findDataset(datasetId) == null) {
            return new EvalViews.ImportResult(0, 0, 0, dryRun, List.of("用例集不存在"));
        }
        List<String> errors = new ArrayList<>();
        List<String[]> accepted = new ArrayList<>();
        String[] lines = content == null ? new String[0] : content.split("\\r?\\n");
        int total = 0;
        String now = Instant.now().toString();
        for (int i = 0; i < lines.length; i++) {
            String raw = lines[i];
            if (raw == null || raw.isBlank() || raw.stripLeading().startsWith("#")) {
                continue;
            }
            total++;
            String[] cols = raw.split("\\t", -1);
            if (cols.length < 2) {
                errors.add("第 " + (i + 1) + " 行：至少需要类别与问题两列");
                continue;
            }
            String category = cols[0].strip();
            String question = cols[1].strip();
            if (!CATEGORIES.contains(category)) {
                errors.add("第 " + (i + 1) + " 行：未知类别 " + category);
                continue;
            }
            if (question.isEmpty()) {
                errors.add("第 " + (i + 1) + " 行：问题为空");
                continue;
            }
            String expected = cols.length > 2 ? cols[2].strip() : "?";
            String note = cols.length > 3 ? cols[3].strip() : "";
            accepted.add(new String[]{category, question, normaliseExpected(expected), note});
            if (!dryRun) {
                repo.upsertCase(datasetId, caseKey(category, question), category, question,
                        normaliseExpected(expected), note, dimensionOf(category), now);
            }
        }
        if (!dryRun && !accepted.isEmpty()) {
            log.info("[EVAL] 导入 {} 条用例到用例集 {}（拒绝 {} 行）", accepted.size(), datasetId, errors.size());
        }
        return new EvalViews.ImportResult(total, accepted.size(), errors.size(), dryRun, errors);
    }

    public EvalViews.Version freeze(long datasetId, String by) {
        long count = repo.countCases(datasetId);
        if (count == 0) {
            return null;
        }
        int version = (int) repo.nextVersion(datasetId);
        String checksum = checksum(datasetId);
        String now = Instant.now().toString();
        long id = repo.insertVersion(datasetId, version, (int) count, checksum, by, now);
        repo.markDatasetFrozen(datasetId);
        return new EvalViews.Version(id, version, (int) count, checksum, now);
    }

    /** 冻结版本的 checksum：只用用例内容，不含时间与自增 id */
    private String checksum(long datasetId) {
        StringBuilder sb = new StringBuilder();
        for (EvalRepository.CaseRow c : repo.listCases(datasetId, 100_000, 0)) {
            sb.append(c.caseKey()).append('|').append(c.category()).append('|')
                    .append(c.question()).append('|').append(c.expected()).append('\n');
        }
        return sha256(sb.toString());
    }

    static String normaliseExpected(String expected) {
        if (expected == null || expected.isBlank() || "?".equals(expected)) {
            return "";
        }
        if ("-".equals(expected)) {
            return "-";
        }
        List<String> ids = new ArrayList<>();
        for (String part : expected.split(";")) {
            String id = part.strip();
            if (!id.isEmpty()) {
                ids.add(id);
            }
        }
        return String.join(";", ids);
    }

    /** 安全类目走安全维度，其余走检索问答 */
    static String dimensionOf(String category) {
        return switch (category) {
            case "inject", "leak", "harm", "privacy" -> "safety";
            default -> "rag";
        };
    }

    static String caseKey(String category, String question) {
        return category + "-" + sha256(question).substring(0, 10);
    }

    static String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return "0";
        }
    }

    private static EvalViews.Dataset toView(EvalRepository.DatasetRow row) {
        return new EvalViews.Dataset(row.id(), row.slug(), row.name(), row.description(),
                row.status(), row.createdAt(), row.caseCount(), row.versionCount());
    }
}

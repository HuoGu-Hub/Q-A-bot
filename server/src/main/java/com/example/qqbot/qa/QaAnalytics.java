package com.example.qqbot.qa;

import com.example.qqbot.persistence.QaAnalyticsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 问答记录的统计分析（S2）。
 *
 * <p><b>只出结构化数据，不负责打印。</b> 这样 CLI 报表（S2）和后台 API（S3）
 * 能共用同一套统计口径 —— 否则两边的"关键词排行"很可能算得不一样。
 *
 * <h2>本类只剩「语义」</h2>
 * 统计 SQL 全部搬进了 {@link QaAnalyticsRepository}。这里管的是：
 * 统计窗口的切法、比率与分位数、把行拼成对外记录、以及库不可用/查询失败时降级成空结果。
 * <b>口径说明（哪些行才算"提问"）也留在 {@code Overview} 的注释里</b> ——
 * 那是产品定义，不是 SQL 细节。
 *
 * <h2>2026-10-02：不再自己开连接</h2>
 * 原先这里每次查询都 {@code DriverManager.getConnection(...)} 新开一个连接、用完就关 ——
 * 那**直接违反了项目自己写下的规则**：
 * {@code SqliteConnectionProvider} 的注释里写着"全项目必须共用同一个连接，
 * 两个连接开同一个文件会在 WAL 共享内存上冲突（实测 {@code SQLITE_IOERR_SHMOPEN}）"。
 * 也就是说这个坑项目踩过、规则写下来了，而本类是那个例外。
 * 现在和别的 Store 一样走 {@code Jdbc}：**同一个共用连接 + 同一把全局锁**。
 */
@Component
public class QaAnalytics {

    private static final Logger log = LoggerFactory.getLogger(QaAnalytics.class);

    /** 数据访问全部委托给它 —— SQL 与 java.sql 都在 persistence */
    private final QaAnalyticsRepository repo;

    public QaAnalytics(QaAnalyticsRepository repo) {
        this.repo = repo;
    }

    // ==================== 结果类型 ====================

    /**
     * 看板总览。**口径在这里说清楚**，因为它被误解过好几轮。
     *
     * <p>产品对「提问」的定义（2026-09-26 确认）：一条消息必须<b>同时</b>满足两条
     * 才算一次提问 —— ① @ 了机器人；② 机器人给出了有效回应且未被拦截。
     * 对照 {@code guard_action} 的四个取值，<b>只有 {@code pass} 同时满足</b>
     * （四个取值各自混装了什么，见 {@link QaAnalyticsRepository} 的注释）。
     *
     * <p>所以：
     * <ul>
     *   <li>{@code total}     —— <b>群消息总数</b>。含没 @ 的闲聊、含被拦的。
     *       它<b>不是</b>提问数，任何对外文案都不许叫它"问答数"。</li>
     *   <li>{@code questions} —— <b>提问量</b>（{@code pass}）。这是唯一能叫"提问"的数。</li>
     *   <li>{@code hits}      —— 提问中检索到了资料的条数。</li>
     *   <li>{@code hitRate}   —— {@code hits / questions}，即"提问→检索命中率"。</li>
     *   <li>{@code users}/{@code groups} —— <b>提过问的</b>独立用户/群。
     *       ⚠️ 曾漏加 {@code pass} 过滤，实测把 90 个"说过话的人"算成独立用户，
     *       真实只有 25 个 —— 群里闲聊的人不是用户。
     *       ⚠️ 只算 {@code group_id > 0}：广场生成的答案写的是 {@code group_id = 0}
     *       （见 {@code PlazaStore.saveGeneratedAnswer}），不排除的话
     *       {@code COUNT(DISTINCT group_id)} 会凭空多出一个"0 号群"。</li>
     *   <li>{@code dropped}/{@code fixedReplies} —— 被拦截的两种，分开列。
     *       两者语义都是"被拦"，不该混进任何"回答量"。</li>
     *   <li>{@code commands}  —— 命中的指令数。有回应，但<b>不是提问</b>。</li>
     *   <li>{@code daily}     —— 每日<b>提问量</b>（{@code pass}），不是每日消息量。</li>
     *   <li>延迟分位数只统计 {@code pass} 行。⚠️ 曾跨全部 action 统计，被 1194 条
     *       {@code drop} 行的 {@code retrieve_ms=0} 拉低 —— 全表均值 39ms，
     *       而真实提问是 697ms。</li>
     * </ul>
     */
    public record Overview(long total, long questions,
                           long hits, double hitRate,
                           long users, long groups,
                           long dropped, long fixedReplies, long commands,
                           long p50RetrieveMs, long p95RetrieveMs,
                           long p50TotalMs, long p95TotalMs,
                           Map<String, Long> guardActions,
                           List<DayCount> daily) {
    }

    public record DayCount(String day, long count) {
    }

    /** 关键词排行。missCount = 这个词出现时"整条消息没检索到任何资料"的次数 */
    public record KeywordStat(String zh, String en, long count, long missCount) {
    }

    /**
     * 未命中问题的关键词排行 + 示例问题。
     *
     * @param bestCosineRaw 这些未命中问题里，A 路（向量）卡阈值<b>之前</b>的最高余弦。
     *                      接近阈值（比如 0.40+）= 差一点被挡，该考虑调低阈值；
     *                      很低（0.2 以下）= 库里真没这份资料，调阈值没用、该补资料。
     */
    public record MissStat(String zh, String en, long count, double bestCosineRaw, List<String> samples) {
    }

    /**
     * 未命中、**且连一个游戏名词都没识别出来**的问题。
     *
     * <p>这类最值得看：说明术语表里根本没有对应的词 ——
     * 连"用户在问什么"都没认出来，比"认出来了但语料里没有"更严重。
     *
     * @param bestCosineRaw 同上：卡阈值之前的最高余弦，用来判断该调阈值还是该补资料
     */
    public record UnmatchedMiss(String ts, String question, double bestCosineRaw) {
    }

    public record Bucket(String range, long count) {
    }

    /** 最近问答记录（后台列表用）。question/answer 可能因原文清理而为空 */
    public record RecordRow(long id, String ts, long groupId, long userId,
                            int hitCount, double bestCosine, String sources, String guardAction,
                            long retrieveMs, long totalMs, String question, String answer,
                            String verdict) {
    }

    public record VisitDay(String day, long count) {
    }

    public record VisitSummary(long total, long visitors, List<VisitDay> daily, List<SourceStat> paths) {
    }

    public record SourceStat(String source, long count, long hits, double hitRate) {
    }

    public record VerdictStat(String verdict, long count) {
    }

    // ==================== 统计入口 ====================

    /** 统计窗口的起始时间（ISO8601）；days<=0 时返回 null 表示不限 */
    private static String cutoff(int days) {
        return days <= 0 ? null : Instant.now().minus(days, ChronoUnit.DAYS).toString();
    }

    /**
     * 跑一段统计查询；库不可用或出错都返回空结果，**绝不抛给调用方**。
     *
     * <p>这个兜底有个副作用值得知道：**SQL 写错也会变成"空结果"而不是报错**。
     * 所以「某个报表对某些时间范围永远是空的」这类症状，多半是 SQL 的问题，
     * 不是真的没数据 —— 见 {@link QaAnalyticsRepository} 类注释里那条踩过的坑。
     */
    private <T> T query(T empty, java.util.function.Supplier<T> fn) {
        if (!repo.isAvailable()) {
            return empty;
        }
        try {
            return fn.get();
        } catch (Exception e) {
            log.warn("[QA] 统计查询失败：{}", e.getMessage());
            return empty;
        }
    }

    // ==================== 各段报表 ====================

    public Overview overview(int days) {
        return query(new Overview(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, Map.of(), List.of()),
                () -> {
                    String cutoff = cutoff(days);
                    QaAnalyticsRepository.OverviewCounts c = repo.overviewCounts(cutoff);

                    List<QaAnalyticsRepository.Latency> latencies = repo.passLatencies(cutoff);
                    List<Long> retrieveMs = new ArrayList<>(latencies.size());
                    List<Long> totalMs = new ArrayList<>(latencies.size());
                    for (QaAnalyticsRepository.Latency l : latencies) {
                        retrieveMs.add(l.retrieveMs());
                        totalMs.add(l.totalMs());
                    }

                    Map<String, Long> guard = new LinkedHashMap<>();
                    for (QaAnalyticsRepository.ActionCount a : repo.actionCounts(cutoff)) {
                        guard.put(a.action(), a.count());
                    }

                    List<DayCount> daily = new ArrayList<>();
                    for (QaAnalyticsRepository.DayCountRow d : repo.dailyQuestions(cutoff)) {
                        daily.add(new DayCount(d.day(), d.count()));
                    }

                    // 命中率的分母是【提问量】，不是消息总数
                    double rate = c.questions() == 0 ? 0 : (double) c.hits() / c.questions();
                    return new Overview(c.total(), c.questions(), c.hits(), rate, c.users(), c.groups(),
                            c.dropped(), c.fixedReplies(), c.commands(),
                            percentile(retrieveMs, 0.50), percentile(retrieveMs, 0.95),
                            percentile(totalMs, 0.50), percentile(totalMs, 0.95),
                            guard, daily);
                });
    }

    public List<KeywordStat> keywords(int days, int topN) {
        return query(List.of(), () -> {
            List<KeywordStat> out = new ArrayList<>();
            for (QaAnalyticsRepository.KeywordStatRow r : repo.keywords(cutoff(days), topN)) {
                out.add(new KeywordStat(r.keyword(), r.termEn(), r.count(), r.missCount()));
            }
            return out;
        });
    }

    /** 未命中排行：整条消息一条资料都没检索到的那些关键词 —— 直接告诉我们要补什么 */
    public List<MissStat> misses(int days, int topN, int samplesPerKeyword) {
        return query(List.of(), () -> {
            String cutoff = cutoff(days);
            List<MissStat> out = new ArrayList<>();
            for (QaAnalyticsRepository.MissRow r : repo.misses(cutoff, topN)) {
                List<String> samples = repo.missSamples(cutoff, r.keyword(), samplesPerKeyword);
                out.add(new MissStat(r.keyword(), r.termEn(), r.count(), r.bestCosineRaw(), samples));
            }
            return out;
        });
    }

    /**
     * 未命中且抽不出关键词的问题 —— 术语表连"这是什么"都没认出来。
     *
     * <p>为什么单独列：{@link #misses} 是靠 {@code qa_keyword} 关联的，
     * 而"没认出词"的记录在关键词表里根本没有行，会被 join 掉、永远不出现。
     */
    public List<UnmatchedMiss> unmatchedMisses(int days, int limit) {
        return query(List.of(), () -> {
            List<UnmatchedMiss> out = new ArrayList<>();
            for (QaAnalyticsRepository.UnmatchedMissRow r : repo.unmatchedMisses(cutoff(days), limit)) {
                out.add(new UnmatchedMiss(r.ts(), r.question(), r.bestCosineRaw()));
            }
            return out;
        });
    }

    /**
     * 最高余弦的分布 —— <b>定 min-score 阈值就看这张表</b>。
     *
     * <p>把 0~1 分成 10 档，每档再拆成"检索到了资料"和"没检索到"。
     * 阈值应该卡在"正常问题"和"无关问题"之间。
     */
    public List<Bucket> cosineDistribution(int days) {
        return query(List.of(), () -> {
            long[] buckets = new long[10];
            for (double v : repo.cosines(cutoff(days))) {
                int idx = (int) Math.floor(Math.max(0, Math.min(0.999, v)) * 10);
                buckets[idx]++;
            }
            List<Bucket> out = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                out.add(new Bucket(String.format("%.1f~%.1f", i / 10.0, (i + 1) / 10.0), buckets[i]));
            }
            return out;
        });
    }

    public List<SourceStat> sources(int days) {
        return query(List.of(), () -> {
            List<SourceStat> out = new ArrayList<>();
            for (QaAnalyticsRepository.SourceStatRow r : repo.sources(cutoff(days))) {
                long c = r.count();
                long h = r.hits();
                out.add(new SourceStat(r.sources(), c, h, c == 0 ? 0 : (double) h / c));
            }
            return out;
        });
    }

    public List<VerdictStat> verdicts(int days) {
        return query(List.of(), () -> {
            List<VerdictStat> out = new ArrayList<>();
            for (QaAnalyticsRepository.VerdictRow r : repo.verdicts(cutoff(days))) {
                out.add(new VerdictStat(r.verdict(), r.count()));
            }
            return out;
        });
    }

    /** 最近的问答记录（后台列表） */
    public List<RecordRow> recentRecords(int days, int limit, int offset, boolean onlyQuestions) {
        return query(List.of(), () -> {
            List<RecordRow> out = new ArrayList<>();
            for (QaAnalyticsRepository.RecordRow r
                    : repo.recentRecords(cutoff(days), limit, offset, onlyQuestions)) {
                out.add(new RecordRow(r.id(), r.ts(), r.groupId(), r.userId(), r.hitCount(),
                        r.bestCosine(), r.sources(), r.guardAction(), r.retrieveMs(), r.totalMs(),
                        r.question(), r.answer(), r.verdict()));
            }
            return out;
        });
    }

    /** 记录总数 —— 与 {@link #recentRecords} 用同一套过滤，供分页用 */
    public long countRecords(int days, boolean onlyQuestions) {
        return query(0L, () -> repo.countRecords(cutoff(days), onlyQuestions));
    }

    /** 管理后台的访问统计 */
    public VisitSummary visits(int days) {
        return query(new VisitSummary(0, 0, List.of(), List.of()), () -> {
            String cutoff = cutoff(days);
            QaAnalyticsRepository.VisitCounts c = repo.visitCounts(cutoff);
            List<VisitDay> daily = new ArrayList<>();
            for (QaAnalyticsRepository.DayCountRow d : repo.visitsDaily(cutoff)) {
                daily.add(new VisitDay(d.day(), d.count()));
            }
            List<SourceStat> paths = new ArrayList<>();
            for (QaAnalyticsRepository.PathCount p : repo.visitPaths(cutoff, 20)) {
                paths.add(new SourceStat(p.path(), p.count(), p.count(), 1.0));
            }
            return new VisitSummary(c.total(), c.visitors(), daily, paths);
        });
    }

    // ==================== 小工具 ====================

    /** 线性插值分位数（先排序） */
    static long percentile(List<Long> values, double p) {
        if (values == null || values.isEmpty()) {
            return 0;
        }
        List<Long> sorted = new ArrayList<>(values);
        sorted.sort(Long::compareTo);
        int idx = (int) Math.ceil(p * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, idx)));
    }
}

package com.example.qqbot.qa;

import com.example.qqbot.persistence.Jdbc;
import com.example.qqbot.persistence.QaAnalyticsRepository;
import com.example.qqbot.persistence.SqliteDatabase;

import com.example.qqbot.config.QaProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 统计服务验收测试。重点是几个**容易算错**的地方：
 * 命中率、分位数、以及"未命中但没认出词"的那一类。
 */
class QaAnalyticsTest {

    @TempDir
    Path base;

    private QaProperties props;
    private SqliteDatabase db;
    private QaStore store;
    private QaAnalytics analytics;

    @BeforeEach
    void setUp() {
        props = new QaProperties();
        props.setDb(base.resolve("qa.sqlite").toString());
        // ⚠️ 一个测试只开**一个**库，store 与 analytics 共用 ——
        //    否则测试自己就制造了"两个连接开同一个文件"（正是这次要消掉的问题）
        db = new SqliteDatabase(props);
        db.init();
        store = new QaStore(db, props, new ObjectMapper());
        store.init();
        analytics = new QaAnalytics(new QaAnalyticsRepository(new Jdbc(db)));
    }

    private QaRecord rec(String question, int hitCount, double cosine, String source,
                         long retrieveMs, long totalMs, List<QaRecord.Keyword> keywords) {
        return new QaRecord(Instant.now().toString(), 1L, 1L, 1L,
                question, null, "答案",
                0, true, hitCount, 0.2, cosine, cosine, source, "[]",
                "pass", retrieveMs, totalMs - retrieveMs, totalMs, "m", keywords);
    }

    private void seed() {
        store.insertBatch(List.of(
                rec("废料杯怎么合成？", 5, 0.55, "both", 100, 1000,
                        List.of(new QaRecord.Keyword("废料杯", "Scrap Cup", true))),
                rec("废料杯怎么获得？", 5, 0.52, "both", 200, 2000,
                        List.of(new QaRecord.Keyword("废料杯", "Scrap Cup", true))),
                rec("卷毛山羊怎么驯服", 5, 0.51, "keyword", 300, 3000,
                        List.of(new QaRecord.Keyword("卷毛山羊", "Frizzy Goat", true))),
                // 认出了词，但知识库没资料
                rec("灵火祭坛怎么升级", 0, 0.0, "none", 150, 1500,
                        List.of(new QaRecord.Keyword("火焰祭坛", "Flame Altar", false))),
                // ★ 连词都没认出来
                rec("那个红色的蘑菇在哪采", 0, 0.0, "none", 120, 1200, List.of())));
    }

    /**
     * 指定时间 / 群 / 动作的记录 —— 用来把两个边界钉死：
     * 跨时区的日期归属、以及"不是群聊产生的行"。
     */
    private QaRecord recAt(String ts, long groupId, long userId, String guardAction, int hitCount) {
        return new QaRecord(ts, groupId, userId, 1L,
                "边界用例", null, "答案",
                0, true, hitCount, 0.0, 0.0, 0.0, "none", "[]",
                guardAction, 100, 100, 200, "m", List.of());
    }

    @AfterEach
    void closeStores() {
        if (db != null) {
            db.close();
        }
        store.close();
    }

    @Test
    @DisplayName("★ 每日分桶按东八区：UTC 20:30 属于次日，不是当天")
    void dailyBucketsUseLocalZone() {
        store.insertBatch(List.of(
                recAt("2026-09-27T15:59:00.000000000Z", 1L, 1L, "pass", 0),   // 本地 09-27 23:59
                recAt("2026-09-27T16:00:00.000000000Z", 1L, 1L, "pass", 0),   // 本地 09-28 00:00
                recAt("2026-09-27T20:30:00.000000000Z", 1L, 1L, "pass", 0))); // 本地 09-28 04:30

        List<QaAnalytics.DayCount> daily = analytics.overview(0).daily();

        assertThat(daily).extracting(QaAnalytics.DayCount::day)
                .as("UTC 的 09-27 16:00 之后已经是本地 09-28")
                .containsExactly("2026-09-27", "2026-09-28");
        assertThat(daily.get(0).count()).isEqualTo(1);
        assertThat(daily.get(1).count()).isEqualTo(2);
    }

    @Test
    @DisplayName("★ 广场行（group_id = 0）不算群消息，也不冒充一个群")
    void nonGroupRowsAreExcluded() {
        store.insertBatch(List.of(
                rec("真群里的提问", 5, 0.5, "both", 100, 1000,
                        List.of(new QaRecord.Keyword("废料杯", "Scrap Cup", true))),
                // 广场生成的答案写的就是 group_id = 0（见 PlazaStore.saveGeneratedAnswer）。
                // 这里刻意给它 pass，证明挡住它的是 group_id 过滤本身，而不是顺带被动作过滤掉
                recAt("2026-09-27T10:00:00Z", 0L, 0L, "pass", 0)));

        QaAnalytics.Overview ov = analytics.overview(0);

        assertThat(ov.total()).as("总数只算群消息").isEqualTo(1);
        assertThat(ov.questions()).as("广场行不算提问").isEqualTo(1);
        assertThat(ov.groups()).as("0 号不该被数成一个群").isEqualTo(1);
        assertThat(ov.guardActions()).as("动作分布也只剩群里的那一条").containsEntry("pass", 1L);
    }

    @Test
    @DisplayName("总览：总数、命中率、分位数")
    void overviewCounts() {
        seed();

        QaAnalytics.Overview ov = analytics.overview(30);

        assertThat(ov.total()).isEqualTo(5);
        assertThat(ov.hits()).isEqualTo(3);
        assertThat(ov.hitRate()).isCloseTo(0.6, org.assertj.core.data.Offset.offset(1e-6));
        // retrieve_ms = 100,120,150,200,300 → P50 = 150，P95 = 300
        assertThat(ov.p50RetrieveMs()).isEqualTo(150);
        assertThat(ov.p95RetrieveMs()).isEqualTo(300);
        assertThat(ov.guardActions()).containsEntry("pass", 5L);
        assertThat(ov.daily()).hasSize(1);
    }

    @Test
    @DisplayName("关键词排行：按次数降序，带未命中次数")
    void keywordRanking() {
        seed();

        List<QaAnalytics.KeywordStat> stats = analytics.keywords(30, 10);

        assertThat(stats).isNotEmpty();
        assertThat(stats.get(0).zh()).isEqualTo("废料杯");
        assertThat(stats.get(0).count()).isEqualTo(2);
        assertThat(stats.get(0).missCount()).as("废料杯两次都命中了").isZero();
        assertThat(stats).anyMatch(s -> s.zh().equals("火焰祭坛") && s.missCount() == 1);
    }

    @Test
    @DisplayName("★ 未命中：认出了词但知识库没资料")
    void missesWithKeyword() {
        seed();

        List<QaAnalytics.MissStat> misses = analytics.misses(30, 10, 3);

        assertThat(misses).hasSize(1);
        assertThat(misses.get(0).zh()).isEqualTo("火焰祭坛");
        assertThat(misses.get(0).count()).isEqualTo(1);
        assertThat(misses.get(0).samples()).containsExactly("灵火祭坛怎么升级");
    }

    @Test
    @DisplayName("★ 未命中：连词都没认出来（不能因为 join 而漏掉）")
    void unmatchedMissesAreNotLost() {
        seed();

        List<QaAnalytics.UnmatchedMiss> unmatched = analytics.unmatchedMisses(30, 10);

        assertThat(unmatched).hasSize(1);
        assertThat(unmatched.get(0).question()).isEqualTo("那个红色的蘑菇在哪采");
    }

    @Test
    @DisplayName("余弦分布：分 10 档")
    void cosineBuckets() {
        seed();

        List<QaAnalytics.Bucket> buckets = analytics.cosineDistribution(30);

        assertThat(buckets).hasSize(10);
        assertThat(buckets.get(0).range()).isEqualTo("0.0~0.1");
        // 两条 0.0 落在第一档
        assertThat(buckets.get(0).count()).isEqualTo(2);
        // 0.51 / 0.52 / 0.55 落在 0.5~0.6
        assertThat(buckets.get(5).count()).isEqualTo(3);
    }

    @Test
    @DisplayName("来源分布与命中率")
    void sourceStats() {
        seed();

        List<QaAnalytics.SourceStat> stats = analytics.sources(30);

        assertThat(stats).anyMatch(s -> s.source().equals("both") && s.count() == 2 && s.hitRate() == 1.0);
        assertThat(stats).anyMatch(s -> s.source().equals("none") && s.count() == 2 && s.hitRate() == 0.0);
    }

    @Test
    @DisplayName("★ 回归：带时间窗口时这几个统计不能是空的（曾经拼出两个 WHERE）")
    void windowedStatsAreNotEmpty() {
        seed();

        // 这三个查询的 SQL 前面已经有 WHERE guard_action='pass'，
        // 而截断条件原先写成 " WHERE ts >= ?" —— 拼出来是两个 WHERE，
        // SQL 语法错误被 query() 兜底吞成空列表：看板上「余弦分布 / 检索来源 / 标注」
        // 对任何「近 7/30/90 天」都是空的，只有「全部」能出数据。
        // 用 days>0（会带 cutoff）来锁死这条回归。
        assertThat(analytics.cosineDistribution(30)).hasSize(10);
        assertThat(analytics.cosineDistribution(30))
                .allMatch(b -> true);
        assertThat(analytics.sources(30)).isNotEmpty();
        assertThat(analytics.verdicts(30)).isNotEmpty();

        // 顺带锁住「全部」也仍然工作（cutoff 为 null 的那条分支）
        assertThat(analytics.cosineDistribution(0)).hasSize(10);
        assertThat(analytics.sources(0)).isNotEmpty();
    }

    @Test
    @DisplayName("库不存在时返回空结果，不抛异常")
    void missingDatabaseIsFine() {
        QaProperties other = new QaProperties();
        other.setDb(base.resolve("nope/never.sqlite").toString());
        // 不 init：SqliteDatabase.isAvailable() 为 false，等价于"库不存在"
        SqliteDatabase missing = new SqliteDatabase(other);
        QaAnalytics empty = new QaAnalytics(new QaAnalyticsRepository(new Jdbc(missing)));

        assertThat(empty.overview(30).total()).isZero();
        assertThat(empty.keywords(30, 10)).isEmpty();
        assertThat(empty.misses(30, 10, 3)).isEmpty();
        assertThat(empty.unmatchedMisses(30, 10)).isEmpty();
    }

    @Test
    @DisplayName("分位数计算：空集合、单元素、边界")
    void percentileMath() {
        assertThat(QaAnalytics.percentile(List.of(), 0.5)).isZero();
        assertThat(QaAnalytics.percentile(List.of(7L), 0.95)).isEqualTo(7);
        assertThat(QaAnalytics.percentile(List.of(1L, 2L, 3L, 4L, 5L), 0.5)).isEqualTo(3);
        assertThat(QaAnalytics.percentile(List.of(1L, 2L, 3L, 4L, 5L), 1.0)).isEqualTo(5);
    }
}

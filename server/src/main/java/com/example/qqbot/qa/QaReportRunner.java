package com.example.qqbot.qa;

import com.example.qqbot.config.QaProperties;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 问答统计报表（S2）—— 一条命令看清知识库的质量。
 *
 * <pre>
 * mvn spring-boot:run -Dspring-boot.run.arguments="--app.qa.report.enabled=true --spring.main.web-application-type=none"
 * </pre>
 *
 * <p>可以只看某一段：{@code --app.qa.report.section=misses}
 * （all / overview / keywords / misses / cosine / sources）
 *
 * <p>本类只负责**打印**，统计口径全在 {@link QaAnalytics} 里 ——
 * 这样 S3 的后台 API 能和 CLI 用同一套数字。
 */
@Component
@ConditionalOnProperty(name = "app.qa.report.enabled", havingValue = "true")
public class QaReportRunner implements ApplicationRunner {

    private static final int BAR_MAX = 40;

    private final QaProperties props;
    private final QaAnalytics analytics;
    private final ConfigurableApplicationContext context;

    public QaReportRunner(QaProperties props, QaAnalytics analytics,
                          ConfigurableApplicationContext context) {
        this.props = props;
        this.analytics = analytics;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            print();
        } catch (Exception e) {
            System.out.println("报表生成失败：" + e.getMessage());
        }
        System.exit(SpringApplication.exit(context, () -> 0));
    }

    private void print() {
        QaProperties.Report cfg = props.getReport();
        int days = cfg.getDays();
        int topN = cfg.getTopN();
        String section = cfg.getSection() == null ? "all" : cfg.getSection().toLowerCase(Locale.ROOT);
        boolean all = "all".equals(section);

        System.out.println();
        System.out.println("================ 问答统计报表 ================");
        System.out.println("统计窗口：" + (days <= 0 ? "全部" : "最近 " + days + " 天"));

        QaAnalytics.Overview ov = analytics.overview(days);
        if (ov.total() == 0) {
            System.out.println();
            System.out.println("  还没有任何记录。");
            System.out.println("  去群里 @ 机器人问几个《雾锁王国》的问题，数据就有了。");
            System.out.println("=============================================");
            return;
        }

        if (all || "overview".equals(section)) {
            printOverview(ov);
        }
        if (all || "keywords".equals(section)) {
            printKeywords(analytics.keywords(days, topN));
        }
        if (all || "misses".equals(section)) {
            printMisses(analytics.misses(days, topN, 3));
            printUnmatchedMisses(analytics.unmatchedMisses(days, topN));
        }
        if (all || "cosine".equals(section)) {
            printCosine(analytics.cosineDistribution(days));
        }
        if (all || "sources".equals(section)) {
            printSources(analytics.sources(days));
            printVerdicts(analytics.verdicts(days));
        }
        System.out.println("=============================================");
    }

    private void printOverview(QaAnalytics.Overview ov) {
        System.out.println();
        System.out.println("【总览】");
        // ⚠️ 口径说明（2026-09-26 确认）：提问 = @ 了机器人 + 有效回应且未被拦截。
        //    total 是【群消息总数】，含没 @ 的闲聊与被拦的，它不是提问数 ——
        //    报表里原来把它叫「问答总数」，比真实提问量高一个数量级。
        System.out.printf("  群消息总数        %d  （含未 @ 与被拦，不是提问数）%n", ov.total());
        System.out.printf("  提问量            %d  （@ 了机器人 + 有效回应且未被拦截）%n", ov.questions());
        System.out.printf("  被拦              drop %d / 固定话术 %d%n", ov.dropped(), ov.fixedReplies());
        System.out.printf("  指令              %d  （有回应，但不是提问）%n", ov.commands());
        System.out.printf("  提过问的用户 / 群  %d / %d%n", ov.users(), ov.groups());
        System.out.printf("  检索命中          %d  (%.1f%%)%n", ov.hits(), ov.hitRate() * 100);
        System.out.printf("  检索耗时 P50/P95   %d ms / %d ms%n", ov.p50RetrieveMs(), ov.p95RetrieveMs());
        System.out.printf("  总耗时   P50/P95   %d ms / %d ms%n", ov.p50TotalMs(), ov.p95TotalMs());

        if (!ov.guardActions().isEmpty()) {
            System.out.println();
            System.out.println("【Guard 分布】");
            ov.guardActions().forEach((k, v) -> System.out.printf("  %-14s %d%n", k, v));
        }

        if (!ov.daily().isEmpty()) {
            System.out.println();
            System.out.println("【每日提问量】");
            long max = ov.daily().stream().mapToLong(QaAnalytics.DayCount::count).max().orElse(1);
            int show = Math.min(ov.daily().size(), 14);
            for (QaAnalytics.DayCount d : ov.daily().subList(ov.daily().size() - show, ov.daily().size())) {
                System.out.printf("  %s  %-20s %d%n", d.day(), bar(d.count(), max), d.count());
            }
        }
    }

    private void printKeywords(List<QaAnalytics.KeywordStat> stats) {
        System.out.println();
        System.out.println("【关键词排行（问得最多的游戏名词）】");
        if (stats.isEmpty()) {
            System.out.println("  （无）");
            return;
        }
        System.out.printf("  %-14s %-26s %6s %10s%n", "关键词", "英文名", "次数", "其中未命中");
        for (QaAnalytics.KeywordStat s : stats) {
            System.out.printf("  %-14s %-26s %6d %10d%n", s.zh(), s.en(), s.count(), s.missCount());
        }
    }

    private void printMisses(List<QaAnalytics.MissStat> stats) {
        System.out.println();
        System.out.println("【★ 未命中：认出了词、但知识库没有资料】");
        if (stats.isEmpty()) {
            System.out.println("  （无）");
            return;
        }
        System.out.printf("  %-14s %-26s %6s%n", "关键词", "英文名", "次数");
        for (QaAnalytics.MissStat s : stats) {
            System.out.printf("  %-14s %-26s %6d%n", s.zh(), s.en(), s.count());
            for (String sample : s.samples()) {
                System.out.println("        示例：" + sample);
            }
        }
    }

    private void printUnmatchedMisses(List<QaAnalytics.UnmatchedMiss> stats) {
        System.out.println();
        System.out.println("【★ 未命中：连词都没认出来（术语表里没有这些词）】");
        if (stats.isEmpty()) {
            System.out.println("  （无）");
            return;
        }
        for (QaAnalytics.UnmatchedMiss m : stats) {
            System.out.printf("  %s  %s%n", m.ts().length() >= 16 ? m.ts().substring(0, 16) : m.ts(),
                    m.question());
        }
    }

    private void printCosine(List<QaAnalytics.Bucket> buckets) {
        System.out.println();
        System.out.println("【最高余弦分布 —— 定 app.kb.min-score 阈值看这里】");
        long max = buckets.stream().mapToLong(QaAnalytics.Bucket::count).max().orElse(1);
        for (QaAnalytics.Bucket b : buckets) {
            System.out.printf("  %s  %-20s %d%n", b.range(), bar(b.count(), max), b.count());
        }
        System.out.println("  （阈值应卡在「正常问题」和「无关问题」之间）");
    }

    private void printSources(List<QaAnalytics.SourceStat> stats) {
        System.out.println();
        System.out.println("【检索来源】");
        System.out.printf("  %-10s %6s %8s%n", "来源", "次数", "命中率");
        for (QaAnalytics.SourceStat s : stats) {
            System.out.printf("  %-10s %6d %7.1f%%%n", s.source(), s.count(), s.hitRate() * 100);
        }
    }

    private void printVerdicts(List<QaAnalytics.VerdictStat> stats) {
        System.out.println();
        System.out.println("【标注结论（S4 才会有人工标注）】");
        for (QaAnalytics.VerdictStat s : stats) {
            System.out.printf("  %-14s %d%n", s.verdict(), s.count());
        }
    }

    private static String bar(long value, long max) {
        if (max <= 0) {
            return "";
        }
        int n = (int) Math.max(value == 0 ? 0 : 1, Math.round((double) value / max * BAR_MAX));
        return "\u2588".repeat(n);
    }

    /** 供将来 S3 后台复用：把总览转成 Map */
    public Map<String, Object> overviewAsMap(int days) {
        QaAnalytics.Overview ov = analytics.overview(days);
        return Map.of("total", ov.total(), "hitRate", ov.hitRate(), "hits", ov.hits());
    }
}

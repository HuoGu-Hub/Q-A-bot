package com.example.qqbot.ops;

import com.example.qqbot.persistence.LlmUsageRepository;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把"按日 × 模型"的原始行拼成管理端要的形状。
 *
 * <h2>为什么单独一层</h2>
 * 控制器只该做参数校验与鉴权，聚合规则（补零日期、排序、合计）放在这里
 * 才能用单元测试钉住 —— 这些规则错了，页面不会报错，只会静默地少一天、
 * 或者把估算值算进真实值里。
 *
 * <h2>估算值的口径</h2>
 * {@code estimatedIn / estimatedOut} 是 {@code tokensIn / tokensOut} 的<b>子集</b>，
 * 不是额外的一笔。库里真实值与估算值分行存，是为了让页面能说"其中多少是估的"；
 * 总量把两类行都算进去；{@code estimatedIn / estimatedOut} 只是标出其中多少来自估算。
 */
public final class LlmUsageReport {

    private LlmUsageReport() {
    }

    /** 一天的总量 */
    public record DayUsage(String day, long calls, long tokensIn, long tokensOut,
                           long estimatedIn, long estimatedOut) {
        public long total() {
            return tokensIn + tokensOut;
        }
    }

    /** 一个供应商 / 模型在区间内的总量 */
    public record ModelUsage(String provider, String model, long calls, long tokensIn, long tokensOut,
                             long estimatedIn, long estimatedOut) {
        public long total() {
            return tokensIn + tokensOut;
        }
    }

    /** 区间合计；{@code days} 是窗口长度，用来算日均 */
    public record UsageTotals(long calls, long tokensIn, long tokensOut,
                              long estimatedIn, long estimatedOut, int days) {
        public long total() {
            return tokensIn + tokensOut;
        }
    }

    public record UsageReport(UsageTotals totals, List<DayUsage> daily, List<ModelUsage> models) {
    }

    /**
     * @param today   今天，按 {@code AppTime.ZONE} 算
     * @param days    窗口长度，至少 1；日期会被补零，图上看不出"没数据"和"缺口"的区别
     * @param rows    已落库的日账
     * @param pending 还在内存里、尚未落库的日账
     */
    public static UsageReport build(LocalDate today, int days,
                                    List<LlmUsageRepository.Row> rows,
                                    List<LlmUsageLedger.Bucket> pending) {
        int window = Math.max(1, days);
        LocalDate from = today.minusDays(window - 1L);

        Map<String, DayAcc> byDay = new LinkedHashMap<>();
        for (int i = 0; i < window; i++) {
            String day = from.plusDays(i).toString();
            byDay.put(day, new DayAcc(day));
        }

        Map<ModelKey, ModelAcc> byModel = new LinkedHashMap<>();
        for (LlmUsageRepository.Row row : rows == null ? List.<LlmUsageRepository.Row>of() : rows) {
            if (inRange(row.day(), from, today)) {
                add(byDay, byModel, row.day(), row.provider(), row.model(),
                        row.estimated(), row.calls(), row.tokensIn(), row.tokensOut());
            }
        }
        for (LlmUsageLedger.Bucket b : pending == null ? List.<LlmUsageLedger.Bucket>of() : pending) {
            if (inRange(b.day(), from, today)) {
                add(byDay, byModel, b.day(), b.provider(), b.model(),
                        b.estimated(), b.calls(), b.tokensIn(), b.tokensOut());
            }
        }

        List<DayUsage> daily = new ArrayList<>(byDay.size());
        long calls = 0;
        long tokensIn = 0;
        long tokensOut = 0;
        long estimatedIn = 0;
        long estimatedOut = 0;
        for (DayAcc acc : byDay.values()) {
            DayUsage day = acc.toDay();
            daily.add(day);
            calls += day.calls();
            tokensIn += day.tokensIn();
            tokensOut += day.tokensOut();
            estimatedIn += day.estimatedIn();
            estimatedOut += day.estimatedOut();
        }

        List<ModelUsage> models = new ArrayList<>(byModel.size());
        for (ModelAcc acc : byModel.values()) {
            models.add(acc.toModel());
        }
        // 花得多的排前面；同额按调用次数，再按名字，保证刷新之间顺序稳定
        models.sort(Comparator.comparingLong(ModelUsage::total).reversed()
                .thenComparing(Comparator.comparingLong(ModelUsage::calls).reversed())
                .thenComparing(ModelUsage::provider)
                .thenComparing(ModelUsage::model));

        return new UsageReport(new UsageTotals(calls, tokensIn, tokensOut, estimatedIn, estimatedOut, window),
                daily, models);
    }

    private static boolean inRange(String day, LocalDate from, LocalDate to) {
        if (day == null) {
            return false;
        }
        LocalDate d;
        try {
            d = LocalDate.parse(day);
        } catch (Exception e) {
            return false;
        }
        return !d.isBefore(from) && !d.isAfter(to);
    }

    private static void add(Map<String, DayAcc> byDay, Map<ModelKey, ModelAcc> byModel,
                            String day, String provider, String model, boolean estimated,
                            long calls, long tokensIn, long tokensOut) {
        String p = provider == null ? "" : provider;
        String m = model == null ? "" : model;
        DayAcc dayAcc = byDay.get(day);
        if (dayAcc != null) {
            dayAcc.add(estimated, calls, tokensIn, tokensOut);
        }
        byModel.computeIfAbsent(new ModelKey(p, m), k -> new ModelAcc(p, m))
                .add(estimated, calls, tokensIn, tokensOut);
    }

    private record ModelKey(String provider, String model) {
    }

    private static final class DayAcc {
        private final String day;
        private long calls;
        private long tokensIn;
        private long tokensOut;
        private long estimatedIn;
        private long estimatedOut;

        private DayAcc(String day) {
            this.day = day;
        }

        private void add(boolean estimated, long calls, long tokensIn, long tokensOut) {
            this.calls += calls;
            this.tokensIn += tokensIn;
            this.tokensOut += tokensOut;
            if (estimated) {
                this.estimatedIn += tokensIn;
                this.estimatedOut += tokensOut;
            }
        }

        private DayUsage toDay() {
            return new DayUsage(day, calls, tokensIn, tokensOut, estimatedIn, estimatedOut);
        }
    }

    private static final class ModelAcc {
        private final String provider;
        private final String model;
        private long calls;
        private long tokensIn;
        private long tokensOut;
        private long estimatedIn;
        private long estimatedOut;

        private ModelAcc(String provider, String model) {
            this.provider = provider;
            this.model = model;
        }

        private void add(boolean estimated, long calls, long tokensIn, long tokensOut) {
            this.calls += calls;
            this.tokensIn += tokensIn;
            this.tokensOut += tokensOut;
            if (estimated) {
                this.estimatedIn += tokensIn;
                this.estimatedOut += tokensOut;
            }
        }

        private ModelUsage toModel() {
            return new ModelUsage(provider, model, calls, tokensIn, tokensOut, estimatedIn, estimatedOut);
        }
    }
}
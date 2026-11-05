package com.example.qqbot;

import com.example.qqbot.config.PersistenceProperties;
import com.example.qqbot.eval.EvalAggregator;
import com.example.qqbot.eval.EvalViews;
import com.example.qqbot.persistence.EvalRepository;
import com.example.qqbot.persistence.Jdbc;
import com.example.qqbot.persistence.SqliteDatabase;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * 门禁 CLI —— 给 CI 与发版脚本用的退出码。
 *
 * <p>为什么不复用接口：CI 里没有后台会话，为了一个布尔结论去登录一次后台
 * 既难写又不该给流水线账号。这里直接读同一个库，只做三件事：
 * 取批次结论、取活动基线、给出退出码。
 *
 * <p>退出码：0 通过，1 警告，2 阻断，3 无法判定。
 * 警告不阻断发版，但会在输出里显式标出，便于流水线决定是否要人看。
 *
 * <p>用法：
 * <pre>
 * java -Dloader.main=com.example.qqbot.eval.EvalGateCli -cp server/target/qqbot-server-*.jar \
 *      org.springframework.boot.loader.launch.PropertiesLauncher --db data/qa/qqbot.sqlite --batch 12
 * </pre>
 * 不传 {@code --batch} 时取最近一个已完成批次。
 */
public final class EvalGateCli {

    private EvalGateCli() {
    }

    public static void main(String[] args) {
        String db = arg(args, "--db", "data/eval/eval.sqlite");
        Long batchId = longOrNull(arg(args, "--batch", null));
        Double minAgreement = doubleOrNull(arg(args, "--min-agreement", null));
        String grader = arg(args, "--grader", "relevance");
        System.exit(run(db, batchId, minAgreement, grader));
    }

    /** 开库、算结论、关库；退出码是唯一输出契约，正文给人看 */
    static int run(String db, Long batchId, Double minAgreement, String grader) {
        PersistenceProperties props = new PersistenceProperties();
        props.setDb(db);
        SqliteDatabase sqlite = new SqliteDatabase(props);
        try {
            sqlite.init();
            EvalRepository repo = new EvalRepository(new Jdbc(sqlite));
            repo.initSchema();
            ObjectMapper mapper = new ObjectMapper();

            if (minAgreement != null) {
                List<EvalRepository.CalibrationRow> history = repo.calibrations(grader, 1);
                Decision cal = calibrationDecision(
                        history.isEmpty() ? null : history.get(0).agreement(), minAgreement, grader);
                System.out.println("EVAL-JUDGE: " + cal.status() + " " + cal.reason());
                if (cal.exitCode() != 0) {
                    return cal.exitCode();
                }
            }

            EvalRepository.BatchRow batch = batchId == null
                    ? latestCompleted(repo) : repo.findBatch(batchId);
            if (batch == null) {
                System.out.println("EVAL-GATE: UNKNOWN 找不到可判定的批次");
                return 3;
            }
            EvalViews.Summary summary = summaryOf(mapper, batch);
            if (summary == null) {
                System.out.println("EVAL-GATE: UNKNOWN 批次 " + batch.id() + " 没有结论");
                return 3;
            }
            Double baseline = baselineRate(mapper, repo.activeBaseline(batch.datasetVersionId()));
            Decision decision = decide(summary, baseline);
            System.out.println("批次 " + batch.id() + " · " + batch.name());
            System.out.println("通过率 " + Math.round(summary.passRate() * 1000) / 10.0 + "% · 判定 "
                    + summary.judged() + " 条 · 失败 " + summary.fail() + " 条");
            System.out.println("EVAL-GATE: " + decision.status() + " " + decision.reason());
            return decision.exitCode();
        } catch (Exception e) {
            System.out.println("EVAL-GATE: UNKNOWN " + e.getMessage());
            return 3;
        } finally {
            sqlite.close();
        }
    }

    /** 判定逻辑单独抽出来，测试不需要开库 */
    public record Decision(String status, String reason, int exitCode) {
    }

    /**
     * 判定器一致率门禁：没有校准历史算"无法判定"，低于阈值算阻断。
     *
     * <p>没有历史不能当通过 —— 一个从未校准过的判定器，它的分数不该拦发版，
     * 但也不该被当成"已验证"。
     */
    public static Decision calibrationDecision(Double agreement, double min, String grader) {
        if (agreement == null) {
            return new Decision("UNKNOWN", "判定器 " + grader + " 没有校准历史", 3);
        }
        String pct = Math.round(agreement * 1000) / 10.0 + "%";
        if (agreement < min) {
            return new Decision("BLOCKED", "判定器 " + grader + " 一致率 " + pct
                    + " 低于阈值 " + Math.round(min * 1000) / 10.0 + "%", 2);
        }
        return new Decision("PASS", "判定器 " + grader + " 一致率 " + pct, 0);
    }

    public static Decision decide(EvalViews.Summary summary, Double baselineRate) {
        if (summary.pass() + summary.fail() == 0) {
            return new Decision("UNKNOWN", "没有可判定的用例", 3);
        }
        if (baselineRate != null) {
            double delta = summary.passRate() - baselineRate;
            String reason = String.format("较基线 %+.1f 个百分点", delta * 100);
            if (delta < -0.03) {
                return new Decision("BLOCKED", reason, 2);
            }
            return new Decision("PASS", reason, 0);
        }
        if (summary.passRate() >= EvalAggregator.PASS_THRESHOLD) {
            return new Decision("PASS", "通过率 " + pct(summary.passRate()) + " 达到默认阈值", 0);
        }
        return new Decision("WARN", "通过率 " + pct(summary.passRate()) + " 低于默认阈值", 1);
    }

    private static EvalRepository.BatchRow latestCompleted(EvalRepository repo) {
        for (EvalRepository.BatchRow row : repo.listBatches(50, 0)) {
            if ("completed".equals(row.status())) {
                return row;
            }
        }
        return null;
    }

    private static EvalViews.Summary summaryOf(ObjectMapper mapper, EvalRepository.BatchRow batch) {
        if (batch.statsJson() == null || batch.statsJson().isBlank()) {
            return null;
        }
        try {
            return mapper.readValue(batch.statsJson(), EvalViews.Summary.class);
        } catch (Exception e) {
            return null;
        }
    }

    private static Double baselineRate(ObjectMapper mapper, EvalRepository.BaselineRow baseline) {
        if (baseline == null || baseline.metricsJson() == null) {
            return null;
        }
        try {
            return mapper.readValue(baseline.metricsJson(), EvalViews.Summary.class).passRate();
        } catch (Exception e) {
            return null;
        }
    }

    private static String pct(double v) {
        return Math.round(v * 1000) / 10.0 + "%";
    }

    private static String arg(String[] args, String key, String fallback) {
        if (args == null) {
            return fallback;
        }
        for (int i = 0; i < args.length - 1; i++) {
            if (key.equals(args[i])) {
                return args[i + 1];
            }
        }
        return fallback;
    }

    private static Double doubleOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(raw.strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long longOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(raw.strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

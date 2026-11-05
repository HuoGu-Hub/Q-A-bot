package com.example.qqbot.eval;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * 定时校准 —— 让"一致率"这条曲线不会因为没人点按钮而长期断档。
 *
 * <p>开关默认关：它会花 token，且依赖判定模型可用。开着判定（app.eval.judge.enabled）
 * 但没有校准，等于有一把没刻度的尺子；定时校准就是每天给它刻一次度。
 *
 * <p>失败不抛：一个校准跑挂不该影响别的定时任务，日志里留原因即可。
 */
@Component
public class EvalCalibrationJob {

    private static final Logger log = LoggerFactory.getLogger(EvalCalibrationJob.class);

    private final JudgeCalibration calibration;
    private final EvalService service;
    private final Environment env;

    public EvalCalibrationJob(JudgeCalibration calibration, EvalService service, Environment env) {
        this.calibration = calibration;
        this.service = service;
        this.env = env;
    }

    @Scheduled(cron = "${app.eval.calibration.cron:0 30 4 * * *}")
    public void run() {
        if (!enabled()) {
            return;
        }
        String provider = env.getProperty("app.eval.judge.provider", "");
        for (String grader : graders()) {
            try {
                JudgeCalibration.Report report = calibration.runDefault(grader, provider);
                if (report == null || report.total() == 0) {
                    log.info("[EVAL] 定时校准跳过 {}：没有可用样本", grader);
                    continue;
                }
                service.saveCalibration(report, provider);
                log.info("[EVAL] 定时校准 {}：一致率 {}（{} 条）", grader, report.agreement(),
                        report.total());
            } catch (RuntimeException e) {
                log.warn("[EVAL] 定时校准 {} 失败：{}", grader, e.getMessage());
            }
        }
    }

    boolean enabled() {
        return Boolean.parseBoolean(env.getProperty("app.eval.calibration.enabled", "false"))
                && Boolean.parseBoolean(env.getProperty("app.eval.judge.enabled", "false"));
    }

    private List<String> graders() {
        return Arrays.stream(env.getProperty("app.eval.calibration.graders", "relevance").split(","))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}

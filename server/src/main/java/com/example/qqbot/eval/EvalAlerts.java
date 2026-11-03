package com.example.qqbot.eval;

import com.example.qqbot.persistence.EvalRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * 告警规则求值 —— 批次跑完时按规则看一遍指标。
 *
 * <p>可判的指标只有汇总里真有的三个：通过率、单条成本、P95。
 * 不把"flaky 率"之类还没算出来的东西写进规则表，避免出现"配了永远不触发"的规则。
 */
@Component
public class EvalAlerts {

    private static final Logger log = LoggerFactory.getLogger(EvalAlerts.class);

    private final EvalRepository repo;
    private final ApplicationEventPublisher publisher;

    public EvalAlerts(EvalRepository repo, ApplicationEventPublisher publisher) {
        this.repo = repo;
        this.publisher = publisher;
    }

    /** 返回本次命中的事件数 */
    public int evaluate(long batchId, EvalViews.Summary summary) {
        List<EvalRepository.AlertRule> rules = repo.listAlertRules().stream()
                .filter(EvalRepository.AlertRule::enabled).toList();
        if (rules.isEmpty()) {
            return 0;
        }
        String now = Instant.now().toString();
        int hits = 0;
        for (EvalRepository.AlertRule rule : rules) {
            Double value = metricValue(rule.metric(), summary);
            if (value == null) {
                continue;
            }
            if (hit(rule.comparator(), value, rule.threshold())) {
                repo.insertAlertEvent(rule.id(), batchId, value, rule.threshold(), rule.severity(), now);
                publisher.publishEvent(new EvalAlertRaised(rule.id(), rule.name(), batchId,
                        rule.metric(), value, rule.threshold(), rule.severity()));
                hits++;
                log.info("[EVAL] 告警命中：{}（{}={} 阈值 {}）", rule.name(), rule.metric(), value,
                        rule.threshold());
            }
        }
        return hits;
    }

    private static Double metricValue(String metric, EvalViews.Summary s) {
        return switch (metric) {
            case "passRate" -> s.passRate();
            case "costPerRun" -> s.pricedRuns() == 0 ? null : (double) s.costMicro() / s.pricedRuns();
            case "p95Ms" -> (double) s.p95Ms();
            default -> null;
        };
    }

    private static boolean hit(String comparator, double value, double threshold) {
        return switch (comparator) {
            case "gt" -> value > threshold;
            case "gte" -> value >= threshold;
            case "lte" -> value <= threshold;
            default -> value < threshold;
        };
    }
}

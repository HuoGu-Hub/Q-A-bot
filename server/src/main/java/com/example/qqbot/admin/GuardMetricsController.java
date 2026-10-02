package com.example.qqbot.admin;

import com.example.qqbot.guard.BudgetGuard;
import com.example.qqbot.guard.GuardMetrics;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Guard 可观测 + 当日预算用量（T7）。
 *
 * <p>单独一个 controller 而不是塞进 {@link AdminController}：这块数据全部来自
 * 进程内计数器，和业务查询没关系，分开更好找。
 *
 * <p>路径在 {@code /admin/api/**} 之下，所以照样被 {@code AdminAuthFilter} 挡住 ——
 * 「谁在被限流」这种信息不该裸奔。
 */
@RestController
@RequestMapping("/admin/api/guard")
public class GuardMetricsController {

    private final GuardMetrics metrics;
    private final BudgetGuard budget;

    public GuardMetricsController(GuardMetrics metrics, BudgetGuard budget) {
        this.metrics = metrics;
        this.budget = budget;
    }

    /**
     * 实时快照（进程内计数，重启归零）：
     * 拦截率、各层拦截分布、回话术分布、被拦最多的用户、当日预算用量。
     */
    @GetMapping("/metrics")
    public Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>(metrics.snapshot());

        BudgetGuard.Snapshot b = budget.snapshot();
        Map<String, Object> bm = new LinkedHashMap<>();
        bm.put("day", b.day());
        bm.put("globalCalls", b.globalCalls());
        bm.put("globalTokens", b.globalTokens());
        bm.put("trackedUsers", b.trackedUsers());
        bm.put("trackedGroups", b.trackedGroups());
        bm.put("globalPerDay", b.globalPerDay());
        bm.put("globalTokensPerDay", b.globalTokensPerDay());
        out.put("budget", bm);
        return out;
    }
}

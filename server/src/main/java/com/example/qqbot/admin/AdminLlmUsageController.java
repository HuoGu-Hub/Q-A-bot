package com.example.qqbot.admin;

import com.example.qqbot.ops.LlmUsageLedger;
import com.example.qqbot.ops.LlmUsageReport;
import com.example.qqbot.persistence.LlmUsageRepository;
import com.example.qqbot.time.AppTime;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * token 用量 —— 「统计 → 用量」页的数据源。
 *
 * <p>和 {@link AdminLlmController} 的分工：那个接口给"最近 60 分钟"的实时曲线，
 * 这个接口给"按天 × 模型"的历史账。前者回答"现在稳不稳"，后者回答"钱花在哪"。
 *
 * <p>返回的是已经聚合好的形状：日期补零、模型按用量排序。
 * 前端只负责画，不再做一次聚合 —— 两处各算一遍，迟早会有一处忘了算估算值。
 */
@Tag(name = "Admin")
@SecurityRequirement(name = "adminSession")
@RestController
@RequestMapping("/admin/api/llm/usage")
public class AdminLlmUsageController {

    /**
     * 最长与保留期一致：日用量只留 180 天，给更大的窗口只会返回一片空日期。
     * 「全部」因此等于最近 180 天，而不是一个用户以为的"从上线到现在"。
     */
    private static final int MAX_DAYS = 180;

    private final LlmUsageRepository repo;
    private final LlmUsageLedger ledger;

    public AdminLlmUsageController(LlmUsageRepository repo, LlmUsageLedger ledger) {
        this.repo = repo;
        this.ledger = ledger;
    }

    /**
     * @param days 窗口长度；0 = 全部（按保留期 180 天算），默认 30 天
     */
    @GetMapping
    public LlmUsageReport.UsageReport usage(@RequestParam(defaultValue = "30") int days) {
        int window = days <= 0 ? MAX_DAYS : Math.min(Math.max(days, 1), MAX_DAYS);
        LocalDate today = LocalDate.now(AppTime.ZONE);
        String from = today.minusDays(window - 1L).toString();
        return LlmUsageReport.build(today, window, repo.between(from, today.toString()),
                ledger.pending());
    }
}
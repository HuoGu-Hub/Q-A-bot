package com.example.qqbot.admin;

import com.example.qqbot.persistence.LlmMetricRepository;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模型调用读数 —— 「统计 → 总览」里那块调用次数 / token / 失败。
 */
@Tag(name = "Admin")
@SecurityRequirement(name = "adminSession")
@RestController
@RequestMapping("/admin/api/llm")
public class AdminLlmController {

    private static final int MAX_MINUTES = 7 * 24 * 60;

    private final LlmMetricRepository repo;

    public AdminLlmController(LlmMetricRepository repo) {
        this.repo = repo;
    }

    @GetMapping
    public LlmResponse recent(@RequestParam(defaultValue = "60") int minutes) {
        int window = Math.min(Math.max(minutes, 10), MAX_MINUTES);
        long nowMinute = System.currentTimeMillis() / 60_000L;
        long from = nowMinute - window + 1;

        Map<Long, LlmMetricRepository.Row> byMinute = new LinkedHashMap<>();
        for (LlmMetricRepository.Row row : repo.recent(window)) {
            byMinute.put(row.minute(), row);
        }

        List<LlmPoint> points = new ArrayList<>(window);
        long totalCalls = 0;
        long totalFailures = 0;
        long totalIn = 0;
        long totalOut = 0;
        long peakCalls = 0;
        int worstP95 = 0;
        for (long m = from; m <= nowMinute; m++) {
            LlmMetricRepository.Row row = byMinute.get(m);
            long calls = row == null ? 0 : row.calls();
            long failures = row == null ? 0 : row.failures();
            long in = row == null ? 0 : row.tokensIn();
            long out = row == null ? 0 : row.tokensOut();
            int p50 = row == null ? 0 : row.p50();
            int p95 = row == null ? 0 : row.p95();
            int max = row == null ? 0 : row.max();

            totalCalls += calls;
            totalFailures += failures;
            totalIn += in;
            totalOut += out;
            peakCalls = Math.max(peakCalls, calls);
            worstP95 = Math.max(worstP95, p95);

            points.add(new LlmPoint(m, calls, failures, in, out, p50, p95, max));
        }

        return new LlmResponse(window, points, new LlmSummary(
                totalCalls, totalFailures, totalIn, totalOut,
                totalCalls == 0 ? 0.0 : (double) totalFailures / totalCalls,
                peakCalls, worstP95));
    }

    public record LlmPoint(long minute, long calls, long failures, long tokensIn,
                           long tokensOut, int p50, int p95, int max) {
    }

    public record LlmSummary(long calls, long failures, long tokensIn, long tokensOut,
                             double failRate, long peakPerMinute, int worstP95) {
    }

    public record LlmResponse(int minutes, List<LlmPoint> points, LlmSummary summary) {
    }
}

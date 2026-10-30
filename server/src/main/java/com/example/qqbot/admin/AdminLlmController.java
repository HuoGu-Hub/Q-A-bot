package com.example.qqbot.admin;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;

import io.swagger.v3.oas.annotations.tags.Tag;

import com.example.qqbot.persistence.LlmMetricRepository;
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
 *
 * <p>和 {@link AdminTrafficController} 同一套形状：窗口内每分钟都铺平，
 * 没有调用的分钟是 0。前端不需要理解 epoch 分钟。
 *
 * <p>为什么单独一个接口而不是并进流量：两者的分母不同。
 * 流量的分母是 HTTP 请求，调用的分母是模型往返 —— 混在一份汇总里，
 * "错误率"到底是 5xx 还是模型超时，读的人要猜。
 */
@Tag(name = "Admin")
@SecurityRequirement(name = "adminSession")
@RestController
@RequestMapping("/admin/api/llm")
public class AdminLlmController {

    /** 最多回 7 天 —— 和落盘保留期一致 */
    private static final int MAX_MINUTES = 7 * 24 * 60;

    private final LlmMetricRepository repo;

    public AdminLlmController(LlmMetricRepository repo) {
        this.repo = repo;
    }

    @GetMapping
    public Map<String, Object> recent(@RequestParam(defaultValue = "60") int minutes) {
        int window = Math.min(Math.max(minutes, 10), MAX_MINUTES);
        long nowMinute = System.currentTimeMillis() / 60_000L;
        long from = nowMinute - window + 1;

        Map<Long, LlmMetricRepository.Row> byMinute = new LinkedHashMap<>();
        for (LlmMetricRepository.Row row : repo.recent(window)) {
            byMinute.put(row.minute(), row);
        }

        List<Map<String, Object>> points = new ArrayList<>(window);
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

            Map<String, Object> point = new LinkedHashMap<>();
            point.put("minute", m);
            point.put("calls", calls);
            point.put("failures", failures);
            point.put("tokensIn", in);
            point.put("tokensOut", out);
            point.put("p50", p50);
            point.put("p95", p95);
            point.put("max", max);
            points.add(point);
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("calls", totalCalls);
        summary.put("failures", totalFailures);
        summary.put("tokensIn", totalIn);
        summary.put("tokensOut", totalOut);
        // 分母是调用次数：模型超时和 5xx 是两回事，不能混进 HTTP 的错误率里
        summary.put("failRate", totalCalls == 0 ? 0.0 : (double) totalFailures / totalCalls);
        summary.put("peakPerMinute", peakCalls);
        summary.put("worstP95", worstP95);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("minutes", window);
        out.put("points", points);
        out.put("summary", summary);
        return out;
    }
}

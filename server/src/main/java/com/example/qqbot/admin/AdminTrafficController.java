package com.example.qqbot.admin;

import com.example.qqbot.persistence.TrafficRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 流量读数 —— 「统计 → 总览」里那块 QPS / P95 / 错误率。
 *
 * <h2>为什么在后端补零</h2>
 * 表里只存"有人来过"的分钟（{@link TrafficRepository}）。画折线需要连续的时间轴，
 * 让前端补零意味着前端要懂"epoch 分钟"这个约定 —— 那是后端的知识，不该漏出去。
 * 这里直接把窗口内每分钟都铺出来，没请求的填 0。
 *
 * <h2>为什么 QPS 不在这里算</h2>
 * 一行是一分钟，QPS 就是 {@code requests / 60}，是个显示层的换算。
 * 后端只给"这一分钟多少次"，前端要显示成 QPS、每分钟次数还是每小时次数都行。
 */
@RestController
@RequestMapping("/admin/api/traffic")
public class AdminTrafficController {

    /** 最多回 7 天 —— 和 {@code TrafficRollupJob} 的保留期一致，再往前库里也没有 */
    private static final int MAX_MINUTES = 7 * 24 * 60;

    private final TrafficRepository repo;

    public AdminTrafficController(TrafficRepository repo) {
        this.repo = repo;
    }

    @GetMapping
    public Map<String, Object> recent(@RequestParam(defaultValue = "180") int minutes) {
        int window = Math.min(Math.max(minutes, 10), MAX_MINUTES);
        long nowMinute = System.currentTimeMillis() / 60_000L;
        long from = nowMinute - window + 1;

        Map<Long, TrafficRepository.Row> byMinute = new LinkedHashMap<>();
        for (TrafficRepository.Row row : repo.recent(window)) {
            byMinute.put(row.minute(), row);
        }

        List<Map<String, Object>> points = new ArrayList<>(window);
        long totalRequests = 0;
        long totalApi = 0;
        long totalErrors = 0;
        long peakRequests = 0;
        int worstP95 = 0;
        for (long m = from; m <= nowMinute; m++) {
            TrafficRepository.Row row = byMinute.get(m);
            long requests = row == null ? 0 : row.requests();
            long api = row == null ? 0 : row.api();
            long errors = row == null ? 0 : row.errors();
            int p50 = row == null ? 0 : row.p50();
            int p95 = row == null ? 0 : row.p95();
            int max = row == null ? 0 : row.max();

            totalRequests += requests;
            totalApi += api;
            totalErrors += errors;
            peakRequests = Math.max(peakRequests, requests);
            worstP95 = Math.max(worstP95, p95);

            Map<String, Object> point = new LinkedHashMap<>();
            point.put("minute", m);
            point.put("requests", requests);
            point.put("api", api);
            point.put("errors", errors);
            point.put("p50", p50);
            point.put("p95", p95);
            point.put("max", max);
            points.add(point);
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("requests", totalRequests);
        summary.put("api", totalApi);
        summary.put("errors", totalErrors);
        // 分母用全部请求：静态资源 5xx 同样是故障，不该被排除在错误率之外
        summary.put("errorRate", totalRequests == 0 ? 0.0 : (double) totalErrors / totalRequests);
        summary.put("peakPerMinute", peakRequests);
        summary.put("avgPerMinute", (double) totalRequests / window);
        summary.put("worstP95", worstP95);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("minutes", window);
        out.put("points", points);
        out.put("summary", summary);
        return out;
    }
}

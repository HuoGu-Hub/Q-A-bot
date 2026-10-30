package com.example.qqbot.admin;

import com.example.qqbot.persistence.TrafficRepository;
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
 * 流量读数 —— 「统计 → 总览」里那块 QPS / P95 / 错误率。
 */
@Tag(name = "Admin")
@SecurityRequirement(name = "adminSession")
@RestController
@RequestMapping("/admin/api/traffic")
public class AdminTrafficController {

    private static final int MAX_MINUTES = 7 * 24 * 60;

    private final TrafficRepository repo;

    public AdminTrafficController(TrafficRepository repo) {
        this.repo = repo;
    }

    @GetMapping
    public TrafficResponse recent(@RequestParam(defaultValue = "180") int minutes) {
        int window = Math.min(Math.max(minutes, 10), MAX_MINUTES);
        long nowMinute = System.currentTimeMillis() / 60_000L;
        long from = nowMinute - window + 1;

        Map<Long, TrafficRepository.Row> byMinute = new LinkedHashMap<>();
        for (TrafficRepository.Row row : repo.recent(window)) {
            byMinute.put(row.minute(), row);
        }

        List<TrafficPoint> points = new ArrayList<>(window);
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

            points.add(new TrafficPoint(m, requests, api, errors, p50, p95, max));
        }

        return new TrafficResponse(window, points, new TrafficSummary(
                totalRequests, totalApi, totalErrors,
                totalRequests == 0 ? 0.0 : (double) totalErrors / totalRequests,
                peakRequests, (double) totalRequests / window, worstP95));
    }

    public record TrafficPoint(long minute, long requests, long api, long errors,
                               int p50, int p95, int max) {
    }

    public record TrafficSummary(long requests, long api, long errors, double errorRate,
                                 long peakPerMinute, double avgPerMinute, int worstP95) {
    }

    public record TrafficResponse(int minutes, List<TrafficPoint> points, TrafficSummary summary) {
    }
}

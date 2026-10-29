package com.example.qqbot.admin;

import com.example.qqbot.persistence.TrafficRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 流量接口的窗口铺平。
 *
 * <p>表里只存"有人来过"的分钟，而折线需要连续的时间轴 ——
 * 补零这件事放在后端，前端拿到的点永远是 {@code minutes} 个。
 */
class AdminTrafficControllerTest {

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> points(Map<String, Object> out) {
        return (List<Map<String, Object>>) out.get("points");
    }

    @Test
    @DisplayName("★ 窗口内每分钟都出现：有数据用数据，没数据补 0")
    void fillsMissingMinutesWithZero() {
        TrafficRepository repo = mock(TrafficRepository.class);
        long now = System.currentTimeMillis() / 60_000L;
        when(repo.recent(60)).thenReturn(List.of(
                new TrafficRepository.Row(now - 2, 10, 8, 1, 20, 90, 200),
                new TrafficRepository.Row(now, 4, 4, 0, 10, 30, 50)));

        Map<String, Object> out = new AdminTrafficController(repo).recent(60);
        List<Map<String, Object>> pts = points(out);

        assertThat(pts).hasSize(60);
        assertThat(pts.get(pts.size() - 1).get("requests")).isEqualTo(4L);
        assertThat(pts.get(pts.size() - 3).get("errors")).isEqualTo(1L);
        // 中间那一分钟没有记录，必须是 0 而不是缺失
        assertThat(pts.get(pts.size() - 2).get("requests")).isEqualTo(0L);
    }

    @Test
    @DisplayName("汇总用全部请求做分母：静态资源的 5xx 同样是故障")
    void summaryCountsEverything() {
        TrafficRepository repo = mock(TrafficRepository.class);
        long now = System.currentTimeMillis() / 60_000L;
        when(repo.recent(10)).thenReturn(List.of(
                new TrafficRepository.Row(now, 100, 60, 2, 10, 40, 90)));

        Map<String, Object> summary = (Map<String, Object>) new AdminTrafficController(repo).recent(10).get("summary");
        assertThat(summary.get("requests")).isEqualTo(100L);
        assertThat(summary.get("api")).isEqualTo(60L);
        assertThat(summary.get("errors")).isEqualTo(2L);
        assertThat((Double) summary.get("errorRate")).isEqualTo(0.02);
        assertThat(summary.get("peakPerMinute")).isEqualTo(100L);
    }

    @Test
    @DisplayName("窗口下限：传 1 分钟也至少回 10 分钟，避免图上只有一根柱子")
    void clampsWindow() {
        TrafficRepository repo = mock(TrafficRepository.class);
        when(repo.recent(10)).thenReturn(List.of());
        assertThat(points(new AdminTrafficController(repo).recent(1))).hasSize(10);
    }
}

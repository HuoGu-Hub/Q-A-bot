package com.example.qqbot.admin;

import com.example.qqbot.persistence.TrafficRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 流量接口的窗口铺平。
 */
class AdminTrafficControllerTest {

    @Test
    @DisplayName("★ 窗口内每分钟都出现：有数据用数据，没数据补 0")
    void fillsMissingMinutesWithZero() {
        TrafficRepository repo = mock(TrafficRepository.class);
        long now = System.currentTimeMillis() / 60_000L;
        when(repo.recent(60)).thenReturn(List.of(
                new TrafficRepository.Row(now - 2, 10, 8, 1, 20, 90, 200),
                new TrafficRepository.Row(now, 4, 4, 0, 10, 30, 50)));

        var out = new AdminTrafficController(repo).recent(60);
        List<AdminTrafficController.TrafficPoint> pts = out.points();

        assertThat(pts).hasSize(60);
        assertThat(pts.get(pts.size() - 1).requests()).isEqualTo(4L);
        assertThat(pts.get(pts.size() - 3).errors()).isEqualTo(1L);
        assertThat(pts.get(pts.size() - 2).requests()).isZero();
    }

    @Test
    @DisplayName("汇总用全部请求做分母：静态资源的 5xx 同样是故障")
    void summaryCountsEverything() {
        TrafficRepository repo = mock(TrafficRepository.class);
        long now = System.currentTimeMillis() / 60_000L;
        when(repo.recent(10)).thenReturn(List.of(
                new TrafficRepository.Row(now, 100, 60, 2, 10, 40, 90)));

        var summary = new AdminTrafficController(repo).recent(10).summary();
        assertThat(summary.requests()).isEqualTo(100L);
        assertThat(summary.api()).isEqualTo(60L);
        assertThat(summary.errors()).isEqualTo(2L);
        assertThat(summary.errorRate()).isEqualTo(0.02);
        assertThat(summary.peakPerMinute()).isEqualTo(100L);
    }

    @Test
    @DisplayName("窗口下限：传 1 分钟也至少回 10 分钟，避免图上只有一根柱子")
    void clampsWindow() {
        TrafficRepository repo = mock(TrafficRepository.class);
        when(repo.recent(10)).thenReturn(List.of());
        assertThat(new AdminTrafficController(repo).recent(1).points()).hasSize(10);
    }
}

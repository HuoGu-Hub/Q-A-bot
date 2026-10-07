package com.example.qqbot.kb.wiki;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 列页翻页的离线验收（2026-10-05）。
 *
 * <p>为什么要有它：翻页原来是**不存在的能力** —— 三个 list 方法都是单次请求拿完，
 * 超过 {@code max-pages-per-source} 的页会被静默丢掉（不报错、不写状态行、
 * 日志里只有"列到 N 页"）。而"漏页"这件事如果只能靠联网 E2E 去验，太慢也覆盖不到边界。
 *
 * <p>所以把"取下一页"抽成一个函数（{@code WikiApiClient.collect}），
 * 这里用假的取页函数钉住四个性质：拼接、到底、上限截断、死循环保险。
 */
class WikiApiClientPagingTest {

    private static Map<String, String> cont(String token) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("apcontinue", token);
        m.put("continue", "-||");
        return m;
    }

    @Test
    @DisplayName("★ 跟着 continue 把所有页拉完（原来只拉第一页）")
    void followsContinueToTheEnd() {
        AtomicInteger calls = new AtomicInteger();
        WikiApiClient.Collected c = WikiApiClient.collect(contMap -> {
            int n = calls.incrementAndGet();
            return switch (n) {
                case 1 -> new WikiApiClient.Page(List.of("A", "B"), cont("B"));
                case 2 -> new WikiApiClient.Page(List.of("C"), cont("C"));
                default -> new WikiApiClient.Page(List.of("D"), Map.of());   // 到底了
            };
        }, 300);
        assertThat(c.titles()).containsExactly("A", "B", "C", "D");
        assertThat(c.truncated()).isFalse();
        assertThat(c.requests()).isEqualTo(3);
    }

    @Test
    @DisplayName("★ 顶到上限就停，并且**明确标出来**（静默截断 = 最难查的遗漏）")
    void stopsAtCapAndReportsTruncation() {
        // 还有下一页（令牌非空）但已经攒够 cap —— 必须停下**并报出来**
        WikiApiClient.Collected c = WikiApiClient.collect(contMap -> contMap.isEmpty()
                ? new WikiApiClient.Page(List.of("A", "B"), cont("B"))
                : new WikiApiClient.Page(List.of("C"), cont("C")), 3);
        assertThat(c.titles()).containsExactly("A", "B", "C");
        assertThat(c.truncated()).isTrue();
    }

    @Test
    @DisplayName("上限比一页还小：只留上限条，且标成截断")
    void capSmallerThanOnePage() {
        WikiApiClient.Collected c = WikiApiClient.collect(
                contMap -> new WikiApiClient.Page(List.of("A", "B", "C"), cont("C")), 2);
        assertThat(c.titles()).containsExactly("A", "B");
        assertThat(c.truncated()).isTrue();
    }

    @Test
    @DisplayName("没有 continue 的一页就是全部：不算截断")
    void singlePageIsComplete() {
        WikiApiClient.Collected c = WikiApiClient.collect(
                contMap -> new WikiApiClient.Page(List.of("A"), Map.of()), 300);
        assertThat(c.titles()).containsExactly("A");
        assertThat(c.truncated()).isFalse();
        assertThat(c.requests()).isEqualTo(1);
    }

    @Test
    @DisplayName("wiki 一直回同一个令牌也不许把导入挂死：靠轮数上限兜住")
    void neverLoopsForever() {
        WikiApiClient.Collected c = WikiApiClient.collect(
                contMap -> new WikiApiClient.Page(List.of("X"), cont("same-token")), 100000);
        assertThat(c.truncated()).isTrue();
        assertThat(c.requests()).isLessThanOrEqualTo(200);
        assertThat(c.titles()).isNotEmpty();
    }
}
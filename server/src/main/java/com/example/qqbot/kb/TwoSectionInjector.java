package com.example.qqbot.kb;

import com.example.qqbot.media.ImageMarkers;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 按出处分两段的注入器。
 *
 * <p>知识库里躺着两类<b>出处不同</b>的东西：我们自己写过/改过的（{@code source=manual}），
 * 和外部整理后导入的资料（{@code source=doc}，带更新时间）。分开标注<b>只为了说明出处和时效</b>，
 * 不是给谁发"更权威"的牌子：改过不等于一定对，导入的也不等于一定错。
 *
 * <p>这一步真正解决的问题是：原来两者混成一块、提示词又统一要求"以资料为准"，
 * 模型无法知道哪段是别人整理来的、哪段是我们昨天改的。
 * 所以措辞刻意<b>不</b>说"以它为准"。
 */
@Component
public class TwoSectionInjector implements KbKnowledgeInjector {

    @Override
    public String render(List<KbRetriever.Hit> hits) {
        if (hits == null || hits.isEmpty()) {
            return "";
        }
        List<KbRetriever.Hit> curated = hits.stream().filter(h -> h.entry().curated()).toList();
        List<KbRetriever.Hit> imported = hits.stream().filter(h -> !h.entry().curated()).toList();

        StringBuilder sb = new StringBuilder();
        if (!curated.isEmpty()) {
            sb.append("【我们维护的内容】以下是我们自己写过或改过的：\n");
            appendHits(sb, curated);
        }
        if (!imported.isEmpty()) {
            sb.append("【导入的资料").append(timeRange(imported))
                    .append("】以下是从外部资料整理导入的，**可能已经过期**：\n");
            appendHits(sb, imported);
        }
        return sb.toString().strip();
    }

    /**
     * 把命中块按 {@code [n] 标题 / url / 正文} 渲染。
     *
     * <p>正文里的配图标记会被**提到块尾单独一行**（见 {@link ImageMarkers#hoistSingleMarker}）：
     * 模型要改写正文，标记夹在句子中间最容易被顺手重写掉 ——
     * 那样"资料里明明有图，机器人却从来不发"，而且不报错。
     */
    private static void appendHits(StringBuilder sb, List<KbRetriever.Hit> hits) {
        int n = 1;
        for (KbRetriever.Hit hit : hits) {
            sb.append('[').append(n++).append("] ").append(hit.entry().title()).append('\n')
                    .append(hit.entry().url()).append('\n')
                    .append(ImageMarkers.hoistSingleMarker(hit.entry().text())).append("\n\n");
        }
    }

    /**
     * 这一批资料的时间跨度，形如 {@code " · 资料时间 2026-09-20 ~ 2026-09-21"}。
     *
     * <p>没有日期就老实说"时间未知" —— 旧语料没有 {@code at} 这一列，
     * 编一个日期出来比没有更糟。
     */
    private static String timeRange(List<KbRetriever.Hit> hits) {
        List<String> dates = hits.stream()
                .map(h -> h.entry().contentAt())
                .filter(s -> s != null && !s.isBlank())
                .map(s -> s.length() >= 10 ? s.substring(0, 10) : s)
                .distinct()
                .sorted()
                .toList();
        if (dates.isEmpty()) {
            return " · 时间未知";
        }
        if (dates.size() == 1) {
            return " · 资料时间 " + dates.get(0);
        }
        return " · 资料时间 " + dates.get(0) + " ~ " + dates.get(dates.size() - 1);
    }
}

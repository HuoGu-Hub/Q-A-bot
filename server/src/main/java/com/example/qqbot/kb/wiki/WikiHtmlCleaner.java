package com.example.qqbot.kb.wiki;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 渲染后的 HTML → 可读纯文本。
 *
 * <h2>为什么需要它</h2>
 * wiki 的数据层模板（{@code {{Get|…}}} 与 Cargo 数据查询）在 wikitext 里只有调用、没有值，
 * 离线怎么清都拿不到 —— 实测 {@code Goo} 那页 wikitext 清出 99 字，渲染后能拿到 2387 字，
 * 且 {@code {{Get|description}}} 已被服务器填成真正的句子。
 *
 * <p>{@code action=parse&prop=text} 返回的就是"照菜谱做好的菜"：模板、解析器函数、数据查询
 * 全都执行完了。代价是每页 17~68KB HTML 与一层噪声，见下。
 *
 * <h2>保留什么、丢什么</h2>
 * 实测这一站的页面结构很固定：
 * <ul>
 *   <li><b>留</b>：{@code .mw-parser-output} 里的正文段落、{@code table.wikitable}、
 *       {@code .cargoDynamicTable} 数据表、{@code ul.gallery} 的图注；</li>
 *   <li><b>丢</b>：{@code span.mw-editsection}（"visual editing" 那一串编辑入口）、
 *       {@code .ranger-navbox} 与 {@code .navigation-not-searchable}（Bestiary / Skills 导航，
 *       实测 Counter Battery 上 2191 字，比这页正文还长）、目录、参考文献。</li>
 * </ul>
 *
 * <h2>字段行排到末尾</h2>
 * 信息框（{@code div.druid-infobox}）里的字段单独收集、统一挪到文档末尾，与 wikitext 那条路
 * 保持一致 —— 实测把字段放在开头会把重排分打下来（0.83 → 0.48），正文留在前面才稳。
 */
public final class WikiHtmlCleaner {

    private WikiHtmlCleaner() {
    }

    /** 整块丢的选择器 */
    private static final String NOISE = String.join(", ",
            "span.mw-editsection", "div.navbox", "table.navbox", "div.ranger-navbox",
            ".navigation-not-searchable", ".navbox-styles", "#toc", ".toc",
            "ol.references", ".references", ".mw-empty-elt", "style", "script", ".noprint");

    /** 直接产出文本的块元素 */
    private static final Set<String> TEXT_BLOCKS = Set.of(
            "p", "h1", "h2", "h3", "h4", "h5", "h6", "li", "dt", "dd",
            "blockquote", "pre", "figcaption", "caption");

    /** 数据信息框：里面的字段单独收集，最后附到文档末尾 */
    private static final String INFOBOX = ".druid-infobox";

    public static String clean(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        Document doc = Jsoup.parse(html);
        Element root = doc.selectFirst(".mw-parser-output");
        if (root == null) {
            root = doc.body();
        }
        if (root == null) {
            return "";
        }
        root.select(NOISE).remove();

        List<String> fields = new ArrayList<>();
        for (Element box : root.select(INFOBOX)) {
            for (Element row : box.select(".druid-row")) {
                String line = rowLine(row);
                if (!line.isEmpty() && !fields.contains(line)) {
                    fields.add(line);
                }
            }
            // 信息框已经单独取过，正文走查时不要再重复一遍
            box.remove();
        }

        List<String> lines = new ArrayList<>();
        for (Element child : root.children()) {
            walk(child, lines);
        }
        StringBuilder out = new StringBuilder();
        for (String line : lines) {
            if (!line.isBlank()) {
                out.append(line.strip()).append('\n');
            }
        }
        if (!fields.isEmpty()) {
            // 正文里已经有的字段不重复放：信息框的描述常常与正文是同一句
            // （实测 Counter Battery 的 "+15% damage…" 会因此出现两次）
            java.util.Set<String> bodyLines = new java.util.HashSet<>();
            for (String line : lines) {
                bodyLines.add(squeeze(line));
            }
            boolean wrote = false;
            for (String f : fields) {
                if (bodyLines.contains(squeeze(f))) {
                    continue;
                }
                if (!wrote && out.length() > 0) {
                    out.append('\n');
                }
                out.append(f).append('\n');
                wrote = true;
            }
        }
        return out.toString()
                .replaceAll("[ \t]+", " ")
                .replaceAll("(?m)^\\s+", "")
                .replaceAll("\\n{3,}", "\n\n")
                .strip();
    }

    /** 去掉全部空白，只用于"这两行是不是同一句"的比较 */
    private static String squeeze(String s) {
        return s.replaceAll("\\s+", "").strip();
    }

    /** 信息框的一行：{@code 标签: 值}；值为空时只留标签 */
    private static String rowLine(Element row) {
        List<String> parts = new ArrayList<>();
        for (Element cell : row.children()) {
            String t = cell.text().strip();
            if (!t.isEmpty()) {
                parts.add(t);
            }
        }
        if (parts.isEmpty()) {
            return "";
        }
        if (parts.size() == 1) {
            return parts.get(0);
        }
        return parts.get(0) + ": " + String.join(" ", parts.subList(1, parts.size()));
    }

    private static void walk(Element el, List<String> lines) {
        String tag = el.tagName();
        if ("tr".equals(tag)) {
            List<String> cells = new ArrayList<>();
            for (Element cell : el.children()) {
                if ("td".equals(cell.tagName()) || "th".equals(cell.tagName())) {
                    String t = cell.text().strip();
                    if (!t.isEmpty()) {
                        cells.add(t);
                    }
                }
            }
            if (!cells.isEmpty()) {
                lines.add(String.join(" | ", cells));
            }
            return;
        }
        if ("table".equals(tag) || "tbody".equals(tag) || "thead".equals(tag) || "tfoot".equals(tag)) {
            for (Element c : el.children()) {
                walk(c, lines);
            }
            return;
        }
        if (TEXT_BLOCKS.contains(tag)) {
            lines.add(el.text());
            return;
        }
        if (el.children().isEmpty()) {
            lines.add(el.text());
            return;
        }
        for (Element c : el.children()) {
            walk(c, lines);
        }
    }
}

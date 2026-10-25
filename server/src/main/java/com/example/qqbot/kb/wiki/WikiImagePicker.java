package com.example.qqbot.kb.wiki;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 从 {@code action=parse} 的渲染 HTML 里选出**这一页的主图**。
 *
 * <h2>为什么主图在 infobox 里，而不是 wikitext 的 image 参数</h2>
 * 实测物品页的 wikitext 里 {@code |image=} 常常是模板调用或一整串文件名，
 * 而渲染后的 {@code .druid-infobox .druid-main-image img} 就是页面上**最终展示的那一张**。
 * 选它，等于选"人在 wiki 上看到的第一眼"，也正是群友想看的东西。
 *
 * <h2>为什么只取一张</h2>
 * 一条回复最多带 2 张图，而一页引用的图平均 8.4 个，绝大多数是全站复用的图标
 * （{@code Skill Point.png}、{@code Blunt Damage.png}）。全抓既浪费磁盘，
 * 也会让回答配上一张与问题无关的图标。共享图标的过滤在导入器里按"被多少页引用"做，
 * 本类只负责**认图**，不判断该不该发。
 *
 * <h2>为什么纯静态、无依赖</h2>
 * 它只做字符串与 DOM 判断，不碰网络、不碰磁盘 —— 所以能用一段真实 HTML 离线钉住，
 * 也能在抓图关闭时被单独测试。
 */
public final class WikiImagePicker {

    private WikiImagePicker() {
    }

    /** 认出来的图：文件名 + 页面上的地址（只用于日志，下载走 Special:FilePath） */
    public record Pick(String fileName, String url) {
    }

    /** 认的图片扩展名。SVG 不在其中：协议端对它的支持不稳定，MediaWiki 缩略后通常也会给 PNG */
    private static final Set<String> EXTENSIONS = Set.of("png", "jpg", "jpeg", "webp", "gif");

    /**
     * 选主图。没有 infobox、没有图、或者文件名不合法时返回 empty。
     *
     * <p>{@code infobox} 一定存在但主图是共享图标的页面（技能页实测如此）也会返回那张图标：
     * 是否采用由调用方按引用频率决定 —— 这一层只看"页面上展示的是什么"。
     */
    public static Optional<Pick> pick(String html) {
        if (html == null || html.isBlank()) {
            return Optional.empty();
        }
        Document doc = Jsoup.parse(html);
        Element infobox = doc.selectFirst(".druid-infobox");
        if (infobox == null) {
            return Optional.empty();
        }
        // 先认主图位；没有就退到信息框里的第一张 —— 有些页面的模板版本还没有 druid-main-image
        Element img = infobox.selectFirst(".druid-main-image img");
        if (img == null) {
            img = infobox.selectFirst("img");
        }
        if (img == null) {
            return Optional.empty();
        }
        // 依次尝试 alt → 链接 → src → data-src，**只接受通过白名单的那个**。
        // 不能先挑第一个非空再校验：alt 被污染时会把后面合法的 fallback 一起挡掉。
        String fileName = firstUsable(
                normalize(img.attr("alt")),
                normalize(fromHref(img)),
                normalize(fromSrc(img.attr("src"))),
                normalize(fromSrc(img.attr("data-src"))));
        if (fileName.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Pick(fileName, img.attr("src")));
    }

    /**
     * 文件名归一化：去掉命名空间、下划线换空格、折叠空白。
     *
     * <p>wiki API 返回 {@code File:Fell Dragon Youngling.png}，HTML 的 alt 是
     * {@code Fell Dragon Youngling.png}，下载地址里又是下划线形式。
     * 三处必须归一到同一个字符串，否则"这张图被多少页引用"的计数会漏。
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.strip();
        if (s.regionMatches(true, 0, "File:", 0, 5)) {
            s = s.substring(5);
        } else if (s.regionMatches(true, 0, "Image:", 0, 6)) {
            s = s.substring(6);
        }
        s = s.replace('_', ' ').replaceAll("\\s+", " ").strip();
        // URL 里可能带 ?hash 或 #fragment
        int cut = s.indexOf('?');
        if (cut >= 0) {
            s = s.substring(0, cut);
        }
        return s.strip();
    }

    /**
     * 文件名白名单。**这是防目录穿越的第一道**：名字最终会变成磁盘路径的一段。
     *
     * <p>允许空格、引号、括号 —— 实测 wiki 上就有
     * {@code Artificer's Boots.png}、{@code Ripped Alchemist's Wall Hanging (Left).png}。
     * 挡掉的只有带路径含义的字符与控制字符。
     */
    public static boolean isSafeFileName(String name) {
        if (name == null || name.isBlank() || name.length() > 160) {
            return false;
        }
        if (name.startsWith(".") || name.contains("..")) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '/' || c == '\\' || c == ':' || c == '*' || c == '?'
                    || c == '"' || c == '<' || c == '>' || c == '|'
                    || Character.isISOControl(c)) {
                return false;
            }
        }
        return hasImageExtension(name);
    }

    /** 扩展名是否在白名单里 */
    public static boolean hasImageExtension(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return false;
        }
        return EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    private static String fromHref(Element img) {
        Element link = img.parent();
        if (link == null) {
            return "";
        }
        String href = link.attr("href");
        int at = href.lastIndexOf("File:");
        return at >= 0 ? href.substring(at + 5) : "";
    }

    /** {@code /images/thumb/Foo.png/250px-Foo.png?hash} → {@code Foo.png} */
    private static String fromSrc(String src) {
        if (src == null || src.isBlank()) {
            return "";
        }
        String s = src;
        int q = s.indexOf('?');
        if (q >= 0) {
            s = s.substring(0, q);
        }
        int slash = s.lastIndexOf('/');
        if (slash >= 0) {
            s = s.substring(slash + 1);
        }
        // 缩略图前缀：250px- / 640px-
        int dash = s.indexOf("px-");
        if (dash > 0 && s.substring(0, dash).chars().allMatch(Character::isDigit)) {
            s = s.substring(dash + 3);
        }
        return s;
    }

    private static String firstUsable(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank() && isSafeFileName(v)) {
                return v;
            }
        }
        return "";
    }
}

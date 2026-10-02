package com.example.qqbot.kb.wiki;

import com.example.qqbot.kb.block.KbBlock;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 英文词条名 → 中文名 —— 让**英文的 wiki 文章块**能被**中文提问**检索到。
 *
 * <h2>为什么需要它（实测缺口）</h2>
 * 导入的任务/机制块是**英文标题 + 英文正文**，而语料里已有 **2,649 条中文物品块**。
 * 中文提问在字面上天然偏向中文块：实测 4 条中文提问只有 1 条让导入块进了前五 ——
 * 「独眼巨人 怎么打」被中文物品"独眼巨人头骨/战利品"全胜，Boss 页进不了前五。
 *
 * <h2>名字从哪来：**从现有语料里挖，不编**</h2>
 * 语料标题大量是「中文（English）」形式（实测挖到 **2,524** 条），
 * 这本身就是一份高质量的中英对照。两种解析方式：
 * <ol>
 *   <li><b>最长前缀匹配</b>：`A Beehive Smoker` → 去掉冠词后逐词缩短，
 *       `Beehive Smoker` 正好在表里 → **蜂巢喷烟器**；
 *       `Almanac of Plants and Seedlings For The Farmer` → **植物与幼苗年鉴**。</li>
 *   <li><b>头词共同前缀</b>：`Cyclops` 单独一条查不到，但语料里有
 *       `Cyclops Skull` / `Cyclops Trophy` 等 6 条以 Cyclops 开头的词条，
 *       它们的中文名（独眼巨人头骨 / 独眼巨人战利品…）**共同前缀是「独眼巨人」**。</li>
 * </ol>
 *
 * <p><b>查不到就返回 null，标题保持英文</b> —— 和中文地名一样：
 * 宁可少一个中文名，也不要编一个错的，编错了会污染整个检索。
 * 实测 `Combat Mechanics` / `Crafting` / `Survival` 这类机制词条语料里没有译名，
 * 就只能留英文（那部分要靠别名表人工补）。
 */
public final class NameZhIndex {

    /** 英文名（小写）→ 中文名 */
    private final Map<String, String> enToZh;

    private NameZhIndex(Map<String, String> enToZh) {
        this.enToZh = enToZh;
    }

    public int size() {
        return enToZh.size();
    }

    /** 从块标题建索引（只认「中文（English）」这一种形态） */
    public static NameZhIndex fromBlocks(Collection<KbBlock> blocks) {
        List<String> titles = new ArrayList<>();
        for (KbBlock b : blocks) {
            if (b.title() != null) {
                titles.add(b.title());
            }
        }
        return fromTitles(titles);
    }

    public static NameZhIndex fromTitles(Collection<String> titles) {
        Map<String, String> map = new LinkedHashMap<>();
        for (String title : titles) {
            if (title == null) {
                continue;
            }
            String t = title.trim();
            int open = t.lastIndexOf('（');
            int close = t.lastIndexOf('）');
            if (open <= 0 || close != t.length() - 1) {
                continue;
            }
            String zh = t.substring(0, open).trim();
            String en = t.substring(open + 1, close).trim();
            if (zh.isEmpty() || en.isEmpty()) {
                continue;
            }
            map.putIfAbsent(en.toLowerCase(Locale.ROOT), zh);
        }
        return new NameZhIndex(map);
    }

    /**
     * 解析一个英文页面标题的中文名。
     *
     * @return 中文名；**查不到返回 {@code null}**（调用方保持英文，不要编）
     */
    public String resolve(String pageTitle) {
        if (pageTitle == null || pageTitle.isBlank()) {
            return null;
        }
        String base = stripArticle(pageTitle.trim());
        String[] words = base.split("\\s+");
        // ① 最长前缀匹配
        for (int n = words.length; n >= 1; n--) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < n; i++) {
                if (i > 0) {
                    sb.append(' ');
                }
                sb.append(words[i]);
            }
            String hit = enToZh.get(sb.toString().toLowerCase(Locale.ROOT));
            if (hit != null) {
                return hit;
            }
        }
        // ② 头词共同前缀兜底
        String head = words[0].toLowerCase(Locale.ROOT) + " ";
        List<String> zhList = new ArrayList<>();
        for (Map.Entry<String, String> e : enToZh.entrySet()) {
            if (e.getKey().startsWith(head)) {
                zhList.add(e.getValue());
            }
        }
        if (zhList.size() >= 2) {
            String prefix = zhList.get(0);
            for (String z : zhList) {
                int i = 0;
                while (i < prefix.length() && i < z.length() && prefix.charAt(i) == z.charAt(i)) {
                    i++;
                }
                prefix = prefix.substring(0, i);
                if (prefix.length() < 2) {
                    return null;
                }
            }
            return prefix.length() >= 2 ? prefix : null;
        }
        return null;
    }

    /** 去掉开头的冠词 —— `A Beehive Smoker` → `Beehive Smoker` */
    static String stripArticle(String s) {
        String lower = s.toLowerCase(Locale.ROOT);
        for (String a : new String[]{"a ", "an ", "the "}) {
            if (lower.startsWith(a)) {
                return s.substring(a.length()).trim();
            }
        }
        return s;
    }
}

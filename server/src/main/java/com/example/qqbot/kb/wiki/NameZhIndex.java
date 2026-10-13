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
            }
            // ⚠️ 前缀太短（同一个头词下的物品译名不一致，如 Fell 系有「邪煞/迸裂/堕落」）
            //    时**不能直接 return null** —— 那会把下面的 ③ 短路掉，
            //    而 ③ 正是为这种不一致准备的（去掉物品修饰词后取多数票）。
            if (prefix.length() >= 2) {
                return prefix;
            }
        }
        // ③ 派生条目兜底（2026-10-13 补）
        //
        //    实体页自己往往没有中文名，但它的**派生物品**有：
        //    `Fell Sicklescythe` 查不到，而 `Fell Sicklescythe Trophy` /
        //    `Fell Sicklescythe Head` / `Mist of a Fell Sicklescythe` 都有。
        //
        //    实测难点：同一个怪的不同物品**译名会不一致**
        //    （战利品叫「堕落镰刃兽战利品」、头颅叫「堕落镰刀魔的头颅」），
        //    所以不能用公共前缀 —— 去掉物品类前后缀之后取**多数票**：
        //    堕落镰刃兽 出现 4 次、堕落镰刀魔 出现 1 次 → 取前者。
        String derived = resolveByDerived(base);
        if (derived != null) {
            return derived;
        }
        return null;
    }

    /**
     * 从「派生条目」反推实体中文名。
     *
     * <p>命中的是英文名里包含本页标题的那些条目（前缀、后缀、中间都算，
     * 覆盖 `X Trophy` / `X Head` / `Mist of a X` 三种常见形态）。
     * 去掉物品类修饰词后取多数票，票数不足 2 的不要 —— 宁可留英文，也不编。
     */
    private String resolveByDerived(String base) {
        String key = base.toLowerCase(Locale.ROOT);
        Map<String, Integer> votes = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : enToZh.entrySet()) {
            String en = e.getKey();
            boolean related = en.startsWith(key + " ") || en.endsWith(" " + key) || en.contains(" " + key + " ");
            if (!related) {
                continue;
            }
            String zh = stripItemAffix(e.getValue());
            if (zh.length() >= 2) {
                votes.merge(zh, 1, Integer::sum);
            }
        }
        return votes.entrySet().stream()
                .filter(v -> v.getValue() >= 2)
                .max(java.util.Comparator.comparingInt(Map.Entry::getValue))
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    /** 去掉物品类的修饰词：前缀（材质）与后缀（战利品/头颅…），留下实体名 */
    static String stripItemAffix(String zh) {
        String v = zh == null ? "" : zh.trim();
        for (String pre : new String[]{"青铜", "白银", "金色", "铁制", "木制", "铜制", "石制", "银制"}) {
            if (v.startsWith(pre) && v.length() > pre.length() + 1) {
                v = v.substring(pre.length());
                break;
            }
        }
        for (String suf : new String[]{"战利品", "的头颅", "头颅", "的头", "之雾", "碎片", "残骸", "的角", "之翼"}) {
            if (v.endsWith(suf) && v.length() > suf.length() + 1) {
                v = v.substring(0, v.length() - suf.length());
                break;
            }
        }
        return v.trim();
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

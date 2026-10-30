package com.example.qqbot.kb;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 玩家看得懂的地点名 —— 把 wiki 的英文地名换成中文。
 *
 * <h2>为什么不直接改 wiki 数据</h2>
 * wiki 的 marker 名是英文，网页地图保留原文更贴近来源；机器人和截图面向中文群友，
 * 只需要在<b>输出</b>时翻译。所以这里是一层只读的显示名转换，不动库里的数据。
 *
 * <h2>翻译到哪一步</h2>
 * 只翻"玩家认得的类型词"和已核对过的完整地名：
 * <ul>
 *   <li>完整地名（Research Camp、Vukah Arena…）走 {@link #FULL}，避免逐词拼出怪名字；</li>
 *   <li>没收录的按类型词替换（Camp→营地、Mine→矿、Tavern→酒馆…），专有名词保留原文；</li>
 *   <li>已经是中文的（火焰神龛·游牧高地）原样返回，绝不二次翻译。</li>
 * </ul>
 * 翻不了就保留英文 —— 宁可中英混排，也不要编一个错的中文地名。
 */
public final class PlaceNames {

    /** 区域名。只收录已在语料里核对过的写法 */
    private static final Map<String, String> REGION = Map.of(
            "springlands", "春之原野",
            "revelwood", "启示林",
            "blackmire", "黑沼",
            "nomad highlands", "游牧高地",
            "kindlewastes", "燃烬荒原",
            "albaneve summits", "阿尔巴内夫峰",
            "veilwater basin", "雾水盆地",
            "hollow halls", "空洞大厅");

    /** 完整地名。键统一小写；这些是资源截图里最常出现的参照点 */
    private static final Map<String, String> FULL = Map.ofEntries(
            Map.entry("research camp", "研究营地"),
            Map.entry("vukah arena", "武卡竞技场"),
            Map.entry("wistful fields", "怅惘田野"),
            Map.entry("vukah camp", "武卡营地"),
            Map.entry("rat infested outpost", "鼠患前哨"),
            Map.entry("abandoned watchtower", "废弃瞭望塔"),
            Map.entry("lover's hideaway", "恋人藏身处"),
            Map.entry("southern caravan camp", "南部商队营地"),
            Map.entry("night sanctum", "夜之圣所"),
            Map.entry("sun temple", "太阳神殿"),
            Map.entry("gem forge", "宝石熔炉"),
            Map.entry("balefire", "炬火"),
            Map.entry("ancient spire", "远古尖塔"),
            Map.entry("flame shrine", "火焰神龛"),
            Map.entry("elixir well", "灵药井"),
            Map.entry("shroud lair", "雾瘴巢穴"),
            Map.entry("ancient vault", "远古宝库"),
            Map.entry("ancient obelisk", "方尖碑"),
            Map.entry("hollow halls", "空洞大厅"),
            Map.entry("revelwood hollow halls", "启示林空洞大厅"),
            Map.entry("enemy camp", "敌营"),
            Map.entry("cave passage", "洞穴通道"),
            Map.entry("hidden tomb", "隐秘墓穴"),
            Map.entry("quest location", "任务地点"),
            Map.entry("music sheet", "乐谱"),
            Map.entry("cyclops skeleton", "独眼巨人骨架"),
            Map.entry("wall tapestry", "壁毯"),
            Map.entry("flame mosaic", "火焰马赛克"),
            Map.entry("dragon's map piece", "龙之地图残片"),
            Map.entry("drak relief", "德拉克浮雕"),
            Map.entry("queen pikemead statue piece", "皮克米德女王雕像碎片"),
            Map.entry("alchemist's wall hanging", "炼金术士壁挂"));

    /**
     * 类型词。按长度从长到短匹配，避免 "Mine" 先于 "Salt Mines" 命中。
     * 只收"地名里反复出现、玩家一看就懂"的词，不做机器翻译。
     */
    private static final List<Map.Entry<String, String>> WORDS = sorted(Map.ofEntries(
            Map.entry("salt mines", "盐矿"),
            Map.entry("salt mine", "盐矿"),
            Map.entry("revelwood", "启示林"),
            Map.entry("springlands", "春之原野"),
            Map.entry("nomad highlands", "游牧高地"),
            Map.entry("kindlewastes", "燃烬荒原"),
            Map.entry("albaneve summits", "阿尔巴内夫峰"),
            Map.entry("veilwater basin", "雾水盆地"),
            Map.entry("blackmire", "黑沼"),
            Map.entry("rat infested", "鼠患"),
            Map.entry("watchtower", "瞭望塔"),
            Map.entry("abandoned", "废弃"),
            Map.entry("hideaway", "藏身处"),
            Map.entry("caravan", "商队"),
            Map.entry("southern", "南部"),
            Map.entry("northern", "北部"),
            Map.entry("eastern", "东部"),
            Map.entry("western", "西部"),
            Map.entry("research", "研究"),
            Map.entry("wistful", "怅惘"),
            Map.entry("lover's", "恋人"),
            Map.entry("outpost", "前哨"),
            Map.entry("tavern", "酒馆"),
            Map.entry("quarry", "采石场"),
            Map.entry("village", "村庄"),
            Map.entry("temple", "神殿"),
            Map.entry("sanctum", "圣所"),
            Map.entry("passage", "通道"),
            Map.entry("fields", "田野"),
            Map.entry("arena", "竞技场"),
            Map.entry("spire", "尖塔"),
            Map.entry("shrine", "神龛"),
            Map.entry("vault", "宝库"),
            Map.entry("obelisk", "方尖碑"),
            Map.entry("halls", "大厅"),
            Map.entry("forge", "熔炉"),
            Map.entry("lair", "巢穴"),
            Map.entry("tomb", "墓穴"),
            Map.entry("camp", "营地"),
            Map.entry("mines", "矿"),
            Map.entry("mine", "矿坑"),
            Map.entry("farm", "农场"),
            Map.entry("ruins", "废墟"),
            Map.entry("cave", "洞穴"),
            Map.entry("tower", "塔"),
            Map.entry("vukah", "武卡"),
            Map.entry("well", "井"),
            Map.entry("root", "根"),
            Map.entry("hollow", "空洞"),
            Map.entry("night", "夜"),
            Map.entry("sun", "太阳"),
            Map.entry("gem", "宝石"),
            Map.entry("ancient", "远古"),
            Map.entry("shroud", "雾瘴"),
            Map.entry("elixir", "灵药"),
            Map.entry("flame", "火焰"),
            Map.entry("balefire", "炬火"),
            Map.entry("fossil", "化石")));

    /** 已经是中文的名字原样返回，避免把「火焰神龛·游牧高地」再翻一遍 */
    private static boolean hasCjk(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x4e00 && c <= 0x9fff) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasAsciiLetter(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) {
                return true;
            }
        }
        return false;
    }

    /**
     * 地名的中文显示名；翻不了返回原名。
     *
     * <p>专有名词（Rothstep、Egerton）没有可靠译名时保留英文，只翻类型词：
     * {@code Egerton Salt Mines → Egerton盐矿}。这比编一个音译更安全。
     */
    public static String zh(String name) {
        if (name == null || name.isBlank() || (hasCjk(name) && !hasAsciiLetter(name))) {
            return name;
        }
        String key = name.strip().toLowerCase(Locale.ROOT);
        String exact = FULL.get(key);
        if (exact != null) {
            return exact;
        }
        String out = name;
        for (Map.Entry<String, String> word : WORDS) {
            out = out.replaceAll("(?i)\\b" + Pattern.quote(word.getKey()) + "\\b", word.getValue());
        }
        out = out.replaceAll("\\s+", " ").strip();
        // 中文两侧不留空格：Egerton 盐 矿 -> Egerton盐矿
        out = out.replaceAll("\\s*([\\u4e00-\\u9fff])\\s*", "$1");
        return out;
    }

    /** 区域中文名；没收录的保留原名，不猜 */
    public static String regionZh(String region) {
        if (region == null || region.isBlank()) {
            return "";
        }
        String key = region.strip().toLowerCase(Locale.ROOT);
        return REGION.getOrDefault(key, region.strip());
    }

    private static List<Map.Entry<String, String>> sorted(Map<String, String> source) {
        List<Map.Entry<String, String>> out = new ArrayList<>(source.entrySet());
        out.sort(Comparator.comparingInt((Map.Entry<String, String> e) -> e.getKey().length()).reversed());
        return List.copyOf(out);
    }

    private PlaceNames() {
    }
}

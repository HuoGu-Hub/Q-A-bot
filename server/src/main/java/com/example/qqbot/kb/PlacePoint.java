package com.example.qqbot.kb;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * 一个可以被检索、也可以被当作方位参照的**地点**。
 *
 * <p>这是核心与摄入来源之间的**唯一交换格式**：wiki 的点位和玩家手标的点位
 * 到了这里长得一样，所以"哪里有 X"的算法不需要知道它从哪来。
 *
 * @param map      地图名（Embervale / Blackmire …）—— 跨地图的距离没有意义
 * @param canvas   坐标所属画布（{@code kb_map_canvas} 的 page）；空表示该地图的主画布
 * @param name     主显示名（优先中文）
 * @param aliases  别名：英文名、管理员补的俗称。检索时一并匹配
 * @param x        画布坐标 x
 * @param y        画布坐标 y（**y 轴向上**，与地图正北一致）
 * @param category {@link PlaceCategory} 里的词
 * @param note     备注正文：管理员写的手记，也是检索语料
 * @param landmark 能不能当方位参照物
 * @param priority 人工优先级，越大越优先（0 表示没特别说明）
 * @param source   来源标记：{@code wiki} / {@code admin}。仅用于展示与排障
 * @param ref      回指标识：wiki 点是 {@code page#markerId}，手标点是 {@code place:<id>}
 */
public record PlacePoint(String map, String canvas, String name, List<String> aliases,
                         double x, double y, String category, String note,
                         boolean landmark, int priority, String source, String ref) {

    /** 别名的安全取值 —— 记录可能来自反序列化或测试，null 不该让检索炸掉 */
    public List<String> aliasesOrEmpty() {
        return aliases == null ? List.of() : aliases;
    }

    /**
     * 这个点位是否匹配检索词。**大小写不敏感的子串匹配**。
     *
     * <p>为什么不在这里做分词或模糊匹配：中文提问的切词属于检索层，
     * 这里的输入已经是"候选词"（例如从「哪里有铁矿」里取出的「铁矿」）。
     * 匹配策略越简单，行为越可预测 —— 这也是它能被单元测试钉死的前提。
     */
    public boolean matches(String term) {
        if (term == null || term.isBlank()) {
            return false;
        }
        String t = term.strip().toLowerCase(Locale.ROOT);
        if (name != null && name.toLowerCase(Locale.ROOT).contains(t)) {
            return true;
        }
        for (String a : aliasesOrEmpty()) {
            if (a != null && a.toLowerCase(Locale.ROOT).contains(t)) {
                return true;
            }
        }
        return note != null && note.toLowerCase(Locale.ROOT).contains(t);
    }

    /** 检索用的全部文本片段（主名 + 别名 + 备注），给上层做"匹配到什么"的解释 */
    public List<String> haystacks() {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (name != null && !name.isBlank()) {
            out.add(name);
        }
        for (String a : aliasesOrEmpty()) {
            if (a != null && !a.isBlank()) {
                out.add(a);
            }
        }
        if (note != null && !note.isBlank()) {
            out.add(note);
        }
        return new ArrayList<>(out);
    }
}

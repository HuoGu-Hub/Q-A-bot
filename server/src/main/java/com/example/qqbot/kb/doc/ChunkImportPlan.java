package com.example.qqbot.kb.doc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 导入计划：**只比对、不改数据**。
 *
 * <p>它回答的问题是：**这次导入会改动什么？**（用户要求"导入前告诉我会改动多少条"）
 *
 * <h2>规则（用户 2026-09-28 明确，无例外）</h2>
 * <ul>
 *   <li>文件里出现的块：id 在库里**已存在 → 覆盖**；**不存在 → 新增**；</li>
 *   <li>文件里**没出现**的块：**一个都不动**；</li>
 *   <li><b>不区分是否人工改过</b> —— 一律以最新导入的为准。</li>
 * </ul>
 *
 * <p>例：库里已有 1..9 九个块，文档只给了第 3、5、7 块，
 * 导入后生效的就是 `1旧 2旧 3新 4旧 5新 6旧 7新 8旧 9旧`。
 *
 * <p>由此推出两个**有意的**行为（不是缺陷）：
 * <ol>
 *   <li>手工订正过的块，只要文件里给了它，就会被覆盖；</li>
 *   <li><b>删除无法用导入表达</b>：某块被外部删掉时它只是不出现，
 *       系统会当成"不用改" —— 要下架得走管理端。</li>
 * </ol>
 *
 * @param added            要新增的块
 * @param updated          要覆盖的块（库里已有、但正文不一样）
 * @param unchanged        文件里给了、但和库里一模一样（幂等导入时全是这个）
 * @param untouchedExisting 库里有多少块**这次完全没被碰**
 * @param warnings         可疑之处（不阻断导入）
 */
public record ChunkImportPlan<T extends ImportableBlock>(List<T> added,
                              List<T> updated,
                              List<T> unchanged,
                              int untouchedExisting,
                              List<String> warnings) {

    /** 这次导入实际会动几条 */
    public int changed() {
        return added.size() + updated.size();
    }

    /**
     * 算出一份导入计划。
     *
     * @param existing 库里现有的块，key → 块（key 就是 {@link ChunkMarkup.Block#key()}）
     * @param incoming 文档里解析出来的块
     */
    public static <I extends ImportableBlock> ChunkImportPlan<I> of(
            Map<String, ? extends ImportableBlock> existing, List<I> incoming) {
        Map<String, ? extends ImportableBlock> lib = existing == null ? Map.of() : existing;

        // 库里 标题 → key，用来发现"同一个块换了 id"这种最容易出错的情况
        Map<String, String> titleToKey = new LinkedHashMap<>();
        for (Map.Entry<String, ? extends ImportableBlock> e : lib.entrySet()) {
            String t = e.getValue().title();
            if (t != null && !t.isBlank()) {
                titleToKey.putIfAbsent(t, e.getKey());
            }
        }

        List<I> added = new ArrayList<>();
        List<I> updated = new ArrayList<>();
        List<I> unchanged = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        for (I b : incoming) {
            ImportableBlock old = lib.get(b.key());
            if (old == null) {
                added.add(b);
                // ★ 最有价值的一条提醒：id 写错会**静默变成重复块**，而不是报错
                String sameTitle = titleToKey.get(b.title());
                if (sameTitle != null && !sameTitle.equals(b.key())) {
                    warnings.add("新增块「" + b.title() + "」（id=" + b.key()
                            + "）的标题，和库里 id=" + sameTitle + " 的块完全相同。"
                            + "如果这本来就是同一块、只是 id 写变了，导入后会变成重复的两块 —— 请确认 id。");
                }
            } else if (!sameText(old.body(), b.body())) {
                updated.add(b);
            } else {
                unchanged.add(b);
            }
        }

        int touched = updated.size() + unchanged.size();
        int untouched = Math.max(0, lib.size() - touched);
        return new ChunkImportPlan(List.copyOf(added), List.copyOf(updated),
                List.copyOf(unchanged), untouched, List.copyOf(warnings));
    }

    private static boolean sameText(String a, String b) {
        String x = a == null ? "" : a.strip();
        String y = b == null ? "" : b.strip();
        return x.equals(y);
    }
}

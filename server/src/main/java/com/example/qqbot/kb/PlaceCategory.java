package com.example.qqbot.kb;

import java.util.Locale;
import java.util.Set;

/**
 * 地点分类的**中性词表**。
 *
 * <h2>为什么要有它，而不是直接传 wiki 的分组名</h2>
 * wiki 的分组名（{@code Ancient_Spire} / {@code LoreKindlewastes} / {@code Music_Sheet} …）
 * 是**摄入来源的方言**：它属于 {@code kb.map}，不属于"地点问答"这件事本身。
 * 如果核心的排序逻辑里写 {@code if (group.equals("Ancient_Spire"))}，
 * 就等于让检索层认识 wiki 的表结构 —— 换一个数据来源，排序逻辑立刻作废。
 *
 * <p>所以摄入方负责**翻译**成下面这几个词，核心只认这几个词。
 */
public final class PlaceCategory {

    /** 大而好认的地标：远古尖塔、火焰神龛、村庄。玩家一听到名字就知道在哪 */
    public static final String LANDMARK = "landmark";
    /** 区域：春之原野、启示林。范围太大，只作兜底描述用 */
    public static final String REGION = "region";
    /** 具名兴趣点：洞窟、营地、矿坑 */
    public static final String POI = "poi";
    /** 服务型 NPC：铁匠、猎人。是"谁在哪"的答案，不是方位参照 */
    public static final String NPC = "npc";
    /** 收集品 */
    public static final String COLLECTION = "collection";
    /** 玩家要的资源点：铁矿、树脂。**这类最需要相对位置描述** */
    public static final String RESOURCE = "resource";
    /** 其它/管理员自定 */
    public static final String CUSTOM = "custom";

    /** 全部合法值 —— 管理端校验用，避免界面拼错一个词就静默落库 */
    private static final Set<String> ALL = Set.of(
            LANDMARK, REGION, POI, NPC, COLLECTION, RESOURCE, CUSTOM);

    private PlaceCategory() {
    }

    public static boolean isKnown(String category) {
        return category != null && ALL.contains(category.toLowerCase(Locale.ROOT));
    }

    /** 归一化：认不出来的一律当 CUSTOM —— 宁可丢一个分类，不要让排序遇到未知值 */
    public static String normalize(String category) {
        if (category == null) {
            return CUSTOM;
        }
        String c = category.trim().toLowerCase(Locale.ROOT);
        return ALL.contains(c) ? c : CUSTOM;
    }

    /**
     * 作为**方位锚点**的名望加成。越大越容易被选成参照物。
     *
     * <p>量级刻意压得很小（上限 0.3）：它只用来在同距离级别里做偏好，
     * 不能大到"为了一个有名的地方让描述绕出去半个地图"。
     */
    public static double anchorBonus(String category) {
        return switch (normalize(category)) {
            case LANDMARK -> 0.30;
            case REGION -> 0.20;
            case POI -> 0.10;
            default -> 0.0;
        };
    }
}

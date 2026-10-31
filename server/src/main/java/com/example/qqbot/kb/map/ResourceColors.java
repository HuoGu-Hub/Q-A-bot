package com.example.qqbot.kb.map;

/**
 * 资源图层与点位的颜色。
 *
 * <p>没有物品图标可用：mod 只带 GUID，不带图片。地图上就用固定颜色区分，
 * 同一层用同一个颜色，同类之间尽量拉开色相。颜色是给人看的，不承载业务语义。
 */
final class ResourceColors {

    private static final String[] ORE = {
            "#9aa0a6", "#f2f2f2", "#c97b3c", "#b07f5a", "#f0a93b", "#b9c4cf",
            "#c9c2a6", "#ded6bd", "#e0d84a", "#a86b4c", "#3d63d9", "#3a3a3a",
            "#d7e1ef", "#a05ad6", "#3b2458", "#f2c230", "#43d6c6", "#e6c7d8",
            "#c0392b", "#9fe8ff", "#ff5fa2",
    };

    private static final String[] CYCLE = {
            "#7fd06a", "#d7a6ff", "#6fc6ff", "#ffb14a", "#ff8a8a", "#9ee0c8",
            "#c9b6ff", "#e6d06a", "#8ad0ff", "#ffa0d0", "#a8d08d", "#c0c0c0",
    };

    private ResourceColors() {
    }

    static String of(String kind, String subtype, int index) {
        return switch (kind) {
            case "chest" -> switch (subtype) {
                case "gold", "gold_sarcophagus", "gold_locked" -> "#e8b23a";
                case "silver", "silver_sarcophagus", "silver_locked" -> "#cdd7e6";
                default -> "#a97c50";
            };
            case "beacon" -> "#45e0d0";
            case "water" -> "well".equals(subtype) ? "#4aa8ff" : "#49c6c0";
            case "ore" -> ORE[Math.max(0, Math.min(ORE.length - 1, index))];
            case "material" -> "#b9a06a";
            case "plant" -> CYCLE[index % CYCLE.length];
            case "bait" -> "#d7a6ff";
            case "lore" -> "#ffb14a";
            case "boss" -> "#ff5d5d";
            case "unique_weapon" -> "#d08cff";
            case "unique_equipment" -> "#ff8ad0";
            case "collectible" -> "#6fe0c8";
            case "summon" -> "#b08cff";
            case "creature" -> CYCLE[index % CYCLE.length];
            default -> "#9aa4b2";
        };
    }
}

package com.example.qqbot.kb;

import java.util.List;

/**
 * 资源层目录 —— 核心向地图生产者索要"有哪些资源层、某一层哪里最密"的契约。
 *
 * <h2>为什么与 {@link PlaceDirectory} 分开</h2>
 * 地点是"具名的地方"，回答是"这几处在哪"；资源是"一层的点云"，回答是"哪一片最密"。
 * 两者的数据形状与回答形状都不同，混进一个接口只会让两边的语义都变糊。
 *
 * <p>与 {@code PlaceDirectory} 同一套路：契约在核心，实现在 {@code kb.map}，
 * 核心（包括工具）不认识 wiki / mod 的任何表。
 *
 * <h2>为什么点没有名字也能查</h2>
 * 名字在<b>层</b>上，不在点上：{@code ore:copper} 这一层叫「铜」，747 个点都只是它的样本。
 * 所以这里匹配的是"提问里的资源名"，返回的是"这一层的热点"，不需要给每个点编名字，
 * 也不需要给点做向量检索。
 */
public interface ResourceDirectory {

    /** 数据是否可以读（库没起来、mod 图层没导入时为 false） */
    boolean isAvailable();

    /**
     * 按提问里出现的资源名查分布。对不上任何资源层时返回 {@code null}。
     *
     * <p>不抛异常：任何一步失败都只表现为"这次没有资源分布"，正文照常回答。
     */
    Answer answer(String term);

    /** 图片标记里的身份：{@code 地图/图层/第几片}，与 {@link #answer} 的热点顺序一致 */
    static String imageKey(Layer layer, int index) {
        return layer.map() + "/" + layer.key() + "/" + index;
    }

    /**
     * 一个资源层。
     *
     * @param map     地图名
     * @param key     图层键，如 {@code ore:copper}
     * @param name    中文名，如「铜」
     * @param kind    类型，如 {@code ore}
     * @param subtype 子类型，如 {@code copper}
     * @param count   该层点位总数
     * @param color   网页上这一层的颜色（留给后续截图复用）
     */
    record Layer(String map, String key, String name, String kind, String subtype,
                 int count, String color) {
    }

    /**
     * 一片热点。
     *
     * @param anchor         最近的地标名；找不到时为空串
     * @param anchorDistance 到该地标的距离（坐标单位）
     * @param anchorDx       热点相对地标的 x 差（向东为正）
     * @param anchorDy       热点相对地标的 y 差（向北为正）
     */
    record Hotspot(double x, double y, int count,
                   double minX, double minY, double maxX, double maxY,
                   String anchor, double anchorDistance, double anchorDx, double anchorDy) {
    }

    /**
     * 一次资源分布的回答。
     *
     * @param term     提问里命中的资源词
     * @param layer    命中的图层
     * @param total    该层点位总数
     * @param radius   热点统计半径
     * @param hotspots 最密的几片，已按点数排序
     */
    record Answer(String term, Layer layer, int total, int radius, List<Hotspot> hotspots) {

        /** 给大模型读的完整文本 —— 确定性输出，同样的数据每次一样 */
        public String text() {
            StringBuilder sb = new StringBuilder();
            sb.append("「").append(term).append("」在地图上有 ").append(total).append(" 个点位。");
            if (hotspots == null || hotspots.isEmpty()) {
                return sb.toString();
            }
            sb.append("点位最密的 ").append(hotspots.size()).append(" 处：\n");
            for (Hotspot h : hotspots) {
                sb.append("· 半径 ").append(radius).append(" 内 ").append(h.count()).append(" 个点");
                if (h.anchor() != null && !h.anchor().isBlank()) {
                    if (h.anchorDistance() < 1) {
                        sb.append("，就在「").append(h.anchor()).append("」旁边");
                    } else {
                        sb.append("，在「").append(h.anchor()).append("」")
                                .append(RelativeLocation.direction(h.anchorDx(), h.anchorDy()))
                                .append("方向约 ").append(Math.round(h.anchorDistance()))
                                .append(" 坐标单位");
                    }
                }
                sb.append("，坐标 (").append(Math.round(h.x())).append(", ")
                        .append(Math.round(h.y())).append(")\n");
            }
            return sb.toString().strip();
        }
    }
}

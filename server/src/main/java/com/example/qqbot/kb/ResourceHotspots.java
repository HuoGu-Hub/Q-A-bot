package com.example.qqbot.kb;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 资源点位「哪里最密」的统计 —— 纯函数，不碰数据库、不调模型。
 *
 * <h2>为什么不是"把相近的点连成一簇"</h2>
 * 实测铜矿 747 个点遍布整张余烬谷：按 300 坐标单位做连通聚类，705 个点会连成一片，
 * 包围盒横跨半张地图——这个"簇"对回答没有意义。资源点本来就是面状分布，
 * 玩家问的"密集"是<b>局部热点</b>，不是几何连通块。
 *
 * <h2>做法</h2>
 * <ol>
 *   <li>按 {@link #CELL} 网格计数，得到"哪一格点最多"；</li>
 *   <li>按<b>半径内点数</b>从高到低取，彼此距离小于 {@link #NMS_RADIUS} 的跳过（非极大值抑制），
 *       避免三个热点全挤在同一片；格点数只在同分时做兜底；</li>
 *   <li>每个热点再统计 {@link #HOT_RADIUS} 内的点数与质心——格心只是候选，
 *       真正报给用户的是这一片点的中心。</li>
 * </ol>
 *
 * <p>参数都是实测值，不是拍脑袋：100 单位能分辨出"这一片"又不至于全是空格；
 * 250 单位对应玩家跑到跟前能看见的范围；1200 单位才能把余烬谷的铜矿分成三片
 * （500 时三片会挤在同一片矿区，报出来等于一个答案）。
 */
public final class ResourceHotspots {

    /** 一个资源点（地图坐标系，y 轴向上） */
    public record Point(double x, double y) {
    }

    /**
     * 一片热点。
     *
     * @param x,y          半径内所有点的质心
     * @param count        半径内的点数
     * @param minX,minY,maxX,maxY 半径内点的包围盒（留给后续点云截图取景）
     */
    public record Hotspot(double x, double y, int count,
                          double minX, double minY, double maxX, double maxY) {
    }

    /** 网格边长（坐标单位） */
    public static final double CELL = 100;

    /** 两个候选热点至少隔这么远，避免三片挤在同一处 */
    public static final double NMS_RADIUS = 1200;

    /** 统计"这一片有多少点"的半径 */
    public static final double HOT_RADIUS = 250;

    /** 一次回答最多给几片 */
    public static final int TOP = 3;

    public static List<Hotspot> find(List<Point> points) {
        return find(points, CELL, NMS_RADIUS, HOT_RADIUS, TOP);
    }

    /**
     * @param cell      网格边长
     * @param nmsRadius 候选热点之间的最小距离
     * @param hotRadius 统计点数的半径
     * @param top       最多取几片
     */
    public static List<Hotspot> find(List<Point> points, double cell, double nmsRadius,
                                     double hotRadius, int top) {
        if (points == null || points.isEmpty() || top <= 0 || cell <= 0) {
            return List.of();
        }
        Map<Long, Integer> cells = new HashMap<>();
        for (Point p : points) {
            long key = key((int) Math.floor(p.x() / cell), (int) Math.floor(p.y() / cell));
            cells.merge(key, 1, Integer::sum);
        }
        // 先算每个候选格"半径内有多少点"：只看单格会漏掉"单格不多、周围很密"的地方，
        // 同分时也会被格子坐标的先后带偏（实测 32 个点的那片因此排不进前三）。
        List<Candidate> candidates = new ArrayList<>(cells.size());
        for (Map.Entry<Long, Integer> e : cells.entrySet()) {
            int cx = (int) (e.getKey() >> 32);
            int cy = (int) (e.getKey() & 0xffffffffL);
            double x = (cx + 0.5) * cell;
            double y = (cy + 0.5) * cell;
            int near = 0;
            for (Point p : points) {
                if (Math.hypot(p.x() - x, p.y() - y) <= hotRadius) {
                    near++;
                }
            }
            candidates.add(new Candidate(e.getKey(), e.getValue(), near, x, y));
        }
        candidates.sort(Comparator
                .comparingInt(Candidate::near).reversed()
                .thenComparing(Comparator.comparingInt(Candidate::cellCount).reversed())
                .thenComparingLong(Candidate::key));

        List<double[]> picked = new ArrayList<>();
        for (Candidate c : candidates) {
            boolean close = false;
            for (double[] p : picked) {
                if (Math.hypot(c.x() - p[0], c.y() - p[1]) < nmsRadius) {
                    close = true;
                    break;
                }
            }
            if (close) {
                continue;
            }
            picked.add(new double[]{c.x(), c.y()});
            if (picked.size() >= top) {
                break;
            }
        }

        List<Hotspot> out = new ArrayList<>(picked.size());
        for (double[] c : picked) {
            double sx = 0;
            double sy = 0;
            double minX = Double.MAX_VALUE;
            double minY = Double.MAX_VALUE;
            double maxX = -Double.MAX_VALUE;
            double maxY = -Double.MAX_VALUE;
            int n = 0;
            for (Point p : points) {
                if (Math.hypot(p.x() - c[0], p.y() - c[1]) > hotRadius) {
                    continue;
                }
                sx += p.x();
                sy += p.y();
                n++;
                minX = Math.min(minX, p.x());
                minY = Math.min(minY, p.y());
                maxX = Math.max(maxX, p.x());
                maxY = Math.max(maxY, p.y());
            }
            if (n > 0) {
                out.add(new Hotspot(sx / n, sy / n, n, minX, minY, maxX, maxY));
            }
        }
        out.sort(Comparator
                .comparingInt(Hotspot::count).reversed()
                .thenComparingDouble(Hotspot::x)
                .thenComparingDouble(Hotspot::y));
        return List.copyOf(out);
    }

    /** 一个候选格：单格点数、半径内点数、格心坐标 */
    private record Candidate(long key, int cellCount, int near, double x, double y) {
    }

    private static long key(int cx, int cy) {
        return ((long) cx << 32) | (cy & 0xffffffffL);
    }

    private ResourceHotspots() {
    }
}

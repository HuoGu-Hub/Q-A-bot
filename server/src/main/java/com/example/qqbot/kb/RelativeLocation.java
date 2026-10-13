package com.example.qqbot.kb;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 相对位置描述 —— 把"两个坐标"翻译成玩家能照着找的一句话。
 *
 * <h2>为什么需要它（而不是直接把坐标甩给模型）</h2>
 * 群友问「哪里有铁矿」，直接回 {@code (3720, 2210)} 等于没回答：
 * 游戏里没有坐标输入框，玩家要的是<b>相对某个他认得的地方往哪走</b>。
 * 而"算方位"是纯几何、不需要大模型 —— 让模型去算反而会算错。
 *
 * <h2>三件事分开，各自可测</h2>
 * <ol>
 *   <li><b>方向</b>：八扇区，由 {@code atan2(dx, dy)} 决定。地图正北朝上，所以
 *       罗盘方位与屏幕方位天然对齐（东北 = 地图右上）；</li>
 *   <li><b>距离</b>：以地图对角线归一化后分档，而不是给一个绝对长度 ——
 *       见 {@link #bucket} 的注释；</li>
 *   <li><b>锚点选择</b>：见 {@link #pickAnchor}，距离为主、名望为辅。</li>
 * </ol>
 *
 * <h2>关于"米"</h2>
 * wiki 的 x/y 是游戏坐标系，公开资料里查不到 {@code 1 单位 = 多少米}。
 * 因此默认**不输出米**，只给坐标单位与远近量级；确证比例后由
 * {@code app.kb.map.units-per-meter} 打开。见 {@link #phrase(double)}。
 */
public final class RelativeLocation {

    /** 八扇区方向名，索引 = {@link #sector} 的返回值。0 号是正北，顺时针 */
    private static final String[] DIRECTION_ZH =
            {"正北", "东北", "正东", "东南", "正南", "西南", "正西", "西北"};

    /**
     * 同一批扇区的**屏幕说法**。
     *
     * <p>为什么要两套：群友盯着地图问的时候想的是"右上角那边"，
     * 而「东北」需要先在心里转一道罗盘。两个都给，谁都不用在脑子里翻译。
     */
    private static final String[] SCREEN_ZH =
            {"正上方", "右上角", "正右方", "右下角", "正下方", "左下角", "正左方", "左上角"};

    /** 以地图对角线为分母的远近分档。数值是**归一化比例**，不是绝对距离 */
    private static final double[] BUCKET_EDGES = {0.02, 0.06, 0.15, 0.30};
    private static final String[] BUCKET_NAMES = {"就在旁边", "很近", "不远", "有点远", "很远"};

    /** 锚点名望的权重上限。刻意远小于 1，保证距离始终是主要因素 */
    private static final double RENOWN_CAP = 1.0;

    private RelativeLocation() {
    }

    /* ==================== 方向 ==================== */

    /**
     * 八扇区编号 0..7。
     *
     * @param dx 目标相对锚点的 x 差（向东为正）
     * @param dy 目标相对锚点的 y 差（**向北为正** —— 与游戏坐标系一致）
     */
    public static int sector(double dx, double dy) {
        // atan2(0, 0) 是 0（正北）。两点重合时给"正北"没有意义，
        // 由调用方保证不会拿重合的点做描述（pickAnchor 已排除）。
        double deg = Math.toDegrees(Math.atan2(dx, dy));
        int idx = (int) Math.round(deg / 45.0);
        return ((idx % 8) + 8) % 8;
    }

    public static String direction(double dx, double dy) {
        return DIRECTION_ZH[sector(dx, dy)];
    }

    public static String screenHint(double dx, double dy) {
        return SCREEN_ZH[sector(dx, dy)];
    }

    /** 从正北顺时针的方位角，0..360。给界面画罗盘指针用 */
    public static double bearingDegrees(double dx, double dy) {
        double deg = Math.toDegrees(Math.atan2(dx, dy));
        return (deg % 360 + 360) % 360;
    }

    /* ==================== 距离 ==================== */

    /** 欧氏距离（坐标单位） */
    public static double distance(double dx, double dy) {
        return Math.hypot(dx, dy);
    }

    /**
     * 远近量级。{@code diagonal} 是地图对角线长度；取不到（&le;0）时返回"较远"这个中性值。
     *
     * <p><b>为什么用比例而不是绝对距离</b>：同样是 800 坐标单位，
     * 在 1485×1267 的黑沼是横跨半张图，在 10240×10240 的余烬谷只是隔壁村。
     * 只有把距离除以地图自身的尺度，"远不远"才有统一含义。
     */
    public static String bucket(double distance, double diagonal) {
        if (diagonal <= 0 || !Double.isFinite(diagonal)) {
            return BUCKET_NAMES[2];
        }
        double ratio = distance / diagonal;
        for (int i = 0; i < BUCKET_EDGES.length; i++) {
            if (ratio < BUCKET_EDGES[i]) {
                return BUCKET_NAMES[i];
            }
        }
        return BUCKET_NAMES[BUCKET_NAMES.length - 1];
    }

    /* ==================== 锚点选择 ==================== */

    /**
     * 一次相对位置描述。
     *
     * @param target 被描述的点
     * @param anchor 参照点
     * @param dx     {@code target.x - anchor.x}
     * @param dy     {@code target.y - anchor.y}
     */
    public record Neighbour(PlacePoint target, PlacePoint anchor, double dx, double dy,
                            double distance, double diagonal) {

        public int sector() {
            return RelativeLocation.sector(dx, dy);
        }

        public String direction() {
            return DIRECTION_ZH[sector()];
        }

        public String screenHint() {
            return SCREEN_ZH[sector()];
        }

        public String bucket() {
            return RelativeLocation.bucket(distance, diagonal);
        }

        /** 不带米制的描述，见类注释「关于米」 */
        public String phrase() {
            return "在「" + anchor.name() + "」" + direction() + "方向（地图" + screenHint() + "），"
                    + "约 " + Math.round(distance) + " 坐标单位，" + bucket();
        }

        /**
         * 带米制的描述。
         *
         * @param unitsPerMeter 1 米等于多少坐标单位；{@code <= 0} 表示**不输出米**
         *                      （默认值就是这个 —— 见类注释）
         */
        public String phrase(double unitsPerMeter) {
            if (unitsPerMeter <= 0 || !Double.isFinite(unitsPerMeter)) {
                return phrase();
            }
            long meters = Math.round(distance / unitsPerMeter);
            return "在「" + anchor.name() + "」" + direction() + "方向（地图" + screenHint() + "），"
                    + "约 " + meters + " 米（" + Math.round(distance) + " 坐标单位），" + bucket();
        }

        /** 一行短摘要：界面上的列表项用 */
        public String shortLine() {
            return direction() + " · 约" + Math.round(distance) + " · " + bucket();
        }
    }

    /**
     * 给一个目标点挑最合适的参照物。找不到返回 {@code null}。
     *
     * <h2>筛选顺序（每一步都有理由）</h2>
     * <ol>
     *   <li>排掉自己 —— 拿自己当自己的参照物会写出「在『铁矿』旁边的『铁矿』」；</li>
     *   <li>优先同画布 —— 不同画布的坐标原点不同，跨画布算出来的 dx/dy 是垃圾；</li>
     *   <li>同画布没有就退到同地图 —— 至少方向量级对得上，好过不给；</li>
     *   <li>优先地标 —— 玩家认得的地方才有资格当"参照物"；</li>
     *   <li>都没标地标就退回全部候选 —— 有参照总比只有一个裸坐标强。</li>
     * </ol>
     *
     * <h2>打分</h2>
     * {@code 距离比例 × 10 − 名望}，取最小值。距离比例落在 [0,10]，
     * 名望上限 {@link #RENOWN_CAP}：<b>距离是主因，名望只在同级里做偏好</b>。
     */
    public static Neighbour pickAnchor(PlacePoint target, List<PlacePoint> candidates, double diagonal) {
        if (target == null || candidates == null || candidates.isEmpty()) {
            return null;
        }
        List<PlacePoint> sameCanvas = new ArrayList<>();
        List<PlacePoint> sameMap = new ArrayList<>();
        for (PlacePoint c : candidates) {
            if (c == null || isSelf(target, c) || !sameMap(target, c)) {
                continue;
            }
            sameMap.add(c);
            if (sameCanvas(target, c)) {
                sameCanvas.add(c);
            }
        }
        List<PlacePoint> pool = sameCanvas.isEmpty() ? sameMap : sameCanvas;
        List<PlacePoint> anchors = new ArrayList<>();
        for (PlacePoint p : pool) {
            if (p.landmark()) {
                anchors.add(p);
            }
        }
        if (anchors.isEmpty()) {
            anchors = pool;
        }
        if (anchors.isEmpty()) {
            return null;
        }

        PlacePoint best = null;
        double bestScore = Double.MAX_VALUE;
        for (PlacePoint a : anchors) {
            double dx = target.x() - a.x();
            double dy = target.y() - a.y();
            double dist = distance(dx, dy);
            double ratio = diagonal > 0 ? dist / diagonal : 0;
            double score = ratio * 10 - renown(a);
            if (score < bestScore) {
                bestScore = score;
                best = a;
            }
        }
        if (best == null) {
            return null;
        }
        double dx = target.x() - best.x();
        double dy = target.y() - best.y();
        return new Neighbour(target, best, dx, dy, distance(dx, dy), diagonal);
    }

    /**
     * 名望：人工优先级（0..100 归一化到 0..0.5）+ 分类加成（上限 0.3）+ 地标标记（0.2）。
     * 上限即 {@link #RENOWN_CAP}，测试里钉着这个上限。
     */
    static double renown(PlacePoint p) {
        double byPriority = Math.max(0, Math.min(100, p.priority())) / 100.0 * 0.5;
        double byCategory = PlaceCategory.anchorBonus(p.category());
        double byFlag = p.landmark() ? 0.2 : 0.0;
        return Math.min(RENOWN_CAP, byPriority + byCategory + byFlag);
    }

    /** 同一个点：ref 相同、或名字与坐标都相同 */
    static boolean isSelf(PlacePoint a, PlacePoint b) {
        if (a.ref() != null && !a.ref().isBlank() && a.ref().equals(b.ref())) {
            return true;
        }
        return a.name() != null && a.name().equals(b.name())
                && Math.abs(a.x() - b.x()) < 1e-6 && Math.abs(a.y() - b.y()) < 1e-6;
    }

    private static boolean sameMap(PlacePoint a, PlacePoint b) {
        return nz(a.map()).equals(nz(b.map()));
    }

    private static boolean sameCanvas(PlacePoint a, PlacePoint b) {
        String ca = nz(a.canvas());
        String cb = nz(b.canvas());
        return ca.equals(cb);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /** 地图对角线 —— 远近量级的分母。宽高任一为 0 时返回 0（表示"无法归一化"） */
    public static double diagonal(double[] worldSize) {
        if (worldSize == null || worldSize.length < 2) {
            return 0;
        }
        return Math.hypot(worldSize[0], worldSize[1]);
    }

    /** 给界面用的方向罗盘文字，例如 {@code "东北 45°"} */
    public static String compass(double dx, double dy) {
        return String.format(Locale.ROOT, "%s %.0f°", direction(dx, dy), bearingDegrees(dx, dy));
    }
}

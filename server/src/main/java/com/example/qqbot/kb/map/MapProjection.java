package com.example.qqbot.kb.map;

import java.util.List;

/**
 * 画布坐标 → 像素的换算 —— <b>后端唯一一份</b>，与前端 {@code mapMath.ts} / {@code MapCanvas.vue} 对齐。
 *
 * <h2>为什么必须有它，且必须只有一份</h2>
 * 前端早有一套换算：拖拽、缩放、打点、命中检测都靠它。后端要自己画图，就得有第二份。
 * 两份只要有一处不一样，标记就画偏，而且<b>不会报错</b> —— 表现只是"图里没圈住那个点"。
 * 所以这里全部是**纯函数**：只吃数字，不碰数据库、不碰网络，可以逐条单测。
 *
 * <h2>三层坐标（与前端注释同一套）</h2>
 * <ul>
 *   <li><b>crs</b> —— wiki 给的 x/y，y 轴向上、原点在左下，数值可能是负数；</li>
 *   <li><b>画布像素</b> —— 左上角 (0,0)，尺寸等于画布 extent。本类输出的就是这一层；</li>
 *   <li>屏幕像素 —— 只属于前端，后端不需要。</li>
 * </ul>
 *
 * <h2>两个必须记住的前提</h2>
 * <ol>
 *   <li>画布四至必须是<b>归一化</b>过的：左上 = 最小 x / 最大 y。余烬谷的收集品画布
 *       四至是 {@code (-3695,-9045) → (6545,1195)}，y 方向与主图相反 ——
 *       不归一化的话 46 个点位与 64 张瓦片全部落在画布外，而且同样不报错。
 *       归一化见 {@link #normalizeCorners}。</li>
 *   <li>瓦片行号用 <b>crs</b> 算，不是用画布像素算；瓦片的 {@code top} 取的是<b>上边</b>。
 *       这两处是实测踩过的坑。</li>
 * </ol>
 */
public final class MapProjection {

    /** 瓦片缺省边长。wiki 的 {@code background.tileSize} 没给时用它 */
    public static final double DEFAULT_TILE = 1280;

    /**
     * 一张画布的几何。
     *
     * @param topLeftX 左上角 x，必须是四至里的<b>最小</b> x
     * @param topLeftY 左上角 y，必须是四至里的<b>最大</b> y
     * @param tileAtX  瓦片网格原点 x，来自 wiki 的 {@code background.at}；没有时退回左上角
     * @param tileAtY  瓦片网格原点 y，同上
     */
    public record Canvas(double topLeftX, double topLeftY, double bottomRightX, double bottomRightY,
                         double tileAtX, double tileAtY, double tileWidth, double tileHeight) {

        /**
         * 由三组数组构造，缺省值在这里兜住：瓦片边长缺省 1280，网格原点缺省左上角。
         *
         * <p>⚠️ 传进来的四至**必须已经归一化** —— 这个构造不做归一化，
         * 因为它也用于表达"原始数据长什么样"的测试场景。
         */
        public static Canvas of(double[] topLeft, double[] bottomRight, double[] tileAt, double[] tileSize) {
            double tw = positive(tileSize, 0, DEFAULT_TILE);
            double th = positive(tileSize, 1, DEFAULT_TILE);
            double ax = tileAt != null && tileAt.length > 0 ? tileAt[0] : topLeft[0];
            double ay = tileAt != null && tileAt.length > 1 ? tileAt[1] : topLeft[1];
            return new Canvas(topLeft[0], topLeft[1], bottomRight[0], bottomRight[1], ax, ay, tw, th);
        }

        private static double positive(double[] pair, int idx, double fallback) {
            if (pair == null || pair.length <= idx || pair[idx] <= 0) {
                return fallback;
            }
            return pair[idx];
        }

        /** 画布宽（crs 单位）。这些画布里 1 crs 单位 = 1 像素 */
        public double width() {
            return Math.abs(bottomRightX - topLeftX);
        }

        public double height() {
            return Math.abs(topLeftY - bottomRightY);
        }
    }

    /** 画布像素坐标 */
    public record Point(double x, double y) {
    }

    /** 一块瓦片在画布上的位置与尺寸 */
    public record Box(double left, double top, double width, double height) {
    }

    /** 要裁的那一块：画布像素坐标系里的矩形 */
    public record Viewport(double left, double top, double width, double height) {
    }

    /**
     * 把四至归一化成「左上 = 最小 x / 最大 y」。
     *
     * <p>wiki 记录的四至方向并不统一：主图是 {@code (0,10240) → (10240,0)}，y 轴向上；
     * 而收集品画布是 {@code (-3695,-9045) → (6545,1195)}，y 轴向下。前端只按前者算，
     * 于是后者整张画布渲染成空白、46 个点位落在画布外 —— 实测确认过。
     *
     * <p>归一化放在**视图层**而不是解析层：不用重新同步一遍 wiki，前端与后端立刻同时变对。
     *
     * @return {@code [topLeft, bottomRight]}，两个长度为 2 的数组
     */
    public static double[][] normalizeCorners(double[] topLeft, double[] bottomRight) {
        double minX = Math.min(topLeft[0], bottomRight[0]);
        double maxX = Math.max(topLeft[0], bottomRight[0]);
        double minY = Math.min(topLeft[1], bottomRight[1]);
        double maxY = Math.max(topLeft[1], bottomRight[1]);
        return new double[][]{{minX, maxY}, {maxX, minY}};
    }

    /** crs → 画布像素。y 被翻转：crs 的 y 越大越靠北，画布上越靠上 */
    public static Point worldOf(Canvas c, double x, double y) {
        return new Point(x - c.topLeftX(), c.topLeftY() - y);
    }

    /**
     * 某个 crs 点落在哪块瓦片里。
     *
     * <p><b>用 crs 算，不是用画布像素算</b>：瓦片网格的原点是 {@code tileAt}，
     * 它与左上角不是同一个点 —— 收集品画布上两者差了上万单位。
     */
    public static int[] tileOf(Canvas c, double x, double y) {
        return new int[]{
                (int) Math.floor((x - c.tileAtX()) / c.tileWidth()),
                (int) Math.floor((y - c.tileAtY()) / c.tileHeight())};
    }

    /**
     * 一块瓦片在画布上的位置。
     *
     * <p>{@code top} 取的是瓦片的<b>上边</b>，所以行号要加一：
     * {@code top = topLeft.y - (tileAt.y + (row+1) * tileHeight)}。
     * 这是与前端 {@code MapCanvas.vue} 完全一致的一行，写错整张底图会上下颠倒。
     */
    public static Box tileBox(Canvas c, int col, int row) {
        return new Box(
                c.tileAtX() + col * c.tileWidth() - c.topLeftX(),
                c.topLeftY() - (c.tileAtY() + (row + 1) * c.tileHeight()),
                c.tileWidth(),
                c.tileHeight());
    }

    /**
     * 算出要裁的那一块：把给定的点都框进去，按目标长宽比调整，跨度再夹进上下限。
     *
     * @param points   画布像素坐标，通常是"目标点 + 最近地标"
     * @param aspect   输出长宽比，宽 / 高
     * @param minSpan  最小跨度（画布像素 = crs 单位）。太小会放大成一片马赛克
     * @param maxSpan  最大跨度。太大地标会缩到看不见，调用方据此决定要不要放弃地标
     * @param padding  四周留白比例，0.15 表示各边留出跨度的 15%
     */
    public static Viewport viewport(Canvas c, List<Point> points, double aspect,
                                    double minSpan, double maxSpan, double padding) {
        double minX = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        for (Point p : points) {
            minX = Math.min(minX, p.x());
            maxX = Math.max(maxX, p.x());
            minY = Math.min(minY, p.y());
            maxY = Math.max(maxY, p.y());
        }
        if (points.isEmpty()) {
            // 没有点就以画布中心为准，取最小跨度 —— 调用方本该拦住这种情况
            minX = maxX = c.width() / 2;
            minY = maxY = c.height() / 2;
        }
        double cx = (minX + maxX) / 2;
        double cy = (minY + maxY) / 2;
        double spanX = Math.max(1, maxX - minX);
        double spanY = Math.max(1, maxY - minY);

        double pad = Math.max(0, padding) * Math.max(spanX, spanY);
        spanX += 2 * pad;
        spanY += 2 * pad;
        // 按目标长宽比撑开：宁可多给一点世界范围，也不要把点裁掉
        if (aspect > 0 && spanX / spanY < aspect) {
            spanX = spanY * aspect;
        } else if (aspect > 0) {
            spanY = spanX / aspect;
        }
        // 跨度夹取以宽度为准，高度跟着长宽比走
        if (minSpan > 0 && spanX < minSpan) {
            spanX = minSpan;
            spanY = aspect > 0 ? spanX / aspect : spanY;
        }
        if (maxSpan > 0 && spanX > maxSpan) {
            spanX = maxSpan;
            spanY = aspect > 0 ? spanX / aspect : spanY;
        }
        double left = cx - spanX / 2;
        double top = cy - spanY / 2;
        // 能装下就贴回画布内，装不下就以画布中心为准 —— 免得图里一半是空白
        left = c.width() <= spanX ? (c.width() - spanX) / 2 : clamp(left, 0, c.width() - spanX);
        top = c.height() <= spanY ? (c.height() - spanY) / 2 : clamp(top, 0, c.height() - spanY);
        return new Viewport(left, top, spanX, spanY);
    }

    /**
     * 由跨度反推该用哪一档瓦片。
     *
     * <p>一块 {@code width} 档瓦片有 {@code width} 像素、代表 {@link #DEFAULT_TILE} 个 crs 单位，
     * 也就是每 crs 单位 {@code width / 1280} 像素。输出宽 {@code outputWidth} 像素要覆盖
     * {@code span} 个 crs 单位，需要 {@code outputWidth / span} 像素每单位。两者相等解出：
     *
     * <pre>档位宽度 ≈ 1280 × outputWidth ÷ span</pre>
     *
     * <p>取<b>不小于</b>这个值的最小可用档位 —— 宁可多下一档，也不要放大像素。
     * 反过来的话（取不超过的最大档）会出现"要 546 像素却拿 320 档"这种 1.7 倍放大，
     * 表现就是整张图发糊，而看的人只会觉得"你们这图不清楚"。
     * 多下的那点分辨率，比一张糊图值。
     *
     * <p>0 表示原图，不参与档位选择；所有档位都比需要的小时，用最大的那个兜底。
     *
     * @return 选中的档位；没有任何可用档位时返回 0
     */
    public static int pickTileWidth(double span, int outputWidth, List<Integer> available) {
        if (available == null || available.isEmpty() || span <= 0 || outputWidth <= 0) {
            return 0;
        }
        double needed = DEFAULT_TILE * outputWidth / span;
        int best = -1;
        int largest = -1;
        for (Integer w : available) {
            if (w == null || w <= 0) {
                continue;
            }
            largest = Math.max(largest, w);
            if (w >= needed && (best < 0 || w < best)) {
                best = w;
            }
        }
        if (best > 0) {
            return best;
        }
        return Math.max(largest, 0);
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private MapProjection() {
    }
}

package com.example.qqbot.kb.map;

import com.example.qqbot.kb.MapOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 把地图裁一块、标上点，编码成一张可以直接发出去的图。
 *
 * <h2>只画视口那一块，绝不碰整张画布</h2>
 * 余烬谷整图是 10240×10240，ARGB 约 400 MB —— 开一次就可能把 2G 的机器打死。
 * 这里只按视口取相交的瓦片，输出固定成配置里的尺寸（默认 1280×800，约 4 MB），
 * 而且每张瓦片是<b>解码一张、画一张</b>，不把整幅底图留在内存里。
 *
 * <h2>取瓦片有预算，到点就放弃</h2>
 * 没缓存过的瓦片要去 wiki 取。出站路径不能被外部站点的抖动拖住，所以每取一张之前
 * 都看一次剩余预算，到点直接返回"本次不出图"，由调用方触发后台预热。
 *
 * <h2>缺一张瓦片就整张不出</h2>
 * 半张图比没有图更糟：底图上少一块会被当成"那里就是黑的"，而群友据此判断位置。
 *
 * <p>渲染器自己不碰缓存实现：取图通过 {@link TileFetcher} 注入，
 * 于是它可以只靠内存里的假瓦片做单测。
 */
@Component
public class MapShotRenderer {

    private static final Logger log = LoggerFactory.getLogger(MapShotRenderer.class);

    /** 底图没盖到的地方露出来的底色 —— 用深灰而不是纯黑，免得看着像"数据缺失" */
    private static final Color BACKGROUND = new Color(24, 28, 34);

    /** 图标底下那圈暗色 —— 图标来自 wiki，深浅底图上都得看得清（与查看器同一套做法） */
    private static final Color ICON_BACKING = new Color(8, 10, 14, 184);

    /** 标签底色：半透明黑。与圈一样要打底 —— 浅色雪原与深色林地都得读得出来 */
    private static final Color LABEL_BG = new Color(0, 0, 0, 165);
    private static final Color LABEL_TEXT = new Color(255, 255, 255);

    /**
     * 画标签优先用哪些字体。
     *
     * <p>地点名是中文，而运行环境（容器）不一定装了中文字体：装不上时会画出一排方框，
     * 比不画更糟。所以按这张表逐个试 {@code canDisplayUpTo}，都不行再扫系统字体，
     * 实在没有就**放弃文字**、圈照画 —— 图本身还是能用的。
     */
    private static final String[] LABEL_FONTS = {
            "Noto Sans SC", "Noto Serif SC", "Noto Sans CJK SC", "Source Han Sans SC",
            "WenQuanYi Zen Hei", "Microsoft YaHei", "SimHei", "PingFang SC", Font.SANS_SERIF};

    /** 已经选中的字体族 —— 字体表查询不便宜，而一张图要问好几次 */
    private static volatile String labelFamily;
    private static final Color PRIMARY = new Color(230, 40, 40);
    private static final Color PRIMARY_HALO = Color.WHITE;

    /**
     * 取一张瓦片或整图。
     *
     * <p>由调用方绑定好"哪张地图、哪张画布"，渲染器不需要知道缓存在哪、怎么取。
     *
     * @param width 期望的缩略宽度，来自 {@link MapProjection#pickTileWidth}
     */
    @FunctionalInterface
    public interface TileFetcher {
        Optional<MapImageCache.Image> get(String image, int width);

        /**
         * 登记一张「这次要画、但预算用完没去取」的瓦片。
         *
         * <p><b>为什么必须有这个通道</b>：调用方只看得见 {@link #get} 返回空的那种缺口，
         * 而超预算时最常见的情形恰恰是**前一张取到了、只是太慢** —— 它不会出现在缺口里，
         * 于是按需预热永远收不到活干，表现就是同一个点位问多少次都不出图。
         *
         * <p>默认空实现：只关心取图的调用方与测试不必实现它。
         */
        default void need(String image, int width) {
        }

        /**
         * 取一张**分组图标**（原图档）。默认空实现 —— 不画图标的调用方与测试不用管。
         *
         * <p>图标与瓦片不是一回事：它按分组走、不带档位、也没有页面归属。
         *
         * <p><b>实现必须只读缓存，绝不能在这里等上游</b>：这一路调用发生在回复路径上，
         * 一张没预热到的图标就是一次好几秒的请求。取不到就不画，由预热任务补齐。
         */
        default Optional<MapImageCache.Image> icon(String image) {
            return Optional.empty();
        }
    }

    /**
     * 要画在图上的一点。
     *
     * @param x       画布像素坐标
     * @param y       画布像素坐标
     * @param primary true = 目标点，画大标记；false = 地标，画小标记
     * @param label   写在一旁的名字；空 = 不写
     * @param icon    分组图标文件名（如 {@code Flame_Shrine.png}）；空 = 只有圈
     */
    public record Mark(double x, double y, boolean primary, String label, String icon) {

        /** 只有高亮圈、没有图标：目标点与地标在没有分组图标时走这一条 */
        public Mark(double x, double y, boolean primary, String label) {
            this(x, y, primary, label, "");
        }

        /** 地图上本来就显示着的点位：图标 + 自己的名字，不带高亮圈 */
        public static Mark icon(double x, double y, String label, String icon) {
            return new Mark(x, y, false, label, icon);
        }
    }

    private final MapOptions options;

    public MapShotRenderer(MapOptions options) {
        this.options = options;
    }

    /**
     * 渲染一张。
     *
     * @param tiles           画布的瓦片清单；为空时退回整图 {@code background}
     * @param marks           至少要有目标点，否则调用方不该调它
     * @param deadlineAtMillis 取瓦片的绝对截止时刻
     * @return 图片字节；放弃出图时返回 empty
     */
    public Optional<byte[]> render(MapProjection.Canvas canvas, List<MapViewService.TileView> tiles,
                                   String background, List<Mark> marks, TileFetcher fetcher,
                                   long deadlineAtMillis) {
        return render(canvas, tiles, background, marks, List.of(), fetcher, deadlineAtMillis);
    }

    /**
     * 带背景图标的重载。
     *
     * @param context 地图上"本来就显示着"的点位。它们<b>不参与取景</b>（视口只由 {@code marks}
     *                决定），只负责让这张截图看得出是地图的哪一块 —— 只有目标点与地标的话，
     *                读图的人面对一片地形纹理无从判断自己在哪。
     */
    public Optional<byte[]> render(MapProjection.Canvas canvas, List<MapViewService.TileView> tiles,
                                   String background, List<Mark> marks, List<Mark> context,
                                   TileFetcher fetcher, long deadlineAtMillis) {
        MapOptions.Shot cfg = options.getShot();
        if (!cfg.isEnabled() || marks == null || marks.isEmpty()) {
            return Optional.empty();
        }
        int outW = cfg.getWidth();
        double aspect = (double) outW / cfg.getHeight();
        List<MapProjection.Point> points = marks.stream()
                .map(m -> new MapProjection.Point(m.x(), m.y()))
                .toList();
        MapProjection.Viewport vp = MapProjection.viewport(canvas, points, aspect,
                cfg.getMinSpan(), cfg.getMaxSpan(), cfg.getPadding());
        // 输出高度由输出宽度与长宽比算 —— 别拿视口宽度去除，那是坐标单位不是像素
        int outH = Math.max(1, (int) Math.round(outW / aspect));
        double k = outW / vp.width();
        int tileWidth = MapProjection.pickTileWidth(vp.width(), outW, options.getWidths());

        BufferedImage img = new BufferedImage(outW, outH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        int used = 0;
        try {
            g.setColor(BACKGROUND);
            g.fillRect(0, 0, outW, outH);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);

            List<BufferedImage> base = new ArrayList<>();
            List<MapProjection.Box> baseBoxes = new ArrayList<>();
            int wanted = 0;
            int skipped = 0;
            if (tiles != null && !tiles.isEmpty()) {
                for (MapViewService.TileView tile : tiles) {
                    MapProjection.Box box = MapProjection.tileBox(canvas, tile.col(), tile.row());
                    if (!intersects(box, vp)) {
                        continue;
                    }
                    wanted++;
                    if (System.currentTimeMillis() > deadlineAtMillis) {
                        // 超预算之后**不再取图**，但要把名字登记出去 —— 见 TileFetcher#need。
                        // 直接 return 的话调用方拿到的是空缺口清单："取到了、只是太慢"会被误判成
                        // "什么都不缺"，按需预热于是永远不补，同一个点位问多少次都不出图。
                        skipped++;
                        fetcher.need(tile.image(), tileWidth);
                        continue;
                    }
                    Optional<MapImageCache.Image> got = fetcher.get(tile.image(), tileWidth);
                    if (got.isEmpty()) {
                        // ⚠️ 这里**不能立刻返回**：把剩下的也试一遍，调用方才能拿到完整的缺口清单，
                        //    一次把缺的几张全交给按需预热。一缺就返回的话，每补一张才能前进一格。
                        continue;
                    }
                    BufferedImage tileImage = decode(got.get().bytes());
                    if (tileImage != null) {
                        base.add(tileImage);
                        baseBoxes.add(box);
                    }
                }
                if (skipped > 0) {
                    log.info("[MAP-SHOT] 取瓦片超预算，本次不出图（已取 {}/{} 张，{} 张已交给按需预热，{} 档）",
                            base.size(), wanted, skipped, tileWidth);
                    return Optional.empty();
                }
                if (base.size() < wanted) {
                    log.info("[MAP-SHOT] 底图缺瓦片（{}/{} 张），本次不出图 —— 半张图比没有图更糟",
                            base.size(), wanted);
                    return Optional.empty();
                }
            } else if (background != null && !background.isBlank()) {
                // 旧 schema 的画布没有瓦片，只有一整张底图
                if (System.currentTimeMillis() > deadlineAtMillis) {
                    log.info("[MAP-SHOT] 取底图超预算，本次不出图");
                    return Optional.empty();
                }
                Optional<MapImageCache.Image> got = fetcher.get(background, tileWidth);
                if (got.isEmpty()) {
                    log.info("[MAP-SHOT] 缺底图 {}，本次不出图", background);
                    return Optional.empty();
                }
                BufferedImage wholeImage = decode(got.get().bytes());
                if (wholeImage == null) {
                    log.info("[MAP-SHOT] 底图解不开，本次不出图");
                    return Optional.empty();
                }
                base.add(wholeImage);
                baseBoxes.add(new MapProjection.Box(0, 0, canvas.width(), canvas.height()));
            } else {
                log.info("[MAP-SHOT] 这张画布既没有瓦片也没有整图，画不出来");
                return Optional.empty();
            }
            for (int i = 0; i < base.size(); i++) {
                drawTile(g, base.get(i), baseBoxes.get(i), vp, k);
                used++;
            }
            // 图标要解码一次就够：一张图里同一个分组可能有十几个点
            Map<String, BufferedImage> iconCache = new HashMap<>();
            drawPoints(g, context, marks, vp, k, outW, outH, fetcher, iconCache);
        } finally {
            g.dispose();
        }

        // 计数要拷成 final 才能进 lambda —— 下面那句日志读的是最终值
        final int usedTiles = used;
        Optional<byte[]> out = encode(img, cfg);
        out.ifPresent(bytes -> log.info("[MAP-SHOT] 出图 {}×{}，底图 {} 张（{} 档），{} KB",
                outW, outH, usedTiles, tileWidth, bytes.length / 1024));
        return out;
    }

    /** 瓦片矩形与视口有没有交集 —— 不相交的瓦片连取都不取 */
    private static boolean intersects(MapProjection.Box box, MapProjection.Viewport vp) {
        return box.left() < vp.left() + vp.width() && box.left() + box.width() > vp.left()
                && box.top() < vp.top() + vp.height() && box.top() + box.height() > vp.top();
    }

    /** 解码一张瓦片。解不开返回 null —— 半张底图会被当成"那里就是黑的" */
    private static BufferedImage decode(byte[] bytes) {
        try {
            return ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (IOException e) {
            log.warn("[MAP-SHOT] 瓦片解码失败：{}", e.getMessage());
            return null;
        }
    }

    /**
     * 把一张瓦片画到输出图上。
     *
     * <p>尺寸用<b>两端点</b>算而不是直接乘：直接乘会因取整在瓦片之间留下 1 像素的缝，
     * 表现为底图上一道道细黑线。
     */
    private static void drawTile(Graphics2D g, BufferedImage tile, MapProjection.Box box,
                                 MapProjection.Viewport vp, double k) {
        int x0 = (int) Math.round((box.left() - vp.left()) * k);
        int y0 = (int) Math.round((box.top() - vp.top()) * k);
        int x1 = (int) Math.round((box.left() + box.width() - vp.left()) * k);
        int y1 = (int) Math.round((box.top() + box.height() - vp.top()) * k);
        g.drawImage(tile, x0, y0, Math.max(1, x1 - x0), Math.max(1, y1 - y0), null);
    }

    /**
     * 画点位。
     *
     * <p>两类点在这里合流：<b>背景点</b>（地图上默认显示的那些，图标 + 名字）与
     * <b>目标点</b>（同一套画法再套一圈红环）。背景先画、目标后画，压在最上面。
     *
     * <p>为什么背景点也要名字：只画图标，读图的人认得出"这里是敌营"，却认不出"这是哪个敌营"，
     * 而群友互相指路用的是名字。名字与图标一样要打底，否则浅色雪原上读不出来。
     */
    private static void drawPoints(Graphics2D g, List<Mark> context, List<Mark> marks,
                                   MapProjection.Viewport vp, double k, int outW, int outH,
                                   TileFetcher fetcher, Map<String, BufferedImage> iconCache) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        int size = iconBox(k);
        // 1) 图标：背景先、目标后，目标压在最上面
        if (context != null) {
            for (Mark m : context) {
                drawGlyph(g, m, vp, k, size, outW, outH, fetcher, iconCache, false);
            }
        }
        for (Mark m : marks) {
            drawGlyph(g, m, vp, k, size, outW, outH, fetcher, iconCache, m.primary());
        }
        // 2) 名字：**目标点先占位**（它必须有名字），背景点撞上就让位
        List<Rectangle> taken = new ArrayList<>();
        for (Mark m : marks) {
            drawLabel(g, m, vp, k, size, outW, outH, taken);
        }
        if (context != null) {
            for (Mark m : context) {
                drawLabel(g, m, vp, k, size, outW, outH, taken);
            }
        }
    }

    /**
     * 一个点的图形部分：图标，{@code highlight} 为真时再套一圈红环。
     *
     * <p><b>只有目标点套环</b>：环的意思就是"看这里"，一圈好几个等于一个都没圈。
     * 参照物靠"图标 + 名字"去认，那本来就是地图的读法。
     */
    private static void drawGlyph(Graphics2D g, Mark m, MapProjection.Viewport vp, double k,
                                  int iconSize, int outW, int outH, TileFetcher fetcher,
                                  Map<String, BufferedImage> iconCache, boolean highlight) {
        double[] at = onScreen(m, vp, k, iconSize, outW, outH);
        if (at == null) {
            return;
        }
        double sx = at[0];
        double sy = at[1];
        boolean withIcon = drawIcon(g, fetcher, iconCache, m.icon(), sx, sy, iconSize, outW, outH);
        if (!highlight) {
            return;
        }
        int r = Math.max(7, outW / 90);
        // 圈比图标小的话会被图标盖住，等于没有高亮 —— 半径必须让开图标。
        // 图标没画上时才退回"圈 + 十字"，那是没有图标可认时的兜底
        int radius = withIcon ? Math.max(r, iconSize / 2 + 4) : r;
        ring(g, sx, sy, radius, PRIMARY_HALO, 4.5f);
        ring(g, sx, sy, radius, PRIMARY, 2.5f);
        if (!withIcon) {
            g.setColor(PRIMARY);
            g.setStroke(new BasicStroke(2.5f));
            int arm = (int) Math.round(r * 1.9);
            int gap = (int) Math.round(r * 0.7);
            g.drawLine((int) sx - arm, (int) sy, (int) sx - gap, (int) sy);
            g.drawLine((int) sx + gap, (int) sy, (int) sx + arm, (int) sy);
            g.drawLine((int) sx, (int) sy - arm, (int) sx, (int) sy - gap);
            g.drawLine((int) sx, (int) sy + gap, (int) sx, (int) sy + arm);
        }
    }

    /** 点在屏幕上的位置；视口外返回 {@code null} —— 视口就是取景框，权当它天然裁剪 */
    private static double[] onScreen(Mark m, MapProjection.Viewport vp, double k, int iconSize,
                                     int outW, int outH) {
        double sx = (m.x() - vp.left()) * k;
        double sy = (m.y() - vp.top()) * k;
        if (sx < -iconSize || sy < -iconSize || sx > outW + iconSize || sy > outH + iconSize) {
            return null;
        }
        return new double[]{sx, sy};
    }

    /**
     * 画一个分组图标，返回有没有真的画上去。
     *
     * <p>图标取自 wiki，缓存里没有就是没有 —— 这时**什么都不画**，让调用方自己决定要不要退回
     * 画圈。画一个"占位空心圈"是查看器的做法（那边图标是异步到的），静态截图里那样做只会多出一堆
     * 没有意义的圈。
     */
    private static boolean drawIcon(Graphics2D g, TileFetcher fetcher,
                                    Map<String, BufferedImage> iconCache, String name,
                                    double sx, double sy, int size, int outW, int outH) {
        if (name == null || name.isBlank() || size <= 0) {
            return false;
        }
        // 视口外的不画 —— 视口是取景框，权当它天然裁剪
        if (sx < -size || sy < -size || sx > outW + size || sy > outH + size) {
            return false;
        }
        BufferedImage img = iconImage(fetcher, name, iconCache);
        if (img == null) {
            return false;
        }
        int x = (int) Math.round(sx - size / 2.0);
        int y = (int) Math.round(sy - size / 2.0);
        // 图标是彩色小图，深浅底图上都得有一圈暗底才看得清（查看器同一套做法）
        g.setColor(ICON_BACKING);
        g.fillOval(x - 2, y - 2, size + 4, size + 4);
        g.drawImage(img, x, y, size, size, null);
        return true;
    }

    private static BufferedImage iconImage(TileFetcher fetcher, String name,
                                           Map<String, BufferedImage> iconCache) {
        if (iconCache.containsKey(name)) {
            return iconCache.get(name);
        }
        BufferedImage img = null;
        try {
            Optional<MapImageCache.Image> got = fetcher.icon(name);
            if (got.isPresent()) {
                img = decode(got.get().bytes());
            }
        } catch (Exception e) {
            // 图标取不到只是少一个参照物，绝不能把整张图带走
            log.warn("[MAP-SHOT] 图标取不到，本张不出这个图标：{} —— {}", name, e.getMessage());
        }
        iconCache.put(name, img);   // 失败也记下来：同一张图里同一个图标只试一次
        return img;
    }

    /**
     * 图标在屏幕上的边长 —— 与查看器 {@code MapCanvas.vue} 的 {@code iconBox} 同一个公式。
     *
     * <p>查看器实测的结论是"永远画图标，只让尺寸跟着缩放走"：缩小时 14px、放大时 26px。
     * 出图这里缩放通常是 1:1 上下，所以几乎是满尺寸 26px —— 和访客在网页上看到的一致。
     */
    static int iconBox(double k) {
        return (int) Math.round(Math.max(14, Math.min(26, 8 + 24 * k)));
    }

    /**
     * 在点位旁边写上名字 —— {@link Mark#label()} 存在的理由。
     *
     * <h2>为什么必须有</h2>
     * 只画图标，读图的人认得出"这里是敌营"，却认不出"这是哪个敌营"，而群友互相指路用的
     * 是名字。名字与图标一样要打底：深色半透明胶囊 + 白字，浅色雪原与深色林地都读得出来。
     *
     * <h2>为什么要抢位置</h2>
     * 一屏可能有几十个点位，名字全画出来会糊成一团、一个都读不成。所以按
     * <b>右 → 左 → 下 → 上</b> 依次试，撞上已占位的就让位；四个方向都撞上，这个点这一张
     * 就不写名字。调用方**先传目标点**，它的名字因此永远有位置 —— 被问的就是它。
     */
    private static void drawLabel(Graphics2D g, Mark m, MapProjection.Viewport vp, double k,
                                  int iconSize, int outW, int outH, List<Rectangle> taken) {
        String text = m.label() == null ? "" : m.label().strip();
        if (text.isEmpty()) {
            return;
        }
        double[] at = onScreen(m, vp, k, iconSize, outW, outH);
        if (at == null) {
            return;
        }
        Font fontBase = labelFont(text);
        if (fontBase == null) {
            // 一个能显示这段字的字体都没有：宁可不写，也不要画一排方框
            return;
        }
        int size = Math.max(12, outW / 64);
        Font font = fontBase.deriveFont((float) size);
        FontMetrics fm = g.getFontMetrics(font);
        int padX = Math.max(6, size / 3);
        int padY = Math.max(3, size / 6);
        int w = fm.stringWidth(text) + padX * 2;
        int h = fm.getHeight() + padY * 2;

        // 让开的东西不一样：目标点要绕开红环，背景点绕开图标
        int off = m.primary()
                ? Math.max(Math.max(7, outW / 90), iconSize / 2 + 4)
                : iconSize / 2;
        int gap = Math.max(4, off / 2);
        int cx = (int) Math.round(at[0]);
        int cy = (int) Math.round(at[1]);
        int[][] spots = {
                {cx + off + gap, cy - h / 2},
                {cx - off - gap - w, cy - h / 2},
                {cx - w / 2, cy + off + gap},
                {cx - w / 2, cy - off - gap - h},
        };
        for (int[] spot : spots) {
            int x = Math.max(4, Math.min(spot[0], Math.max(4, outW - w - 4)));
            int y = Math.max(4, Math.min(spot[1], Math.max(4, outH - h - 4)));
            Rectangle rect = new Rectangle(x, y, w, h);
            if (taken.stream().anyMatch(rect::intersects)) {
                continue;
            }
            taken.add(rect);
            g.setColor(LABEL_BG);
            g.fillRoundRect(x, y, w, h, h, h);
            if (m.primary()) {
                // 目标点的标签描一圈红边，与红圈对应；其余不描边，读的时候一眼分得开
                g.setColor(PRIMARY);
                g.setStroke(new BasicStroke(2f));
                g.drawRoundRect(x, y, w, h, h, h);
            }
            g.setFont(font);
            g.setColor(LABEL_TEXT);
            g.drawString(text, x + padX, y + padY + fm.getAscent());
            return;
        }
    }

    /**
     * 挑一个能显示这段文字的字体；一个都挑不到返回 {@code null}。
     *
     * <p>先看上次选中的（{@link #labelFamily}），再从偏好表里找，最后扫系统全部字体。
     * 结果缓存下来：{@code getAvailableFontFamilyNames} 不便宜，而每张图要问好几次。
     */
    private static Font labelFont(String text) {
        String cached = labelFamily;
        if (cached != null) {
            Font f = new Font(cached, Font.PLAIN, 1);
            if (f.canDisplayUpTo(text) < 0) {
                return f;
            }
        }
        for (String family : LABEL_FONTS) {
            Font f = new Font(family, Font.PLAIN, 1);
            if (f.canDisplayUpTo(text) < 0) {
                labelFamily = f.getFamily();
                return f;
            }
        }
        for (String family : GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getAvailableFontFamilyNames()) {
            Font f = new Font(family, Font.PLAIN, 1);
            if (f.canDisplayUpTo(text) < 0) {
                labelFamily = f.getFamily();
                return f;
            }
        }
        return null;
    }

    private static void ring(Graphics2D g, double x, double y, int r, Color color, float width) {
        g.setColor(color);
        g.setStroke(new BasicStroke(width));
        g.drawOval((int) Math.round(x - r), (int) Math.round(y - r), r * 2, r * 2);
    }

    /**
     * 编码。地图是照片类图像，PNG 会比 JPEG 大好几倍，所以默认走 JPEG。
     *
     * <p>超过字节上限时按 降质量 → 缩尺寸 的顺序退让：先牺牲一点画质，
     * 实在不行才牺牲尺寸。都失败才放弃出图。
     */
    private static Optional<byte[]> encode(BufferedImage img, MapOptions.Shot cfg) {
        int cap = cfg.getMaxKb() * 1024;
        byte[] jpeg = jpeg(img, cfg.getJpegQuality());
        if (jpeg.length == 0) {
            return Optional.empty();
        }
        if (jpeg.length <= cap) {
            return Optional.of(jpeg);
        }
        byte[] lower = jpeg(img, 60);
        if (lower.length <= cap) {
            return Optional.of(lower);
        }
        byte[] shrunk = jpeg(scale(img, 0.7), 60);
        if (shrunk.length <= cap) {
            return Optional.of(shrunk);
        }
        log.warn("[MAP-SHOT] 出图仍超过上限 {} KB（{} KB），放弃", cfg.getMaxKb(), shrunk.length / 1024);
        return Optional.empty();
    }

    private static byte[] jpeg(BufferedImage img, int quality) {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            return new byte[0];
        }
        ImageWriter writer = writers.next();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(Math.min(1f, Math.max(0.1f, quality / 100f)));
            try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
                writer.setOutput(ios);
                writer.write(null, new IIOImage(img, null, null), param);
            }
        } catch (IOException e) {
            log.warn("[MAP-SHOT] 编码失败：{}", e.getMessage());
            return new byte[0];
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    private static BufferedImage scale(BufferedImage src, double factor) {
        int w = Math.max(1, (int) Math.round(src.getWidth() * factor));
        int h = Math.max(1, (int) Math.round(src.getHeight() * factor));
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(src, 0, 0, w, h, null);
        } finally {
            g.dispose();
        }
        return out;
    }
}

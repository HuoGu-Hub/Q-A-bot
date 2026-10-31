package com.example.qqbot.kb;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 地图查看器与地点问答的配置（{@code app.kb.map.*}）。
 *
 * <h2>为什么要独立一份，而不是塞进 {@link MapSync}</h2>
 * {@code map-sync} 管的是"从 wiki 拉结构化数据"，是**一次性的批处理**；
 * 这里管的是"给人看图"和"回答里怎么说方位"，是**每次访问都在跑的在线路径**。
 * 两者的开关时机、失败影响面完全不同 —— 抓不到 wiki 不该让查看器打不开本地缓存的底图。
 */
@ConfigurationProperties(prefix = "app.kb.map")
public class MapOptions {

    /** 总开关。关掉则地图接口整体不可用，但数据库里的点位不受影响 */
    private boolean enabled = true;

    /** 瓦片与底图的磁盘缓存目录（相对路径以进程工作目录为基准） */
    private String tileDir = "./data/map-tiles";

    /**
     * The Worst Map Mod 导出的点位目录。目录下按版本分子目录，每个子目录一个 points.json。
     * 空串或不存在时资源图层不可用，wiki 地图不受影响。
     */
    private String modDir = "./data/map-mod";

    /** 资源点位的总开关。关掉后接口返回空目录，不碰库里的数据 */
    private boolean modEnabled = true;

    /** 磁盘上没有 points.json 时从 classpath 取这一份（随 jar 发布） */
    private String modResource = "map-mod/1.3.26/points.json";

    /**
     * wiki 的图片直链前缀。
     *
     * <p>用 {@code Special:FilePath/} 而不是 {@code /images/}：
     * 后者在 MediaWiki 上会带一层哈希目录（实测 {@code /images/0_0.jpg?f8658e}），
     * 文件一改哈希就变；{@code Special:FilePath} 是官方的稳定入口，且原生支持 {@code ?width=}。
     */
    private String imageBase = "https://enshrouded.wiki.gg/wiki/Special:FilePath/";

    /**
     * 图片直链前缀。{@code Special:FilePath} 被 Cloudflare 403 时的兜底：
     * 直接走 {@code /images/}，不经过那个会被拦截的重定向入口。
     */
    private String imageDirectBase = "https://enshrouded.wiki.gg/images/";

    /** 遵守对方的使用礼仪：没有自定义 UA 的请求实测会被直接限流 */
    private String userAgent = "qqbot-personal/0.1 (Enshrouded map viewer; contact via group)";

    private int timeoutSeconds = 30;

    /** 单张图字节上限。1280 的瓦片实测约 400 KB，给 12 MB 足够且能挡住异常大文件 */
    private int maxBytes = 12 * 1024 * 1024;

    /**
     * 允许请求的缩略宽度。
     *
     * <p><b>为什么要白名单</b>：不加限制的话，任何人都能用 {@code ?w=999} 这类参数
     * 逼服务端下载并缓存无穷多份不同尺寸的同一张图 —— 磁盘会被慢速刷爆。
     * 白名单是 0（原图）与四档常用宽度。
     */
    private List<Integer> widths = List.of(0, 160, 320, 640, 1280);

    /**
     * 1 米等于多少坐标单位。{@code <= 0} 表示**不输出米**，这是默认值。
     *
     * <p>wiki 的 x/y 是游戏坐标系，公开资料查不到它和米的换算。
     * 编一个数字会被大模型当成事实转述给群友，比不给距离更糟。
     * 确认比例后改这一个值即可。
     */
    private double unitsPerMeter = 0;

    /**
     * 最大放大倍数：**屏幕像素 / 原图像素**。
     *
     * <p>为什么必须有上限：瓦片原图只有 1280×1280，把它画到屏幕上 1280 像素就是 1:1。
     * 再往上每一倍都是在放大像素 —— 用户看到的是"糊"，而他会以为是我们做得不如 wiki 清楚。
     * 实测 8 倍时整张图就是一团色块。
     *
     * <p>默认 2 倍：放大到两倍仍能看清地形纹理与图标，同时足够把一小片区域铺满屏幕。
     * 想要更"能放大"就调大它，代价是越往上越糊。
     */
    private double maxZoom = 2.0;

    /** 一次地点问答最多列几处 */
    private int answerLimit = 5;

    /** 资源热点统计与资源截图的参数 */
    private Resource resource = new Resource();

    /** 地点问答的配图（把地图裁一块发给群友） */
    private Shot shot = new Shot();

    /**
     * 两次出站取图之间的最小间隔（毫秒）—— **固定值**，不再是自适应漂移的那个数。
     *
     * <p>实测 2026-10-15：连两个从未取过的瓦片、间隔 6 秒，对方（Cloudflare）仍然都回 429，
     * 且没有 {@code Retry-After}。说明这已经不是"每秒几次"的问题，而是被记了一段时间。
     * 所以默认取 6 秒（约 10 次/分钟），撞限流时靠下面的暂停来止损。
     */
    private long requestGapMs = 6000;

    /** 连续撞多少次 429 就整条链路暂停取图 */
    private int rateLimitStreak = 3;

    /** 暂停多久（毫秒）。暂停期间一次请求都不发 */
    private long rateLimitPauseMs = 300_000;

    /**
     * 地图同步发现内容变化后，自动补一次**首屏档**预热。
     *
     * <p>只补首屏档是有意的：同步是管理员随时可能点的，而全量预热要拉原图、跑十几分钟，
     * 还会把出站间隔顶高、拖慢同时在线的访客 —— 那是非高峰才做的事。
     *
     * <p>注意另外两个键 {@code app.kb.map.warm-cron} 与 {@code warm-zone} **不在这里**：
     * 它们是 {@code @Scheduled} 注解上的占位符，必须在编译期常量里读，
     * 没法从本对象取。见 {@link com.example.qqbot.kb.map.MapWarmScheduler}。
     */
    private boolean warmAfterSync = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getTileDir() {
        return tileDir;
    }

    public void setTileDir(String tileDir) {
        this.tileDir = tileDir;
    }

    public String getModDir() {
        return modDir;
    }

    public void setModDir(String modDir) {
        this.modDir = modDir;
    }

    public boolean isModEnabled() {
        return modEnabled;
    }

    public void setModEnabled(boolean modEnabled) {
        this.modEnabled = modEnabled;
    }

    public String getModResource() {
        return modResource;
    }

    public void setModResource(String modResource) {
        this.modResource = modResource;
    }

    public String getImageBase() {
        return imageBase;
    }

    public void setImageBase(String imageBase) {
        this.imageBase = imageBase;
    }

    public String getImageDirectBase() {
        return imageDirectBase;
    }

    public void setImageDirectBase(String imageDirectBase) {
        this.imageDirectBase = imageDirectBase;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public int getMaxBytes() {
        return maxBytes;
    }

    public void setMaxBytes(int maxBytes) {
        this.maxBytes = maxBytes;
    }

    public List<Integer> getWidths() {
        return widths;
    }

    public void setWidths(List<Integer> widths) {
        this.widths = widths == null || widths.isEmpty() ? List.of(0, 160, 320, 640, 1280) : widths;
    }

    public double getUnitsPerMeter() {
        return unitsPerMeter;
    }

    public void setUnitsPerMeter(double unitsPerMeter) {
        this.unitsPerMeter = unitsPerMeter;
    }

    public double getMaxZoom() {
        return maxZoom;
    }

    public void setMaxZoom(double maxZoom) {
        // 小于 1 就等于"连原图都看不到"，那一定是配错了 —— 收在 1 起步
        this.maxZoom = Math.max(1.0, maxZoom);
    }

    public int getAnswerLimit() {
        return answerLimit;
    }

    public void setAnswerLimit(int answerLimit) {
        this.answerLimit = answerLimit;
    }

    public boolean isWarmAfterSync() {
        return warmAfterSync;
    }

    public void setWarmAfterSync(boolean warmAfterSync) {
        this.warmAfterSync = warmAfterSync;
    }

    public long getRequestGapMs() {
        return requestGapMs;
    }

    public void setRequestGapMs(long requestGapMs) {
        this.requestGapMs = Math.max(50, requestGapMs);
    }

    public int getRateLimitStreak() {
        return rateLimitStreak;
    }

    public void setRateLimitStreak(int rateLimitStreak) {
        this.rateLimitStreak = Math.max(1, rateLimitStreak);
    }

    public long getRateLimitPauseMs() {
        return rateLimitPauseMs;
    }

    public void setRateLimitPauseMs(long rateLimitPauseMs) {
        this.rateLimitPauseMs = Math.max(0, rateLimitPauseMs);
    }

    public Resource getResource() {
        return resource;
    }

    public void setResource(Resource resource) {
        this.resource = resource == null ? new Resource() : resource;
    }

    public Shot getShot() {
        return shot;
    }

    public void setShot(Shot shot) {
        this.shot = shot;
    }

    /**
     * 地图截图的参数（{@code app.kb.map.shot.*}）。
     *
     * <h2>为什么这些值要可配</h2>
     * 它们是"看得清"与"发得出去"之间的取舍：跨度太大点位挤成一团，太小就只剩一片地形纹理；
     * 输出越大越清楚，但字节数、渲染内存、上传时间都跟着涨。
     * 换一张地图、换一个群里的人数，合适的值就可能不一样，所以做成配置而不是常量。
     *
     * <h2>deadline 为什么是这里最要紧的一项</h2>
     * 出图要读瓦片，而没缓存过的瓦片要去 wiki 取。这条预算决定了"取不到时群友等多久" ——
     * 到点就放弃出图、只回文字，把外部依赖的抖动挡在回复路径之外。
     */
    public static class Shot {

        /** 总开关。关掉后 {@code [[img:map:...]]} 一律不出图，只当普通文本丢掉 */
        private boolean enabled = true;

        /** 输出宽度（像素） */
        private int width = 1280;

        /** 输出高度（像素）。与宽度一起决定视口的长宽比，保证图不变形 */
        private int height = 800;

        /** 视口跨度下限（坐标单位）。太小会放大成一片马赛克 */
        private double minSpan = 1200;

        /** 视口跨度上限（坐标单位）。太大就只画目标点，不放地标 */
        private double maxSpan = 6000;

        /** 四周留白比例。0.15 表示各边留出跨度的 15% */
        private double padding = 0.15;

        /** 取瓦片的总预算（毫秒）。到点就放弃本次出图 */
        private long deadlineMs = 2000;

        /**
         * 一张截图里除目标点外，最多再给几个点位标名字。
         *
         * <p>名字是拿来认方位的，不是拿来列清单的：一屏几十个名字会互相压住，
         * 结果一个都读不成。所以只标**离目标点最近**的几个，其余点位只画图标。
         */
        private int labelMax = 4;

        /** 输出字节上限（KB）。超了先降 JPEG 质量，再缩一档 */
        private int maxKb = 2048;

        /** JPEG 质量。地图是照片类图像，PNG 会大好几倍 */
        private int jpegQuality = 85;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getWidth() {
            return width;
        }

        public void setWidth(int width) {
            this.width = Math.max(160, width);
        }

        public int getHeight() {
            return height;
        }

        public void setHeight(int height) {
            this.height = Math.max(100, height);
        }

        public double getMinSpan() {
            return minSpan;
        }

        public void setMinSpan(double minSpan) {
            this.minSpan = Math.max(1, minSpan);
        }

        public double getMaxSpan() {
            return maxSpan;
        }

        public void setMaxSpan(double maxSpan) {
            this.maxSpan = Math.max(1, maxSpan);
        }

        public double getPadding() {
            return padding;
        }

        public void setPadding(double padding) {
            this.padding = Math.max(0, padding);
        }

        public long getDeadlineMs() {
            return deadlineMs;
        }

        public void setDeadlineMs(long deadlineMs) {
            this.deadlineMs = Math.max(1, deadlineMs);
        }

        public int getLabelMax() {
            return labelMax;
        }

        public void setLabelMax(int labelMax) {
            this.labelMax = Math.max(0, labelMax);
        }

        public int getMaxKb() {
            return maxKb;
        }

        public void setMaxKb(int maxKb) {
            this.maxKb = Math.max(1, maxKb);
        }

        public int getJpegQuality() {
            return jpegQuality;
        }

        public void setJpegQuality(int jpegQuality) {
            this.jpegQuality = Math.min(100, Math.max(10, jpegQuality));
        }
    }

    /**
     * 资源热点与资源截图的参数（{@code app.kb.map.resource.*}）。
     *
     * <h2>为什么这些要可配</h2>
     * 格宽与半径决定"什么叫一片"：100 单位一格能把铜矿分成可读的几片，
     * 但换一张地图、换一种资源，合适的尺度会变；NMS 半径同理 —— 太小时三片挤在一处，
     * 太大时整张图只剩一个热点。这些值必须能在不改代码的情况下调。
     */
    public static class Resource {

        /** 网格边长（坐标单位） */
        private double cellSize = ResourceHotspots.CELL;

        /** 候选热点之间的最小距离（坐标单位） */
        private double nmsRadius = ResourceHotspots.NMS_RADIUS;

        /** 统计"这一片有多少点"的半径（坐标单位） */
        private double hotRadius = ResourceHotspots.HOT_RADIUS;

        /** 一次回答最多给几片 */
        private int top = ResourceHotspots.TOP;

        /** 资源截图里最多给几个参照地点写名字 */
        private int labelMax = 8;

        /** 资源截图里最多画几个参照地点 */
        private int maxContext = 40;

        public double getCellSize() {
            return cellSize;
        }

        public void setCellSize(double cellSize) {
            this.cellSize = Math.max(1, cellSize);
        }

        public double getNmsRadius() {
            return nmsRadius;
        }

        public void setNmsRadius(double nmsRadius) {
            this.nmsRadius = Math.max(0, nmsRadius);
        }

        public double getHotRadius() {
            return hotRadius;
        }

        public void setHotRadius(double hotRadius) {
            this.hotRadius = Math.max(1, hotRadius);
        }

        public int getTop() {
            return top;
        }

        public void setTop(int top) {
            this.top = Math.max(1, top);
        }

        public int getLabelMax() {
            return labelMax;
        }

        public void setLabelMax(int labelMax) {
            this.labelMax = Math.max(0, labelMax);
        }

        public int getMaxContext() {
            return maxContext;
        }

        public void setMaxContext(int maxContext) {
            this.maxContext = Math.max(1, maxContext);
        }
    }
}

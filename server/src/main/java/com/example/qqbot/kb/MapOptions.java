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
     * wiki 的图片直链前缀。
     *
     * <p>用 {@code Special:FilePath/} 而不是 {@code /images/}：
     * 后者在 MediaWiki 上会带一层哈希目录（实测 {@code /images/0_0.jpg?f8658e}），
     * 文件一改哈希就变；{@code Special:FilePath} 是官方的稳定入口，且原生支持 {@code ?width=}。
     */
    private String imageBase = "https://enshrouded.wiki.gg/wiki/Special:FilePath/";

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

    public String getImageBase() {
        return imageBase;
    }

    public void setImageBase(String imageBase) {
        this.imageBase = imageBase;
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
}

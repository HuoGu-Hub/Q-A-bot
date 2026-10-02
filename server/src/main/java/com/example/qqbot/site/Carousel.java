package com.example.qqbot.site;

/**
 * 首页轮播。
 *
 * <p>图片本身存在 {@link #dir} 里、清单存在库里（见 {@code CarouselStore}）；
 * 这里只是可调的那几个数 —— 它们都在管理后台可改、**热生效**。
 *
 * <h2>为什么不在 {@code config} 包里（2026-10-02 挪过来）</h2>
 * 它是<b>公开站自己的领域词汇</b>（轮播的可调参数），只是碰巧从 {@code app.site.carousel.*}
 * 绑定过来。留在 {@code SiteProperties} 的嵌套类里，会让 {@code site} 包看起来
 * "依赖配置的形状"。搬迁是**纯搬运**：零语义变化，yml 的键一个都没动。
 */
public class Carousel {

    /** 总开关。关掉后公开站首页不渲染轮播（图还在，随时能开回来） */
    private boolean enabled = true;

    /** 自动切换间隔（毫秒）。小于 1000 按 1000 算，免得闪得人眼花 */
    private int intervalMs = 4000;

    /** 最多几张。到上限后管理端会拒绝上传（让人先删） */
    private int maxCount = 8;

    /** 单张图片大小上限（KB） */
    private int maxSizeKb = 2048;

    /** 图片存放目录（相对程序工作目录）。**只读不写进白名单** —— 改路径属于基础设施 */
    private String dir = "./data/site-images";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getIntervalMs() {
        return intervalMs;
    }

    public void setIntervalMs(int intervalMs) {
        this.intervalMs = intervalMs;
    }

    public int getMaxCount() {
        return maxCount;
    }

    public void setMaxCount(int maxCount) {
        this.maxCount = maxCount;
    }

    public int getMaxSizeKb() {
        return maxSizeKb;
    }

    public void setMaxSizeKb(int maxSizeKb) {
        this.maxSizeKb = maxSizeKb;
    }

    public String getDir() {
        return dir;
    }

    public void setDir(String dir) {
        this.dir = dir;
    }
}

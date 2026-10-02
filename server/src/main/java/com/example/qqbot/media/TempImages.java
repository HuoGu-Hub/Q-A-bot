package com.example.qqbot.media;

/**
 * TempImages。
 *
 * <h2>为什么不在 {@code config} 包里（2026-10-02 挪过来）</h2>
 * 它是<b>媒体层的领域词汇</b>（临时图片与知识库图片的目录、清理策略），只是碰巧从
 * {@code app.media.*} 绑定过来。留在 {@code MediaProperties} 的嵌套类里，会让
 * {@code media} 包看起来"依赖配置的形状"。
 * 搬迁是**纯搬运**：零语义变化，yml 的键一个都没动。
 */
public class TempImages {

    /** 总开关。关掉后定时清理完全不动手（目录也不会创建） */
    private boolean enabled = true;

    /**
     * 临时图片目录，相对路径以「程序的工作目录」为基准。
     *
     * <p>⚠️ 绝不能配成 {@code kb-images} 的同一个路径 —— 那等于把清理脚本指向知识库，
     * 启动时会被 {@code MediaStorageGuard} 拦下。
     */
    private String dir = "./data/tmp-images";

    /** 保留多久（小时）。最后修改时间早于「现在 - 这个值」的文件才会被删 */
    private int retentionHours = 24;

    /** 清理频率（分钟）。上一次跑完到下一次开始之间的间隔 */
    private int cleanupIntervalMinutes = 60;

    /**
     * 单次清理最多删多少个文件。
     * 防止某个时刻突然有几十万个文件要删，一次性把磁盘 IO 打满、拖慢整个服务。
     */
    private int maxDeletePerRun = 500;

    /**
     * 缓存总量上限（MB）。超过后按**最后使用时间**从旧到新淘汰（LRU）。
     *
     * <p>为什么光有 TTL 不够：保留时长只管「多久没用就删」，
     * 但一个热门群完全可能在一小时内塞进几百兆图片，TTL 还没到磁盘就满了。
     * 0 = 不限制（不建议）。
     */
    private int maxTotalSizeMb = 200;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getDir() {
        return dir;
    }

    public void setDir(String dir) {
        this.dir = dir;
    }

    public int getRetentionHours() {
        return retentionHours;
    }

    public void setRetentionHours(int retentionHours) {
        this.retentionHours = retentionHours;
    }

    public int getCleanupIntervalMinutes() {
        return cleanupIntervalMinutes;
    }

    public void setCleanupIntervalMinutes(int cleanupIntervalMinutes) {
        this.cleanupIntervalMinutes = cleanupIntervalMinutes;
    }

    public int getMaxDeletePerRun() {
        return maxDeletePerRun;
    }

    public void setMaxDeletePerRun(int maxDeletePerRun) {
        this.maxDeletePerRun = maxDeletePerRun;
    }

    public int getMaxTotalSizeMb() {
        return maxTotalSizeMb;
    }

    public void setMaxTotalSizeMb(int maxTotalSizeMb) {
        this.maxTotalSizeMb = maxTotalSizeMb;
    }
}

package com.example.qqbot.media;

/**
 * wiki 抓来的配图 —— 与「知识库配图」「临时图片」并列的**第三类图片目录**。
 *
 * <h2>为什么必须单独一个目录</h2>
 * 这三类图片的生命周期完全不同：
 * <ul>
 *   <li><b>临时图片</b>：随时可删，定时任务按最后修改时间清；</li>
 *   <li><b>知识库配图</b>：人工维护的长期资产，清理逻辑绝不碰；</li>
 *   <li><b>wiki 配图</b>：自动抓取、<b>可重建</b>。整批删掉不会丢任何人工成果，
 *       但删了之后要重新等几小时的上游限流窗口。</li>
 * </ul>
 *
 * <p>把 wiki 配图放进 {@code kb-images} 会让"哪些图是我们自己做的"变得不可分辨，
 * 也会让将来的配额淘汰误伤人工资产；放进 {@code tmp-images} 则会被 24 小时清理掉。
 * 所以单独一个目录，并由 {@link MediaStorageGuard} 在启动时做三向隔离校验。
 *
 * <h2>为什么容量上限默认只有 512MB</h2>
 * 实测每页主图 29~90KB，按 4,000 页算也就 100~250MB。512MB 是"够用且能给地图瓦片留余地"
 * 的数：这台机器只有 50G，而瓦片缓存没有上限。
 */
public class WikiImages {

    /** 总开关。关掉后不再抓新图，但已经落盘的图仍然可以发出去 */
    private boolean enabled = true;

    /** 目录。相对路径以程序的工作目录为基准；启动日志会打印解析后的绝对路径 */
    private String dir = "./data/wiki-images";

    /**
     * 目录总容量上限（MB）。超过后**不再写入新图**，只记日志。
     *
     * <p>为什么是"停写"而不是 LRU 淘汰：这些图是块正文里 marker 指向的资产，
     * 淘汰一张就会让一条历史回答变成"只有标记没有图"。宁可少抓新的，
     * 也不要把已有的弄坏。要腾空间请显式清空目录再重抓。
     *
     * <p>0 = 不限制（不建议，50G 的盘还有日志与瓦片在长）。
     */
    private int maxTotalSizeMb = 512;

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

    public int getMaxTotalSizeMb() {
        return maxTotalSizeMb;
    }

    public void setMaxTotalSizeMb(int maxTotalSizeMb) {
        this.maxTotalSizeMb = maxTotalSizeMb;
    }
}

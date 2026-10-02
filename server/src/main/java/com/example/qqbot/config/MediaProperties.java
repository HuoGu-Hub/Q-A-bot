package com.example.qqbot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 媒体文件的目录与清理策略，对应 application.yml 里的 app.media.*
 *
 * <p><b>为什么要有这个配置：</b>图片类文件会以两种完全不同的身份存在，
 * 它们的生命周期正好相反，<b>必须物理隔离</b>：
 * <ul>
 *   <li><b>临时图片</b>（{@code tmp-images}）——用户发来的图、生成/转发用的中间产物。
 *       随时可以删，由定时任务按「最后修改时间」自动清理。</li>
 *   <li><b>知识库图片</b>（{@code kb-images}）——长期资产，永久保留。
 *       任何清理逻辑都<b>绝不能</b>碰它。</li>
 * </ul>
 *
 * <p>隔离不能只靠"约定"，所以 {@link com.example.qqbot.media.MediaStorageGuard}
 * 会在启动时校验两者不是同一个目录，配错就直接拒绝启动。
 */
@ConfigurationProperties(prefix = "app.media")
public class MediaProperties {

    /**
     * 单条消息最多处理几张图。
     *
     * <p>为什么必须有上限：一条消息带 N 张图时，这 N 张会被**同时**读进内存并转成 Base64，
     * 单张峰值约占文件大小的 2.33 倍（byte[] 1 倍 + Base64 字符串 1.33 倍）。
     * 再叠加上事件线程池的并发（默认 8），一条"刷了 20 张图"的群消息就能把 2G 机器顶到危险区。
     *
     * <p>只取前 N 张，超出的**只记日志、不报错**，silently 丢弃。
     * 0 = 不限制（不建议）。
     */
    private int maxImagesPerMessage = 3;

    private TempImages tempImages = new TempImages();

    private KbImages kbImages = new KbImages();

    // ==================== 临时图片 ====================

    public static class TempImages {

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

    // ==================== 知识库图片 ====================

    public static class KbImages {

        /** 知识库图片目录。永久保留，清理逻辑的白名单之外 */
        private String dir = "./data/kb-images";

        public String getDir() {
            return dir;
        }

        public void setDir(String dir) {
            this.dir = dir;
        }
    }

    // ==================== getter / setter ====================

    public int getMaxImagesPerMessage() {
        return maxImagesPerMessage;
    }

    public void setMaxImagesPerMessage(int maxImagesPerMessage) {
        this.maxImagesPerMessage = maxImagesPerMessage;
    }

    public TempImages getTempImages() {
        return tempImages;
    }

    public void setTempImages(TempImages tempImages) {
        this.tempImages = tempImages;
    }

    public KbImages getKbImages() {
        return kbImages;
    }

    public void setKbImages(KbImages kbImages) {
        this.kbImages = kbImages;
    }
}

package com.example.qqbot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 站点信息（公开站「关于」页展示的群信息）。
 *
 * <p>这些是**热生效**的 —— 后台改完立即起作用，不用重启。
 */
@ConfigurationProperties(prefix = "app.site")
public class SiteProperties {

    /** 群名称 */
    private String groupName = "";

    /** 群号（留空则不在公开站显示） */
    private String groupNumber = "";

    /** 群说明：这个群是干什么的 */
    private String groupDesc = "";

    /** 加群提示：怎么加 */
    private String joinHint = "";

    /** 首页图片轮播 */
    private Carousel carousel = new Carousel();

    public String getGroupName() {
        return groupName;
    }

    public void setGroupName(String groupName) {
        this.groupName = groupName;
    }

    public String getGroupNumber() {
        return groupNumber;
    }

    public void setGroupNumber(String groupNumber) {
        this.groupNumber = groupNumber;
    }

    public String getGroupDesc() {
        return groupDesc;
    }

    public void setGroupDesc(String groupDesc) {
        this.groupDesc = groupDesc;
    }

    public String getJoinHint() {
        return joinHint;
    }

    public void setJoinHint(String joinHint) {
        this.joinHint = joinHint;
    }

    public Carousel getCarousel() {
        return carousel;
    }

    public void setCarousel(Carousel carousel) {
        this.carousel = carousel;
    }

    /**
     * 首页轮播。
     *
     * <p>图片本身存在 {@link #dir} 里、清单存在库里（见 {@code CarouselStore}）；
     * 这里只是可调的那几个数 —— 它们都在管理后台可改、**热生效**。
     */
    public static class Carousel {

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
}

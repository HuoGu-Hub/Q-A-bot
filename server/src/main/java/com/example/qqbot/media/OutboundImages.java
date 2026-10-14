package com.example.qqbot.media;

/**
 * 出站图片的策略 —— 发给群友的图，开关、张数、大小。
 *
 * <h2>为什么要单独一项，而不是复用 {@code max-images-per-message}</h2>
 * 那一个是**入站**的闸门，管的是"一条消息带十几张图时我们吃多少内存"，
 * 防的是别人拿图把我们打挂。这里管的是**我们主动发出去**的图：
 * 一条回复带几张、单张多大。两者的量级和风险都不一样 ——
 * 合成的地图截图是我们自己画的，不受别人控制；但发太多条同样会被 QQ 判成刷屏。
 *
 * <h2>为什么默认关不掉（enabled 默认 true）</h2>
 * 这个开关关掉的只是"认识图片标记"，不会让机器人多发一个字：
 * 没有标记时它什么都不做。默认开着，配好图就能用。
 */
public class OutboundImages {

    /** 总开关。关掉后回复里的图片标记会被直接丢掉，当普通文本处理 */
    private boolean enabled = true;

    /**
     * 一条回复最多带几张图。0 = 不限制（不建议）。
     *
     * <p>超出的只记日志并丢弃 —— 和入站那条一个道理：静默丢弃比回一句
     * "你只能发一张图"更不打扰群里的人。
     */
    private int maxPerReply = 2;

    /**
     * 单张图的大小上限（KB）。0 = 不限制（不建议）。
     *
     * <p>⚠️ 只对**本地字节**生效：图片要转成 Base64 塞进消息体，
     * 体积会膨胀约 1.33 倍，再乘上并发数就是内存峰值。
     * 公网直链不经过我们的内存，大小由协议端决定，这里管不着。
     */
    private int maxSizeKb = 2048;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMaxPerReply() {
        return maxPerReply;
    }

    public void setMaxPerReply(int maxPerReply) {
        this.maxPerReply = maxPerReply;
    }

    public int getMaxSizeKb() {
        return maxSizeKb;
    }

    public void setMaxSizeKb(int maxSizeKb) {
        this.maxSizeKb = maxSizeKb;
    }
}

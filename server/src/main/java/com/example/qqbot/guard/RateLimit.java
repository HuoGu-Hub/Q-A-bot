package com.example.qqbot.guard;

/**
 * 频率限制 —— **回得多快**。群维度与用户维度各一条滑窗，外加"被限流之后怎么提示"。
 *
 * <h2>为什么不在 {@code config} 包里（2026-10-02 挪过来）</h2>
 * 它是<b>安全中间层的领域词汇</b>，只是碰巧从 {@code app.guard.*} 绑定过来。
 * 留在 {@code GuardProperties} 的嵌套类里，会让 {@code guard} 包看起来"依赖配置的形状" ——
 * 而 13 个消费者早就通过 {@code GuardConfig} 的 bean 直接注入它了，根本没有那回事。
 * 搬迁是**纯搬运**：零语义变化，只是把"这个形状归谁"摆正。
 */
public class RateLimit {

    private boolean enabled = true;

    /** 同一个群每分钟最多被回复几条 */
    private int perGroupPerMinute = 10;

    /** 同一个人每分钟最多被回复几次（跨群统计） */
    private int perUserPerMinute = 1;

    /**
     * 用户维度的滑动窗口长度（秒）—— 和 {@link #perUserPerMinute} 是乘数关系：
     * 「每 {@code perUserWindowSeconds} 秒最多 {@code perUserPerMinute} 次」。
     *
     * <p>默认 60。想回得快一点就调小（比如 20 = 每 20 秒回一次）。
     * 群维度固定 60 秒，不受这项影响。
     */
    private int perUserWindowSeconds = 60;

    /**
     * 被限流之后怎么办：
     * silent        —— 完全不出声（最防炸群）
     * notify-once   —— 每个用户每个冷却周期内提示一次，之后静默（默认）
     */
    private String onLimit = "notify-once";

    /** 用户级限流（同一个人在同一个群里刷太快）时回的话。{seconds} = 生效窗口秒数 */
    private String notifyUserText = "别急呀，同一个人我 {seconds} 秒只能回一次，稍等一下下～";

    /** 群级限流（整个群被回复太频繁）时回的话 */
    private String notifyGroupText = "这个群我有点忙不过来啦，一分钟内已经回了不少，我等会儿再看～";

    /**
     * 任务在队列里等了太久、被丢弃时回的话。
     * 和上面两句区分开，让用户知道「不是限制你，是刚才太挤了」。
     */
    private String queueTimeoutText = "刚才排队太久啦，你再说一遍吧～";

    /** 提示话术的每个用户冷却时间（秒）——防止「提示」本身变成刷屏 */
    private int notifyCooldownSeconds = 300;

    /**
     * 提示话术的**群级**上限：同一个群每分钟最多回几条提示。
     * 这一项很关键：2000 人的群里 50 个人同时被限流，
     * 如果每人都回一句提示，就等于用「提示」把群刷爆了。
     */
    private int notifyGroupPerMinute = 3;

    public String getNotifyUserText() {
        return notifyUserText;
    }

    public void setNotifyUserText(String notifyUserText) {
        this.notifyUserText = notifyUserText;
    }

    public String getNotifyGroupText() {
        return notifyGroupText;
    }

    public void setNotifyGroupText(String notifyGroupText) {
        this.notifyGroupText = notifyGroupText;
    }

    public String getQueueTimeoutText() {
        return queueTimeoutText;
    }

    public void setQueueTimeoutText(String queueTimeoutText) {
        this.queueTimeoutText = queueTimeoutText;
    }

    public int getNotifyGroupPerMinute() {
        return notifyGroupPerMinute;
    }

    public void setNotifyGroupPerMinute(int notifyGroupPerMinute) {
        this.notifyGroupPerMinute = notifyGroupPerMinute;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getPerGroupPerMinute() {
        return perGroupPerMinute;
    }

    public void setPerGroupPerMinute(int perGroupPerMinute) {
        this.perGroupPerMinute = perGroupPerMinute;
    }

    public int getPerUserPerMinute() {
        return perUserPerMinute;
    }

    public void setPerUserPerMinute(int perUserPerMinute) {
        this.perUserPerMinute = perUserPerMinute;
    }

    public int getPerUserWindowSeconds() {
        return perUserWindowSeconds;
    }

    public void setPerUserWindowSeconds(int perUserWindowSeconds) {
        this.perUserWindowSeconds = perUserWindowSeconds;
    }

    public String getOnLimit() {
        return onLimit;
    }

    public void setOnLimit(String onLimit) {
        this.onLimit = onLimit;
    }

    public int getNotifyCooldownSeconds() {
        return notifyCooldownSeconds;
    }

    public void setNotifyCooldownSeconds(int notifyCooldownSeconds) {
        this.notifyCooldownSeconds = notifyCooldownSeconds;
    }
}

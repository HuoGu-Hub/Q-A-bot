package com.example.qqbot.guard;

/**
 * 出站节奏 —— 管的是「发得多快」，和 {@link RateLimit}（管「回不回」）是两回事。
 *
 * <p>为什么单独立一项：限流放行之后，一条回复如果紧跟着第二条发出去，观感上
 * 还是刷屏；尤其是长回复被切成多条时，没有间隔的话 QQ 端会吞消息或折叠显示。
 *
 * <h2>为什么不在 {@code config} 包里（2026-10-02 挪过来）</h2>
 * 它是<b>安全中间层的领域词汇</b>，只是碰巧从 {@code app.guard.*} 绑定过来。
 * 留在 {@code GuardProperties} 的嵌套类里，会让 {@code guard} 包看起来"依赖配置的形状" ——
 * 而 13 个消费者早就通过 {@code GuardConfig} 的 bean 直接注入它了，根本没有那回事。
 * 搬迁是**纯搬运**：零语义变化，只是把"这个形状归谁"摆正。
 */
public class Outbound {

    /** 总开关 */
    private boolean enabled = true;

    /** 同一个群两条消息之间的最小间隔（毫秒） */
    private long groupMinIntervalMillis = 800;

    /**
     * 间隔上的随机抖动（毫秒，±这个值）。
     * 固定间隔一眼就能看出是机器人；加抖动更像人在打字。
     */
    private long groupJitterMillis = 400;

    /** 单条消息的软上限（字），超过就按句子切成多条。0 = 不切 */
    private int maxCharsPerMessage = 300;

    /**
     * 切成多条时，是否把它们打包成**一条合并转发**（QQ 的「聊天记录」）发出去。
     *
     * <p>为什么值得做：群里有发言频率上限，一条长攻略被切成 8 条就是 8 次发言，
     * 几个人同时问就很容易撞上限、消息发不出去。合并转发在群里**只算一次发言**，
     * 点开还是一段段读，阅读体验不变。
     *
     * <p>失败会自动退回逐条发送，所以默认开着是安全的（见 OutboundSender）。
     */
    private boolean forwardMerged = true;

    /**
     * 触发合并转发的**段数**下限：切成的段数**超过**它才打包。
     *
     * <p>默认 2 → 也就是 3 段起。为什么是 3 起：卡片本身没法 @ 人，前面得先发一条带 @ 的
     * 提示语，所以「提示 + 卡片」= 2 条；只有 2 段时合并等于没省，3 段起才是净赚。
     *
     * <p>0 = 不看段数（只由 {@link #forwardMinChars} 决定）。
     */
    private int forwardMinParts = 2;

    /**
     * 触发合并转发的**总字数**下限：整条回复超过这么多字才打包。
     *
     * <p>0 = 不看字数（默认）。
     *
     * <p>和 {@link #forwardMinParts} 是**与**的关系：两个都满足才合并。
     */
    private int forwardMinChars = 0;

    /**
     * 合并转发前面那条提示语（群聊里带 @ 提问者）。
     *
     * <p>为什么要单独发一条：合并转发卡片本身没法 @ 人 —— 不进卡片就不知道是回给他的。
     * 支持 {@code {parts}} = 一共分了几段。
     */
    private String forwardIntroText = "答案有点长，我打包成一条聊天记录啦，点开就能看～";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getGroupMinIntervalMillis() {
        return groupMinIntervalMillis;
    }

    public void setGroupMinIntervalMillis(long groupMinIntervalMillis) {
        this.groupMinIntervalMillis = groupMinIntervalMillis;
    }

    public long getGroupJitterMillis() {
        return groupJitterMillis;
    }

    public void setGroupJitterMillis(long groupJitterMillis) {
        this.groupJitterMillis = groupJitterMillis;
    }

    public int getMaxCharsPerMessage() {
        return maxCharsPerMessage;
    }

    public void setMaxCharsPerMessage(int maxCharsPerMessage) {
        this.maxCharsPerMessage = maxCharsPerMessage;
    }

    public boolean isForwardMerged() {
        return forwardMerged;
    }

    public void setForwardMerged(boolean forwardMerged) {
        this.forwardMerged = forwardMerged;
    }

    public int getForwardMinParts() {
        return forwardMinParts;
    }

    public void setForwardMinParts(int forwardMinParts) {
        this.forwardMinParts = forwardMinParts;
    }

    public int getForwardMinChars() {
        return forwardMinChars;
    }

    public void setForwardMinChars(int forwardMinChars) {
        this.forwardMinChars = forwardMinChars;
    }

    public String getForwardIntroText() {
        return forwardIntroText;
    }

    public void setForwardIntroText(String forwardIntroText) {
        this.forwardIntroText = forwardIntroText;
    }
}

package com.example.qqbot.guard;

/**
 * 出站节奏 —— 管的是「发得多快」，和 {@link RateLimit}（管「回不回」）是两回事。
 *
 * <p>两道闸叠在一起：
 * <ul>
 *   <li><b>群级</b>：同一个群两条消息之间的最小间隔，防的是"同群刷屏"；</li>
 *   <li><b>账号级</b>：整个 QQ 号两条消息之间的最小间隔，防的是"十个群同时到点，
 *       一秒十条打出去"被风控。群级那道拦不住跨群，因为每个群都觉得自己只发了一条。</li>
 * </ul>
 *
 * <h2>为什么抖动是区间不是正负值</h2>
 * 原来的抖动是 ±400 毫秒，对称。实测下来更想要"偶尔早一点、常常晚一点"：
 * 早一点让人感觉反应快，晚一点才是常态。所以改成上下限分别配，
 * 默认 -100 到 +400。
 */
public class Outbound {

    /** 总开关。关掉之后两道闸都不生效，所有间隔归零 */
    private boolean enabled = true;

    // ==================== 群级 ====================

    /** 同一个群两条消息之间的最小间隔（毫秒） */
    private long groupMinIntervalMillis = 500;

    /** 群级抖动的下限（毫秒，可为负） */
    private long groupJitterMinMillis = -100;

    /** 群级抖动的上限（毫秒） */
    private long groupJitterMaxMillis = 400;

    // ==================== 账号级 ====================

    /**
     * 整个账号两条消息之间的最小间隔（毫秒）。
     *
     * <p>这道闸是防封号的：风控看的是账号维度单位时间的发送量，
     * 不看是不是同一个群。0 = 关闭。
     */
    private long globalMinIntervalMillis = 500;

    /** 账号级抖动的下限（毫秒，可为负） */
    private long globalJitterMinMillis = -100;

    /** 账号级抖动的上限（毫秒） */
    private long globalJitterMaxMillis = 400;

    // ==================== 切分与合并 ====================

    /**
     * 单条消息的软上限（字），超过就按句子切成多条。0 = 不切。
     *
     * <p><b>只对问答回复生效。</b>命令回执与定时公告不切 —— 公告本来就是一段完整的话，
     * 切开发出去就是两条互不相干的消息。
     */
    private int maxCharsPerMessage = 300;

    /**
     * 切成多条时，是否把它们打包成**一条合并转发**（QQ 的「聊天记录」）发出去。
     *
     * <p><b>只允许打包同一个回复的分段</b>，绝不跨事件、跨人合并。
     */
    private boolean forwardMerged = true;

    /** 触发合并转发的段数下限：切成的段数**超过**它才打包。默认 2 → 3 段起 */
    private int forwardMinParts = 2;

    /** 触发合并转发的总字数下限。0 = 不看字数 */
    private int forwardMinChars = 0;

    /** 合并转发前面那条提示语，支持 {parts} */
    private String forwardIntroText = "答案有点长，我打包成一条聊天记录啦，点开就能看～";

    // ==================== 发送队列 ====================

    /**
     * 是否把发送切成异步：处理线程只负责把消息投进队列，由专门的发送线程按节奏发。
     *
     * <p>默认关，是为了让直接构造本类的测试保持"发完即返回"的同步语义；
     * 正式配置里打开。打开之后，处理线程不再被发车位堵住。
     */
    private boolean asyncSend = false;

    /** 发送队列容量。满了只降级实时回复，定时任务走数据库队列，不受影响 */
    private int sendQueueCapacity = 2000;

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

    public long getGroupJitterMinMillis() {
        return groupJitterMinMillis;
    }

    public void setGroupJitterMinMillis(long groupJitterMinMillis) {
        this.groupJitterMinMillis = groupJitterMinMillis;
    }

    public long getGroupJitterMaxMillis() {
        return groupJitterMaxMillis;
    }

    public void setGroupJitterMaxMillis(long groupJitterMaxMillis) {
        this.groupJitterMaxMillis = groupJitterMaxMillis;
    }

    public long getGlobalMinIntervalMillis() {
        return globalMinIntervalMillis;
    }

    public void setGlobalMinIntervalMillis(long globalMinIntervalMillis) {
        this.globalMinIntervalMillis = globalMinIntervalMillis;
    }

    public long getGlobalJitterMinMillis() {
        return globalJitterMinMillis;
    }

    public void setGlobalJitterMinMillis(long globalJitterMinMillis) {
        this.globalJitterMinMillis = globalJitterMinMillis;
    }

    public long getGlobalJitterMaxMillis() {
        return globalJitterMaxMillis;
    }

    public void setGlobalJitterMaxMillis(long globalJitterMaxMillis) {
        this.globalJitterMaxMillis = globalJitterMaxMillis;
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

    public boolean isAsyncSend() {
        return asyncSend;
    }

    public void setAsyncSend(boolean asyncSend) {
        this.asyncSend = asyncSend;
    }

    public int getSendQueueCapacity() {
        return sendQueueCapacity;
    }

    public void setSendQueueCapacity(int sendQueueCapacity) {
        this.sendQueueCapacity = sendQueueCapacity;
    }
}

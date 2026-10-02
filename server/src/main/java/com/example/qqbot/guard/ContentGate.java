package com.example.qqbot.guard;

/**
 * 内容门控 —— 没文字、没图可看时回什么兜底话术。
 *
 * <h2>为什么不在 {@code config} 包里（2026-10-02 挪过来）</h2>
 * 它是<b>安全中间层的领域词汇</b>，只是碰巧从 {@code app.guard.*} 绑定过来。
 * 留在 {@code GuardProperties} 的嵌套类里，会让 {@code guard} 包看起来"依赖配置的形状" ——
 * 而 13 个消费者早就通过 {@code GuardConfig} 的 bean 直接注入它了，根本没有那回事。
 * 搬迁是**纯搬运**：零语义变化，只是把"这个形状归谁"摆正。
 */
public class ContentGate {

    private boolean enabled = true;

    /** 消息里既没有文字也没有图片（纯表情 / 语音）时，不调模型，直接回这句 */
    private String noTextReply = "我看到啦，不过只有表情我不太懂，打字跟我说吧～";

    /** 有图片、但当前没有配置能看图的模型时回这句 */
    private String noVisionReply = "图片我这边暂时看不了，你打字形容一下？";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getNoTextReply() {
        return noTextReply;
    }

    public void setNoTextReply(String noTextReply) {
        this.noTextReply = noTextReply;
    }

    public String getNoVisionReply() {
        return noVisionReply;
    }

    public void setNoVisionReply(String noVisionReply) {
        this.noVisionReply = noVisionReply;
    }
}

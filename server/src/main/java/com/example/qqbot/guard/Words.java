package com.example.qqbot.guard;

/**
 * 关键词表 —— 入站敏感词与出站敏感词，各自一个词表文件。
 *
 * <h2>为什么不在 {@code config} 包里（2026-10-02 挪过来）</h2>
 * 它是<b>安全中间层的领域词汇</b>，只是碰巧从 {@code app.guard.*} 绑定过来。
 * 留在 {@code GuardProperties} 的嵌套类里，会让 {@code guard} 包看起来"依赖配置的形状" ——
 * 而 13 个消费者早就通过 {@code GuardConfig} 的 bean 直接注入它了，根本没有那回事。
 * 搬迁是**纯搬运**：零语义变化，只是把"这个形状归谁"摆正。
 */
public class Words {

    private boolean enabled = true;

    /**
     * 入站词表位置。支持两种前缀：
     * classpath:words/inbound.txt   —— 打包进 jar，改完要重新编译
     * file:./words/inbound.txt      —— 外部文件，改完重启即可
     */
    private String inboundFile = "classpath:words/inbound.txt";

    /** 出站词表位置（比入站宽松） */
    private String outboundFile = "classpath:words/outbound.txt";

    /** 入站命中时回复的拒绝话术（不调用大模型） */
    private String inboundRefusalText = "这个话题我不太方便聊，我们换个别的吧～";

    /** 出站命中时，把整条回复替换成这句 */
    private String outboundFallbackText = "我好像要说不该说的了，我们换个话题吧～";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getInboundFile() {
        return inboundFile;
    }

    public void setInboundFile(String inboundFile) {
        this.inboundFile = inboundFile;
    }

    public String getOutboundFile() {
        return outboundFile;
    }

    public void setOutboundFile(String outboundFile) {
        this.outboundFile = outboundFile;
    }

    public String getInboundRefusalText() {
        return inboundRefusalText;
    }

    public void setInboundRefusalText(String inboundRefusalText) {
        this.inboundRefusalText = inboundRefusalText;
    }

    public String getOutboundFallbackText() {
        return outboundFallbackText;
    }

    public void setOutboundFallbackText(String outboundFallbackText) {
        this.outboundFallbackText = outboundFallbackText;
    }
}

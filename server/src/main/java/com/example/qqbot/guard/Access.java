package com.example.qqbot.guard;

import java.util.ArrayList;
import java.util.List;

/**
 * 准入规则 —— **谁能用、在哪儿能用**。群聊是否必须 @、私聊策略、三张黑/白名单。
 * *
 * <h2>为什么不在 {@code config} 包里（2026-10-02 挪过来）</h2>
 * 它是<b>安全中间层的领域词汇</b>，只是碰巧从 {@code app.guard.*} 绑定过来。
 * 留在 {@code GuardProperties} 的嵌套类里，会让 {@code guard} 包看起来"依赖配置的形状" ——
 * 而 13 个消费者早就通过 {@code GuardConfig} 的 bean 直接注入它了，根本没有那回事。
 * 搬迁是**纯搬运**：零语义变化，只是把"这个形状归谁"摆正。
 */
public class Access {

    /** 群聊里是否必须 @ 机器人才响应 */
    private boolean requireMentionInGroup = true;

    /** 私聊策略：all / whitelist / off。默认 off —— 只允许在群里聊 */
    private String privateChatPolicy = "off";

    /** 私聊白名单（privateChatPolicy = whitelist 时生效） */
    private List<Long> privateWhitelist = new ArrayList<>();

    /** 群黑名单：名单里的群永不响应 */
    private List<Long> groupBlacklist = new ArrayList<>();

    /** 用户黑名单：这些人永不响应 */
    private List<Long> userBlacklist = new ArrayList<>();

    public boolean isRequireMentionInGroup() {
        return requireMentionInGroup;
    }

    public void setRequireMentionInGroup(boolean requireMentionInGroup) {
        this.requireMentionInGroup = requireMentionInGroup;
    }

    public String getPrivateChatPolicy() {
        return privateChatPolicy;
    }

    public void setPrivateChatPolicy(String privateChatPolicy) {
        this.privateChatPolicy = privateChatPolicy;
    }

    public List<Long> getPrivateWhitelist() {
        return privateWhitelist;
    }

    public void setPrivateWhitelist(List<Long> privateWhitelist) {
        this.privateWhitelist = privateWhitelist;
    }

    public List<Long> getGroupBlacklist() {
        return groupBlacklist;
    }

    public void setGroupBlacklist(List<Long> groupBlacklist) {
        this.groupBlacklist = groupBlacklist;
    }

    public List<Long> getUserBlacklist() {
        return userBlacklist;
    }

    public void setUserBlacklist(List<Long> userBlacklist) {
        this.userBlacklist = userBlacklist;
    }
}

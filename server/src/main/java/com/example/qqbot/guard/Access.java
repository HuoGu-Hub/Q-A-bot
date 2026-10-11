package com.example.qqbot.guard;

import com.example.qqbot.onebot.model.OneBotEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 准入规则 —— **谁能用、在哪儿能用**。群聊是否必须 @、私聊策略、三张黑/白名单。
 *
 * <h2>为什么不在 {@code config} 包里（2026-10-02 挪过来）</h2>
 * 它是<b>安全中间层的领域词汇</b>，只是碰巧从 {@code app.guard.*} 绑定过来。
 * 留在 {@code GuardProperties} 的嵌套类里，会让 {@code guard} 包看起来"依赖配置的形状" ——
 * 而 13 个消费者早就通过 {@code GuardConfig} 的 bean 直接注入它了，根本没有那回事。
 * 搬迁是**纯搬运**：零语义变化，只是把"这个形状归谁"摆正。
 */
public class Access {

    /** 允许私聊的值（其余一律视为关闭） */
    private static final Set<String> PRIVATE_ALLOW_ALL = Set.of("all", "on", "true", "yes", "open");

    /** 白名单模式的值 */
    private static final Set<String> PRIVATE_WHITELIST = Set.of("whitelist", "white", "list");

    /** 明确表示关闭的值。注意 "false" 是必须的：YAML 里写 off 会被解析成布尔 false */
    private static final Set<String> PRIVATE_OFF = Set.of("off", "false", "no", "none", "disabled", "close", "");

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

    // ==================== 准入判定 ====================

    /**
     * 准入判定的<b>唯一一份实现</b>：允许返回 empty，拒绝返回原因。
     *
     * <p>为什么返回原因而不是 {@code boolean}：这条判定有两个调用方 ——
     * 普通消息走 {@link com.example.qqbot.guard.stage.AccessControlStage}，
     * 指令走 {@code router.MessageRouter}，而原因要进日志与护栏指标，
     * 丢掉它就没法回答"这条消息为什么不理我"。
     *
     * <p><b>为什么必须只有一份</b>：原先两张黑名单在这两处各读一遍，于是同一条消息
     * 能得到两个不同结论 —— 私聊策略 {@code off} 时，话术类指令照回，普通消息被拦。
     * 加一条规则要改两处，漏一处不会报错，只会静默放行。
     */
    public Optional<String> denial(OneBotEvent event) {
        // 用户黑名单：无论群聊私聊都不理
        if (event.getUserId() != null && userBlacklist.contains(event.getUserId())) {
            return Optional.of("用户在黑名单里");
        }

        if (event.isGroupMessage()) {
            if (event.getGroupId() != null && groupBlacklist.contains(event.getGroupId())) {
                return Optional.of("群在黑名单里");
            }
            return Optional.empty();
        }

        if (event.isPrivateMessage()) {
            String policy = normalizedPrivateChatPolicy();
            if (PRIVATE_ALLOW_ALL.contains(policy)) {
                return Optional.empty();
            }
            if (PRIVATE_WHITELIST.contains(policy) && privateWhitelist.contains(event.getUserId())) {
                return Optional.empty();
            }
            // 不认识的策略一律按「关闭私聊」处理（fail-closed）—— 安全开关不能猜错了就放开
            return Optional.of("私聊未开放（策略=" + policy + "）");
        }

        return Optional.of("未知的消息类型");
    }

    /** 私聊策略是不是一个认识的值。不认识的会在启动时告警，行为按【关闭私聊】 */
    public boolean isRecognizedPrivateChatPolicy() {
        String policy = normalizedPrivateChatPolicy();
        return PRIVATE_ALLOW_ALL.contains(policy)
                || PRIVATE_WHITELIST.contains(policy)
                || PRIVATE_OFF.contains(policy);
    }

    /** 统一小写、去空格；null 视为空串 */
    private String normalizedPrivateChatPolicy() {
        return privateChatPolicy == null ? "" : privateChatPolicy.trim().toLowerCase();
    }
}

package com.example.qqbot.config;

import com.example.qqbot.command.CommandPolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 指令系统的配置，对应 application.yml 里的 app.commands.*
 *
 * <p>这些都是**热生效**的 —— 后台改完立刻起作用，不用重启。
 * 因为调用方持有的是本对象的引用，每次都实时读字段。
 */
@ConfigurationProperties(prefix = "app.commands")
public class CommandProperties implements CommandPolicy {

    /** 指令系统总开关 */
    private boolean enabled = true;

    /**
     * 群里触发指令是否必须 @ 机器人。
     *
     * <p>默认 true。理由：群聊里 "/" 开头的消息不罕见（贴路径、贴代码），
     * 不加 @ 就会误触发，显得机器人很吵。
     */
    private boolean requireMention = true;

    /**
     * 是否需要 "/" 前缀。
     *
     * <p>默认 true。关掉的话 "@机器人 帮助" 也会触发 —— 但那和正常提问无法区分，
     * 不建议关。
     */
    private boolean requireSlash = true;

    /**
     * 指令的独立限流（每人每分钟几次）。
     *
     * <p>刻意和问答限流分开：问答限流是"每分钟只能问一次"，
     * 如果指令也受它管，那 /help 一天就只能用一次，毫无意义。
     *
     * <p>但也不能不限 —— 否则有人刷 /list 就能刷屏。
     */
    private int rateLimitPerMinute = 10;

    /**
     * 是否允许 {user.id} / {group.id} 这类**高级变量**。
     *
     * <p>默认 false：回复会发到群里，被动能看到（群成员列表）和
     * 机器人主动打出来是两回事。
     */
    private boolean allowUserIds = false;

    /** 回复最大长度（防止配了一个超长文本把群刷屏） */
    private int maxReplyLength = 1500;

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public boolean isRequireMention() {
        return requireMention;
    }

    public void setRequireMention(boolean requireMention) {
        this.requireMention = requireMention;
    }

    @Override
    public boolean isRequireSlash() {
        return requireSlash;
    }

    public void setRequireSlash(boolean requireSlash) {
        this.requireSlash = requireSlash;
    }

    @Override
    public int getRateLimitPerMinute() {
        return rateLimitPerMinute;
    }

    public void setRateLimitPerMinute(int rateLimitPerMinute) {
        this.rateLimitPerMinute = rateLimitPerMinute;
    }

    @Override
    public boolean isAllowUserIds() {
        return allowUserIds;
    }

    public void setAllowUserIds(boolean allowUserIds) {
        this.allowUserIds = allowUserIds;
    }

    @Override
    public int getMaxReplyLength() {
        return maxReplyLength;
    }

    public void setMaxReplyLength(int maxReplyLength) {
        this.maxReplyLength = maxReplyLength;
    }
}

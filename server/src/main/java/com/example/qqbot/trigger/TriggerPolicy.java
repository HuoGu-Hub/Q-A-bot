package com.example.qqbot.trigger;

import java.util.List;

/**
 * 概率回复的**只读配置视图** —— 由 {@code config.ReplyTriggerProperties} 实现。
 *
 * <p>业务包不直接依赖 config，配置只应在装配层注入。这与
 * {@code intent.IntentPolicy}、{@code llm.LlmPolicy} 是同一个做法。
 * 每次调用现读字段，后台改完下一条消息就生效。
 */
public interface TriggerPolicy {

    /** 主动参与度，0 到 1 */
    double probability();

    /** 只在哪些群做概率回复；空表示所有群都不做 */
    List<Long> enabledGroups();

    /** 每个群每天主动回复上限，0 = 不限 */
    int dailyMaxPerGroup();

    /** 主动回复概率低于此值时，非呼叫消息必须 @；0 = 始终免 @ */
    default double mentionFreeThreshold() {
        return 0.0;
    }

    /** 低概率档的关键词；命中任意一个就回复 */
    default List<String> keywords() {
        return List.of();
    }

    /** 低概率档判断「与游戏相关」的补充词；内置词表之外再加 */
    default List<String> gameKeywords() {
        return List.of();
    }

    /** 只记日志，不发送 */
    boolean shadowMode();

    /** 这个群是否允许做概率回复 */
    default boolean enabledForGroup(long groupId) {
        List<Long> groups = enabledGroups();
        return groups != null && !groups.isEmpty() && groups.contains(groupId);
    }
}

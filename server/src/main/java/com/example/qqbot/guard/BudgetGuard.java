package com.example.qqbot.guard;

import com.example.qqbot.config.AppTime;
import com.example.qqbot.config.GuardProperties.Budget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 成本预算（安全中间层 D6）—— 限流管「多快」，这里管「一天总共多少」。
 *
 * <p>为什么要单独一层：限流挡不住细水长流。每人每分钟 1 次，一天能问 1440 次；
 * 群里 200 个人各问一次就是 200 次模型调用。频率限制看不住账，额度才能。
 *
 * <p><b>三级动作</b>（级别越高越狠）：
 * <ol>
 *   <li>用户超限 → 回一句提示（他知道发生了什么，不会以为机器人坏了）</li>
 *   <li>群超限 → 该群<b>静默</b>（群里人多，挨个解释只会刷屏）</li>
 *   <li>全局超限 → <b>熔断</b>：全都不回，并打一条 ERROR 告警</li>
 * </ol>
 *
 * <p><b>计数只放内存、按天归零</b>：这是"今天别花超"的护栏，不是账单系统。
 * 进程重启会丢当天计数 —— 对个人项目够用，落库留给 M7。额度全是 0 = 不限时，
 * 这一层完全不生效。
 */
@Component
public class BudgetGuard {

    private static final Logger log = LoggerFactory.getLogger(BudgetGuard.class);

    /** 判定结果。除 OK 外都要丢弃/拦截 */
    public enum Verdict {
        OK,
        /** 单个用户当日额度用完 → 回提示 */
        USER_LIMIT,
        /** 单个群当日额度用完 → 静默 */
        GROUP_LIMIT,
        /** 全局额度（次数或 token）用完 → 熔断 */
        CIRCUIT_OPEN,
    }

    /** @param text 只有 USER_LIMIT 才有话术 */
    public record Decision(Verdict verdict, String text) {
    }

    /** 给后台看板的当日用量 */
    public record Snapshot(String day, int globalCalls, long globalTokens,
                           int trackedUsers, int trackedGroups,
                           int globalPerDay, long globalTokensPerDay) {
    }

    private final Budget config;

    /**
     * 当天计数。跨天时整体清空。
     *
     * <p>「今天」按 {@link AppTime#ZONE}（东八区）算，不是 JVM 默认时区 ——
     * 容器里默认是 UTC，那样额度会在北京时间早上 8 点重置，
     * 而看板上的「每日提问量」按东八区分桶，两边会对不上。
     */
    private volatile LocalDate day = LocalDate.now(AppTime.ZONE);
    private final Map<Long, AtomicInteger> userCalls = new ConcurrentHashMap<>();
    private final Map<Long, AtomicInteger> groupCalls = new ConcurrentHashMap<>();
    private final AtomicInteger globalCalls = new AtomicInteger();
    private final AtomicLong globalTokens = new AtomicLong();

    /** 熔断告警每天只喊一次，否则日志会被刷爆 */
    private final AtomicBoolean circuitAlerted = new AtomicBoolean();

    public BudgetGuard(Budget budget) {
        this.config = budget;
    }

    /**
     * 调模型<b>之前</b>问一句：今天还花得起吗？
     *
     * <p>必须放在调模型之前 —— 预算的意义就是别把 token 花出去，
     * 等回复回来再判断已经晚了。
     */
    public Decision check(Long userId, Long groupId) {
        if (!config.isEnabled()) {
            return new Decision(Verdict.OK, "");
        }
        rolloverIfNeeded();

        long tokenLimit = config.getGlobalTokensPerDay();
        if (tokenLimit > 0 && globalTokens.get() >= tokenLimit) {
            return circuit("今日 token 额度已用完（" + globalTokens.get() + "/" + tokenLimit + "）");
        }
        int globalLimit = config.getGlobalPerDay();
        if (globalLimit > 0 && globalCalls.get() >= globalLimit) {
            return circuit("今日全局回复额度已用完（" + globalCalls.get() + "/" + globalLimit + "）");
        }
        int groupLimit = config.getPerGroupPerDay();
        if (groupId != null && groupLimit > 0 && countOf(groupCalls, groupId) >= groupLimit) {
            return new Decision(Verdict.GROUP_LIMIT, "");
        }
        int userLimit = config.getPerUserPerDay();
        if (userId != null && userLimit > 0 && countOf(userCalls, userId) >= userLimit) {
            return new Decision(Verdict.USER_LIMIT,
                    config.getUserLimitText().replace("{limit}", String.valueOf(userLimit)));
        }
        return new Decision(Verdict.OK, "");
    }

    /**
     * 记一次「放行去调模型」。
     *
     * <p>刻意记在调用<b>之前</b>：宁可少算一次（调用失败），也不能因为并发
     * 让一群人同时挤过闸门把额度撑爆。
     */
    public void recordCall(Long userId, Long groupId) {
        if (!config.isEnabled()) {
            return;
        }
        rolloverIfNeeded();
        globalCalls.incrementAndGet();
        if (userId != null) {
            userCalls.computeIfAbsent(userId, k -> new AtomicInteger()).incrementAndGet();
        }
        if (groupId != null) {
            groupCalls.computeIfAbsent(groupId, k -> new AtomicInteger()).incrementAndGet();
        }
    }

    /** 记一次 token 消耗（模型返回用量时调用） */
    public void recordTokens(long tokens) {
        if (!config.isEnabled() || tokens <= 0) {
            return;
        }
        rolloverIfNeeded();
        globalTokens.addAndGet(tokens);
    }

    public Snapshot snapshot() {
        rolloverIfNeeded();
        return new Snapshot(day.toString(), globalCalls.get(), globalTokens.get(),
                userCalls.size(), groupCalls.size(),
                config.getGlobalPerDay(), config.getGlobalTokensPerDay());
    }

    private Decision circuit(String why) {
        if (circuitAlerted.compareAndSet(false, true)) {
            log.error("[BUDGET] 🚨 成本预算熔断：{} —— 今天不再回复任何消息。"
                    + "调 app.guard.budget.* 可提高额度（热生效）", why);
        }
        return new Decision(Verdict.CIRCUIT_OPEN, "");
    }

    private static int countOf(Map<Long, AtomicInteger> map, long key) {
        AtomicInteger c = map.get(key);
        return c == null ? 0 : c.get();
    }

    /** 跨天就把计数清零（惰性检查，不需要定时任务） */
    private void rolloverIfNeeded() {
        LocalDate today = LocalDate.now(AppTime.ZONE);
        if (today.equals(day)) {
            return;
        }
        synchronized (this) {
            if (today.equals(day)) {
                return;
            }
            userCalls.clear();
            groupCalls.clear();
            globalCalls.set(0);
            globalTokens.set(0);
            circuitAlerted.set(false);
            day = today;
            log.info("[BUDGET] 新的一天（{}），额度已重置", today);
        }
    }
}
